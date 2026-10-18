package dev.guestguard;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Single explicit request, bounded output, no replay or shell interpolation of data. */
final class RootClient {
    static final String BACKEND = "/data/adb/modules/guestguard/bin/guestctl.sh";
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static String run(String... args) throws Exception {
        StringBuilder command = new StringBuilder("/system/bin/sh " + quote(BACKEND));
        for (String arg : args) command.append(' ').append(quote(arg));
        final Process process;
        try {
            process = new ProcessBuilder("su", "-c", command.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Magisk su could not be started. Check that Magisk root is enabled.", e);
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                byte[] buffer = new byte[4096];
                try {
                    InputStream in = process.getInputStream();
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        synchronized (output) {
                            if (output.size() < 131072) output.write(buffer, 0, Math.min(n, 131072 - output.size()));
                        }
                    }
                } catch (IOException ignored) { }
            }
        }, "root-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                throw new IOException("Root request timed out. It was NOT retried. Android may still finish it. Refresh before doing anything else. Check Magisk Superuser for Guest Guard.");
            }
            reader.join(2000);
            String result;
            synchronized (output) { result = new String(output.toByteArray(), "UTF-8").trim(); }
            if (process.exitValue() != 0) throw new IOException(result.length() == 0 ? "Root denied or command failed. Grant Guest Guard in Magisk Superuser." : result);
            if (reader.isAlive()) throw new IOException("Output did not finish. Refresh to check actual state; do not repeat the action blindly.");
            return result;
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            process.destroy();
        }
    }
}
