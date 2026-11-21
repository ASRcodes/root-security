# Firestore rule verification

The live configuration was read, without modification, on 2026-09-24. It allowed public reads and writes until 2028-12-07. The replacement rules are in `../firestore.rules`; they have not been deployed.

The v2 rules require the matching authenticated backend and Android client. Profiles can be read only by their account owner. The client can update only its FCM token; profile/phone updates and contact creation/acceptance go through the backend. Trusted contacts can block or remove the relationship. Requests, reports, alerts and history have scoped access. Server bookkeeping collections have no client permissions.

From the Android repository root:

```powershell
npm --prefix security ci
firebase emulators:exec --only firestore --project demo-dmm-v2 --config firebase.emulators.json "npm --prefix security test"
```

The demo project and localhost Firestore emulator are intentionally separate from production. Tests cover anonymous access, profile privacy, token ownership, forged contacts, block ownership, request visibility/decline, sender spoofing, recipient-only alert handling, private history, server-only records and accountable reports.

The separate backend repository also contains `server.integration.test.js`, which refuses to run unless `FIRESTORE_EMULATOR_HOST=127.0.0.1:8087` and `GCLOUD_PROJECT=demo-dmm-v2`. It tests real Firestore transactions with fake Auth and FCM, including concurrent claims for the same phone number. Run it inside `firebase emulators:exec`, using its absolute file path.

Do not deploy these rules independently of the v2 migration plan in `../docs/V2_RELEASE.md`: old clients rely on operations the new rules intentionally deny.
