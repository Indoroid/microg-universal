# Universal microG support + GmsCompat-Stub — analysis & plan

## 1. What exists today

### Morphe `GmsCore support` (morphe-patches `shared/misc/gms`)
App-specific factory `gmsCoreSupportPatch(fromPackageName, toPackageNameDefault, primeMethodFingerprint, mainActivityOnCreateFingerprint, …)`. Instantiated only for YouTube / YT Music.

| Step | Universal? | Why |
|---|---|---|
| Replace `com.google.android.gms` + ~240 hand-listed permission/action/authority strings with `app.revanced.*` | yes | pure const-string rewrite |
| Rewrite `content://<gms authority>` URIs | yes | |
| `GooglePlayUtilityFingerprint.returnEarly(0)` (`isGooglePlayServicesAvailable` → SUCCESS) | mostly | strings live in play-services-basement, survive R8; must be optional (`orNull`) |
| `ServiceCheckFingerprint` early return | mostly | same |
| `primeMethodFingerprint`, GNP registration, account-capability hook | **no** | YouTube internals |
| `checkGmsCore(Activity)` injected in main activity `onCreate` | **no** | needs per-app fingerprint + Morphe shared Utils/resources |
| Manifest: package rename, C2D_MESSAGE, c2dm, `SPOOFED_PACKAGE_NAME/SIGNATURE` meta-data | yes, except signature | signature hard-coded per app (`24bb24…`) |
| `gmsCorePackageQueriesPatch` (library) | yes | already universal |

### morphe-patches-library
`spoofSignaturePatch` shows the universal recipe: compute data from `packageMetadata.signingCertificates`, rewrite every `PackageManager.getPackageInfo` call site to a static wrapper in a self-contained dex (`extendWith("generated/extensions/signature.dex")`). No app fingerprints.

### MicroG-RE (`app.revanced.android.gms`)
* Registers both `com.google.*` and `${basePackageName}.*` names → the exact rename table can be **generated** from its manifests (310 names; Morphe's list has 243, misses e.g. `recaptcha`, `appset`, `pay`, `identitycredentials`, `SmsRetrieverApiService`, `ModuleInstallService`).
* Reads `app.revanced.android.gms.SPOOFED_PACKAGE_NAME/SIGNATURE` from the caller manifest (`PackageSpoofUtils`) → re-signed app keeps its original identity toward Google (FCM, Sign-In, Maps keys).

### GrapheneOS GmsCompat
Two halves:
1. **Framework** (`frameworks/base …/internal/gmscompat`, `android.app.compat.gms.GmsCompat`): runs *inside every app process* (`GmsCompat.maybeEnable` from `Instrumentation.newApplication`), intercepts binder proxies, `bindService`, PackageManager, dynamite loading, file access. Requires OS build.
2. **App** `app.grapheneos.gmscompat` (privileged, `platform_apis`): `PersistentFgService` (ref-counted FG service that keeps GmsCore at foreground priority), `GmsClientCompatService`/`GmsCompatClientService` (binding targets that raise process priority), `GservicesProvider`, missing-app notifications, location forwarder, config UI.

Not portable as-is: (1) needs a custom ROM; (2) needs signature|privileged permissions. What *is* portable: the architecture.

## 2. Mapping GmsCompat → sideloadable world

| GmsCompat (ROM) | This project (any phone, no root) |
|---|---|
| Framework hooks in every process | **Patch-time** call-site rewriting + injected `microg.dex` (`MicroGBridge`) — this is the "GmsCompatLib" equivalent, per patched app |
| `GmsCompat.maybeEnable()` early init | Injected `<provider>` (`MicroGInitProvider`) — runs before `Application.onCreate`, no fingerprint |
| `PersistentFgService` keeping GmsCore alive | Stub `KeepAliveService`: FG service holding `BIND_IMPORTANT` bindings to microG |
| `GmsClientCompatService` / missing-app notifications | Stub `BrokerProvider` + one notification instead of every app nagging |
| Config UI | Stub `MainActivity`: detected variant, signer check, battery-opt, background state |

## 3. Universal patch design (`MicroG support (universal)`)

No `compatibleWith` → offered for every APK. Options: `gmsCoreVendor` (default `app.revanced`), `spoofIdentity` (default on), `useStub` (default on), `forcePlayServicesAvailable` (default on).

1. **String rewrite, one pass** over all dex: exact `com.google.android.gms` + generated rename table (from MicroG-RE manifests ∩ GmsCore manifest from GApps). Never rewritten: `com.google.android.gms.version` (client's own meta-data), class names, dynamite module ids.
2. **`content://` authority rewrite** (generated authority list).
3. **Signature spoof of GmsCore toward client code** (obfuscation-proof): every `PackageManager.getPackageInfo/getApplicationInfo` call → `MicroGBridge` wrapper; when the queried package is the microG package, the Google release cert (extracted from the GApps GmsCore APK, cross-checked with MicroG-RE `fake-signature`) is substituted in `signatures` and `signingInfo`. `GoogleSignatureVerifier` / `isGooglePlayServicesAvailable` then succeed without fingerprints.
4. **Belt-and-braces fingerprints** (optional, `orNull`): `isGooglePlayServicesAvailable` → 0, "Google Play Services not available" ensure-method → return.
5. **Manifest**: rename c2dm/permissions/actions in attributes, `<uses-permission>` for renamed ones, `<queries>` for all variants + stub, `SPOOFED_PACKAGE_NAME` = original pkg, `SPOOFED_PACKAGE_SIGNATURE` = SHA-1 of the original signing cert computed at patch time, init provider, optional package rename.
6. **Runtime** (`MicroGInitProvider` → `MicroGBridge`): detect microG (stub broker → fallback probes), one-time dialog if missing / battery-optimised, ping stub keep-alive.

## 4. GmsCompat-Stub app (`app.morphe.gmscompat`)
Unprivileged, minSdk 23, sideload anywhere. `BrokerProvider` (read-only status), `KeepAliveService` (ref-counted FG service à la `PersistentFgService`), `PingReceiver`, `MainActivity` (status/fix buttons). Patched apps work without it; it only improves reliability (push on aggressive OEMs) and centralises prompts.

## 5. Data refinement from GApps
NikGApps core (Android 17, 2026-11-19, sha256-verified) → `GmsCore.apk`: (a) Google release cert, (b) full list of GmsCore-owned actions/permissions/authorities. `tools/gen_constants.py` intersects with MicroG-RE's renamed names → `Constants.kt`.

## 6. Limits (honest)
* Play Integrity / SafetyNet-gated apps, Play Billing, Play Asset Delivery, apps verifying their own signature → not solved by this patch.
* Apps that hard-code `com.google.android.gms` in native code or encrypted strings.
* Re-signing is unavoidable; `SPOOFED_PACKAGE_SIGNATURE` only helps toward Google servers through microG.

## 7. Findings while building (refinements over Morphe's code)
* Morphe's `GooglePlayUtilityFingerprint` (`(L,I)I` + "MetadataValueReader") does **not** match current
  play-services-basement 18.12.1: those strings moved to a `void` manifest-check helper. New anchor:
  `" requires Google Play services, but their signature is invalid."` in `isGooglePlayServicesAvailable(Context,I)I`.
* R8 class merging made that method an **instance** method (`b(Context,I)I`) in a minified client → fingerprints
  must not pin `STATIC`/`PUBLIC`.
* The availability check also verifies `com.android.vending` against GMS's signer (flags `0x2040`) → the
  PackageManager wrapper spoofs both packages.
* GmsCore 25.34.34 (NikGApps 2026-11-19) is v3-signed with a rotated key (SHA-256 `5f2391…`, = GrapheneOS
  `gmscompat_known_signers`); legacy `GET_SIGNATURES` returns the lineage's oldest cert = Google original
  (SHA-1 `38918a45…`) = Phonesky cert = MicroG `fake_signature`. That cert is embedded in the bridge.
* Generated rename table: 271 actions, 22 permissions, 9 authorities (MicroG-RE); GmsCore exports 1056 actions /
  90 permissions / 49 authorities, 731 of them unimplemented by MicroG-RE and intentionally left alone.

## 8. Verification (static, `tools/repack_and_verify.sh`)
Target: `testapp` (R8, play-services-base 18.11.1, firebase-messaging 26.0.2) patched through morphe-patcher 2.5.0:
manifest c2dm receiver/permission renamed, SPOOFED_* meta-data, init provider, queries ✔; 0 stale
`com.google.android.gms` const-strings, 28 → `app.revanced.android.gms`; 8/8 `getPackageInfo` call sites →
`MicroGBridge`; 1 availability hook; 0 placeholders; APK signs and verifies.
Not done here: on-device run (no emulator in this environment).

## 9. Real apps (NikGApps 2026-11-19 builds, arm64)
| App | Version | Dex | `getPackageInfo` → bridge | availability hooks | stale `com.google.android.gms` | static verdict |
|---|---|---|---|---|---|---|
| Gmail `com.google.android.gm` | 2026.02.23 | 11 | 94 | 2 | 0 | PASS |
| Chrome `com.android.chrome` (+ unpatched TrichromeLibrary) | 763221832 | – | 51 | 1 | 0 | PASS |
| Maps `com.google.android.apps.maps` | 26.16.12 | – | 84 | 1 | 0 | PASS |
| Keep `com.google.android.keep` | 5.26.042 | – | 48 | 2 | 0 | PASS |
Patch time 12–20 s per app. MicroG-RE 7.3.2 built from source (`app.revanced.android.gms`, vc 255070315).
BHIM / APKPure: not obtainable from this environment (APKPure answers automated clients with a Cloudflare challenge).
