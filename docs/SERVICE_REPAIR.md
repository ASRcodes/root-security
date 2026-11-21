# Alert and phone-number service repair (2026-09-25)

The physical-phone failures were traced to a deployed-server mismatch. Live, empty POST requests to `/v2/sendEmergency` and `/v2/requests` returned Express `404 Cannot POST` pages. `/health` also returned 404. The app translated those missing routes into "This action is temporarily unavailable". The matching v2 backend existed only in the local `DontMissMOM-Push` checkout.

## Repair

- Published backend commit `c973e950f6d7d2e9886758929174f0e781c62548` to `ASRcodes/DontMissMom-Backend`, branch `main`. It contains the authenticated alert, profile, request and acceptance endpoints used by the current app.
- Preserved the released v1 alert payload on `/sendEmergency` during migration, including clients without a sender UID. The adapter resolves the sender's stored profile by phone and applies mutual-contact/block checks. Its pushes include the recipient UID required by the v2 receiver. `/v2/*` still requires a valid Firebase ID token, and invalid tokens cannot fall back to legacy behavior.
- The legacy route still has the old unauthenticated contract; a phone number is not proof of identity. Disable it using `ALLOW_LEGACY_ALERTS=false` after v1 users migrate. Production Firestore rules and Functions are unchanged by this repair.
- Profile saves, contact requests and acceptance now use the same bounded HTTP transport as alerts: 15 seconds to connect, 90 seconds for a response, and 120 seconds overall. This replaces their 25-second response timeout and detached 45-second UI timer. There is one POST per attempt; ambiguous writes are not automatically replayed.
- Unknown phone numbers, duplicate contacts/requests, missing server routes, authentication failures and unconfirmed responses retain distinct outcomes. Changing the wording alone is not the endpoint fix.

## Verification

The final backend passes 23 HTTP/cleanup tests, including the original v1 payload, addressed pushes for v2 receivers, and rejection of unauthenticated v2 calls. The Firestore emulator integration test passes onboarding, concurrent phone claims, contact request/acceptance, send/retry, concurrent alert IDs and blocking. FCM is mocked in these tests; no real alerts are sent.

The Android cold-start test now covers all four mutation paths with a 60-second delayed response and verifies they stay pending past the former deadline, complete successfully, and submit once. Account-response tests distinguish an unknown number from a missing route and require explicit success before confirming a write.

## Deployment gate

Publishing to GitHub is not proof that Render has deployed the change. From the backend checkout run:

```powershell
npm.cmd run verify:deployment -- https://dontmissmom-backend.onrender.com c973e950f6d7d2e9886758929174f0e781c62548
```

The gate requires `/health` to identify the expected revision and every app mutation endpoint to reject an empty unauthenticated POST with 401, not 404. It never creates accounts/requests or sends a push. If Render is not already deploying, use **Manual Deploy > Deploy latest commit** for `dontmissmom-backend`.

The live deployment gate passed on 2026-09-25 at approximately 22:35 IST: `/health` identified commit `c973e950f6d7d2e9886758929174f0e781c62548`, and all four v2 mutation routes returned 401 for empty unauthenticated requests. The prior 404 failure is resolved on the live service. No manual Render deployment is needed now.

All 18 Android JVM tests passed on the current source (10 transport, 3 account-response and 5 domain tests). `testDebugUnitTest assembleDebug lintDebug` completed successfully; lint reports zero errors, 74 warnings and two hints. The debug APK was rebuilt at `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `1b6db76b18edb27739fd87433288d5bf4bc2b11db46260e3dc266568ef61eae2`. This is a debug-signed build, not a Play release. The older release AAB does not include this follow-up's phone/contact transport change.

The user is testing on a physical phone. ADB currently lists no connected devices, so the updated APK has not been installed from this session. The user plans to install the rebuilt debug APK and report the results of adding a number and sending/receiving a test alert. The installed v2 APK can already use the backend repair; installing the new APK also supplies the phone/contact timeout change. That physical-device outcome remains unverified; server readiness and emulator tests do not establish notification arrival. The live deployment checks still pass. After three consecutive goal turns with this remaining dependency, verification is blocked awaiting the user's physical-phone test result or a connected device.
