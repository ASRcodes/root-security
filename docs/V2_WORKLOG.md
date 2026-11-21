# Version 2 implementation and verification

## Implemented

- Actual Home / Circle / Settings bottom navigation, Back behavior, selected-tab restoration and shared system/IME insets.
- Consistent light/dark Material design across login, phone setup, disclosure, Home, Circle, Requests, Settings and incoming alerts.
- Empty/loading/offline/validation/error states, contact actions, permission Settings links and bounded request confirmation.
- Immutable voice attachment per broadcast; blocked recipients excluded; retry only unconfirmed recipients with the same alert IDs.
- Authenticated backend identity, mutual consent, duplicate/self request checks, atomic contact acceptance, private profiles and phone reservations.
- Incoming payload compatibility, coordinate validation, HTTPS voice allowlist, repeat-alert updates, media cleanup and six-hour mute expiry.
- Removed invalid persistent listener/restart services and automatic SMS receiver behavior. Offline SOS offers a user-operated SMS composer.
- Node 22 / modular Firebase Admin 14, updated Functions SDK, Cloudinary raw/video cleanup with shared-asset protection.

## Verification (2026-09-24 to 2026-09-25)

| Check | Result |
| --- | --- |
| Android unit tests, final build | 5 passed (phone, mute, coordinates, payloads, URL validation) |
| Final debug APK and unsigned release AAB | Built successfully; release lintVital passed |
| Final API 26 UI regression suite | 9 passed; light/dark and landscape screenshots reviewed. Status-bar contrast and system-button insets verified |
| Final API 36 UI regression suite, gesture navigation | 9 passed; light/dark, incoming alert and landscape screenshots reviewed |
| API 36 at 200% system text size, gesture navigation | 9 passed; text wraps and scrollable actions remain reachable |
| API 36 with three-button system navigation | 9 passed; portrait bottom and landscape side insets verified |
| Final Android lint | 0 errors, 74 warnings and 2 hints; dependency updates, legacy unused resources/style suggestions remain. The compiler also reports deprecated Google sign-in APIs |
| Backend HTTP and cleanup tests on Node 22 | 16 passed |
| Firebase Functions tests on Node 22 | 3 passed |
| Firestore rules tests on local demo emulator | 8 passed |
| HTTP backend with real Firestore emulator transactions | Passed, including concurrent phone claims and the full request/accept/send/retry/block flow |
| npm audits: backend, Functions, rules tests | Zero known vulnerabilities at the time of the check |
| Deployed Firestore configuration, read only | Public read/write until 2028-12-07; history composite index READY |

The test repository uses fictional accounts and does not send real FCM/SMS alerts. Rules tests run on demo-dmm-v2 at localhost. Service-account credentials were used only for the read-only rules/index configuration check and were not printed or copied into source.

## Local verification complete

The nine Android UI tests were run in each of the four configurations above; these are repeated configurations of the same suite, not 36 distinct tests. They cover navigation, Back, recreation, system insets, landscape reachability, contact validation, alert presentation and SOS retry/attachment state with a fake repository.

The first Android 16 attempts were interrupted by emulator/System UI unresponsiveness under host memory pressure. The successful runs used a recovered emulator at 720 x 1600, density 280 (approximately 411 dp wide). They do not replace tests on physical phones. No app-source changes were needed between these runs and the final build.

The final debug APK and unsigned release AAB are listed with SHA-256 hashes in [V2_RELEASE.md](V2_RELEASE.md). Full local UI logs and captures are retained in the ignored `.work` folder.

## Reviewed screenshots

These captures use fictional test accounts:

- [Home, light](screenshots/home-light.png) and [Home, dark](screenshots/home-dark.png).
- [Trusted circle](screenshots/circle.png) and [incoming alert](screenshots/alert.png).
- [Home at 200% text size](screenshots/home-large-text.png) and [landscape at 200% text size](screenshots/landscape-large-text.png).
- [Home with three-button navigation](screenshots/home-three-button.png) and [landscape with three-button navigation](screenshots/landscape-three-button.png).

## Release gates

See [V2_RELEASE.md](V2_RELEASE.md). Nothing has been deployed or published. The v2 client, backend and rules must ship together with an explicit v1 migration plan. End-to-end Google sign-in, real FCM/voice/DND/OEM behavior, signed upgrade and two-device testing require the staging/device environment.

## Reproduction

Android: Gradle wrapper tasks testDebugUnitTest, assembleDebug, assembleDebugAndroidTest, connectedDebugAndroidTest, lintDebug and bundleRelease. On this machine, run one build/emulator at a time. The final build passed using a 768 MB heap, 640 MB metaspace, one worker and the in-process Kotlin compiler.

Rules: `firebase emulators:exec --only firestore --project demo-dmm-v2 --config firebase.emulators.json "npm --prefix security test"` after `npm ci` in security.

Backend/Functions: `npm ci`, `npm test`, `npm audit` in each folder using Node 22.

Platform references: [Android edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge), [foreground service restrictions](https://developer.android.com/about/versions/15/changes/foreground-service-types), [Firebase rule management](https://firebase.google.com/docs/rules/manage-deploy).
