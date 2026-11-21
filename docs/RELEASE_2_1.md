# Version 2.1 / code 5

This update offers emergency permissions the first time an installation opens, before Google sign-in. It requests notifications on Android 13+, foreground location and the microphone. Approximate location is accepted. A second setup screen offers Android's Do Not Disturb access and, on Android 14+, full-screen alert access. Normal install-time permissions need no user prompt.

Skipping setup or declining any access displays one explanation of the emergency purpose and the affected features. The user can continue. The decision and acknowledgment persist across restarts; rotation preserves the current step and pending explanation. No microphone recording, location lookup or alert is started by setup. App Settings retains permission controls, including a link to Android's app-permissions page.

The version increases from the working source's 2.0 / code 4 to 2.1 / code 5. The older signed `app/release/app-release.aab` contains version 1.2 / code 3. No Play Console access is available to check other uploaded tracks.

## Signing

The existing `dontmissmom_key` contains alias `dontmissmom`. Local `keystore.properties` is ignored by git and has the following properties: `storeFile`, `keyAlias`, `storePassword`, `keyPassword`. Gradle also accepts `DMM_KEYSTORE_FILE`, `DMM_KEY_ALIAS`, `DMM_KEYSTORE_PASSWORD`, and `DMM_KEY_PASSWORD` environment variables. Passwords must come from the existing key owner. Do not generate a replacement key for this update.

The new signed bundle's certificate matches the previous release: SHA-256 `12:76:6C:97:05:77:78:28:5A:ED:B0:1F:1E:A4:AF:41:A9:F9:35:53:A2:CF:AC:B0:1A:8A:9A:BA:D6:64:FB:87`. `bundleRelease` requires complete signing configuration. The existing signed version 1.2 / code 3 bundle is preserved at `app/release/app-release.aab`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
.\gradlew.bat :app:bundleRelease
```

## Verification and release findings

- Local backend: 33 tests passed on 2026-09-28, including contacts, authentication, alert deduplication, history retention, shared voice cleanup, reports and notification/email retries.
- Firebase Functions: four tests passed.
- All nine Firestore rules tests and both HTTP/legacy Firestore integration tests passed in the isolated `demo-dmm-v2` emulator. The first combined invocation used the wrong working directory; after correcting the test runner, the complete run passed.
- The final signed build passed all 18 Android JVM tests. Debug and release lint each reported zero errors, 104 warnings and two hints.
- Android 16 / API 36: all 20 app UI tests passed, including sent/received history, outcomes without audio, pagination, rotation, contact/request flows, attachment reuse and retries. Both first-launch permission tests also passed: skip, denial, grants, approximate location without precise access, special-settings links, recreation, a single explanation, restart persistence and incoming History routing.
- Android 8 / API 26: all 20 app UI tests and both first-launch permission tests passed. Its first permission run reached the final DND grant and exposed an unsupported test-fixture shell command. The corrected fixture uses Android 8's existing secure setting, preserving other package grants, as implemented by [AOSP's PolicyAccess](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#5482). The final permission rerun passed (`.work/release21-permissions-api26-final.txt`). This changes only test setup, not application permission behavior.
- The Android 8 five-minute siren test passed: the foreground service remained active before the deadline, stopped automatically after five real minutes, and the test's history entry remained. No real contact, FCM alert or audible emulator output was used.
- Early API 36 runs exposed test synchronization and emulator problems: reused permission-control IDs, display/input scaling, an invisible system dialog after snapshot restoration, and a System UI startup ANR. The test now waits for distinct permission messages and verifies the approximate selection before granting. After a cold boot, recovering System UI and restoring normal system animations, the complete permission run passed (`.work/release21-permissions-api36-cleanboot.txt`). No permission assertions were removed.
- Live Render revision remains `68d581e91b79cfef54ba7af9d922a0c46fd70ba6`. Empty unauthenticated checks for sending alerts, phone updates, creating requests and accepting requests return HTTP 401 as expected.
- Production `/v2/requests/:id/report` and `/v2/voice/register` return HTTP 404. Their implementations exist in the separate backend working tree and pass local tests, but are not deployed. Report submission and upload registration therefore are not ready in production.
- The live Firestore rules were verified earlier on 2026-09-28 to permit public reads and writes until 2028-12-07. The private rules in this repository are not deployed. Review legacy-client compatibility before a coordinated rules rollout.
- The user reports the previous APK works on their device. The new permission flow has passed on Android 8 and Android 16 emulators. This report does not claim a complete live FCM, release-certificate Google sign-in, physical-device sound or Cloudinary deletion test.

The signed bundle passes bundletool validation and JAR signature verification. All 1,169 content entries were read and their signatures verified against the previous release certificate. Its embedded manifest confirms package `com.asrcodes.dontmissmom`, version 2.1 / code 5, minimum API 26, target API 36, and no debuggable or test-only flag. SHA-256: `7a8c0471237bbb33b46eb7932e20f14977d87fd92082ec83a05a5523480d08a3`.

## Artifacts

- Signed Play bundle: `app/release/DontMissMOM-2.1-code5.aab` (15,920,360 bytes).
- Test APK: `app/release/DontMissMOM-2.1-code5-debug.apk` (24,387,554 bytes), SHA-256 `f7bb7aec82f8f22c49fe2b81ed6a6c7932c130787daa5b9b7f56e5ee70b384bc`.

The APK is debug-signed and can update a build signed with the same local debug key. It cannot update a Play-installed copy with a different signing certificate. The signed AAB is intended for an internal Play testing track first; it is not installed directly on a phone. No Play upload or production configuration change was performed for this update.

## Native-library compatibility

The initial release audit found DataStore 1.1.7's `libdatastore_shared_counter.so` in both 64-bit ABIs. Its load segments were aligned to 16 KB, but its GNU_RELRO endpoint was not. A dependency constraint now selects DataStore Preferences 1.2.1 and its matching core modules. The final signed AAB contains version 1.2.1; its arm64 and x86_64 ELF load segments and GNU_RELRO endpoints (49152 bytes) pass 16 KB alignment checks. Bundle configuration also declares `PAGE_ALIGNMENT_16K`. These are static checks; a device with a 16 KB kernel was not available. This is the only production dependency change for this update.

The keystore passwords supplied in the local ignored file were verified, and the key's certificate matches the previous signed release. Passwords were not printed or copied into the repository.

Android references: [runtime permissions](https://developer.android.com/training/permissions/requesting), [full-screen access](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications), [upload-key signing](https://developer.android.com/studio/publish/app-signing), [16 KB compatibility and RELRO checks](https://developer.android.com/guide/practices/page-sizes), [DataStore 1.2.1](https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1).
