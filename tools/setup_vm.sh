#!/usr/bin/env bash
# GApps-free test VM: API 30 google_apis x86_64 (has ARM translation) with GmsCore hidden and
# GSF / Play LicenseChecker uninstalled for user 0. Works without KVM (TCG, ~9 min boot).
set -uo pipefail
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk} ANDROID_I_WANT_MY_TCG=yes
ADB=$ANDROID_HOME/platform-tools/adb
IMG="system-images;android-30;google_apis;x86_64"
LOG=${LOG:-/home/user/gapps-dl/emulator-vm.log}
boot_done() { [ "$(timeout 20 $ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
pm_ok() { [ "$(timeout 60 $ADB shell pm list packages 2>/dev/null | grep -c '^package:')" -gt 50 ]; }

if ! pgrep -f qemu-system >/dev/null; then
  echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n gapi30 -k "$IMG" -d pixel_4 --force >/dev/null 2>&1
  printf "hw.ramSize=4096\nhw.cpu.ncore=4\ndisk.dataPartition.size=12G\n" >> ~/.android/avd/gapi30.avd/config.ini
  nohup $ANDROID_HOME/emulator/emulator -avd gapi30 -accel off -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory 4096 -cores 4 -partition-size 12288 > "$LOG" 2>&1 &
fi
t0=$(date +%s)
until boot_done && pm_ok; do sleep 20; pgrep -f qemu-system >/dev/null || { echo "emulator died"; exit 1; }; done
echo "booted in $(( $(date +%s) - t0 ))s"
$ADB root >/dev/null; sleep 8; $ADB wait-for-device; until pm_ok; do sleep 10; done
# system_server restarts a few times right after first boot (and once after hiding GmsCore), so retry.
visible() { timeout 60 $ADB shell pm list packages 2>/dev/null | grep -cE 'android\.gms$|gsf$|vending$'; }
for attempt in $(seq 1 10); do
  until pm_ok; do sleep 10; done
  for p in com.google.android.gsf com.android.vending; do
    timeout 120 $ADB shell pm list packages | grep -q "$p\$" && echo "uninstall $p: $(timeout 120 $ADB shell pm uninstall -k --user 0 $p 2>&1 | tr -d '\r')"
  done
  # Uninstalling GmsCore restarts system_server on this image; hiding it is equivalent for apps.
  timeout 120 $ADB shell pm list packages | grep -q "android.gms\$" && echo "hide gms: $(timeout 120 $ADB shell pm hide com.google.android.gms 2>&1 | tr -d '\r')"
  sleep 45; until pm_ok; do sleep 10; done
  [ "$(visible)" = 0 ] && break
  echo "attempt $attempt: still visible=$(visible)"
done
[ "$(visible)" = 0 ] || { echo "could not remove GApps"; exit 1; }
$ADB shell setprop pm.dexopt.install extract
echo "GMS/GSF/Play visible to apps: $(timeout 60 $ADB shell pm list packages | grep -cE 'android\.gms$|gsf$|vending$')"
echo "ABIs: $($ADB shell getprop ro.product.cpu.abilist | tr -d '\r')"
echo VM_READY
