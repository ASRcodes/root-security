# Optional Firebase Functions route

This Node.js 22 package implements the legacy Firestore alert-trigger route for family SOS. It exports `sendEmergencyPush` for new `alerts/{alertId}` documents. The Android application's configured external HTTP backend is a separate service; neither route is deployed by building the APK.

## Main files

| Source | Responsibility |
|---|---|
| [index.js](index.js) | Check profiles and mutual unblocked trust, reserve delivery/history, call FCM and record status. |
| [message.js](message.js) | Build high-priority data payloads, validate coordinates and constrain voice URLs to the configured Cloudinary prefix. |
| [legacyHistory.js](legacyHistory.js) | Reserve alerts transactionally, retain history, expire older voice references and queue cleanup records. |
| [message.test.js](message.test.js) | Test payloads and reservation behavior with test doubles. |
| [legacyHistory.integration.test.js](legacyHistory.integration.test.js) | Test history helpers against an isolated Firestore emulator. |

## Delivery boundaries

Reservations precede FCM calls. Existing reservations prevent replay of ambiguous pushes on trigger retries. An `accepted` status records FCM acceptance, not receipt or audio playback on a phone. This package queues expired voice references; it does not itself delete Cloudinary media.

[firebase.json](../firebase.json) selects this Functions directory. Deployment scripts use the selected Firebase project and are separate from local verification. Review the configured service/account prefixes for your own deployment.

## Push payload contract

[message.js](message.js) builds an FCM data payload for the target token, with Android high priority and a 120,000 ms time-to-live. It does not include an FCM notification object; the receiving Android application controls presentation. Priority and TTL do not guarantee delivery.

| Data fields | Encoding and constraints |
|---|---|
| `type`, `alertId`, `fromUid`, `toUid` | Emergency type is `EMERGENCY_CALL`; IDs identify the alert and its participants. |
| `fromName`, `fromPhone` | Sender profile values, bounded to 120 and 32 characters respectively; name falls back to `A trusted contact`. |
| `latitude`, `longitude`, `hasLocation` | String values; coordinates must be finite and in range. Zero/zero requires explicit `hasLocation: true` to count as a location. Invalid/absent location becomes zero coordinates with `false`. |
| `sentAt` | Send-time epoch milliseconds encoded as a string. |
| `voiceUrl` | Optional; retained only under the configured HTTPS Cloudinary account and raw/video upload prefix. |

Profile lookup, mutual trust and delivery reservation happen in [index.js](index.js) before payload submission. The builder's input checks are not a substitute for those authorization steps.

## History and voice cleanup records

[legacyHistory.js](legacyHistory.js) reserves an alert transactionally and creates separate sent and received history records with the corresponding owners. Existing delivery reservations prevent a second reservation of the same alert ID. Sender history does not retain the voice URL; receiver history may retain it while eligible.

Trimming retains voice URLs on the ten newest received records that contain recordings per owner. Text-only records do not consume this quota. Voice URLs are removed from sent records and received recordings beyond the quota; history metadata remains. Matching recipient alert records are marked voice-expired when their reference is removed.

Voice assets are tracked using a SHA-256 key derived from the URL. An existing asset marked deleting/deleted or owned by another account is not attached to received history; this check does not remove a voice URL already placed in the outgoing push payload. Active references receive a ten-minute pin, and expired references produce cleanup records with a ten-minute delay. Trimming handles up to 150 excess history records per transaction, with additional related alert and cleanup writes, and retains a pending-trim flag while work remains. A separate cleanup implementation must consume those records and remove remote media; this package does not perform that deletion.

## Local tests

From the repository root with Node.js 22:

```powershell
npm.cmd ci --prefix functions
npm.cmd test --prefix functions
```

Dependency installation needs package-registry access; the unit suite does not invoke live FCM delivery.

For integration checks, use the Firebase CLI and its Firestore-emulator prerequisites. Start a disposable emulator:

```powershell
firebase.cmd emulators:start --only firestore --project demo-dmm-v2 --config firebase.emulators.json
```

In a second terminal at the repository root:

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8087'
$env:GCLOUD_PROJECT = 'demo-dmm-v2'
npm.cmd run test:integration --prefix functions
```

The integration test rejects other host/project values and writes synthetic records to the disposable emulator. It calls history helpers directly; it does not establish deployed trigger delivery, FCM receipt or media deletion. These are available commands, not a new test-execution report.
