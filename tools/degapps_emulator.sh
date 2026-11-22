#!/usr/bin/env bash
# Turn a google_apis emulator (started with -writable-system) into a GApps-free device:
# removes GmsCore, GSF, Play LicenseChecker and the preinstalled Google apps under test.
set -uo pipefail
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
wait_boot() { until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 15; done; }

$ADB wait-for-device; wait_boot
$ADB root; sleep 5; $ADB wait-for-device
if ! $ADB shell 'touch /system/.rw_test && rm /system/.rw_test' 2>/dev/null; then
  $ADB disable-verity
  $ADB reboot; sleep 30; $ADB wait-for-device; wait_boot
  $ADB root; sleep 5; $ADB wait-for-device
fi
$ADB remount
for pkg in com.google.android.gms com.google.android.gsf com.android.vending \
           com.google.android.gm com.google.android.apps.maps com.android.chrome; do
  for path in $($ADB shell pm list packages -f "$pkg" | tr -d '\r' | grep "=$pkg\$" | sed 's/^package://; s/=.*//'); do
    dir=$(dirname "$path")
    echo "removing $pkg ($dir)"
    $ADB shell rm -rf "$dir"
  done
  $ADB shell pm uninstall "$pkg" >/dev/null 2>&1   # drop any /data updates
done
$ADB reboot; sleep 30; $ADB wait-for-device; wait_boot
echo "--- remaining GMS-related packages:"
$ADB shell pm list packages | grep -E "android\.gms$|gsf$|vending$|\.gm$|apps\.maps$|android\.chrome$" || echo "none (GApps-free)"
$ADB shell getprop ro.product.cpu.abilist
