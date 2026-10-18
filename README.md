# Root Tools

A native Android app focused initially on multi-user management for rooted devices (Android 9 / API 28 and newer).

## Features

- Opens directly into the app without a startup root-check screen or `id` preflight. The interface opens immediately; `pm list users` is sent through `su` directly when Guest controls are opened.
- Privileged operations run directly through `su`; their real exit codes and output determine success. Output is drained concurrently to prevent pipe-buffer deadlocks; missing `su`, denials, and timeouts include diagnostic details. Commands are not automatically replayed after failures/timeouts.
- Lists Android device users and identifies the active profile when the system reports it.
- Creates users, switches profiles, renames users, and removes users through Android's privileged `pm` and `am` commands.
- Long-press a user (or tap its action menu) to see available actions. Deletion always requires confirmation; owner ID 0 cannot be renamed or deleted.
- Root commands are run off the UI thread and user-provided names are validated and shell-quoted.

Root access must be granted by the device's installed superuser manager. User switching and user-management support vary by Android build and device vendor. Removing a user permanently erases its data. This project does not include an APK binary; the GitHub Actions workflow builds a debug APK and uploads it as the `root-tools-debug-apk` artifact on each push, pull request, or manual run.

## CI

The `Build Android APK` workflow is a **chained tool pipeline**. Every stage
publishes its result as step outputs, and every later stage consumes them, so a
failure is always explained by the stage before it.

| # | Stage | Script | Publishes |
|---|---|---|---|
| 1 | Environment probe | `.github/scripts/probe-toolchain.sh` | `java_version`, `gradle_version`, `sdk_dir`, `build_tools` |
| 2 | Host-side regression tests | `tests/run-root-shell-tests.sh` | `test_status`, `test_passed`, `test_failed`, `test_failure` |
| 3 | APK build (layered fallbacks) | `.github/scripts/build-with-fallback.sh` | `build_status`, `build_mode`, `apk_sha256`, `apk_size_bytes` |
| 4 | Artifact verification | `.github/scripts/verify-apk.sh` | `apk_sha256`, `apk_size_bytes`, `apk_verified` |
| 5 | CI report | `.github/scripts/ci-report.sh` | `ci-report.md` / `.json` / `.txt` + job summary |
| 6 | Report job (always runs) | `.github/scripts/ci-report.sh` | `ci-report` artifact |

The build stage itself is still layered, so a run essentially cannot fail
without producing the APK:

1. Uses the Android SDK preinstalled on GitHub runners (no flaky SDK setup action); missing SDK packages are only fetched if absent, with retries.
2. Provisions Gradle 8.9 from cache, the repo wrapper, a compatible preinstalled Gradle, or three download mirrors with zip-integrity checks.
3. Retries the Gradle build up to 3 times (daemon reset + dependency refresh between attempts).
4. If Gradle is unusable for any reason, assembles and signs the same APK directly with raw SDK tools (`aapt2` / `javac` / `d8` / `zipalign` / `apksigner`) — a network-free path on GitHub runners.
5. Always uploads `root-tools-debug-apk` (with an upload retry); if anything still fails, a `build-diagnostics` artifact is published with the build logs.

The build engine lives in `.github/scripts/build-with-fallback.sh` and exits non-zero only if no APK could be produced at all.

### Reading the CI report

Every run — successful or not — leaves a report behind. Download it with:

```sh
gh run download <run-id> --name ci-report        # final consolidated report
gh run download <run-id> --name ci-report-run    # report written by the build job
gh run download <run-id> --name build-diagnostics --dir logs   # raw logs
```

| File | Purpose |
|---|---|
| `ci-report.md` | Human readable: verdict, tool chain table, artifact facts, extracted test/build/SDK evidence and a **What to do next** section. The same content is rendered in the run's job summary. |
| `ci-report.json` | The same facts as a single machine readable object — the easiest thing to parse when planning the next change. |
| `ci-report.txt` | The same facts without markup. |

The report names the exact stage that failed and quotes the relevant log
lines, so the next iteration can be planned from evidence instead of guesswork.

### Guardrails against repeat failures

- The runner image is pinned to `ubuntu-24.04` so the scheduled
  `ubuntu-latest` image change cannot break a build unnoticed.
- Actions are pinned to their Node 24 majors (`checkout@v7`, `setup-java@v6`,
  `cache@v6`, `upload-artifact@v7`, `download-artifact@v8`).
- A failing test suite no longer aborts the build: the APK is still produced
  and the report is still written. A **gate** step at the end of the job turns
  the test failure into a red build, so nothing is silently swallowed.
- The test runner has a hard timeout, so a hanging fake `su` can never burn
  the whole job budget.
- The root-shell test fixtures speak both `su` protocols, so a change to the
  invocation convention cannot silently invalidate the suite.

## Build locally

With JDK 17, Android SDK platform 35, and Gradle 8.9 installed:

```sh
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Root command troubleshooting

Root Tools opens without a separate startup root check or a **Check root** button.
Opening Guest controls sends the real user-list command through `su` directly;
the home screen does not request root. The Guest controls page also has a manual
refresh. If the root manager asks for approval, that request is for the actual
Android operation. The phone still needs a functional `su` installation—an
Android app-permissions toggle cannot provide root access.

A failed command reports its own output in Guest controls or in the action error
dialog. Root approval alone does not guarantee multi-user support; Android/vendor
restrictions or unsupported commands may still cause an operation to fail. After
an operation times out, refresh the list before retrying because the system
service may still finish it. Never retry a destructive operation blindly. If an
update reports a signature mismatch (CI debug signing keys can change),
uninstall the old Root Tools app and install the new APK; this clears app settings.

### Regression tests

With JDK 17 or newer on Linux/macOS:

```sh
bash tests/run-root-shell-tests.sh
```

Tests use fake superuser executables; no real root or Android device is needed.
They cover direct command execution (without a separate UID probe), denial
diagnostics, executable-path fallbacks, large output, exit codes, timeouts
without command replay, regex-free `pm list users` and active-user parsing,
and error-line detection. They also pin the `su` invocation contract: the suite
asserts that the modern `su -c <script>` protocol is used, that a `su` without
`-c` support still works through the legacy stdin protocol, and that a `su`
rejecting both fails fast instead of hanging. CI runs them before building the APK.

If `javac` is unavailable, set `ECJ_JAR` to an Eclipse compiler jar
(`org.eclipse.jdt.internal.compiler.batch.Main`) to run the suite anyway.

A physical rooted-device test is still required to validate device-specific
root managers, SELinux policy, and user-management support.
