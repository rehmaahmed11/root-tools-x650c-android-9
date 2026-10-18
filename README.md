# Root Tools

A native Android app focused initially on multi-user management for rooted devices (Android 9 / API 28 and newer).

## Features

- Checks superuser access when first needed, with a maximum 25-second wait for the root manager to respond. On success, it remembers that root has previously been granted and shows a brief green confirmation.
- Lists Android device users and identifies the active profile when the system reports it.
- Creates users, switches profiles, renames users, and removes users through Android's privileged `pm` and `am` commands.
- Long-press a user (or tap its action menu) to see available actions. Deletion always requires confirmation; owner ID 0 cannot be renamed or deleted.
- Root commands are run off the UI thread and user-provided names are validated and shell-quoted.

Root access must be granted by the device's installed superuser manager. User switching and user-management support vary by Android build and device vendor. Removing a user permanently erases its data. This project does not include an APK binary; the GitHub Actions workflow builds a debug APK and uploads it as the `root-tools-debug-apk` artifact on each push, pull request, or manual run.

## Build locally

With JDK 17, Android SDK platform 35, and Gradle 8.9 installed:

```sh
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
