#!/usr/bin/env bash
# Emulator smoke test: microG fork + stub + patched test app.
#   emulator_smoke.sh <microg.apk> <vending.apk> <stub.apk> <patched-testapp.apk> <out dir>
# Fails if a service the fork declares does not resolve, or if microG or the test app crashes.
set -uo pipefail
MICROG=$1 VENDING=$2 STUB=$3 TESTAPP=$4 OUT=$5
BASE=app.revanced
PKG=$BASE.android.gms
mkdir -p "$OUT"
fail=0
adb wait-for-device
adb shell settings put global package_verifier_enable 0 || true
adb install -r -g "$MICROG" || exit 1
adb install -r -g "$VENDING" || exit 1
adb install -r -g "$STUB" || exit 1
adb install -r -g "$TESTAPP" || exit 1
adb logcat -c

for action in \
  $BASE.android.gms.safetynet.service.START \
  $BASE.android.gms.safebrowsing.SafeBrowsingService.START \
  $BASE.android.mobstore.service.START \
  $BASE.android.gms.time.trustedtime.service.START \
  $BASE.android.gms.auth.aang.events.services.START \
  $BASE.android.gms.clearcut.sampler.service.START \
  com.google.android.play.engage.BIND_APP_ENGAGE_SERVICE \
  com.google.android.play.core.grouping.BIND_GROUPING_API_SERVICE \
  androidx.enterprise.feedback.action.APP_STATES; do
  if adb shell cmd package query-services -a "$action" | grep -q 'ComponentInfo\|packageName='; then
    echo "resolves: $action"
  else
    echo "MISSING:  $action"; fail=1
  fi
done | tee "$OUT/services.txt"
grep -q MISSING "$OUT/services.txt" && fail=1

adb shell am start -W -n $PKG/org.microg.gms.ui.MainSettingsActivity >/dev/null 2>&1
sleep 20
adb shell am start -W -n test.gmsclient/.MainActivity >/dev/null 2>&1
sleep 40
adb exec-out screencap -p > "$OUT/testapp.png" || true
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -E "Process: ($PKG|test.gmsclient|app.morphe.gmscompat)"; then
  echo "crash found"; fail=1
fi
adb shell pidof $PKG >/dev/null && echo "microG running" || { echo "microG not running"; fail=1; }
exit $fail
