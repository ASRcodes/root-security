# Firestore rule verification

This directory tests the checked-in [Firestore rules](../firestore.rules) against a disposable local emulator. Repository tests do not establish which rules are currently deployed in a production Firebase project.

## Verification scope

A historical read-only inspection dated 2026-09-24 recorded permissive live rules and an undeployed replacement. That observation is historical, not a current production-state check. The final Android release evidence does not establish that these rules have since been deployed.

The checked-in v2 rules scope profiles to their owners and restrict client profile updates to the FCM token. Profile/phone changes and contact creation/acceptance use the matching backend. Trusted contacts can block or remove relationships. Requests, reports, alerts and history have scoped access; bookkeeping collections have no client permissions.

## Run locally

Use Node.js/npm, the Firebase CLI and a compatible Firestore-emulator Java runtime. From the repository root:

```powershell
npm.cmd ci --prefix security
firebase.cmd emulators:exec --only firestore --project demo-dmm-v2 --config firebase.emulators.json "npm.cmd test --prefix security"
```

[firebase.emulators.json](../firebase.emulators.json) binds Firestore to `127.0.0.1:8087` with the emulator UI disabled. [rules.test.js](rules.test.js) uses project `demo-dmm-v2`, clears emulator records before each case and creates synthetic fixtures. Keep this environment separate from production and from any emulator data you need to retain.

The suite covers anonymous access, profile privacy, token ownership, forged contacts, block ownership, request visibility, sender spoofing, recipient-only alert handling, private history, server-only records and accountable reports. It checks rule decisions, not notification delivery or device behavior. The commands above are instructions, not a claim that the suite ran during this documentation update.

## Deployment boundary

Coordinate rule changes with the matching Android client and authenticated backend. The [v2 migration notes](../docs/V2_RELEASE.md) explain why older clients rely on operations these rules deny; those notes are historical and do not certify a current deployment.

The separate HTTP backend has its own integration suite. It is outside this repository. The optional [Firebase Functions tests](../functions/README.md#local-tests) exercise a different route and are not a replacement for that backend's verification.
