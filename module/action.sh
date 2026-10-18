#!/system/bin/sh
MODDIR=${0%/*}
exec /system/bin/sh "$MODDIR/bin/guestctl.sh" launch
