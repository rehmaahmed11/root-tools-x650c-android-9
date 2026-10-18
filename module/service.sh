#!/system/bin/sh
MODDIR=${0%/*}
# Late-start service only installs the companion. It never changes users.
tries=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$tries" -lt 120 ]; do
  sleep 2
  tries=$((tries + 1))
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
mkdir -p /data/adb/guestguard
chmod 700 /data/adb/guestguard
# Locks live on tmpfs so a reboot clears any interrupted operation.
/system/bin/sh "$MODDIR/bin/guestctl.sh" install > /data/adb/guestguard/install.log 2>&1
chmod 600 /data/adb/guestguard/install.log
