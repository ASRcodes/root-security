# Emergency dashboard, received history and trusted-contact notifications

This change extends the existing XML/Material Android UI, FirebaseAppRepository, authenticated Render HTTP service, Firestore collections, FCM receiver, and unsigned Cloudinary upload. It does not replace authentication or the idempotent SOS transport.

## Architecture and files

The HTTP backend is a separate working tree at `C:/Users/rajsi/OneDrive/Desktop/DontMissMOM-Push`. Both repositories must be released together. No production deployment, real SOS, administrator email, or Cloudinary deletion was performed during local verification.

Android files changed for this feature:

- `MainActivity.kt`, `MainViewModel.kt`, `AppModels.kt`, `FirebaseAppRepository.kt`: Home contact actions, History navigation/subscription, existing send confirmation, and authenticated report/voice registration calls.
- `EmergencyHistory.kt`, `HistoryAdapter.kt`, `HistoryScreen.kt`: receiver records, server receipt updates, date/location/status cards, bounded asynchronous voice playback and lifecycle cleanup.
- `EmergencySoundService.kt`, `AlertDelivery.kt`: five-minute timeout, bounded wake lock, interrupted-alert status, and a quiet History notification after timeout. Stopping the service never deletes history.
- `RequestNotifications.kt`, `AppFirebaseMessagingService.kt`, `FcmTokenSync.kt`, `LoginActivity.kt`, `PhoneLinkActivity.kt`, `LocationDisclosureActivity.kt`, `RequestsActivity.kt`, `AndroidManifest.xml`: request FCM handling/channel, recipient-checked notification routing, request focusing, and token refresh/retry.
- `VoiceRecorderHelper.kt`, `VoiceRegistrationQueue.kt`: retain the same Cloudinary upload and queue registration of returned URLs for orphan cleanup, including uploads cancelled after the server accepted them.
- `res/layout/page_home.xml`, `page_history.xml`, `item_history.xml`, `activity_main.xml`, `res/menu/main_bottom_nav.xml`, `res/drawable/ic_history.xml`, `res/values/strings.xml`: existing theme colors/styles reused in light/dark mode.
- `V2UiTest.kt`, `SirenTimeoutTest.kt`: dashboard, notification route/report, History themes/rotation, and a full five-minute service test.

Documentation: `README.md`, this guide, and `docs/screenshots/emergency-features/`.

Firebase files: `firestore.rules`, `firestore.indexes.json`, `firebase.json`, `security/rules.test.js`, `functions/index.js`, `functions/legacyHistory.js`, `functions/message.test.js`, `functions/legacyHistory.integration.test.js`, `functions/package.json`.

Backend files: `server.js`, `sendPush.js`, `history.js`, `workflows.js`, `voiceCleanup.js`, `migrate-history.js`, `verify-deployment.js`, `server.test.js`, `server.integration.test.js`, `workflows.test.js`, `package.json`, `package-lock.json`, `.env.example`, `README.md`.

## Data and retention

Existing `history/{alertId}_received` documents remain the source of truth. `ownerUid` is the receiver; existing counterpart fields retain the sender identity. `fromUid`, `toUid`, `alertId`, server `timestamp`, coordinates, `voiceUrl`, and `deliveryStatus` describe the event. Optional device fields are `receivedAt`, `sirenStatus`, and `sirenStoppedAt`. FCM acceptance is not represented as proof of device receipt or human response.

The record is committed in the same transaction as the existing delivery reservation **before FCM**, so lost responses and siren shutdown cannot lose it. The emergency push remains high-priority data-only with the original idempotent retry policy. Legacy Firestore-triggered alerts feed the same history. The recipient can update only receipt/siren metadata, never sender/location/voice/timestamp/ownership or deletion.

The [2026-09-28 history correction](HISTORY_FIX.md) supersedes the original record limit. The screen queries `ownerUid == currentUid`, `timestamp DESC`, showing sent and received alerts in a live window that grows by 50 when the user chooses **Load older alerts**. All history metadata is retained. Only the ten newest received records **with recordings** keep `voiceUrl`; older recordings expire without deleting the history or legacy alert document. Text-only alerts do not consume the recording budget. `users.historyNeedsTrim` recovers failed audio maintenance. Cleanup checks every remaining recipient reference before deleting a shared Cloudinary asset.

The one-time migration defaults to a read-only count of expiring audio references. `--apply` clears those references and queues audio cleanup; it never deletes history metadata. Already-deleted records cannot be recovered by this migration.

## Requests, reports and FCM

`POST /v2/requests` retains its existing body/authentication and now persists a request version and notification outbox fields, then attempts the push. The same Admin Messaging instance sends a notification+data payload containing `type`, `requestId`, requester/receiver UIDs, and request version. Android displays the notification automatically in the background; the existing messaging service displays it while foregrounded. The `trusted_contact_requests` channel is separate from the emergency alarm channel within the same FCM architecture. Tap routes to the request list, focusing the request; a closed request shows an explanatory message. Accept and reject continue using existing operations.

`POST /v2/requests/:requestId/report` takes `{reason}` plus Firebase ID-token auth. A transaction verifies the current user is the pending request recipient, derives identities and request details on the server, writes a stable report ID into existing `reports`, and marks the request `reported`. Repeated submission is idempotent; a reported request cannot be accepted or recreated. Clients cannot forge reports or edit mail state.

Request notifications and report emails use leases and durable retry timestamps on their existing documents. Invalid FCM tokens are cleared only if they still match the failed token, preserving concurrent refreshes. Missing tokens retry after the receiver opens the app. Request/email sends can be repeated after a worker crashes following provider acceptance; notification tags and stable email Message-ID reduce visible duplication. Emergency sends keep their stricter existing no-replay behavior.

Token registration runs on authentication/app resume/network recovery and `onNewToken`, with bounded retries and a server update timestamp. Notifications still require Android permission and enabled channels; force-stopped apps and manufacturer restrictions cannot be overridden. [Firebase message handling](https://firebase.google.com/docs/cloud-messaging/android/receive-messages).

## Voice storage

Existing unsigned upload cloud/preset and `raw/upload` multipart format stay unchanged. No Cloudinary secret is added to Android.

`POST /v2/voice/register` accepts `{voiceUrl}` with auth and uses existing `voiceAssets`/`voiceCleanup`. Unused registered uploads receive a 24-hour attachment grace period. Registration failures remain in a per-account local queue for resume/network retry. Expired history voices are queued with a ten-minute grace period. The cleanup worker checks *all* retained history references, including other receivers, and active send leases before claiming deletion. It uses the correct Cloudinary raw/video public ID rules, retries failures, and requires server credentials. A shared recording remains until every retained recipient reference expires. Existing uploads for which no URL was ever received/registered are not discoverable through Firestore.

## Server configuration and release

Keep existing `FIREBASE_SERVICE_ACCOUNT` or `GOOGLE_APPLICATION_CREDENTIALS`, Firebase project, and `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET`. `ALLOW_LEGACY_ALERTS` behavior is unchanged. Deploy with Node 22 and `npm ci` / `npm start`.

For Gmail over HTTPS configure **only on the server**:

- `REPORT_ADMIN_EMAIL`: admin recipient.
- `GMAIL_SENDER`: the project Gmail account or an approved send-as address.
- `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REFRESH_TOKEN`: server OAuth credentials authorized for `https://www.googleapis.com/auth/gmail.send`. Enable Gmail API and use a durable production OAuth consent configuration. Testing-mode refresh tokens can expire after seven days.

Alternative SMTP configuration: `REPORT_ADMIN_EMAIL`, `SMTP_USER`, `SMTP_PASS` (provider app password), optionally `SMTP_HOST` (default `smtp.gmail.com`), `SMTP_PORT` (default `465`) and `SMTP_FROM`. Gmail HTTPS configuration takes precedence. [Render free services block SMTP ports](https://render.com/docs/free); [Gmail send API](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send).

Reports remain stored with `emailStatus=unconfigured` until mail settings are provided, then retry. A stored report is acknowledged without falsely promising email delivery. The maintenance loop runs every minute at startup/while the Render process is running. Use an always-on service for continuous retry/cleanup; a sleeping free service resumes this work when it wakes. No secrets belong in git or the APK.

Release sequence (not executed against production in this session):

1. Deploy backend including the new modules; set Cloudinary/Gmail server environment values.
2. Deploy `firestore:indexes`, wait for the received-history index to be ready, then deploy `functions:sendEmergencyPush` and `firestore:rules` from the Android repository. Review compatibility with remaining legacy clients before the rules rollout.
3. Run `npm run migrate:history` for a dry-run; review its count and run `npm run migrate:history -- --apply` once for existing owners.
4. Run `npm run verify:deployment -- https://dontmissmom-backend.onrender.com <deployed-commit>`; probes are empty/unauthenticated and send no alerts.
5. Install the rebuilt APK and run two consenting-device acceptance checks for actual FCM foreground/background/closed-app delivery, microphone/voice playback, map opening, Cloudinary deletion and admin inbox arrival.

## Verification results

- Debug APK builds successfully; lint has **zero errors**, 96 warnings and two hints (including existing deprecations, resource suggestions and dependency update notices).
- 18 Android JVM tests pass, preserving authenticated transport, retry IDs/attachments and domain behavior.
- All 18 UI tests pass on Android 8/API 26 and Android 16/API 36. The separate five-minute service timer test passes on API 26 with its history fixture preserved. The timer uses Android elapsed time; host wall time on the loaded emulator was longer.
- All 30 backend tests pass on both Node 24 and Node 22; `npm audit --omit=dev` reports zero vulnerabilities.
- Nine Firestore permission tests pass. The backend Firestore integration test passes concurrent alert-ID deduplication, 10-record received retention, requests/report state and mocked email-outbox delivery. The legacy Functions Firestore integration also passes real transactional retention. Four Functions unit/payload tests pass.
- Screenshots under `docs/screenshots/emergency-features/` show the actual Home and History layouts in light/dark mode, including a large-text History capture. Production push arrival, admin inbox delivery and actual Cloudinary deletion require the coordinated deployment and consenting-device checks listed above.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. SHA-256: `918b6c7cada23f734ced50136811735f9458a10265e47faceddda254638b520c`. This is a debug-signed APK.

## Local verification commands

From the Android repository, PowerShell:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=640m" "-Pkotlin.compiler.execution.strategy=in-process" assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=640m" "-Pkotlin.compiler.execution.strategy=in-process" assembleDebug connectedDebugAndroidTest lintDebug
# Final focused UI run, plus JVM/lint/build checks:
.\gradlew.bat --no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=640m" "-Pkotlin.compiler.execution.strategy=in-process" "-Pandroid.testInstrumentationRunnerArguments.class=com.asrcodes.dontmissmom.V2UiTest" assembleDebug connectedDebugAndroidTest lintDebug testDebugUnitTest
npm.cmd test --prefix functions
firebase.cmd emulators:start --only firestore --project demo-dmm-v2 --config firebase.emulators.json
npm.cmd test --prefix security
$env:FIRESTORE_EMULATOR_HOST='127.0.0.1:8087'
$env:GCLOUD_PROJECT='demo-dmm-v2'
npm.cmd run test:integration --prefix functions
```

From `C:/Users/rajsi/OneDrive/Desktop/DontMissMOM-Push`:

```powershell
npm.cmd test
npm.cmd audit --omit=dev
npx.cmd --yes --package=node@22 node --test server.test.js voiceCleanup.test.js workflows.test.js
$env:FIRESTORE_EMULATOR_HOST='127.0.0.1:8087'
$env:GCLOUD_PROJECT='demo-dmm-v2'
npm.cmd run test:integration
```

Tests use fictional identities and mocked FCM/Gmail/Cloudinary providers or the isolated Firestore emulator. Android instrumentation uses the existing fake repository; the five-minute test runs the real foreground sound service. It does not prove a physical-device FCM delivery or a production email/deletion. The local Node runtime is 24.11. The same 30 backend tests also pass under Node 22 via the explicit command above; the backend deployment engine stays Node 22.

The final test-only APK was rebuilt with `assembleDebugAndroidTest` using the same Gradle memory/compiler flags above. Android 16/API 36 was checked with the same APK and instrumentation package using:

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5556 shell am instrument -w -e class com.asrcodes.dontmissmom.V2UiTest com.asrcodes.dontmissmom.test/androidx.test.runner.AndroidJUnitRunner
```

The API 36 emulator initially showed a System UI ANR dialog blocking app focus; the OS dialog was dismissed before rerunning app tests. The existing popup-close assertion was synchronized with activity destruction, and the new Home action test uses the visible contact button to exercise the actual user interaction.
