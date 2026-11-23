#!/usr/bin/env bash
# Install and launch every patched APK of a suite run on the running VM and classify the outcome.
#   usage: tools/suite_device.sh [clone|normal] [wait seconds]  -> out/suite/<mode>/device.tsv
set -uo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
MODE=${1:-clone} WAIT=${2:-120}
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
BT=${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0
OUT=$R/out/suite/$MODE; mkdir -p "$OUT/device"
RES=$OUT/device.tsv
[ -f "$RES" ] || printf 'package\tinstall\tlaunch\talive\tcrash\tanr\tbridge\tgms_complaints\tnote\n' > "$RES"
pm_install() { timeout 600 $ADB push "$1" /data/local/tmp/suite.apk >/dev/null && timeout 900 $ADB shell pm install -r -g /data/local/tmp/suite.apk 2>&1 | tail -1 | tr -d '\r'; }

for apk in "$R"/out/real/*-"$MODE"-microg.apk; do
  pkg=$("$BT/aapt2" dump packagename "$apk" 2>/dev/null) || continue
  grep -q "^$pkg	" "$RES" && continue
  inst=$(pm_install "$apk")
  if [ "$inst" != Success ]; then
    printf '%s\t%s\t-\t-\t-\t-\t-\t-\t\n' "$pkg" "${inst:-timeout}" >> "$RES"; echo "$pkg install: $inst"; continue
  fi
  act=$($ADB shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tail -1 | tr -d '\r')
  $ADB logcat -c
  launch=no-launcher; tries=0
  if [[ "$act" == */* ]]; then
    # Under software emulation an ARM-translated process can miss the 10 s attach deadline on a
    # cold start; relaunch like a user would.
    for tries in 1 2 3; do
      launch=$(timeout 240 $ADB shell am start -W -n "$act" 2>&1 | grep -oE 'Status: [a-z]+' | cut -d' ' -f2)
      sleep 30
      [ -n "$($ADB shell pidof "$pkg" | tr -d '\r')" ] && break
    done
  fi
  sleep "$WAIT"
  log=$OUT/device/$pkg.logcat.txt
  timeout 120 $ADB logcat -d > "$log"
  pids=$(grep -oE "Start proc [0-9]+:$pkg(/|:)" "$log" | grep -oE '[0-9]+' | sort -u | tr '\n' '|' | sed 's/|$//')
  alive=$([ -n "$($ADB shell pidof "$pkg" | tr -d '\r')" ] && echo yes || echo no)
  crash=$(grep -A2 'FATAL EXCEPTION' "$log" | grep -c "Process: $pkg")
  anr=$(grep -cE "ANR in $pkg|Killing [0-9]+:$pkg.*(anr|start timeout)" "$log")
  bridge=$(grep -cE "MorpheMicroG" "$log")
  own=$([ -n "$pids" ] && grep -E "^\S+ \S+ +($pids) " "$log" || true)
  complaints=$(echo "$own" | grep -cE "requires Google Play services, but they are missing|SERVICE_MISSING|signature is invalid")
  note=$(grep -A3 'FATAL EXCEPTION' "$log" | grep -A2 "Process: $pkg" | grep -oE '(java|android)\.[A-Za-z.]+(Exception|Error)[^)]{0,120}' | head -1)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$pkg" "$inst" "${launch:-timeout}/try$tries" "$alive" "$crash" "$anr" "$bridge" "$complaints" "$note" >> "$RES"
  echo "$pkg launch=$launch alive=$alive crash=$crash anr=$anr bridge=$bridge complaints=$complaints $note"
  timeout 60 $ADB exec-out screencap -p > "$OUT/device/$pkg.png"
  $ADB shell am force-stop "$pkg"
done
cat "$RES"
