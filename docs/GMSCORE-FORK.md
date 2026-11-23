# gmscore/: what differs from mainline microG

Base: `microg/GmsCore` `master` at `f686900c` (2026-11-12) with the MicroG-RE layer (`ab96213`),
imported as one squashed commit. Upstream authorship and history stay in the upstream repositories.

| Layer | Content | Where |
|---|---|---|
| MicroG-RE | UI redesign (Material 3 settings, account cards, self-check chips), `${basePackageName}` rename (`app.revanced.android.gms`), updater, map engine choice | RE `ab96213` on its upstream base `342c280`, merged into `master` |
| Removed | YouTube/Photos hard-coded Google signature in `PackageUtils`, `app.morphe.`/`app.revanced.`/`app.rvx.` prefix trust and remaps, patcher-name detector and checks | `PackageUtils`, self-check |
| Patched apps | recognised by the universal patch's `SPOOFED_PACKAGE_NAME` meta-data (self-check "Patched apps", phenotype, Gservices) | `PackageSpoofUtils`, `GServicesProvider` |
| API surface | 6 service IDs + 83 bind actions of Play services 26.11.36 answered with `API_DISABLED` instead of a failed bind | `GmsService`, `DummyService`, see `GMS-COVERAGE.md` |
| New services | SafetyNet (all transactions), Safe Browsing API, MobStore files, Trusted Time, Auth AANG, telemetry and Clearcut sampler | see `GMS-COVERAGE.md` §7 |
| Play Store side (`vending-app`) | Engage v1/v2, Play Grouping API, enterprise app states | `com.google.android.finsky.*` |
| Push | MCS endpoint pool `alt1..alt8-mtalk` (Play services order), last good endpoint first | `McsService` |
| TLS provider | conscrypt loaded from microG's APK when the app's ABI differs from microG's primary ABI | `ProviderInstallerImpl` |
| Release | `0.3.18.252432.1` (code 255070400), updater reads `Indoroid/microg-universal` releases (`microg-*.apk`) | `build.gradle` |

## Settings that changed behaviour

| Screen | Switch / list | Effect |
|---|---|---|
| SafetyNet | Enable SafetyNet | off: SafetyNet, reCAPTCHA and Safe Browsing calls are refused for every app |
| SafetyNet | Play Protect | drives verify apps (harmful-apps list, APK checks against Safe Browsing) |
| SafetyNet → apps | per-app switch | lists every app that used SafetyNet, Play Integrity or reCAPTCHA; off blocks that app only |
| Push notifications → all apps | list | every installed app that requests C2DM `RECEIVE`, registered or not; settings stick before first contact |
| Privacy → Network | Allow connecting to Google | off: microG's own Google traffic (checkin, MCS, SNTP, Safe Browsing) is not sent |
| Privacy → Network | Route through Tor | sends the same traffic through Orbot's SOCKS port (127.0.0.1:9050) |

Merge conflicts resolved (16): kept RE's rename and UI; re-added upstream's `InAppReachService`, per-account
Timeline settings (account details navigation), timeline accounts preference, document scanner and camera
low-light actions (renamed to `${basePackageName}`); string resources merged by name.

Update from upstream (the import is squashed, so merge rather than `subtree pull`):

```bash
git fetch https://github.com/microg/GmsCore master
```

```bash
git merge -s subtree -Xsubtree=gmscore --allow-unrelated-histories FETCH_HEAD
```
