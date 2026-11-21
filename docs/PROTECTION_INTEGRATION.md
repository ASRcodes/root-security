# DontMissMOM + Suraksha Kavach MVP

> Historical 3.0 integration record. The current application is **root-security 3.1-mvp**. Its URL policy, online privacy boundary and demo behaviour supersede the offline URL/demo claims below. See [current URL checks](ROOT_SECURITY_URL_CHECKS.md) and `protection/root-security-validation.json` for the new implementation and validation status. Original 3.0 results below are retained as historical evidence, not claims about the current build.

DontMissMOM is the host application (`com.asrcodes.dontmissmom`). Version 3.0-mvp adds a Protection destination alongside Home, Circle, History, and Settings. The existing Firebase authentication, trusted-contact requests, SOS delivery, voice messages, and emergency history remain in the host app.

## Source and module boundaries

The initial integration uses the current DontMissMOM working tree, including its existing uncommitted changes (base commit `d07c6628620a8a123521a56e11d443f11965c78a`), and Suraksha Kavach commit `57d6f37a7c24633d764da3854c550ae1c07eb0a9`.

- `:app`: application identity, Firebase, emergency features, and the Protection landing page.
- `:protection`: Android library containing the protection screens, notification listener, warning activity/overlay/audio, file scanner, folder watcher, encrypted local history, guardian settings, and bundled models.
- `:protection-core`: Kotlin/JVM scoring, domain rules, file rules, family-code hashing, guardian policy, and the original model parity fixtures.

The host application class initializes the local protection runtime. Detection does not require Firebase or an internet connection. Model and database loading run asynchronously. The Protection page reports readiness and background access separately; installed software alone is not treated as active background protection.

There is one launcher and one package. The feature uses its original Kotlin namespace but prefixes Android resources and local storage to avoid colliding with the host. Feature screens offer a return to DontMissMOM. Their internal protection history is distinct from emergency History.

The integration follows Android's [library-module](https://developer.android.com/studio/projects/android-library) and [manifest-merging](https://developer.android.com/build/manage-manifests) guidance. Existing AGP 8.13.2/Gradle 8.13 remain; Kotlin is aligned to 2.2.20 and JVM compilation to 17 for the imported Compose feature. API 26 remains the minimum and API 36 the target. Hindi and Punjabi resources stay in the app bundle even when the feature language differs from the device language.

## Available flows

1. Open Protection, then scan a pasted message/link or choose a file. Manual checks work without granting notification access. Sharing text from another app opens an editable check screen; the user starts the scan.
2. Review protection setup to opt into notification access and alerts. Android permissions, notification channels, and an active listener determine background warning availability.
3. Optionally enable overlay warnings, select a watched folder, set a guardian, and configure a family code word.
4. Guardian SMS requires separate opt-in, Android SMS permission, and carrier service. It is not the DontMissMOM SOS flow and does not automatically message trusted contacts.
5. Use protection settings for language, local warning audio, pause, retention, and the optional public blocklist refresh.

Clearing protection data is scoped to the feature. It must not invoke `clearApplicationUserData`, sign out of Firebase, reset emergency permissions, delete SOS history, or remove host preferences. The reset invalidates queued scans/history writes and leaves automatic protection paused until the user resumes it. Android's notification-listener grant remains installed. Explicit Android system “clear storage” still applies to the whole app.

An active SOS popup or siren takes priority over intrusive scam warnings. The protection engine still records a warning and can post a silent, tappable notification, but does not place a second full-screen warning or overlay over the emergency controls or start scam warning audio. File scanning and message scanning have separate execution locks so a slow document provider cannot block incoming-message checks.

The file scanner is a risk checker, not a full antivirus. Its bundled hash test is EICAR; APK heuristics inspect metadata. The feature does not intercept links, install APKs, record calls, or analyze cloned voices. A warning describes risk, not a guarantee that a message/file is malicious or safe.

## Build and checks

Use JDK 17, Android SDK 36, the existing ignored Firebase configuration, and the repository Gradle wrapper:

```powershell
.\gradlew.bat :protection-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :protection:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The host keeps its existing release-signing configuration. Test applications replace the guardian SMS sender with an in-memory fake; automated tests must not contact a carrier or send an SOS.

Gradle uses one worker, a 1 GB heap, and the Kotlin compiler in the Gradle process to fit this development machine. Run emulator checks after compilation when memory is limited. The tested delivery copy is `app/build/outputs/apk/suraksha-mvp/DontMissMOM-3.0-mvp-debug.apk`.

Existing Suraksha evaluation percentages describe its original datasets and are not measurements of the merged app on a physical phone.

## Integration validation — 2026-11-21

The combined debug app and instrumentation APK build successfully. Both Android modules pass lint with no errors; dependency-update and existing style/resource warnings remain. The original 31 detection-core tests and all 18 DontMissMOM unit tests pass.

APK metadata confirms `com.asrcodes.dontmissmom`, version code 6 / `3.0-mvp`, the DontMissMOM label, and one launcher (`LoginActivity`). All 12 bundled native libraries pass the 16 KB ELF alignment check, and the APK passes `zipalign -c -P 16 4`. All nine imported model/data/notice assets match the Suraksha source byte for byte.

The seven new device tests cover launcher/share registration, all eight feature routes, rotation and status, shared-text review before scanning, actual benign/scam model results, protection-only reset, and SOS priority. They use a fake emergency repository and SMS sender. Device runs and results are saved in `docs/protection/`.

On Android 16 / API 36, all 30 distinct device tests pass across the integration, existing regression, and targeted rerun logs. This includes the real five-minute siren timeout. The existing approximate-location test needed a more reliable accessibility click on Android's moving permission dialog; its checked-state and coarse-granted/fine-denied assertions remain. The original 23-test regression log records that initial test-interaction failure, and the targeted rerun records its pass. A separate reset assertion confirms automatic protection remains paused after clearing its data.

All seven protection integration tests also pass on Android 8 / API 26 with system dark mode enabled. [The machine-readable validation summary](protection/validation.json) records the test cases and APK checksum. These are emulator results, not physical-phone results.

Screenshots from the combined app: [Protection tab](protection/combined-protection-home.png) and [message checker](protection/combined-message-check.png).

Android 8 dark mode: [Protection tab](protection/combined-protection-home-api26-dark.png) and [message checker](protection/combined-message-check-api26-dark.png).

Physical-phone timing, carrier SMS with explicit opt-in, real incoming notification previews, and OEM background/DND behavior still require device verification. This source integration does not deploy Firebase/Render services or publish a Play Store release.

## Attribution

The imported source license is retained at `protection/LICENSE`. Third-party model/data notices remain in `protection/src/main/assets/THIRD_PARTY_NOTICES.txt`. The imported assets include the Public Suffix List, model JSON, training-derived blocklist, allowlist, and static local warning audio. No signing keys or server credentials are imported from Suraksha Kavach.
