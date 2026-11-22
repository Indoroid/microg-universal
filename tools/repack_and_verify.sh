#!/usr/bin/env bash
# Repack harness output into an installable APK and statically verify the microG patch.
# usage: repack_and_verify.sh <original.apk> <harness out dir> <vendor> <patched.apk>
set -euo pipefail
ORIG=$1 OUT=$2 VENDOR=$3 DEST=$4
BT=${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0
WORK=$(mktemp -d)
ls "$OUT"/classes*.dex >/dev/null 2>&1 || { echo "RESULT: FAIL (no patched dex in $OUT)"; exit 1; }

python3 - "$ORIG" "$OUT" "$WORK/unsigned.apk" <<'EOF'
import sys, zipfile, os, glob
orig, out, dest = sys.argv[1:]
res = zipfile.ZipFile(os.path.join(out, "resources.apk")) if os.path.exists(os.path.join(out, "resources.apk")) else None
replaced = set(res.namelist()) if res else set()
dex = {os.path.basename(p): p for p in glob.glob(os.path.join(out, "classes*.dex"))}
with zipfile.ZipFile(orig) as src, zipfile.ZipFile(dest, "w") as dst:
    for item in src.infolist():
        n = item.filename
        if n in replaced or n in dex or n.startswith("META-INF/"):
            continue
        dst.writestr(item, src.read(n))
    if res:
        for item in res.infolist():
            dst.writestr(item, res.read(item.filename))
    for name, path in sorted(dex.items()):
        dst.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
EOF

KS=$WORK/test.keystore
keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias test \
  -keyalg RSA -keysize 2048 -validity 1000 -dname "CN=microg-universal-test" >/dev/null 2>&1
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$DEST" "$WORK/aligned.apk"
"$BT/apksigner" verify "$DEST" && echo "signature: OK"

echo "== manifest"
M=$WORK/manifest.txt
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$DEST" > "$M"
check() { if grep -q -- "$2" "$1"; then echo "  ✔ $3"; else echo "  ✘ $3"; FAIL=1; fi; }
FAIL=0
check "$M" "\"$VENDOR.android.c2dm.permission.SEND\"" "receiver permission → $VENDOR c2dm SEND"
check "$M" "\"$VENDOR.android.c2dm.intent.RECEIVE\"" "receiver action → $VENDOR c2dm RECEIVE"
check "$M" "\"$VENDOR.android.gms.SPOOFED_PACKAGE_NAME\"" "SPOOFED_PACKAGE_NAME meta-data"
check "$M" "\"$VENDOR.android.gms.SPOOFED_PACKAGE_SIGNATURE\"" "SPOOFED_PACKAGE_SIGNATURE meta-data"
check "$M" "app.morphe.extension.microg.MicroGInitProvider" "init provider"
check "$M" "\"app.morphe.gmscompat\"" "stub package query"
check "$M" "\"com.google.android.gms.version\"" "client gms.version meta-data untouched"
if grep -q '"com.google.android.c2dm.permission.SEND"' "$M"; then echo "  ✘ stale com.google c2dm SEND"; FAIL=1; fi

echo "== bytecode"
D=$WORK/dex.txt
for f in "$OUT"/classes*.dex; do "$BT/dexdump" -d "$f"; done > "$D" 2>/dev/null
count() { grep -c -- "$1" "$D" || true; }
echo "  const-string \"com.google.android.gms\" left: $(count 'const-string.*"com.google.android.gms" ')"
echo "  const-string \"$VENDOR.android.gms\": $(count "const-string.*\"$VENDOR.android.gms\"")"
echo "  getPackageInfo → MicroGBridge: $(count 'invoke-static.*MicroGBridge;.getPackageInfo')"
echo "  PackageManager.getPackageInfo left outside extension: $(grep 'invoke-virtual.*Landroid/content/pm/PackageManager;.getPackageInfo' "$D" | grep -vc 'MicroGBridge' || true)"
echo "  availability hooks (forcePlayServicesAvailable): $(count 'MicroGBridge;.forcePlayServicesAvailable')"
echo "  placeholders left: $(count 'MICROG_[A-Z_]*_PLACEHOLDER')"
[ "$(count 'MICROG_[A-Z_]*_PLACEHOLDER')" = 0 ] || FAIL=1
[ "$(count 'invoke-static.*MicroGBridge;.getPackageInfo')" != 0 ] || FAIL=1
rm -rf "$WORK"
[ $FAIL = 0 ] && echo "RESULT: PASS" || { echo "RESULT: FAIL"; exit 1; }
