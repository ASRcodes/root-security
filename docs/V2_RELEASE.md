# Version 2 release checklist

## Reviewable local changes

The [history correction](HISTORY_FIX.md) preserves all sent/received alert metadata and expires only recordings beyond the ten newest received voice memos. It supersedes earlier history record limits. Its isolated backend commit `68d581e` is deployed and the live revision/authentication gate passes. Physical-device history acceptance and Cloudinary cleanup confirmation remain before release sign-off.

The follow-up [service repair](SERVICE_REPAIR.md) addresses the missing live alert/contact endpoints and supersedes this document's earlier blanket statement that no backend changes were published. It preserves the v1 alert contract during migration; the private rules rollout remains separate.

The alert reliability follow-up, live Render contract finding, timeout/cancellation policy and duplicate-prevention tests are documented in [ALERT_FIX.md](ALERT_FIX.md). Its deployment finding supersedes the earlier assumption that the live service already accepted the v2 alert contract.

Android versionName 2.1 / versionCode 5. The [2.1 release report](RELEASE_2_1.md) covers first-launch permissions, the signed bundle and current checks, and supersedes older artifact/test results below. Code 5 exceeds all locally available versions; check other Play Console tracks before upload.

The application ID remains `com.asrcodes.dontmissmom`. The new Home / Circle / Settings navigation retains its selected page after recreation, and Back returns to Home from the other tabs. All major screens use the same light/dark design and system/keyboard inset handling.

SOS broadcasts capture their attachment once, wait for all recipient results and distinguish acceptance from unconfirmed delivery. Retry targets only unconfirmed recipients and reuses the original IDs and attachment. Backend deduplication requires the updated backend.

Temporary mute expires at receipt time, even if the main screen has not been opened. Incoming alerts preserve coordinates/voice, update an existing popup, bound playback/network waits and restore alarm volume after the sound service ends.

## Live configuration finding

A read-only check on 2026-09-24 found the deployed Firestore rules allow public reads and writes until 2028-12-07. No production configuration was changed. The checked-in firestore.rules replaces that access with private profiles, owner-scoped data and server-authorized contact creation/acceptance. Eight rules-emulator tests pass. The history composite index is already READY.

## Backend contract and compatibility

The Android app currently calls:
`https://dontmissmom-backend.onrender.com/v2/sendEmergency`.

The local backend accepts both `/sendEmergency` and `/v2/sendEmergency`, with:
- `Authorization: Bearer <Firebase ID token>`
- JSON `toUid`, `alertId`, optional `latitude`, `longitude`, `hasLocation`, `voiceUrl`.
- HTTP 200 plus `success: true` means FCM accepted the message.
- 401: sign in again; 403 or coded 404: contact unavailable; uncoded 404: backend update required; 409: in flight, uncertain delivery, missing receiver token or expired voice; 429: rate limit; 5xx: unconfirmed.

The v2 receiver requires the payload recipient UID to match the currently signed-in account. Older unaddressed pushes are ignored to prevent cross-account alerts.

The old app does not send the authentication header. Deploying this backend as-is makes v1 SOS calls fail with 401. Keeping the old unauthenticated endpoint exposes the original spoofing risk. This needs an explicit release/migration decision, not a silent backend replacement.

Recommended sequence:
Run local rules tests with `firebase emulators:exec --only firestore --project demo-dmm-v2 --config firebase.emulators.json "npm --prefix security test"`. This configuration targets an isolated demo emulator; it is not the production deployment configuration.

1. Set up a staging Firebase project, Cloudinary preset and Render service, with two consenting test accounts/devices.
2. Verify the live database rules and indexes against every new client operation; run permission tests using ordinary user tokens, not Admin credentials.
3. Exercise two-device alert delivery and upgrade from the released APK, including the checks below.
4. Review the mandatory-update or separate-v2-service migration with the existing v1 population.
5. Sign the reviewed bundle with the existing upload key and use an internal Play track before a staged public rollout.
6. Deploy only the reviewed backend/Functions/rules configuration, then monitor acceptance, errors and crash reports.

No deployment, push to git, signed publication or real SOS was performed during this local work.

## Runtime and server configuration

Both Node projects now use Firebase Admin 14's modular APIs and Node 22. Functions uses firebase-functions 7's v1 trigger API to preserve the existing trigger generation. The targeted gaxios 6.7.1 override uses uuid 11.1.1 or later; gaxios uses its compatible v4 API. Re-check this override on future Google SDK updates.

Render needs `FIREBASE_SERVICE_ACCOUNT` (JSON) or `GOOGLE_APPLICATION_CREDENTIALS` (file path). Optional Cloudinary cleanup uses `CLOUDINARY_API_KEY` and `CLOUDINARY_API_SECRET`. No credentials should be copied into source or build artifacts.

History trimming needs an index for `history(ownerUid ASC, timestamp DESC)`. The cleanup worker uses `voiceCleanup(notBeforeMs)` and `history(voiceUrl)` single-field indexes. Delivery reservations, rate limits and voice cleanup/asset leases are server-owned collections and must not be writable by app users. Configure retention/TTL for old delivery/rate records.

The v2 client uses authenticated `/v2/profile/phone`, `/v2/requests` and `/v2/requests/:id/accept` endpoints. It no longer queries other users' private profiles. The new rules keep email and FCM tokens visible only to their owner. Existing user documents do not need to be copied to a new schema; phone reservations are created transactionally when a number is saved, with checks against legacy profiles. Both the v2 backend and rules are required before these operations are ready for public use.

## Device acceptance checks still required

The owner confirmed that no separate staging environment or two test phones/accounts are available. This handoff is a local release candidate; production and real-device checks remain outstanding.

- Google sign-in using the real release certificate; existing-profile routing; sign out/in to a different account; token rotation.
- Two real devices: trusted request/accept/reject/block/remove and duplicate/self request handling with deployed rules.
- SOS: foreground, background, locked screen, notifications denied, DND enabled/disabled, full-screen access denied, battery restrictions, process removed, force-stop and offline receiver.
- Voice: actual microphone recording, Cloudinary upload, two-recipient playback, failure/retry, retention and cleanup without deleting another recipient's recording.
- Poor network, Render cold start, response loss, expired token and partly successful broadcast.
- Receiver acknowledgement is not implemented: acceptance is not a delivery/read receipt.
- Actual OEM sound behavior and accessibility services. Emulator screenshots cannot establish real-device loudness or delivery reliability.

## Local artifacts

Version 2.1 / code 5 artifacts are listed with checksums in the [release report](RELEASE_2_1.md). The AAB is signed with the existing upload key. The debug APK is a preview build and cannot upgrade a Play-installed copy signed with the production key. Use the debug APK with an existing debug installation or use the signed AAB through an internal Play testing track.

- Test APK: `app/release/DontMissMOM-2.1-code5-debug.apk`.
- Signed Play bundle: `app/release/DontMissMOM-2.1-code5.aab`.

## Rollback considerations

Keep the previous signed app and backend revision available. An Android rollout rollback cannot uninstall the new version from users who already updated. Check payload compatibility in both directions. Do not revert to the insecure unauthenticated endpoint without explicitly evaluating that exposure.
