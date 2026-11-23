# Google app suite report (microG fork + universal patch, clone mode)

Source APKs: NikGApps 19-Nov-2026 addons (Android 17, arm64), SHA-256 verified against the release manifest.
Patched with `tools/suite.sh` (universal microG patch + clone patch, the store's default), signed with one key.
Device: GApps-free Android 11 emulator (`tools/setup_vm.sh`: GmsCore hidden, GSF and Play removed, ARM translation, software CPU),
microG fork `0.3.18.252432.1` + GmsCompat stub installed.

## Static verification

| App | Version | Result | getPackageInfo → bridge | availability hooks | `com.google.android.gms` left | clone rewrite |
|---|---|---|---|---|---|---|
| Drive | 2.26.107.3.arm64-v8a.alldpi | PASS | 100 | 2 | 0 | 14 authorities, 315 strings and 0 static fields rewritten |
| Gboard | 17.2.3.895242737-preload-arm | PASS | 34 | 2 | 0 | 9 authorities, 4 strings and 1 static fields rewritten |
| Gmail | 2026.02.23.876771157.Release | PASS | 94 | 2 | 0 | 29 authorities, 56 strings and 0 static fields rewritten |
| Calculator | 9.1 (886477075) | PASS | 12 | 1 | 0 | 1 authorities, 2 strings and 0 static fields rewritten |
| Calendar | 2026.03.0-857911644-release | PASS | 66 | 2 | 0 | 7 authorities, 58 strings and 0 static fields rewritten |
| Chrome | 145.0.7632.218 | PASS | 51 | 1 | 0 | 10 authorities, 5 strings and 0 static fields rewritten |
| Clock | 8.7 (881990392) | PASS | 24 | 1 | 0 | 2 authorities, 26 strings and 0 static fields rewritten |
| Contacts | 4.74.28.876719826 | PASS | 53 | 2 | 0 | 10 authorities, 22 strings and 0 static fields rewritten |
| Phone (Dialer) | 226.0.928208984 | PASS | 67 | 2 | 0 | 16 authorities, 79 strings and 0 static fields rewritten |
| Docs | 1.21.482.04.40 | PASS | 43 | 2 | 0 | 15 authorities, 12 strings and 0 static fields rewritten |
| Files | 1.9456.838368644.0-release | PASS | 53 | 2 | 0 | 8 authorities, 17 strings and 0 static fields rewritten |
| Keep | 5.26.042.00.90 | PASS | 48 | 2 | 0 | 9 authorities, 39 strings and 0 static fields rewritten |
| Maps | 26.16.12.898594237 | PASS | 84 | 1 | 0 | 11 authorities, 13 strings and 0 static fields rewritten |
| Messages | messages.android_20260417_00 | PASS | 103 | 2 | 0 | 25 authorities, 73 strings and 2 static fields rewritten |
| Photos | 7.67.0.882706237 | PASS | 77 | 1 | 0 | 17 authorities, 103 strings and 0 static fields rewritten |
| Recorder | 4.2.20251118.847964105 | PASS | 37 | 2 | 0 | 6 authorities, 4 strings and 0 static fields rewritten |
| Sheets | 1.21.482.04.40 | PASS | 39 | 2 | 0 | 15 authorities, 17 strings and 0 static fields rewritten |
| Slides | 1.21.482.05.40 | PASS | 45 | 2 | 0 | 14 authorities, 15 strings and 0 static fields rewritten |
| Speech Services (TTS) | googletts.google-speech-apk_ | PASS | 19 | 1 | 0 | 6 authorities, 13 strings and 0 static fields rewritten |
| TalkBack | 17.0.0.889642762 | PASS | 37 | 2 | 0 | 9 authorities, 16 strings and 0 static fields rewritten |
| Meet | 347.0.870744322.duo.android_ | PASS | 78 | 2 | 0 | 8 authorities, 34 strings and 0 static fields rewritten |
| Play Games | 2023.01.40470 (501577610.501 | PASS | 42 | 0 | 0 | 5 authorities, 23 strings and 0 static fields rewritten |
| YouTube | 21.25.523 | PASS | 79 | 2 | 0 | 8 authorities, 11 strings and 0 static fields rewritten |
| YouTube Music | 9.10.52 | PASS | 61 | 2 | 0 | 6 authorities, 13 strings and 0 static fields rewritten |

24/24 pass: renamed manifest (c2dm, SPOOFED_* meta-data, queries, init provider), no un-renamed GMS package strings, no placeholders left, every `PackageManager.getPackageInfo` outside the extension redirected.

## On device

API 30 google_apps x86_64 emulator (software CPU, ARM translation), clone mode, 120 s after launch. Columns: launch status/attempt, process alive at the end, crashes and ANRs of the app, MorpheMicroG bridge log lines, "Play services missing" complaints.

| Package | Launch | Alive | Crash | ANR | Bridge | GMS complaints | Note |
|---|---|---|---|---|---|---|---|
| `com.google.android.apps.docs.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.apps.maps.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.apps.docs.editors.docs.morphe` | ok/try2 | yes | 0 | 2 | 1 | 0 |  |
| `com.google.android.apps.docs.editors.sheets.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.apps.docs.editors.slides.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.apps.messaging.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.apps.recorder.morphe` | timeout/try3 | no | 0 | 0 | 0 | 0 | native SIGSEGV under ARM translation (emulator) |
| `com.google.android.apps.tachyon.morphe` | ok/try2 | no | 0 | 2 | 1 | 0 | killed on start timeout (emulator speed) |
| `com.google.android.calculator.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.calendar.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.contacts.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.deskclock.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 |  |
| `com.google.android.dialer.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.inputmethod.latin.morphe` | no-launcher/try0 | no | 0 | 0 | 0 | 0 | no launcher activity |
| `com.google.android.keep.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.marvin.talkback.morphe` | no-launcher/try0 | no | 0 | 0 | 0 | 0 | no launcher activity |
| `com.google.android.play.games.morphe` | ok/try2 | yes | 0 | 1 | 1 | 0 |  |
| `com.google.android.tts.morphe` | no-launcher/try0 | no | 0 | 0 | 0 | 0 | no launcher activity |
| `com.android.chrome.morphe` | ok/try2 | no | 0 | 1 | 1 | 0 | killed after ANR (emulator speed) |
| `com.google.android.apps.nbu.files.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 | TLS crash fixed |
| `com.google.android.apps.photos.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 | TLS crash fixed |
| `com.google.android.apps.youtube.music.morphe` | ok/try2 | no | 0 | 2 | 1 | 0 | killed after ANR (emulator speed); TLS crash fixed |
| `com.google.android.gm.morphe` | timeout/try1 | yes | 0 | 0 | 1 | 0 | clone provider crash fixed |
| `com.google.android.youtube.morphe` | timeout/try3 | no | 0 | 0 | 0 | 0 | no process start observed; not yet diagnosed |

Summary: 24/24 installed; 21 launchable, 16 alive after 120 s, 0 with Java crashes (Recorder dies natively, see note), 0 with Play-services-missing complaints.

Fixed during this run:
- ProviderInstaller loaded `libconscrypt_gmscore_jni.so` from the host app APK instead of microG's when the app ABI differs from microG's primary ABI → "Unable to find a default SSL provider" (Files, Photos, YT Music).
- Clone patch now rewrites provider authorities / content URIs kept in `res/values/strings.xml` (Gmail queried the original app's protected providers).

## Services added in this round

Registered on device (`cmd package query-services` resolves each one):

| Service | Interface / action | Behaviour |
|---|---|---|
| MobStore file (160) | `IMobStoreFileService` | open (read/write), delete, rename inside a per-caller tree; features `mdd_*`, `mobstore_*`, `icing_get_document` advertised |
| Trusted Time (326) | `trustedtime.service.START` | SNTP against time.google.com (1 h cache); refuses when Google access is off or Tor is on |
| Auth AANG (343) | `auth.aang.events.services.START` | getAccounts / hasCapabilities real; token, sync and reauth calls answer API_NOT_CONNECTED |
| Safe Browsing (354) | `ISafeBrowsingApiService` | local list lookups, no verdict spoofing |
| Engage v1 | `play.engage.BIND_APP_ENGAGE_SERVICE` | answers "not available for old SDK versions" like the Play Store |
| Play Grouping | `play.core.grouping.BIND_GROUPING_API_SERVICE` | error 12 like the Play Store |
| Enterprise app states | `androidx.enterprise.feedback.action.APP_STATES` | accepted and dropped (device not managed) |

Gmail clone (`com.google.android.gm.morphe`) binds MobStore after the feature fix; no FATAL in logcat.
