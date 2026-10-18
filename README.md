# Guest Guard — Magisk User Manager

A clean rebuild of this repository as a **Magisk module with a bundled native Android GUI**. It uses your existing Magisk root; it is not a rooting tool. The old app, Gradle project and fallback-build stack have been removed. The existing GitHub Actions workflow location is retained and now builds a flashable module ZIP.

**Android 9 / API 28+, Magisk 24+, owner user 0.** Designed with the Infinix X650C / Android 9 use case in mind, but not yet tested on that physical device. OEM user-management commands and SELinux policies can differ. No device-specific support is promised before testing.

## Install the ZIP

1. Back up important files and make sure you know your owner lock-screen PIN.
2. In **Magisk → Modules → Install from storage**, select `GuestGuard-v2.0.0.zip`.
3. Reboot normally. A late-start service installs the bundled **Guest Guard** app for owner user 0 once Android finishes booting.
4. Open **Guest Guard** in your app launcher, or tap the module's **Action** button in a Magisk version that supports module Actions.
5. Tap **Connect & load users**, then grant Guest Guard root in **Magisk → Superuser**.

The app is **inside the module ZIP**; no separate WebUI, browser, Internet permission or extra manager is needed. Magisk controls its own module options layout; this module supplies `action.sh` to open the full app, not a custom embedded menu inside Magisk. If your Magisk version has no Action button, use the launcher.

**GitHub Actions:** download the `GuestGuard-v2.0.0-Magisk` artifact, extract the artifact wrapper, and flash the **inner** `GuestGuard-v2.0.0.zip`. A GitHub source ZIP is **not** a flashable module. Build outputs are intentionally not committed to Git.

## What's in the GUI

| Screen | Details and controls |
|---|---|
| Overview | Current user name and ID, total existing accounts, verified guests, protected accounts, Android-reported maximum, nominal remaining capacity, Android version, data-volume free space, read-only `fw.max_users` value |
| Users | All accounts returned by Android, active marker, owner/guest/protected classification, raw Android flags, switch to owner/guest/regular full users, create guest, confirmed guest deletion |
| Safety | Safeguards, compatibility information, recovery guidance, and a shortcut to Android's own user settings |
| Diagnostics | Root/command errors, live refresh and copyable status report that omits account names |

Regular full users can be switched to but never deleted or renamed. Managed/work profiles remain read-only. Guest creation does **not** automatically switch sessions. Save work before switching and unlock the target user normally; the module cannot bypass PINs or encryption.

### Capacity is honest, not “unlimited”

- The primary limit comes from `pm get-max-users`, not a guessed constant. Unsupported or invalid responses show **unknown**.
- Counts cover the `UserInfo` records Android returns. Nominal free slots are `max(0, reported maximum − listed accounts)`.
- This is informational, **not a promise of how many guests you can create**. Android counts profiles, removing accounts, guests and full users differently. Storage, administrator restrictions and OEM policy also affect creation.
- Many Android 9 ROMs permit only **one guest** at a time, independently of their full-user maximum. This module reports a rejected creation with Android's actual error and never falls back to a regular user.
- **Unlimited users are not supported.** Setting a huge `fw.max_users` value cannot guarantee unlimited accounts and can destabilize low-memory devices. This module does not change limits or enable unsupported multi-user features.

## Deletion protection

Checks run in both the GUI and a root backend that re-reads Android's state immediately before acting:

- **User ID 0 is never a deletion target**, regardless of its name or flags.
- Only nonzero users with Android's **guest bit (`0x4`)** are eligible.
- Primary, administrator, restricted, managed-profile, system, profile and main flags override the guest bit: conflicting types remain protected.
- Missing, malformed or duplicate user records block changes. User type is never inferred from a name such as “Guest”.
- Creation and deletion require the current owner session. The active guest cannot be deleted.
- Each deletion requires typing `DELETE-GUEST-ID`. Deletion permanently erases that guest's apps and data.
- No bulk deletion, regular-user creation, owner rename, credential reset, automatic guest expiry or automatic user switching.
- Mutations are serialized using a root-owned `/dev/.guestguard-operation` directory. Read-only refresh is still available. Reboot clears an interrupted lock.
- Commands have bounded output and a 60-second app-side wait. Failed/timed-out operations are **never replayed automatically**. Android may still complete an operation after the app stops waiting; refresh before deciding what to do next.

These guarantees apply to **this module's operations**, not to Android Settings, other root apps, an administrator or someone manually running `pm remove-user`. The external Settings screen has its own controls. Do not run simultaneous user changes from other tools: Android's shell API does not provide an atomic “remove only if guest” transaction.

## Safe defaults and recommended additions

Included: typed confirmation, guest-only operations, owner-session restriction, no bulk delete, storage visibility, root diagnostics, no boot-time user changes, and an owner-return reminder.

Deliberately excluded: fake unlimited capacity, forced enabling of unsupported multi-user mode, automatic data erasure, modifications to `/data/system/users`, SELinux changes, `system.prop`, boot-image patches and network root-command servers.

A future optional feature could be **guest reminders** (notify, never auto-delete). Test normal switching and one disposable guest on the actual device before considering additional operations.

## Troubleshooting and removal

- **No app after reboot:** boot as owner, allow Android to finish starting, then tap Magisk's module Action. Installation diagnostics are in `/data/adb/guestguard/install.log` (root-only). `build/companion.apk` is also available as a CI artifact for manual owner-side installation.
- **Root denied:** grant `dev.guestguard` / Guest Guard in Magisk Superuser, then refresh. This app cannot grant root to itself.
- **Backend missing/disabled:** install the built module ZIP and reboot. A standalone APK is not a replacement for the module.
- **Create rejected:** read the actual Android error. No regular user or limit tweak is attempted. If the only guest is active, return to owner, back up the guest and delete it explicitly before creating a replacement.
- **Switching unsupported:** use the stock user switcher if available. Some OEM builds disable multi-user entirely; this module intentionally does not force-enable it.
- **Interrupted request / stale lock:** refresh first. Reboot if new changes remain blocked. No operation is resumed automatically.
- **Signing mismatch on update:** never auto-uninstall. Local builds reuse `.signing/development.jks`; ordinary CI runs generate new development keys. Keep a stable private keystore for your own builds using `KEYSTORE`, `KEY_ALIAS` and `KEY_PASS`. If manually uninstalling a mismatched companion, only app settings/root approval are reset; **Android users are not removed**. Reinstall the bundled app afterward. Old `com.example.usermanager` apps are not removed automatically; uninstall the obsolete Root Tools app yourself.
- **Remove:** uninstall the module from Magisk and reboot, then uninstall Guest Guard from owner Settings if desired. No Android user is deleted. The small root-only install log may remain in `/data/adb/guestguard`.

## Source layout

```text
module/                  Magisk metadata, installer, Action, service, root backend
companion/               dependency-free native Android Java GUI and icon
scripts/build.sh         compile, dex, sign, verify, package
scripts/package.py       deterministic ZIP layout and permission checks
tests/test_backend.py   fake Android command safety tests (no real device needed)
.github/workflows/android-apk.yml  preserved workflow location, rebuilt module job
```

## Build and test

With JDK 17, Python 3, `zip`, Android SDK platform 35 and build-tools 35.0.0:

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 -m unittest discover -s tests -v
bash tests/run-companion-tests.sh
bash scripts/build.sh
```

Outputs:

- `dist/GuestGuard-v2.0.0.zip` — **flash this** in Magisk.
- `dist/GuestGuard-v2.0.0.zip.sha256` — integrity checksum.
- `build/companion.apk` — signed GUI bundled inside the ZIP.

The backend explicitly uses Magisk’s bundled BusyBox `awk` because Android 9 may not include a system `awk`.

The build uses SDK `aapt2`, `javac`, `d8` and `apksigner` directly, without Gradle or external app libraries. Advanced/offline environments can override `JAVA`, `JAVAC`, `KEYTOOL`, `AAPT2`, `ANDROID_JAR`, `D8`, `APKSIGNER`; `ECJ_JAR`, `D8_JAR`, `DX_JAR` and `APKSIGNER_JAR` are optional host-tool alternatives. Host tools are not shipped in the module. The companion uses Android framework APIs only.

Host tests simulate owner/guest/profile flags, root denial, malformed records, wrong confirmation, command injection attempts, current-user failures, capacity responses, guest-quota failures, misleading exit codes, action launch, locks, disabled modules and install-signature failures. The companion model/quoting suite adds 19 JVM assertions. The ZIP packager checks required files, APK structure, archive integrity and executable modes; `apksigner` verifies the APK signature. **These are host-side checks, not a successful flash or real-device safety certification.**
