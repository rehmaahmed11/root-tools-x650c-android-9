#!/system/bin/sh
# Root-only backend. No eval, no arbitrary command API, no user XML edits.
set -f
umask 077
MODDIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
fail() { echo "ERROR: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "Magisk root was not granted."
# Android 9 often has no system awk. Use Magisk's bundled applet explicitly.
BB=/data/adb/magisk/busybox
[ -x "$BB" ] || fail "Magisk BusyBox is missing; repair the Magisk installation."
awk() { "$BB" awk "$@"; }
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || fail "Module is disabled or pending removal."
[ "$(getprop sys.boot_completed)" = 1 ] || fail "Wait for Android to finish booting."

valid_id() {
  case "$1" in ''|*[!0-9]*|0[0-9]*) return 1;; esac
  [ "${#1}" -le 6 ]
}
current() {
  CURRENT=$(am get-current-user 2>/dev/null) || fail "Cannot determine active user."
  valid_id "$CURRENT" || fail "Invalid active-user response."
}
# Parse canonical UserInfo records. Names may contain colons; flags are hexadecimal.
# Any unparseable UserInfo line, duplicate ID or missing owner fails closed.
load_users() {
  RAW=$(pm list users 2>&1) || fail "Cannot list users: $RAW"
  USERS=$(printf '%s\n' "$RAW" | awk '
    /UserInfo\{/ {
      s=$0; sub(/^.*UserInfo\{/, "", s)
      if (s !~ /^[0-9]+:.*:[0-9a-fA-F]+\}([[:space:]].*)?$/) { bad=1; next }
      id=s; sub(/:.*/, "", id)
      if (length(id)>6 || (id ~ /^0[0-9]/) || seen[id]++) { bad=1; next }
      flags=s; sub(/^.*:/, "", flags); sub(/\}.*/, "", flags)
      if (length(flags)>6) { bad=1; next }
      name=s; sub(/^[0-9]+:/, "", name); sub(/:[0-9a-fA-F]+\}.*/, "", name)
      gsub(/[[:cntrl:]]/, " ", name)
      print id "\t" flags "\t" name
      count++; if (id == "0") owner=1
    }
    END { if (bad || !owner || !count) exit 1 }
  ') || fail "Unrecognized user listing. All changes blocked for safety."
}
find_user() {
  valid_id "$1" || fail "Invalid user ID."
  RECORD=$(printf '%s\n' "$USERS" | awk -F '\t' -v id="$1" '$1 == id {print}')
  [ -n "$RECORD" ] || fail "User no longer exists; refresh."
  HEX=$(printf '%s\n' "$RECORD" | cut -f2)
  FLAGS=$((0x$HEX))
}
# Positive guest bit required. Primary/admin/restricted/managed/system/profile/main
# flags take precedence over guest, even if a vendor returns conflicting flags.
verified_guest() { [ "$1" != 0 ] && [ $((FLAGS & 4)) -ne 0 ] && [ $((FLAGS & 22571)) -eq 0 ]; }
owner_active() { current; [ "$CURRENT" = 0 ] || fail "Return to owner user 0 before managing guests."; }
lock() {
  LOCK=/dev/.guestguard-operation
  mkdir "$LOCK" 2>/dev/null || fail "Another operation is in progress. Refresh before retrying; reboot if a stale lock persists."
  trap 'rmdir "$LOCK" 2>/dev/null' EXIT
  trap 'exit 1' HUP INT TERM
}
install_app() {
  [ -s "$MODDIR/companion.apk" ] || fail "Bundled companion APK is missing."
  SIZE=$(wc -c < "$MODDIR/companion.apk" | tr -d ' ')
  # stdin avoids asking system_server to traverse the private /data/adb directory.
  RESULT=$(pm install --user 0 -r -S "$SIZE" < "$MODDIR/companion.apk" 2>&1)
  CODE=$?
  [ "$CODE" = 0 ] && printf '%s\n' "$RESULT" | grep -q '^Success' || fail "Companion install failed: $RESULT. Never uninstall automatically; check signing compatibility."
  echo "Companion installed for owner user 0."
}
[ "$#" -ge 1 ] || fail "Expected status, create-guest, switch, delete-guest, install or launch."
COMMAND=$1; shift
case "$COMMAND" in
  status)
    [ "$#" = 0 ] || fail "Unexpected arguments."
    current; load_users
    MAX_RAW=$(pm get-max-users 2>&1) || MAX_RAW=unknown
    MAX=$(printf '%s\n' "$MAX_RAW" | sed -n 's/^Maximum supported users: \([0-9][0-9]*\)[[:space:]]*$/\1/p')
    valid_id "$MAX" || MAX=unknown
    [ "$MAX" != 0 ] || MAX=unknown
    echo "CURRENT	$CURRENT"
    echo "MAX	$MAX"
    echo "PROPERTY_MAX	$(getprop fw.max_users | tr -cd '0-9' | cut -c1-6)"
    echo "ANDROID	$(getprop ro.build.version.release | tr -cd '0-9A-Za-z._-')"
    # Names are display data only, never interpreted as code.
    printf '%s\n' "$USERS" | while IFS="$(printf '\t')" read -r ID HEX NAME; do
      FLAGS=$((0x$HEX)); TYPE=protected
      if verified_guest "$ID"; then TYPE=guest; fi
      [ "$ID" != 0 ] || TYPE=owner
      printf 'USER\t%s\t%s\t%s\t%s\n' "$ID" "$TYPE" "$HEX" "$NAME"
    done
    ;;
  create-guest)
    [ "$#" = 1 ] || fail "Expected a guest name."
    NAME=$1
    [ -n "$NAME" ] && [ "${#NAME}" -le 32 ] || fail "Name must be 1–32 ASCII characters."
    case "$NAME" in -*|*[!a-zA-Z0-9\ ._-]*) fail "Use letters, numbers, spaces, dot, dash or underscore; no leading dash.";; esac
    lock; owner_active; load_users
    # Android enforces its real guest quota, storage checks and restrictions.
    # Never change fw.max_users or silently fall back to creating a regular user.
    RESULT=$(pm create-user --guest "$NAME" 2>&1); CODE=$?
    [ "$CODE" = 0 ] && printf '%s\n' "$RESULT" | grep -q '^Success: created user id ' || fail "Guest creation failed: $RESULT"
    echo "$RESULT"
    ;;
  switch)
    [ "$#" = 1 ] || fail "Expected user ID."
    lock; current; load_users; find_user "$1"
    [ "$1" != "$CURRENT" ] || fail "This user is already active."
    # Full regular users are safe switch targets, but never deletion targets.
    # Managed/system/profile records cannot be switched like foreground users.
    [ "$1" = 0 ] || [ $((FLAGS & 6176)) -eq 0 ] || fail "This account is a system user or profile, not a foreground switch target."
    [ $((FLAGS & 64)) = 0 ] || fail "This user is disabled."
    RESULT=$(am switch-user "$1" 2>&1); CODE=$?
    [ "$CODE" = 0 ] || fail "Switch failed: $RESULT"
    case "$RESULT" in *Error*|*error*|*Exception*|*Failure*) fail "Switch rejected: $RESULT";; esac
    echo "Switch requested. Unlock the target user; refresh to confirm the active ID."
    ;;
  delete-guest)
    [ "$#" = 2 ] || fail "Expected guest ID and confirmation DELETE-GUEST-ID."
    valid_id "$1" || fail "Invalid ID."
    [ "$2" = "DELETE-GUEST-$1" ] || fail "Deletion was not explicitly confirmed."
    lock; owner_active; load_users; find_user "$1"
    verified_guest "$1" || fail "Deletion blocked: only verified guests can be deleted. Owner, regular users and profiles are protected."
    current
    [ "$1" != "$CURRENT" ] || fail "Cannot delete the active user. Return to owner first."
    RESULT=$(pm remove-user "$1" 2>&1); CODE=$?
    [ "$CODE" = 0 ] && printf '%s\n' "$RESULT" | grep -q '^Success: removed user' || fail "Guest removal failed: $RESULT"
    echo "$RESULT"
    ;;
  install)
    [ "$#" = 0 ] || fail "Unexpected arguments."
    lock; install_app
    ;;
  launch)
    [ "$#" = 0 ] || fail "Unexpected arguments."
    owner_active
    # Action also works before first reboot once Android is booted.
    if ! pm path --user 0 dev.guestguard | grep -q '^package:'; then
      lock; install_app
    fi
    am start --user 0 -n dev.guestguard/.MainActivity || fail "Could not open GUI. Open Guest Guard from the owner launcher."
    ;;
  *) fail "Unknown operation.";;
esac
