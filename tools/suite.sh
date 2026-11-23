#!/usr/bin/env bash
# Patch every Google app APK found under a directory (clone mode by default, like the store) and
# record static verification results.
#   usage: tools/suite.sh <apk root dir> [clone|normal]   -> out/suite/<mode>/results.tsv
set -uo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$1 MODE=${2:-clone}
OUT=$R/out/suite/$MODE; mkdir -p "$OUT"
BT=${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0
RES=$OUT/results.tsv
[ -f "$RES" ] || printf 'package\tversion\tabi\tresult\tcall_sites\thooks\tgms_left\tclone\tseconds\n' > "$RES"
find "$ROOT" -name '*.apk' -size +2M | grep -vE 'Trichrome|DocumentsUI|StorageManager' | sort | while read -r apk; do
  pkg=$("$BT/aapt2" dump packagename "$apk" 2>/dev/null) || continue
  grep -q "^$pkg	" "$RES" && { echo "skip $pkg"; continue; }
  ver=$("$BT/aapt2" dump badging "$apk" 2>/dev/null | grep -oE "versionName='[^']+'" | head -1 | cut -d"'" -f2)
  abi=$("$BT/aapt2" dump badging "$apk" 2>/dev/null | grep -oE "^native-code: .*" | tr -d "'" | cut -d' ' -f2- | tr ' ' ',')
  name=$(echo "$pkg" | tr . _)
  t0=$SECONDS
  log=$OUT/$name.log
  KS=$R/out/deps/release.keystore "$R/tools/e2e.sh" patch "$apk" "$([ "$MODE" = clone ] && echo clone)" "$name-$MODE" > "$log" 2>&1
  result=$(grep -oE 'RESULT: [A-Z]+' "$log" | tail -1 | cut -d' ' -f2); result=${result:-ERROR}
  calls=$(grep -oE 'getPackageInfo → MicroGBridge: [0-9]+' "$log" | grep -oE '[0-9]+$')
  hooks=$(grep -oE 'forcePlayServicesAvailable\): [0-9]+' "$log" | grep -oE '[0-9]+$')
  left=$(grep -oE '"com.google.android.gms" left: [0-9]+' "$log" | grep -oE '[0-9]+$')
  clone=$(grep -oE '^clone: .*' "$log" | head -1)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$pkg" "$ver" "${abi:-none}" "$result" "${calls:-}" "${hooks:-}" "${left:-}" "${clone:-}" "$((SECONDS - t0))" >> "$RES"
  echo "$pkg $result $((SECONDS - t0))s"
done
cat "$RES"
