package com.example.usermanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs one requested operation through {@code su}, without a separate UID-check command.
 * A marker records whether the payload started so the legacy protocol can only be
 * retried when the first invocation rejected {@code -c} before running anything.
 *
 * <p>Two invocation protocols are supported, in this order:
 * <ol>
 *   <li>{@code su -c <script>} - the API understood by Magisk, KernelSU, APatch
 *       and SuperSU. It reliably raises the manager's per-app grant prompt.</li>
 *   <li>{@code su} with the script piped to stdin - the legacy protocol used by
 *       older root managers. It is only used when the binary clearly rejected
 *       the {@code -c} argument, so the operation is never executed twice.</li>
 * </ol>
 */
final class RootShell {
    private static final String COMMAND_STARTED = "__ROOT_TOOLS_COMMAND_STARTED__";
    private static final int OUTPUT_LIMIT = 128 * 1024;
    private static final int FLAG_GUEST = 0x4;
    private final String[] candidates;

    RootShell() {
        this(new String[]{"su", "/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/su/bin/su", "/debug_ramdisk/su"});
    }

    RootShell(String[] candidates) { this.candidates = candidates.clone(); }

    static final class Result {
        final String output;
        final int exitCode;
        final boolean timedOut;
        final boolean commandStarted;
        Result(String output, int exitCode, boolean timedOut, boolean commandStarted) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.commandStarted = commandStarted;
        }
    }

    static final class DeviceUser {
        final int id;
        final String name;
        final boolean running;
        final boolean guest;
        DeviceUser(int id, String name, boolean running, boolean guest) {
            this.id = id;
            this.name = name;
            this.running = running;
            this.guest = guest;
        }
    }

    Result run(String command, int timeoutSeconds) {
        String script = buildScript(command);
        Process process = null;
        StringBuilder launchErrors = new StringBuilder();
        int launched = -1;
        for (int i = 0; i < candidates.length; i++) {
            try {
                // An explicit `su -c` request is the Android root-manager API used by
                // Magisk, KernelSU, APatch and SuperSU.  In particular, this reliably
                // causes the manager's per-app grant prompt, unlike opening su and
                // waiting to feed an interactive stdin pipe on some Android 9 ROMs.
                // Never try another candidate after a process starts: that could replay
                // a privileged/destructive operation.
                process = new ProcessBuilder(candidates[i], "-c", script)
                        .redirectErrorStream(true).start();
                launched = i;
                break;
            } catch (IOException | RuntimeException e) {
                launchErrors.append(candidates[i]).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (process == null) return new Result("Cannot start a superuser shell. A working su/root manager must be installed.\n"
                + launchErrors, -1, false, false);

        Result result = execute(process, script, timeoutSeconds, false);

        if (rejectedDashC(result)) {
            // A few root managers ship a `su` that does not understand `-c`.  Only
            // the very same binary is retried, with the legacy stdin protocol, and
            // only when the first attempt provably did not run anything (a usage
            // error and no command-start marker). The operation still executes at
            // most once.
            Process legacy;
            try {
                legacy = new ProcessBuilder(candidates[launched]).redirectErrorStream(true).start();
            } catch (IOException | RuntimeException e) {
                return result;
            }
            Result retry = execute(legacy, script, timeoutSeconds, true);
            if (retry.commandStarted || !retry.output.isEmpty()) return retry;
        }
        return result;
    }

    private static String buildScript(String command) {
        return "export PATH=/system/bin:/system/xbin:/sbin:$PATH\n"
                + "echo '" + COMMAND_STARTED + "'\n"
                + "(\n" + command + "\n)\n"
                + "result=$?\nexit \"$result\"\n";
    }

    /**
     * Detects the "this su does not support -c" case. The command-start marker is
     * printed before the operation runs, so its absence proves that nothing was
     * executed and that a retry is safe.
     */
    private static boolean rejectedDashC(Result result) {
        if (result.timedOut || result.commandStarted) return false;
        if (result.exitCode != 1 && result.exitCode != 2 && result.exitCode != 64
                && result.exitCode != 126 && result.exitCode != 127) return false;
        String output = result.output.toLowerCase(Locale.ROOT);
        return output.contains("unrecognized option")
                || output.contains("invalid option")
                || output.contains("unknown option")
                || output.contains("illegal option")
                || output.contains("usage:")
                || output.contains("invalid usage");
    }

    private Result execute(Process process, String script, int timeoutSeconds, boolean legacyStdin) {
        final Process child = process;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        int remaining = OUTPUT_LIMIT - captured.size();
                        if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
                    }
                }
            } catch (Throwable ignored) { /* Process teardown closes the pipe. */ }
        }, "root-output");
        reader.setDaemon(true);
        reader.start();

        final java.util.concurrent.atomic.AtomicReference<String> writeFailure =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        if (legacyStdin) {
            // Feed the script on stdin and close it so the shell sees EOF.  The
            // write happens on its own thread: a `su` that never reads stdin must
            // not be able to block the timeout handling below.
            Thread writer = new Thread(() -> {
                try (OutputStreamWriter out =
                             new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) {
                    out.write(script);
                    out.flush();
                } catch (Throwable e) {
                    // su closed stdin (for example it rejected the request). Keep
                    // the reason so the UI can explain what happened.
                    writeFailure.set("Superuser shell closed: " + e.getMessage());
                }
            }, "root-stdin");
            writer.setDaemon(true);
            writer.start();
        } else {
            // Nothing will read stdin in the `-c` protocol; closing it releases the
            // descriptor and gives any reader an immediate EOF.
            try {
                process.getOutputStream().close();
            } catch (Throwable ignored) { }
        }

        boolean timedOut = false;
        int exitCode = -1;
        String failure = "";
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                timedOut = true;
                process.destroyForcibly();
            } else exitCode = process.exitValue();
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "Root request cancelled.";
        } catch (Throwable t) {
            failure = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            try { process.destroyForcibly(); } catch (Throwable ignored) { }
            try { reader.join(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try { process.getOutputStream().close(); } catch (Throwable ignored) { }
        }
        String rawOutput;
        synchronized (captured) { rawOutput = new String(captured.toByteArray(), StandardCharsets.UTF_8); }
        boolean[] markerFound = new boolean[]{false};
        String output = stripMarker(rawOutput, markerFound);
        boolean commandStarted = markerFound[0];
        if (failure.isEmpty() && writeFailure.get() != null) failure = writeFailure.get();
        if (!failure.isEmpty()) output += "\n" + failure;
        if (timedOut) output += "\nTimed out after " + timeoutSeconds
                + " seconds. The operation may have started; refresh before retrying.";
        return new Result(output.trim(), exitCode, timedOut, commandStarted);
    }

    private static String stripMarker(String raw, boolean[] markerFound) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder cleaned = new StringBuilder(raw.length());
        int start = 0;
        int len = raw.length();
        while (start < len) {
            int nl = raw.indexOf('\n', start);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > start && raw.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            if (contentEnd - start == COMMAND_STARTED.length()
                    && raw.regionMatches(start, COMMAND_STARTED, 0, COMMAND_STARTED.length())) {
                markerFound[0] = true;
            } else {
                cleaned.append(raw, start, nl < 0 ? len : nl + 1);
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return cleaned.toString().trim();
    }

    /**
     * Parses {@code pm list users} output without regular expressions.
     * Android's ICU regex engine rejects unescaped closing braces {@code }}
     * with {@code PatternSyntaxException}, so direct string scanning is used.
     */
    static List<DeviceUser> parseUsers(String output) {
        List<DeviceUser> result = new ArrayList<>();
        if (output == null || output.isEmpty()) return result;
        int searchFrom = 0;
        final String prefix = "UserInfo{";
        while (searchFrom < output.length()) {
            int start = output.indexOf(prefix, searchFrom);
            if (start < 0) break;
            int contentStart = start + prefix.length();
            int lineEnd = output.indexOf('\n', contentStart);
            if (lineEnd < 0) lineEnd = output.length();
            int closeBrace = output.indexOf('}', contentStart);
            if (closeBrace < 0 || closeBrace > lineEnd) {
                searchFrom = lineEnd + 1;
                continue;
            }
            String inside = output.substring(contentStart, closeBrace);
            String afterBrace = output.substring(closeBrace + 1, lineEnd);
            searchFrom = lineEnd + 1;

            int firstColon = inside.indexOf(':');
            if (firstColon <= 0) continue;
            String idPart = inside.substring(0, firstColon).trim();
            int secondColon = inside.indexOf(':', firstColon + 1);
            String rawName = (secondColon < 0
                    ? inside.substring(firstColon + 1)
                    : inside.substring(firstColon + 1, secondColon)).trim();
            String flagsPart = secondColon < 0 ? "" : inside.substring(secondColon + 1).trim();
            int thirdColon = flagsPart.indexOf(':');
            if (thirdColon >= 0) flagsPart = flagsPart.substring(0, thirdColon).trim();

            try {
                int id = Integer.parseInt(idPart);
                int flags = 0;
                if (!flagsPart.isEmpty()) {
                    try {
                        flags = Integer.parseInt(flagsPart, 16);
                    } catch (NumberFormatException ignored) { }
                }
                String name = rawName.isEmpty() ? (id == 0 ? "Owner" : "User " + id) : rawName;
                boolean running = afterBrace.toLowerCase(Locale.ROOT).contains("running");
                boolean guest = id != 0 && ("GuestUser".equalsIgnoreCase(name)
                        || "Guest".equalsIgnoreCase(name)
                        || (flags & FLAG_GUEST) != 0);
                result.add(new DeviceUser(id, name, running, guest));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    /**
     * Extracts the active user ID from {@code CURRENT_USER=<id>} or a standalone numeric
     * line, falling back to running user entries when {@code am get-current-user} is silent.
     */
    static int parseCurrentUser(String output, List<DeviceUser> users) {
        if (output != null && !output.isEmpty()) {
            final String tag = "CURRENT_USER=";
            int idx = output.indexOf(tag);
            if (idx >= 0) {
                int pos = idx + tag.length();
                while (pos < output.length() && (output.charAt(pos) == ' ' || output.charAt(pos) == '\t')) pos++;
                int end = pos;
                while (end < output.length() && Character.isDigit(output.charAt(end))) end++;
                if (end > pos) {
                    try { return Integer.parseInt(output.substring(pos, end)); } catch (NumberFormatException ignored) { }
                }
            }
            int start = 0;
            while (start < output.length()) {
                int nl = output.indexOf('\n', start);
                String line = (nl < 0 ? output.substring(start) : output.substring(start, nl)).trim();
                if (!line.isEmpty()) {
                    boolean allDigits = true;
                    for (int i = 0; i < line.length(); i++) {
                        if (!Character.isDigit(line.charAt(i))) { allDigits = false; break; }
                    }
                    if (allDigits) {
                        try { return Integer.parseInt(line); } catch (NumberFormatException ignored) { }
                    }
                }
                if (nl < 0) break;
                start = nl + 1;
            }
        }
        if (users != null) {
            int runningNonOwner = -1;
            boolean ownerRunning = false;
            for (int i = 0; i < users.size(); i++) {
                DeviceUser u = users.get(i);
                if (u.running) {
                    if (u.id == 0) ownerRunning = true;
                    else runningNonOwner = u.id;
                }
            }
            if (runningNonOwner >= 0) return runningNonOwner;
            if (ownerRunning) return 0;
        }
        return -1;
    }

    /** Detects error/exception lines in command output without regular expressions. */
    static boolean hasFailureLine(String output) {
        if (output == null || output.isEmpty()) return false;
        int start = 0;
        while (start < output.length()) {
            int nl = output.indexOf('\n', start);
            String line = (nl < 0 ? output.substring(start) : output.substring(start, nl))
                    .trim().toLowerCase(Locale.ROOT);
            if (line.startsWith("error:")
                    || line.startsWith("failure")
                    || line.startsWith("exception")
                    || line.startsWith("java.")
                    || line.startsWith("cannot ")) {
                return true;
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return false;
    }
}
