package com.example.usermanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host-side regression tests: fake su, no device/root permission needed. */
public final class RootShellTest {
    private static int passed;

    private static Path fakeSu(Path dir, String name, String script) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, ("#!/bin/sh\n" + script + "\n").getBytes(StandardCharsets.UTF_8));
        if (!file.toFile().setExecutable(true)) throw new AssertionError("Cannot make fake su executable");
        return file;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS: " + name);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("root-shell-test");
        try {
            Path granted = fakeSu(dir, "granted", "id() { echo 0; }\n[ \"$1\" = '-c' ] && eval \"$2\"");
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

            Path denied = fakeSu(dir, "denied", "echo 'Permission denied' >&2; exit 1");
            Path replay = dir.resolve("replayed");
            Path trap = fakeSu(dir, "trap", "touch '" + replay + "'; exit 1");
            result = new RootShell(new String[]{denied.toString(), trap.toString()}).run("echo unsafe", 3);
            check(!result.rootVerified && result.exitCode != 0 && result.output.contains("Permission denied")
                    && !Files.exists(replay), "denial is diagnosed without retrying another su: " + result.output);

            Path nonroot = fakeSu(dir, "nonroot", "id() { echo 2000; }\neval \"$(cat)\"");
            Path mutation = dir.resolve("mutation");
            result = new RootShell(new String[]{nonroot.toString()}).run("touch '" + mutation + "'", 3);
            check(!result.rootVerified && result.exitCode == 77 && !Files.exists(mutation),
                    "nonzero UID blocks privileged operation");

            result = new RootShell(new String[]{dir.resolve("missing").toString(), granted.toString()}).run("echo fallback", 3);
            check(result.rootVerified && result.exitCode == 0 && result.output.equals("fallback"),
                    "missing su executable falls back to next known path");

            result = new RootShell(new String[]{dir.resolve("missing").toString()}).run("id", 3);
            check(!result.rootVerified && result.output.contains("Cannot start"), "missing root installation is explained");

            Path slow = fakeSu(dir, "slow", "exec sleep 10");
            long start = System.nanoTime();
            result = new RootShell(new String[]{slow.toString()}).run("id", 1);
            check(result.timedOut && !result.rootVerified && (System.nanoTime() - start) < 5000000000L,
                    "unanswered approval times out without hanging");

            Path once = dir.resolve("once");
            result = shell.run("echo executed >> '" + once + "'; sleep 2", 1);
            check(result.timedOut && result.rootVerified && Files.readAllLines(once).size() == 1,
                    "timed-out operation is never replayed");
            System.out.println(passed + " root-shell regression tests passed.");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
