#!/usr/bin/env bash
# Single entry point for building, patching and testing microg-universal.
#   e2e.sh sync              copy shared/ MicroGContract into the library extension
#   e2e.sh check             fail if the two contract copies differ
#   e2e.sh build             library jar (patch + microg.dex), harness, GmsCompat-Stub
#   e2e.sh patch <apk> [clone] [name]   patch + repack + static verify -> out/real/<name>-microg.apk
#   e2e.sh vm                GApps-free emulator (tools/setup_vm.sh)
#   e2e.sh device            baseline vs patched suite on the VM (tools/run_device_suite.sh)
#   e2e.sh ui                screenshots of the stub and the injected dialog, light + dark
#   e2e.sh all               check, build, vm, ui, device
set -uo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
LIB=${LIB:-/home/user/src/morphe-patches-library}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
ADB=$ANDROID_HOME/platform-tools/adb
CONTRACT=app/morphe/extension/microg/contract/MicroGContract.java
SHARED=$R/shared/src/main/java/$CONTRACT
EXT=$LIB/extensions/microg/src/main/java/$CONTRACT
LIBJAR() { ls "$LIB"/patch-library/build/libs/morphe-patches-library-*[0-9].jar | head -1; }
GRADLE=${GRADLE:-/opt/gradle/bin/gradle}
NOGHP=$R/tools/no-github-packages.gradle

dismiss_anr() { for _ in 1 2 3; do $ADB shell dumpsys window windows 2>/dev/null | grep -q "Application Not Responding" || return; $ADB shell input tap 336 1240; sleep 4; done; }
shot() { dismiss_anr; $ADB exec-out screencap -p > "$1"; echo "screenshot: $1"; }

cmd_sync() { mkdir -p "$(dirname "$EXT")"; cp "$SHARED" "$EXT"; echo "contract synced"; }
cmd_check() { cmp -s "$SHARED" "$EXT" && echo "contract: in sync" || { echo "contract: OUT OF SYNC (run e2e.sh sync)"; exit 1; }; }

cmd_build() {
  cmd_check
  (cd "$LIB" && ./gradlew -q -I "$NOGHP" :patch-library:jar --no-daemon) || exit 1
  MORPHE_PATCHES_LIBRARY_JAR=$(LIBJAR) "$GRADLE" -q -p "$R/harness" installDist --no-daemon || exit 1
  "$GRADLE" -q -p "$R/gmscompat-stub" assembleRelease --no-daemon || exit 1
  cp "$R/gmscompat-stub/app/build/outputs/apk/release/app-release.apk" "$R/out/gmscompat-stub.apk"
  echo "built: $(LIBJAR), harness, out/gmscompat-stub.apk"
}

cmd_patch() {
  local apk=$1 clone=${2:-} name=${3:-$(basename "${1%.apk}")}
  local work; work=$(mktemp -d)
  JAVA_OPTS=${JAVA_OPTS:--Xmx6g} "$R/harness/build/install/microg-harness/bin/microg-harness" "$apk" "$work" app.revanced $clone \
    | grep -E "^PATCH|^clone:" || exit 1
  mkdir -p "$R/out/real"
  "$R/tools/repack_and_verify.sh" "$apk" "$work" app.revanced "$R/out/real/$name-microg.apk"
  rm -rf "$work"
}

cmd_ui() {
  local D=$R/out/ui; mkdir -p "$D"
  $ADB install -r -g "$R/out/gmscompat-stub.apk" | tail -1
  for mode in no yes; do
    local t; [ $mode = yes ] && t=dark || t=light
    $ADB shell cmd uimode night $mode >/dev/null; sleep 6
    $ADB shell am force-stop app.morphe.gmscompat
    $ADB shell am start -W -n app.morphe.gmscompat/.MainActivity >/dev/null 2>&1; sleep 20
    shot "$D/stub-$t.png"
    $ADB shell input swipe 540 1800 540 500 400; sleep 5; shot "$D/stub-$t-scrolled.png"
  done
  # Injected dialog: shown only when the stub does not handle prompts and microG is missing or restricted.
  $ADB uninstall test.gmsclient >/dev/null 2>&1
  $ADB install -r -g "$R/out/testapp-microg.apk" | tail -1
  $ADB shell pm disable-user app.morphe.gmscompat >/dev/null
  $ADB shell dumpsys deviceidle whitelist -app.revanced.android.gms >/dev/null
  for mode in no yes; do
    local t; [ $mode = yes ] && t=dark || t=light
    $ADB shell cmd uimode night $mode >/dev/null; sleep 6
    $ADB shell pm clear test.gmsclient >/dev/null
    $ADB shell am start -W -n test.gmsclient/.MainActivity >/dev/null 2>&1; sleep 25
    shot "$D/dialog-$t.png"
  done
  $ADB shell pm enable app.morphe.gmscompat >/dev/null
  $ADB shell dumpsys deviceidle whitelist +app.revanced.android.gms >/dev/null
  $ADB shell cmd uimode night no >/dev/null
}

case "${1:-}" in
  sync) cmd_sync ;;
  check) cmd_check ;;
  build) cmd_build ;;
  patch) shift; cmd_patch "$@" ;;
  vm) "$R/tools/setup_vm.sh" ;;
  device) "$R/tools/run_device_suite.sh" ;;
  ui) cmd_ui ;;
  all) cmd_check && cmd_build && "$R/tools/setup_vm.sh" && cmd_ui && "$R/tools/run_device_suite.sh" ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
