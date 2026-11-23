# microg-universal

Run Google-dependent apps on phones **without Google Play services and without root**, in one monorepo:

- **`gmscore/`** — microG fork: latest mainline microG (upstream `master`) with MicroG-RE's UI and rootless
  package rename (`app.revanced.android.gms`), without YouTube-specific code or a bundled store.
- **`store/`** — Aurora Store fork that patches apps for microG **before installing them**
  (clone package by default, "install as original" optional), plus *Patch for microG* for APKs you already have.
- **`patches/`** — Morphe patch library with the universal microG patch and the clone patch (no per-app fingerprints).
- **`stub/`** — optional GmsCompat stub: keeps microG alive, one setup screen for all patched apps.

Docs: API coverage against Play services 26.11.36 [`docs/GMS-COVERAGE.md`](docs/GMS-COVERAGE.md),
microG fork changes [`docs/GMSCORE-FORK.md`](docs/GMSCORE-FORK.md), store fork changes
[`docs/STORE-FORK.md`](docs/STORE-FORK.md), Google app suite results [`docs/REPORT-v2.md`](docs/REPORT-v2.md).

## Layout

| Path | What |
|---|---|
| `gmscore/` | microG fork (squashed import of `microg/GmsCore` `f686900c` + MicroG-RE) |
| `store/` | Aurora Store fork (squashed import of `AuroraOSS/AuroraStore` `12840634f`), `com.aurora.store.microg` |
| `patches/` | `morphe-patches-library` + `universalMicroGSupportPatch`, `microGClonePatch`, `extensions/microg` |
| `patches/bundle/` | diff adding the named patch "MicroG support (universal)" to `morphe-patches` |
| `stub/` | GmsCompat stub `app.morphe.gmscompat` (Material 3 / Material You) |
| `shared/` | `MicroGContract.java`, compiled into the stub and the injected extension |
| `harness/` | JVM driver running the patches through `morphe-patcher` |
| `testapp/` | R8-minified GMS client used as a patch target |
| `tools/` | `e2e.sh` (build/patch/vm/device/ui), `suite.sh` + `suite_device.sh` (Google app suite), `gms_coverage.py`, `ci/` |
| `.github/workflows/` | CI: builds, unit tests, patch check, emulator smoke test |

## Use

1. Install the microG build from `gmscore/` (`app.revanced.android.gms`; replaces MicroG-RE).
2. Install the store from `store/` and use it like Aurora: apps that need Play services are patched on the phone
   before installation. Settings → Installation → *microG*: **Patch apps for microG**, **Install as clone**.
3. Optional: the stub from `stub/`.
4. Already have an APK? Open or share it with the store → *Patch for microG*.

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

Builds need JDK 17/21 and the Android SDK; `tools/central-mirror.gradle` avoids Maven Central rate limits.
The patch library and the store need `app.morphe:morphe-patcher:2.5.0` in Maven local, built from source
(GitHub Packages, used by its build, needs `GITHUB_ACTOR` / `GITHUB_TOKEN` with `read:packages`):

```bash
tools/ci/morphe-patcher.sh
```

```bash
GRADLE_MICROG_VERSION_WITHOUT_GIT=1 gmscore/gradlew -p gmscore :play-services-core:assembleDefaultRelease
```

```bash
patches/gradlew -p patches -I tools/no-github-packages.gradle :patch-library:jar
```

```bash
store/gradlew -p store -I tools/central-mirror.gradle :app:assembleVanillaRelease
```

```bash
tools/e2e.sh all
```

CI (`.github/workflows/`) runs the same steps on every push and pull request:

| Workflow | Jobs |
|---|---|
| `build.yml` | microG (`assembleDefaultRelease` + unit tests), patch library + harness + stub, store (`assembleVanillaRelease` + unit tests); APKs uploaded as artifacts |
| `emulator.yml` | KVM emulator (API 30): installs microG, the stub and a patched test app, checks that every new service resolves and that nothing crashes; logcat uploaded |

Design: the stub (MDC 1.14, dynamic color, large top app bar, cards, MaterialSwitch, themed icon) and the
library-free dialog injected into patched apps share one M3 tonal palette (system Material You colors on
Android 12+, M3 baseline before), so setup looks the same in every patched app and in the stub.

## Credits and licenses

The imports are squashed; full history and authorship live upstream.

| Path | Upstream | License |
|---|---|---|
| `gmscore/` | [microG GmsCore](https://github.com/microg/GmsCore) `f686900c` with the [MicroG-RE](https://github.com/MorpheApp/MicroG-RE) layer | Apache-2.0 |
| `store/` | [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore) `12840634f` | GPL-3.0 |
| `patches/` | [Morphe patches library](https://github.com/MorpheApp/morphe-patches-library) | GPL-3.0 |
