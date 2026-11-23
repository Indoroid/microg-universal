#!/usr/bin/env bash
# Align and sign an APK with a throwaway CI key (test installs only, never released).
#   sign.sh <in.apk> <out.apk>
set -euo pipefail
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
KS=${CI_KEYSTORE:-$RUNNER_TEMP/ci.keystore}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias ci \
  -keyalg RSA -keysize 2048 -validity 3650 -dname CN=ci >/dev/null 2>&1
"$BT/zipalign" -f -p 4 "$1" "$2.aligned"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$2" "$2.aligned"
rm -f "$2.aligned" "$2.idsig"
