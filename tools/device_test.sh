#!/usr/bin/env bash
# Install an APK on the running emulator, launch it, and triage GMS-related behaviour.
# usage: device_test.sh <label> <apk> [extra apk to install first...]
set -uo pipefail
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
LABEL=$1 APK=$2; shift 2
OUT=${OUT:-/home/user/microg-universal/out/device}
WAIT=${WAIT:-90}
mkdir -p "$OUT"
PKG=$(/opt/android-sdk/build-tools/35.0.0/aapt2 dump packagename "$APK")

for dep in "$@"; do $ADB install -r "$dep" 2>&1 | tail -1; done
$ADB uninstall "$PKG" >/dev/null 2>&1
echo "[$LABEL] install: $($ADB install -r -g "$APK" 2>&1 | tail -1)"
$ADB logcat -c
ACT=$($ADB shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -1 | tr -d '\r')
echo "[$LABEL] launch $ACT"
$ADB shell am start -W -n "$ACT" 2>&1 | grep -E "Status|TotalTime|Error" | tr '\n' ' '; echo
sleep "$WAIT"
PID=$($ADB shell pidof "$PKG" | tr -d '\r')
echo "[$LABEL] running: ${PID:-NO}"
$ADB logcat -d > "$OUT/$LABEL.logcat.txt"
echo "[$LABEL] crashes: $(grep -c 'FATAL EXCEPTION' "$OUT/$LABEL.logcat.txt")"
grep -E "FATAL EXCEPTION|AndroidRuntime: (Process|java|Caused)" "$OUT/$LABEL.logcat.txt" | head -6 | sed "s/^/[$LABEL]   /"
grep -E "requires Google Play services|Google Play services (is missing|out of date)|GooglePlayServicesUtil|MorpheMicroG|SERVICE_MISSING|ConnectionResult" "$OUT/$LABEL.logcat.txt" \
  | sed 's/^.\{19\}//' | sort | uniq -c | sort -rn | head -8 | sed "s/^/[$LABEL]   /"
$ADB exec-out screencap -p > "$OUT/$LABEL.png" && echo "[$LABEL] screenshot: $OUT/$LABEL.png"
