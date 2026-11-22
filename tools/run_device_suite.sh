#!/usr/bin/env bash
# Baseline (unpatched, no microG) vs patched (+ MicroG-RE + GmsCompat-Stub) on a GApps-free emulator.
set -uo pipefail
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
R=/home/user/microg-universal
X=/home/user/gapps-dl/addons/x
T="$R/tools/device_test.sh"
GMAIL=$X/Gmail/AppSet/Gmail/Gmail/___app___PrebuiltGmail/PrebuiltGmail.apk
KEEP=$X/GoogleKeep/AppSet/GoogleKeep/GoogleKeep/___app___GoogleKeepPrebuilt/GoogleKeepPrebuilt.apk
TRICHROME=$X/GoogleChrome/AppSet/GoogleChrome/TrichromeLibrary/___app___TrichromeLibrary/TrichromeLibrary.apk
export WAIT=${WAIT:-150}

echo "===== A. baseline: unpatched apps, no GMS, no microG"
"$T" A-testapp "$R/testapp/app/build/outputs/apk/release/app-release.apk"
"$T" A-keep "$KEEP"
SKIP_INSTALL=1 "$T" A-gmail-preinstalled "$GMAIL"
for p in test.gmsclient com.google.android.keep; do $ADB uninstall $p >/dev/null 2>&1; done

echo "===== B. MicroG-RE + GmsCompat-Stub"
echo "microg: $($ADB install -r -g "$R/out/deps/microg-re.apk" 2>&1 | tail -1)"
echo "stub:   $($ADB install -r -g "$R/out/gmscompat-stub.apk" 2>&1 | tail -1)"
$ADB shell dumpsys deviceidle whitelist +app.revanced.android.gms >/dev/null
$ADB shell dumpsys deviceidle whitelist +app.morphe.gmscompat >/dev/null
$ADB shell am start -W -n app.morphe.gmscompat/.MainActivity >/dev/null 2>&1; sleep 20
$ADB exec-out screencap -p > "$R/out/device/B-stub.png"

echo "===== C. patched apps"
"$T" C-testapp "$R/out/testapp-microg.apk"
"$T" C-keep "$R/out/real/keep-microg.apk"
"$T" C-gmail "$R/out/real/gmail-microg-clone.apk"
"$T" C-maps "$R/out/real/maps-microg-clone.apk"
"$T" C-chrome "$R/out/real/chrome-microg-clone.apk" "$TRICHROME"

echo "===== stub registry / microG view"
$ADB shell content call --uri content://app.morphe.gmscompat.broker --method status --arg x 2>&1 | tail -1
$ADB shell dumpsys activity services app.morphe.gmscompat | grep -E "ServiceRecord|isForeground" | head -4
