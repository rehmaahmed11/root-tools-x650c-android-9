# Root Tools

A native Android app focused initially on multi-user management for rooted devices (Android 9 / API 28 and newer).

## Features

- Requests and verifies a real UID 0 shell on launch and when returning from the root manager, with up to 60 seconds for approval. Never treats a saved permission flag as proof of root. After verification, it immediately loads users and shows a green confirmation.
- Every privileged operation verifies UID 0 again in the same shell before executing. Output is drained concurrently to prevent pipe-buffer deadlocks; missing `su`, denials, and timeouts include diagnostic details. Commands are not automatically replayed after failures/timeouts.
- Lists Android device users and identifies the active profile when the system reports it.
- Creates users, switches profiles, renames users, and removes users through Android's privileged `pm` and `am` commands.
- Long-press a user (or tap its action menu) to see available actions. Deletion always requires confirmation; owner ID 0 cannot be renamed or deleted.
- Root commands are run off the UI thread and user-provided names are validated and shell-quoted.

Root access must be granted by the device's installed superuser manager. User switching and user-management support vary by Android build and device vendor. Removing a user permanently erases its data. This project does not include an APK binary; the GitHub Actions workflow builds a debug APK and uploads it as the `root-tools-debug-apk` artifact on each push, pull request, or manual run.

## CI

The `Build Android APK` workflow is designed so that a run essentially cannot fail without producing the APK:

1. Uses the Android SDK preinstalled on GitHub runners (no flaky SDK setup action); missing SDK packages are only fetched if absent, with retries.
2. Provisions Gradle 8.9 from cache, the repo wrapper, a compatible preinstalled Gradle, or three download mirrors with zip-integrity checks.
3. Retries the Gradle build up to 3 times (daemon reset + dependency refresh between attempts).
4. If Gradle is unusable for any reason, assembles and signs the same APK directly with raw SDK tools (`aapt2` / `javac` / `d8` / `zipalign` / `apksigner`) — a network-free path on GitHub runners.
5. Always uploads `root-tools-debug-apk` (with an upload retry); if anything still fails, a `build-diagnostics` artifact is published with the build logs.

The build engine lives in `.github/scripts/build-with-fallback.sh` and exits non-zero only if no APK could be produced at all.

## Build locally

With JDK 17, Android SDK platform 35, and Gradle 8.9 installed:

```sh
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Root troubleshooting (1.0.2)

Install the **newly built** APK (version 1.0.2 / code 3), not an older downloaded artifact. This release uses an explicit `su -c` request so Android root managers receive a per-app access request, and shows an immediate in-app toast while waiting.
Open Root Tools, allow its prompt in Magisk/SuperSU/your installed root manager,
and wait for **ROOT READY**. The user list loads automatically. Return to the app
or tap the root badge / Check root to reverify after changing root-manager settings.
An Android app-permissions toggle alone does not provide root; the phone must
already have a functional `su` installation. No Android runtime permission can
replace this.

If verification fails, the dialog reports the shell's actual error. If root is
verified but a user operation fails, Android/vendor restrictions or unsupported
commands may be responsible. Root approval alone does not guarantee multi-user
support. After an operation times out, refresh the list before retrying: the
system service may still finish it. Never retry a destructive operation blindly.
If an update reports a signature mismatch (CI debug signing keys can change),
uninstall the old Root Tools app and install the new APK; this clears app settings.

### Regression tests

With JDK 17 or newer on Linux/macOS:

```sh
bash tests/run-root-shell-tests.sh
```

Tests use fake superuser executables; no real root or Android device is needed.
They cover approval, non-root rejection, denial diagnostics, executable-path
fallbacks, large output, exit codes, and timeouts without command replay. CI runs
them before building the APK. A physical rooted-device test is still required to
validate device-specific root managers, SELinux policy, and user-management support.
