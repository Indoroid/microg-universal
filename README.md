# microg-universal

Universal **"MicroG support (universal)"** Morphe patch + **GmsCompat-Stub** sideloadable companion.
Patch any APK that uses Google Play services so it runs against a user-installed microG
(MicroG-RE / ReVanced GmsCore by default) without root and without GApps.

Design, analysis of Morphe / MicroG-RE / GrapheneOS GmsCompat, and limits: [`docs/PLAN.md`](docs/PLAN.md).

## Layout

| Path | What |
|---|---|
| `morphe-patches-library.patch` | Library change: `all/misc/microg/*` (factory patch, fingerprints, generated constants) + `extensions/microg` (runtime dex) + build wiring |
| `morphe-patches.patch` | Bundle change: named patch **MicroG support (universal)** with options |
| `gmscompat-stub/` | Sideloadable stub app `app.morphe.gmscompat` (broker provider, keep-alive FGS, setup UI) |
| `tools/gen_constants.py` | Regenerates `MicroGConstants.kt` from MicroG-RE manifests, cross-checked against a real GmsCore APK |
| `tools/repack_and_verify.sh` | Repacks patcher output into a signed APK and statically verifies every rewrite |
| `harness/` | Minimal JVM driver that runs the patch through `morphe-patcher` |
| `testapp/` | R8-minified client (play-services-base 18.11.1 + firebase-messaging 26.0.2) used as patch target |

## Use

1. Install **MicroG-RE** (`app.revanced.android.gms`).
2. Optional: sideload `gmscompat-stub` APK, open it once (starts keep-alive, asks for notification + battery exemption).
3. Patch the target APK with **MicroG support (universal)** (Morphe Manager / CLI after applying the two patches above).
   Options: `gmsCoreVendor` (`app.revanced` | `com.mgoogle` | `com.google`), `spoofIdentity`, `forceAvailable`,
   `useGmsCompatStub`, `showPrompts`.
4. Install the patched APK. If the original is installed, uninstall it first (signature differs).

## What the patch does (no per-app fingerprints)

1. One-pass const-string rewrite: `com.google.android.gms`, account type `com.google`, and the 302 actions /
   permissions / authorities MicroG-RE serves under its prefix, plus `content://` URIs.
2. Every `PackageManager.getPackageInfo(String, …)` call → `MicroGBridge`, which reports microG (and a Play Store
   companion) with Google's original certificate and a sufficient version code, so `GoogleSignatureVerifier` /
   `isGooglePlayServicesAvailable` pass even in fully obfuscated apps.
3. Optional fingerprint hooks on `isGooglePlayServicesAvailable` / `ensurePlayServicesAvailable` (only when microG is
   actually installed).
4. Manifest: renamed c2dm/GMS permissions & actions, `SPOOFED_PACKAGE_NAME` + `SPOOFED_PACKAGE_SIGNATURE` (SHA-1 of the
   original signer, computed at patch time), `<queries>`, `uses-library required=false` for `com.google.*`, and an
   init `<provider>` that starts the bridge before `Application.onCreate`.

## Build

```bash
# library (needs morphe-patcher 2.5.0 in mavenLocal)
./gradlew :patch-library:jar
```

```bash
# stub
cd gmscompat-stub && gradle assembleRelease
```
