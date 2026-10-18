package com.example.usermanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs one operation once, in the same shell in which UID 0 is verified. */
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
        Process process = null;
        StringBuilder launchErrors = new StringBuilder();
        for (String candidate : candidates) {
            try {
                // Interactive stdin works with both traditional su and Magisk/SuperSU.
                // Only try another path if exec itself fails, never replay an operation.
                process = new ProcessBuilder(candidate).redirectErrorStream(true).start();
                break;
            } catch (IOException e) {
                launchErrors.append(candidate).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (process == null) return new Result("Cannot start a superuser shell. A working su/root manager must be installed.\n"
                + launchErrors, -1, false, false);

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
        boolean timedOut = false;
        int exitCode = -1;
        String failure = "";
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write("export PATH=/system/bin:/system/xbin:/sbin:$PATH\n"
                        + "if [ \"$(id -u)\" != \"0\" ]; then echo 'Superuser shell did not provide UID 0'; exit 77; fi\n"
                        + "echo '" + VERIFIED + "'\n"
                        + "(\n" + command + "\n)\n"
                        + "result=$?\nexit \"$result\"\n");
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                timedOut = true;
                process.destroyForcibly();
            } else exitCode = process.exitValue();
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "Root request cancelled.";
        } catch (IOException e) {
            failure = "Superuser shell closed: " + e.getMessage();
            try { reader.join(1000); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            process.destroyForcibly();
            // su may reject access before stdin is written (broken pipe). Still
            // collect its denial text rather than racing the output reader.
            try { reader.join(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }
        String output;
        synchronized (captured) { output = new String(captured.toByteArray(), StandardCharsets.UTF_8); }
        boolean verified = java.util.regex.Pattern.compile("(?m)^" + VERIFIED + "\\r?$").matcher(output).find();
        output = output.replaceAll("(?m)^" + VERIFIED + "\\r?\\n?", "").trim();
        if (!failure.isEmpty()) output += "\n" + failure;
        if (timedOut) output += "\nTimed out after " + timeoutSeconds
                + " seconds. The operation may have started; refresh before retrying.";
        return new Result(output.trim(), exitCode, timedOut, verified);
    }
}
