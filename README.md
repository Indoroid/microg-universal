# microg-universal

Universal **"MicroG support (universal)"** Morphe patch + **GmsCompat-Stub** sideloadable companion.
Patch any APK that uses Google Play services so it runs against a user-installed microG
(MicroG-RE / ReVanced GmsCore by default) without root and without GApps.

Design, analysis of Morphe / MicroG-RE / GrapheneOS GmsCompat, and limits: [`docs/PLAN.md`](docs/PLAN.md).

## Layout

| Path | What |
|---|---|
| `morphe/` | Morphe integration: `morphe-patches-library.patch` (universal patch, fingerprints, generated constants, runtime extension with M3 dialog), `morphe-patches.patch` (named patch + options), `apply.sh` applies both |
| `shared/` | `MicroGContract.java`: the one contract compiled into both the stub and the injected extension (provider, calls, keys, signers, Google cert) |
| `gmscompat-stub/` | Sideloadable stub `app.morphe.gmscompat`: Material 3 / Material You UI, broker provider, keep-alive FGS |
| `tools/e2e.sh` | Single entry point: `check`, `build`, `patch`, `vm`, `device`, `ui`, `all` |
| `tools/gen_constants.py` | Regenerates `MicroGConstants.kt` from MicroG-RE manifests, cross-checked with a real GmsCore APK |
| `harness/` | JVM driver running the patch through `morphe-patcher` (optional test clone mode) |
| `testapp/` | R8-minified GMS client used as a patch target |
| `out/ui/` | Screenshots of the stub and the injected dialog (light/dark) from the VM |

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

## Build and test

```bash
morphe/apply.sh ../morphe-patches-library ../morphe-patches
```

```bash
tools/e2e.sh all
```

Design: the stub (MDC 1.14, dynamic color, large top app bar, cards, MaterialSwitch, themed icon) and the
library-free dialog injected into patched apps share one M3 tonal palette (system Material You colors on
Android 12+, M3 baseline before), so setup looks the same in every patched app and in the stub.
