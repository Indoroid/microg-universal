# GMS API coverage (microG fork vs Google Play services 26.11.36)

Reference: Google Play services 26.11.36 and Play Store 51.8 as shipped in a public Android 17 GApps
package. Only interface facts were used for compatibility: manifest components, service IDs, bind
actions, Binder descriptors, transaction and callback codes, parcel field layouts, status codes and
network endpoints. No Google code is included.

## 1. Manifest surface (`tools/gms_coverage.py`)

| Surface | Real GMS | Fork declares | Covered | Missing |
|---|---|---|---|---|
| service actions | 213 | 318 | 181 (84%) | 32 |
| activity actions | 282 | 54 | 26 (9%) | 256 |
| receiver actions | 38 | 37 | 0 (0%) | 38 |
| provider authorities | 42 | 15 | 5 (11%) | 37 |

Service actions: MicroG-RE 7.3.2 covered 145/213 (68%); this fork covers 181/213 (84%). Activity,
receiver and provider entries are mostly GMS-internal UI, Chimera routing and system hooks.

Remaining missing service actions (internal or system-only: persistent, chimera test, FRP, backup):
(32)

- **?**: `PixelImpulseClient.BIND`
- **ads**: `ads.identifier.service.PERSISTENT_START`, `ads.service.SDK_SIGNAL`
- **auth**: `auth.APP_CERT`, `auth.DATA_PROXY`, `auth.frp.FRP_BIND`
- **backup**: `backup.BackupAccountManagerService`, `backup.BackupStatsService`, `backup.D2D_MIGRATE_HELPER`, `backup.LOCAL_RESTORE`, `backup.RESTORE_SESSION`
- **car**: `car.CALL`, `car.PROXY_IN_CALL_SERVICE`
- **carsetup**: `carsetup.CAR_DATA`, `carsetup.DRIVING_MODE`
- **chimera**: `chimera.container.DYNAMITE_LOG`, `chimera.test.START_SERVICE`
- **fitness**: `fitness.cache.DataUpdateListenerCacheService`
- **gcm**: `gcm.ACTION_TASK_READY`, `gcm.WEARABLE`
- **home**: `home.matter.SYSTEM_COMMISSIONING_SERVICE`
- **ipa**: `ipa.mediastoreindexer.PERSISTENT_START`, `ipa.smsindexer.PERSISTENT_START`
- **magictether**: `magictether.BIND`
- **play**: `play.cloud.search.grpc.io.action.BIND`
- **tapandpay**: `tapandpay.issuer.GENERATE_PAYMENT_CREDENTIALS`
- **wallet**: `wallet.service.ia.IIaService`, `wallet.service.ib.IIbService`, `wallet.service.ib.UpdatePaymentDetailsService`, `wallet.service.orchestration.IOrchestrationService`, `wallet.service.ow.IOwInternalService`, `wallet.service.reauth.IReauthService`

## 2. Client service table (Play services client libraries)

113 client classes give (service ID, Binder descriptor, bind action). Against the fork's
`GmsService` enum:

- 6 service IDs unknown to microG, now added: `LOG_AGGREGATION(446)`, `GOOGLE_ONE_STORAGE_MANAGEMENT(450)`,
  `GESTURE_EXCHANGE(451)`, `STATS_CROSS_USER(452)`, `ONBOARDING_CONSENT(460)`, `REMOTE_LOCK_SETTINGS(477)`;
- 48 bind actions no manifest declared (incl. `auth.aang.events.services.START` = `IGoogleAuthAangService`,
  `clearcut.sampler`, `common.telemetry.notification`, `mdisync`, `dtdi`, `time.trustedtime`, `googleone`);
- all of them, plus 35 START actions from section 1, are answered by `DummyService` with
  `ConnectionResult.API_DISABLED`: client libraries fail fast with an `ApiException` instead of a
  failed bind or a hang.

## 3. What the target apps actually call

Binder descriptors in the dex of Gmail, Maps, Drive, Keep and Chrome: 250 GMS interfaces, 107 not in
the fork, 63 of them implemented by GMS. Used by all five apps and now answered (API_DISABLED):
`auth.aang.IGoogleAuthAangService`, `clearcut.sampler.IClearcutLoggerSamplingService`,
`common.internal.service.IClientNotificationTelemetryService`, `mobstore.IMobStoreFileService`,
`mdisync.IMdiSyncService`, `people.contactssync.IContactsSyncThirdPartyService`.
App-specific: Chrome (chromesync firstparty, fido2 firstparty, safebrowsing, password checkup), Gmail
(dtdi), Maps (udc, contextmanager, semanticlocation, checkin API, tflite dynamite), Drive/Maps (learning).
Real implementations are candidates for later work; none is required to start these apps.

## 4. Content providers

Of 51 GMS/GSF authorities, the apps reference 4 the fork lacks: `auth.accountstate` (Gmail, Drive;
`exported=false` in GMS, so not reachable by apps there either), `common.appdoctor` (Maps), `people`
(Gmail), and the bare package. The fork provides `gsf.gservices`, `settings`, `phenotype`,
`auth.accounts`, `chimera`, `fonts`, `subscribedfeeds`. Fixed: Gservices rejected patched apps (it
checked the real package name and the un-renamed `READ_GSERVICES` permission).

## 5. Protocol deltas applied

| Area | Play services 26.11.36 | microG before | Fork now |
|---|---|---|---|
| MCS endpoints | `mtalk.google.com` + `alt1..alt4-mtalk:443`, `alt5..alt8-mtalk:5228` | `mtalk.google.com:5228/443` only | full pool, last working endpoint first |
| Checkin interval | 43200 s, flex 10800 s | 12 h / 3 h min | unchanged (already equal) |
| Checkin endpoint | `android.googleapis.com/checkin` + `android_checkin` OAuth | `android.clients.google.com/checkin` | unchanged (works; switching untested) |

## 6. Play Store services

Exported actions: 13 implemented by the fork's vending (licensing, in-app review, install referrer,
asset delivery, split install, in-app update, integrity, engage v2, age signals), 7 out of scope
(billing, setup/restore, integrity backports), 30 missing — store-internal (details, catalog,
reviews, content filters, deku, verifier) or deep install integration, deferred per scope.

## 7. Implemented services

Each service follows the Play services protocol (transaction codes, parcel layout, callback codes,
status codes). Where a result depends on Google-internal server state, the service answers the way
Play services answers when that state is absent, never with a forged result.

| Service | Interface / action | Fork behaviour |
|---|---|---|
| SafetyNet (all 21 transactions) | `safetynet.internal.ISafetyNetService` | attest stays deprecated (status 17, as Play services); lookupUri, verify apps, harmful apps, APK digest, shared UUID implemented (Safe Browsing v4 hash prefixes, full-hash compare on the device); Safe Browsing database / list state = `SAFE_BROWSING_API_NOT_AVAILABLE`; trust token = `API_NOT_CONNECTED` |
| Safe Browsing API (354, Chrome / WebView) | `safebrowsing.internal.ISafeBrowsingService` | lookupUri + supported threat types, same client as SafetyNet |
| Client notification telemetry (449) | `common.internal.service.IClientNotificationTelemetryService` | accepted and dropped (nothing leaves the device) |
| Clearcut sampler (414) | `clearcut.sampler.IClearcutLoggerSamplingService` | always "not sampled" |
| MobStore files (160) | `mobstore.IMobStoreFileService` | open (read/write) / delete / rename with Play services' URI rules and status codes (33500 not found); features `mdd_*`, `mobstore_*`, `icing_get_document` advertised; each caller confined to its own tree, since the server-pushed directory allowlist isn't available |
| Trusted Time (326, `play-services-time`) | `time.trustedtime.service.START` | SNTP to time.google.com (1 h cache); TimeSignalResult anchored to elapsedRealtime, error = RTT/2; GlobalState with `NEW_TIME_SIGNAL`; obeys the Google-network and Tor switches |
| Auth AANG (343) | `auth.aang.events.services.START` | getAccounts (accounts with a gaia id) and hasCapabilities (microG capability store), first-party only; getToken / reauth / sync / app restrictions answer `API_NOT_CONNECTED`, so clients fall back to AccountManager |
| Engage v1 (vending) | `play.engage.BIND_APP_ENGAGE_SERVICE` | service_error_code 2 "not available for old SDK versions", as the Play Store |
| Engage v2 (vending) | `play.engage` v2 | publish / delete / status callbacks answered; availability false |
| Play Grouping API (vending) | `play.core.grouping.BIND_GROUPING_API_SERVICE` | error 12 (app not allowed); tokens need Play's server session |
| Enterprise app states (vending) | `androidx.enterprise.feedback.action.APP_STATES` | validated and dropped (a rootless store is never the managed store) |

Still `API_DISABLED` (they need Google's sync backend): `mdisync`, `people.contactssync`.

Per-app controls: SafetyNet / reCAPTCHA / Safe Browsing obey the per-app switch on the SafetyNet
screen; the "Play Protect" switch drives verify apps; the push list shows every app that requests C2DM
`RECEIVE`; Privacy → Network blocks or Tor-routes microG's own Google traffic (`GoogleNetworkPolicy`).
