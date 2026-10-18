package com.example.usermanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Host-side regression tests: fake su, no device/root permission needed. */
public final class RootShellTest {
    private static int passed;

    /**
     * Writes a fake {@code su} that executes the requested script and understands
     * BOTH invocation protocols used by {@link RootShell}: the modern
     * {@code su -c <script>} form and the legacy form where the script is piped
     * to the shell on stdin.  Keeping the fixture protocol-agnostic means the
     * tests keep working when the invocation convention changes.
     */
    private static Path fakeSu(Path dir, String name, String preamble) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, ("#!/bin/sh\n"
                + (preamble == null ? "" : preamble + "\n")
                + "if [ \"$1\" = '-c' ] && [ -n \"$2\" ]; then\n"
                + "  __root_tools_script=\"$2\"\n"
                + "else\n"
                + "  __root_tools_script=\"$(cat)\"\n"
                + "fi\n"
                + "eval \"$__root_tools_script\"\n").getBytes(StandardCharsets.UTF_8));
        if (!file.toFile().setExecutable(true)) throw new AssertionError("Cannot make fake su executable");
        return file;
    }

    /**
     * Writes a fake {@code su} that never runs the payload: it denies access,
     * pretends to hang, or simply ignores whatever it was asked to do.
     */
    private static Path inertSu(Path dir, String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        if (!file.toFile().setExecutable(true)) throw new AssertionError("Cannot make fake su executable");
        return file;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS: " + name);
    }

    public static void main(String[] args) {
        Path dir = null;
        int status = 0;
        try {
            dir = Files.createTempDirectory("root-shell-test");
            runAll(dir);
        } catch (AssertionError e) {
            System.out.println("FAIL: " + e.getMessage());
            System.out.flush();
            e.printStackTrace(System.err);
            System.err.flush();
            status = 1;
        } catch (Exception e) {
            System.out.println("FAIL: unexpected error: " + e);
            System.out.flush();
            e.printStackTrace(System.err);
            System.err.flush();
            status = 1;
        } finally {
            if (dir != null) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception ignored) { }
            }
        }
        System.exit(status);
    }

    private static void runAll(Path dir) throws Exception {
        Path granted = fakeSu(dir, "granted", "id() { echo 0; }");
        RootShell shell = new RootShell(new String[]{granted.toString()});
        RootShell.Result result = shell.run("echo 'Success: created user id 10'", 3);
        check(result.rootVerified && result.exitCode == 0 && !result.timedOut
                && result.output.equals("Success: created user id 10"), "grant verifies UID and runs operation immediately");

        result = shell.run("echo failure >&2; exit 9", 3);
        check(result.rootVerified && result.exitCode == 9 && result.output.contains("failure"),
                "operation failure retains root status and stderr");

        result = shell.run("i=0; while [ $i -lt 15000 ]; do echo 01234567890123456789; i=$((i+1)); done", 5);
        check(result.exitCode == 0 && !result.timedOut && result.output.length() <= 128 * 1024,
                "large output is drained without pipe deadlock and memory is bounded");

        Path denied = inertSu(dir, "denied", "echo 'Permission denied' >&2; exit 1");
        Path replay = dir.resolve("replayed");
        Path trap = inertSu(dir, "trap", "touch '" + replay + "'; exit 1");
        result = new RootShell(new String[]{denied.toString(), trap.toString()}).run("echo unsafe", 3);
        check(!result.rootVerified && result.exitCode != 0 && result.output.contains("Permission denied")
                && !Files.exists(replay), "denial is diagnosed without retrying another su: " + result.output);

        Path nonroot = fakeSu(dir, "nonroot", "id() { echo 2000; }");
        Path mutation = dir.resolve("mutation");
        result = new RootShell(new String[]{nonroot.toString()}).run("touch '" + mutation + "'", 3);
        check(!result.rootVerified && result.exitCode == 77 && !Files.exists(mutation),
                "nonzero UID blocks privileged operation");

        result = new RootShell(new String[]{dir.resolve("missing").toString(), granted.toString()}).run("echo fallback", 3);
        check(result.rootVerified && result.exitCode == 0 && result.output.equals("fallback"),
                "missing su executable falls back to next known path");

        result = new RootShell(new String[]{dir.resolve("missing").toString()}).run("id", 3);
        check(!result.rootVerified && result.output.contains("Cannot start"), "missing root installation is explained");

        Path slow = inertSu(dir, "slow", "exec sleep 10");
        long start = System.nanoTime();
        result = new RootShell(new String[]{slow.toString()}).run("id", 1);
        check(result.timedOut && !result.rootVerified && (System.nanoTime() - start) < 5000000000L,
                "unanswered approval times out without hanging");

        Path once = dir.resolve("once");
        result = shell.run("echo executed >> '" + once + "'; sleep 2", 1);
        check(result.timedOut && result.rootVerified && Files.readAllLines(once).size() == 1,
                "timed-out operation is never replayed");

        // The modern `su -c` protocol is what real root managers expect, so the
        // fake su records the argument it was given.
        Path argvLog = dir.resolve("argv");
        Path recorder = fakeSu(dir, "recorder", "printf '%s' \"$1\" > '" + argvLog + "'\nid() { echo 0; }");
        result = new RootShell(new String[]{recorder.toString()}).run("echo protocol", 3);
        String argv = new String(Files.readAllBytes(argvLog), StandardCharsets.UTF_8);
        check(result.rootVerified && "-c".equals(argv), "the modern su -c protocol is used");

        // A root manager whose su does not understand `-c` must still work: the
        // same binary is retried with the legacy stdin protocol.
        Path legacyOnly = dir.resolve("legacy-only");
        Files.write(legacyOnly, ("#!/bin/sh\n"
                + "if [ \"$1\" = '-c' ]; then\n"
                + "  echo 'su: unrecognized option -c' >&2\n"
                + "  echo 'Usage: su [-l] [-c COMMAND]' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "id() { echo 0; }\n"
                + "eval \"$(cat)\"\n").getBytes(StandardCharsets.UTF_8));
        if (!legacyOnly.toFile().setExecutable(true)) throw new AssertionError("Cannot make fake su executable");
        result = new RootShell(new String[]{legacyOnly.toString()}).run("echo legacy", 3);
        check(result.rootVerified && result.exitCode == 0 && result.output.equals("legacy"),
                "su without -c support falls back to the stdin protocol: " + result.output);

        // A su that rejects `-c` and also ignores stdin must fail fast and loudly
        // instead of hanging the app.
        Path rejectOnly = dir.resolve("reject-only");
        Files.write(rejectOnly, ("#!/bin/sh\n"
                + "if [ \"$1\" = '-c' ]; then\n"
                + "  echo 'su: unrecognized option -c' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "echo 'su: this build does not read a script from stdin' >&2\n"
                + "exit 1\n").getBytes(StandardCharsets.UTF_8));
        if (!rejectOnly.toFile().setExecutable(true)) throw new AssertionError("Cannot make fake su executable");
        start = System.nanoTime();
        result = new RootShell(new String[]{rejectOnly.toString()}).run("echo never", 3);
        check(!result.rootVerified && result.exitCode != 0 && (System.nanoTime() - start) < 5000000000L,
                "a su that rejects -c and ignores stdin fails fast with a clear error: " + result.output);

        System.out.println(passed + " root-shell regression tests passed.");
    }
}
