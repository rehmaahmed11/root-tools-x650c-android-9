package com.example.usermanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs one operation once, in the same shell in which UID 0 is verified.
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
    private static final String VERIFIED = "__ROOT_TOOLS_UID_ZERO__";
    private static final int OUTPUT_LIMIT = 128 * 1024;
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
        final boolean rootVerified;
        Result(String output, int exitCode, boolean timedOut, boolean rootVerified) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.rootVerified = rootVerified;
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
            } catch (IOException e) {
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
            // error and no verification marker).  The operation still executes at
            // most once.
            Process legacy = null;
            try {
                legacy = new ProcessBuilder(candidates[launched]).redirectErrorStream(true).start();
            } catch (IOException e) {
                return result;
            }
            Result retry = execute(legacy, script, timeoutSeconds, true);
            if (retry.rootVerified || !retry.output.isEmpty()) return retry;
        }
        return result;
    }

    private static String buildScript(String command) {
        return "export PATH=/system/bin:/system/xbin:/sbin:$PATH\n"
                + "if [ \"$(id -u)\" != \"0\" ]; then echo 'Superuser shell did not provide UID 0'; exit 77; fi\n"
                + "echo '" + VERIFIED + "'\n"
                + "(\n" + command + "\n)\n"
                + "result=$?\nexit \"$result\"\n";
    }

    /**
     * Detects the "this su does not support -c" case.  The verification marker is
     * printed before the operation runs, so its absence proves that nothing was
     * executed and that a retry is safe.
     */
    private static boolean rejectedDashC(Result result) {
        if (result.timedOut || result.rootVerified) return false;
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
            } catch (IOException ignored) { /* Process teardown closes the pipe. */ }
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
                } catch (IOException e) {
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
            } catch (IOException ignored) { }
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
        } finally {
            process.destroyForcibly();
            try { reader.join(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
        String output;
        synchronized (captured) { output = new String(captured.toByteArray(), StandardCharsets.UTF_8); }
        boolean verified = java.util.regex.Pattern.compile("(?m)^" + VERIFIED + "\\r?$").matcher(output).find();
        output = output.replaceAll("(?m)^" + VERIFIED + "\\r?\\n?", "").trim();
        if (failure.isEmpty() && writeFailure.get() != null) failure = writeFailure.get();
        if (!failure.isEmpty()) output += "\n" + failure;
        if (timedOut) output += "\nTimed out after " + timeoutSeconds
                + " seconds. The operation may have started; refresh before retrying.";
        return new Result(output.trim(), exitCode, timedOut, verified);
    }
}
