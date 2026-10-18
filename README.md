# Root Tools

A native Android app focused initially on multi-user management for rooted devices (Android 9 / API 28 and newer).

## Features

- Checks superuser access when first needed, with a maximum 25-second wait for the root manager to respond. On success, it remembers that root has previously been granted and shows a brief green confirmation.
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
