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
