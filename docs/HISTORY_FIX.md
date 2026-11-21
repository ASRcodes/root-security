# Sent/received history and recording retention — 2026-09-28

The confirmed requirement is to keep **all sent and received alert metadata**, regardless of the siren or delivery outcome. Only audio expires: each account retains recordings on its **ten newest received alerts that contain a voice memo**. Text-only alerts do not count toward that allowance. This supersedes the earlier ten-received/five-sent record limit.

## Findings

- The previous Android query filtered to received alerts and limited the result to ten. It never filtered to missed sirens, but it excluded all sent history.
- A read-only live `/health` check identified Render revision `c973e950f6d7d2e9886758929174f0e781c62548`. That revision writes history after FCM succeeds, then trims old records. The newer history implementation in the working tree has not been deployed.
- After the owner reauthenticated Firebase CLI, a read-only production check confirmed that only the owner/timestamp history index exists and it is `READY`. Running the old received-only query against a synthetic nonexistent owner returned HTTP 400 `FAILED_PRECONDITION`; the corrected combined query returned HTTP 200. This verifies the query/index mismatch behind history loading failure without reading actual users' alert contents.
- `firebase functions:list` returned no deployed functions. The current Android sender calls Render. The updated local legacy writer is available for a future legacy-trigger rollout; deploying a new function is not necessary to repair the current HTTP history path.

## Final behavior

Android queries the signed-in owner's records in descending trigger time, including both directions and every status. It starts with 50 records plus one lookahead record. **Load older alerts** expands the live window by 50; rotation preserves that window. The screen labels **Sent to** / **Received from**, distinguishes acceptance from confirmed receipt, and shows **Voice note expired · alert details kept** where appropriate. An expired recording stops playing when its history update arrives.

The HTTP backend commits both history copies with the idempotency reservation before FCM. Valid, mutually trusted attempts with a missing receiver token also retain history and show `unavailable`. Provider rejection and ambiguous failures update both copies. A successful retry reuses the original record and timestamp. Authentication, validation, block and rate-limit rejection do not create a trusted emergency event. Requests that never reach the server are outside this online-history change; offline queuing remains deferred.

Neither siren stop, timeout, interruption nor automatic recording maintenance deletes history. The maintenance transaction clears only old `voiceUrl` fields and sets `voiceExpired: true`, preserving identity, coordinates, timestamp and status. Sent copies contain no recording reference. Legacy Firestore-triggered alerts use the same policy and create both history directions.

Expired audio is queued for the existing Cloudinary cleanup worker with a ten-minute grace period. Cleanup checks remaining history references and active-send leases before deletion. A recording shared with another recipient stays in Cloudinary while that recipient still retains it. Failed deletion remains queued. The worker requires Cloudinary credentials in the server environment and an awake server; clearing a URL alone does not free Cloudinary storage.

All metadata remains until an explicit owner deletion. Firestore storage/read costs therefore grow with the archive. The current maintenance routine scans an owner's timestamp-ordered history; a separate active-recording index can be considered if archives become large. Previously deleted history or audio cannot be restored by this change.

## Components

- Android: `AppModels.kt`, `FirebaseAppRepository.kt`, `MainViewModel.kt`, `EmergencyHistory.kt`, `HistoryAdapter.kt`, `HistoryScreen.kt`, History layouts and strings.
- Backend checkout `C:/Users/rajsi/OneDrive/Desktop/DontMissMOM-Push`: `server.js`, `history.js`, `migrate-history.js`, backend regression/integration tests and README.
- A separate release worktree at `.work/history-backend-release` starts from revision `c973e95` and contains only the history fix, maintenance scheduling, audio-cleanup retry handling and relevant tests. It leaves pending request notification/report-email features out of this release. The owner approved publication; commit `68d581e91b79cfef54ba7af9d922a0c46fd70ba6` is now deployed to Render.
- Legacy writer: `functions/legacyHistory.js`, `functions/index.js`, legacy integration test.
- Validation: `V2UiTest.kt`, `security/rules.test.js`. No dependency, manifest, authentication or rules changes were needed for this correction.

## Rollout and remaining checks

1. The existing production `history(ownerUid ASC, timestamp DESC)` index is verified ready; the updated app query works against it. No new index is required. The [Firebase index documentation](https://firebase.google.com/docs/firestore/query-data/indexing) describes missing-index errors.
2. Completed: published the isolated history patch and verified Render's exact revision. The main development checkout was aligned to that commit only after confirming no staged changes; SHA-256 comparisons verified all 16 tracked/untracked file contents were preserved. Its additional pending V2 changes remain local. If the currently undeployed legacy trigger is introduced later, use the matching updated writer. No Firestore rules or Functions were deployed.
3. Confirm that Cloudinary API credentials are configured in Render, without copying secrets into the app or repository. For a one-time cleanup of existing owners, run `node migrate-history.js` as a read-only count first. The revised `--apply` command expires only audio references; it does not delete history records. No manual production migration or direct Cloudinary deletion was run; the deployed maintenance worker follows the updated policy. Actual cloud deletion remains unverified until credentials and its result are confirmed.
4. Install the rebuilt debug APK on a compatible test installation. With two consenting devices, verify sent and received entries appear immediately, remain after dismissing the siren, and show correct location/time. Check more than ten mixed alerts, more than ten voice-bearing received alerts, and older pages. Confirm actual Cloudinary deletion after its grace period and shared-reference checks.
5. A signed release AAB and Play rollout remain part of the later release process, after device acceptance.

## Verification results

- Final debug APK and test APK built successfully. Debug lint: zero errors, 96 warnings and two hints (existing deprecations/resource/dependency suggestions).
- 18 Android JVM tests passed. All 20 UI tests passed on API 26, including every history status, sent/received labels, expired audio with preserved location, 120-record pagination and rotation. The final UI run disabled emulator animations and ran without competing build/database processes; earlier runs exposed animation/lifecycle timing failures. The new rapid-row-update test disables only its RecyclerView change animator to avoid matching both cross-fading holders.
- Development backend: 32 tests passed in the full run, plus the added 352-record archive regression passed separately (33 distinct tests). Nine Firestore rules tests, four Functions tests and both development Firestore integration tests passed.
- The isolated release backend passed all 27 HTTP/cleanup tests and its Firestore integration test. Concurrent sends retain all metadata and exactly ten received audio references. A 29-trigger scenario preserves all 58 sent/received copies after seven old recordings are cleaned up; mixed archives retain 352 metadata entries while preserving shared audio needed by another owner.
- Production read-only checks: history owner/timestamp index `READY`; old received-only query HTTP 400 `FAILED_PRECONDITION`; updated combined query HTTP 200; no deployed Firebase Functions.
- After publication, `/health` identifies `68d581e91b79cfef54ba7af9d922a0c46fd70ba6`. The existing deployment gate passed for `/v2/sendEmergency`, `/v2/profile/phone`, `/v2/requests` and `/v2/requests/deployment-check/accept`: all four reject empty unauthenticated requests with HTTP 401. These probes cannot create contacts or send alerts.
- Actual microphone/FCM/Cloudinary deletion and physical-phone history acceptance remain unverified. No real alerts, Firebase rules/index changes, Functions deployment or Play release was performed.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (SHA-256 `6b05bf08d88dc44c56cf893325c5d70754fd915261509216a3ec86bc786787e5`). This is a debug-signed test build; it cannot replace a differently signed Play installation. The release AAB was not rebuilt or published for this correction.

Final UI log: `.work/history-ui-api26-stable.txt`; screenshot reviewed: `.work/history-light.png`. The release worktree is based on the verified current GitHub/Render revision and contains no dependency changes, email workflow changes or new Firebase deployments.
