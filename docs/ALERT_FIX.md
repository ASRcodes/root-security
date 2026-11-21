# Alert reliability fix — 2026-09-25

## Root causes and live evidence

Follow-up: [SERVICE_REPAIR.md](SERVICE_REPAIR.md) records the deployment repair for the missing `/v2` routes and applies the bounded HTTP transport to phone/contact operations as well.

- `FirebaseAppRepository.post` introduced a 25-second response timeout and a 45-second overall timer. The timer could finish the UI while its queued/running request continued. Render documents approximately one minute to wake a free service: https://render.com/docs/free.
- `MainActivity` used `send_failed` whenever zero recipients were confirmed. That string asserted the alert was not sent and blamed connectivity for HTTP errors, timeouts and lost responses alike.
- The running Render service still uses the legacy contract. Safe probes returned: `GET /health` → 404 (about 23 seconds); empty, unauthenticated `POST /sendEmergency` → 400 `{"error":"Missing fields"}`; empty `POST /v2/sendEmergency` → 404. The current app supplies authenticated identity and recipient/alert IDs; the legacy backend requires client-supplied sender name/phone. This explains immediate failures even with a working connection. These probes had no recipient and could not send an alert. No production delivery logs or authenticated recipient records were inspected, so a particular user's past delivery cannot be confirmed.
- `MainViewModel.sending`, the activity guard and `TrustedContactAdapter.busy` locked every contact and stored only one operation's recipients/attachments/retries. Merely enabling other buttons would have overwritten the active operation.
- The local backend released a `sending` reservation after 60 seconds, and retried generic FCM failures. Either can duplicate a notification when the original send is slow or FCM accepted it before its response/receipt was lost.

The committed v1 activity also displayed success before inspecting the HTTP response. That behavior has not been restored.

## Implemented behavior

The alert transport still uses `HttpURLConnection` and Firebase ID tokens. Only SOS uses the new bounded call: 15 seconds to connect, 90 seconds for the response, and a 120-second overall deadline covering auth, queueing, writing and reading. Location capture retains its existing eight-second bound. Other API timeouts are unchanged. The UI stays in Sending while the call is active. Exactly one completion is delivered; deadline/cancellation disconnects the call and suppresses late callbacks.

There is one POST per attempt, with streaming mode and redirects disabled. No automatic retry or preflight/wakeup POST is added. The app uses `/v2/sendEmergency` to require the authenticated, idempotent contract; an old deployment produces a service-update error. Connect failures, auth/permission errors, backend rejection/rate limits, and unconfirmed timeouts/5xx have distinct messages. Once a POST may have been submitted, a missing response is never represented as proof that it was not sent.

Per-contact pending state prevents repeat taps for the same contact while allowing other contacts to start and finish independently. Every retry, including tapping a failed contact again, keeps its original alert ID and attachment. Confirmed recipients are excluded. Results are queued so independent completions do not overwrite each other's dialogs. Configuration changes retain operations. Finishing the activity/signing out cancels outstanding client work; cancelling a confirmation sends nothing. Moving between tabs or temporarily backgrounding the activity does not cancel an already requested SOS. A submitted alert cannot be recalled. No process-death/background-resume delivery queue is introduced.

The all-contacts SOS and shared voice controls retain their existing lock during an overlapping send because they affect the same recipients/attachment; individual contact buttons and options remain independent. Recording/uploading a shared voice attachment retains its existing preparation lock.

The backend atomically reserves each alert ID in Firestore. Accepted IDs return acceptance without invoking FCM again. Pending/uncertain reservations cannot be reclaimed on timeout. Only a definitive invalid-registration-token rejection permits a later send with that ID after token repair. A crash before sending or after acceptance but before receipt persistence can remain unconfirmed: FCM and Firestore do not provide an atomic transaction. This policy prevents a speculative duplicate; it cannot guarantee delivery or infer a recipient acknowledgement. Retain deduplication records for alert IDs that may still be retried.

FCM payloads, recipient-side deduplication, notification/siren delivery, Firebase authentication, voice upload and Firestore fallback remain in place. Diagnostic logs use `AlertRequest` on Android and `Alert request` on the server, with alert ID, stage/status and elapsed time; they do not log tokens, contacts, coordinates or voice URLs.

## Verification

Files changed for this fix (the workspace also contains earlier v2 changes):

| Repository | File | Change |
| --- | --- | --- |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/AlertHttpCall.kt` | Bounded cancellable SOS transport and typed HTTP results |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/FirebaseAppRepository.kt` | Use the v2 alert route, retain Firebase auth, classify results and log request stages |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/AppModels.kt` | Return cancellation handles for alert operations |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/MainViewModel.kt` | Independent pending recipients, IDs/attachments, result queue and lifecycle cleanup |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/MainActivity.kt` | Observe per-contact state and show accurate, operation-specific results/retries |
| Android | `app/src/main/java/com/asrcodes/dontmissmom/TrustedContactAdapter.kt` | Only the active contact shows Sending and becomes disabled |
| Android | `app/src/main/res/values/strings.xml` | Distinguish rejected, unavailable, unconfirmed and connection failures |
| Android | `app/src/test/java/com/asrcodes/dontmissmom/AlertHttpCallTest.kt` | Loopback transport/cold-start/cancellation/error regression tests |
| Android | `app/src/androidTest/java/com/asrcodes/dontmissmom/V2UiTest.kt` | Contact independence, retry identity, attachment and lifecycle regressions |
| Android | `docs/ALERT_FIX.md` | Findings, behavior, checks and deployment instructions |
| Android | `docs/V2_RELEASE.md` | Correct alert endpoint, deployment finding and rebuilt artifact hashes |
| DontMissMOM-Push | `server.js` | Keep uncertain reservations, distinguish definite rejection and log stages |
| DontMissMOM-Push | `server.test.js` | Response/receipt loss, slow-send, token and voice rejection regressions |
| DontMissMOM-Push | `server.integration.test.js` | Concurrent same-ID requests against real Firestore emulator transactions |
| DontMissMOM-Push | `README.md` | Document retry policy and required Render contract update |

The test suites use fictional accounts, loopback HTTP and/or the isolated `demo-dmm-v2` Firestore emulator. FCM is mocked for backend tests. No real SOS/notification was sent.

- Transport tests cover a normal authenticated POST; a 60-second response delay extending beyond both old timeouts; connection refusal; lost response; separate read/overall deadlines; late authentication; cancellation before/after submission; account change; and HTTP 400/401/403/404/409/429/500/502/503/504. Request counters assert there is no automatic replay.
- Android UI regressions cover three independent contacts, duplicate taps/callbacks, independent attachments/retry IDs, rotation and screen-close cancellation, plus the existing navigation, voice, blocked-contact, retry and incoming-alert tests.
- Backend tests cover existing authentication, consent, history/voice cleanup and rate limits, plus expired in-flight reservations, failed receipt persistence, ambiguous FCM response loss and token-repair retries.
- A real Firestore transaction integration test checks onboarding/request/accept/send/retry/block and concurrent identical alert IDs, with exactly one mocked FCM send for that ID. The existing rules and Functions suites are also run.

Results on the final source: 15 Android JVM tests passed (10 transport, 5 existing domain); all 14 Android UI tests passed on API 26; 21 backend/cleanup tests passed; 3 Firebase Functions tests passed; 8 Firestore rules tests passed; and the real Firestore transaction integration test passed: 62 tests total, zero failures. The debug APK and unsigned release AAB built successfully; `jarsigner -verify` confirmed the AAB is unsigned. Debug lint reports zero errors, 74 warnings and two hints; release lintVital passed. Artifact hashes are in `V2_RELEASE.md`. The 60-second delay is a deterministic cold-start simulation, not a live two-device Render/FCM acceptance test.

Android verification uses the Gradle wrapper tasks `testDebugUnitTest assembleDebug connectedDebugAndroidTest lintDebug bundleRelease`, with one worker, a 768 MB heap/640 MB metaspace and the in-process Kotlin compiler. Node suites run via `npm test` in the backend, `functions` and `security` directories. The integration test uses `FIRESTORE_EMULATOR_HOST=127.0.0.1:8087 GCLOUD_PROJECT=demo-dmm-v2 npm run test:integration`. The backend integration and rules tests were run sequentially against the local emulator. Node tests used the installed Node 24.11 runtime; the deployment remains configured for Node 22.

## Deployment and device checks

1. Deploy the local `DontMissMOM-Push` backend changes together with the authenticated v2 contract. Use Node 22, `npm ci`, and `npm start` (`sendPush.js`). Preserve the existing Firebase service-account configuration (`FIREBASE_SERVICE_ACCOUNT` or `GOOGLE_APPLICATION_CREDENTIALS`) and project; no new secret or FCM setting is required for this fix. Keep any existing Cloudinary cleanup settings.
2. Coordinate the existing v1 migration requirement in `V2_RELEASE.md`: the old app sends unauthenticated requests. Keep its service available during migration (for example, a separate v2 Render service and matching app base URL) or use the project's agreed mandatory-update rollout. Replacing the legacy service with authenticated v2 without that plan would break old installed clients. No deployment or production configuration change was performed here.
3. Verify `GET /health` returns `{"status":"ok","version":"2.0"}` and empty unauthenticated `POST /v2/sendEmergency` returns 401. Then release the matching APK through the normal signing/internal-test process. The local debug APK cannot upgrade a Play-signed installation.
4. With two consenting test accounts/devices, verify warm delivery, then allow Render to idle for at least 15 minutes without health polling and verify a cold send remains Sending until acceptance. Match the Android alert ID against server `received`/`fcm_accepted`/`already_accepted` logs and verify one recipient notification. Also exercise airplane mode, background/locked receiver, voice, DND and a lost-response retry.

No rules, Firebase Functions or FCM deployment changes were introduced by this fix. The broader pre-existing v2 deployment prerequisites still apply.
