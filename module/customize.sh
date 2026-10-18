#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app, not recovery."
[ "$MAGISK_VER_CODE" -ge 24000 ] || abort "Magisk 24+ is required."
[ "$API" -ge 28 ] || abort "Android 9+ is required."
[ -s "$MODPATH/companion.apk" ] || abort "Missing companion APK: use the built release ZIP."
ui_print "Guest Guard: no boot image or multi-user property changes."
ui_print "Reboot, then open Guest Guard or tap the module Action."
ui_print "Grant Magisk root to the companion app when requested."
ui_print "Only verified, inactive guests can be deleted. Owner is protected."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/guestctl.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
