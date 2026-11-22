# Application module

`:app` is the installable root-security application, with application ID `com.asrcodes.dontmissmom`. It owns sign-in, trusted contacts, family SOS, emergency history and navigation into the protection library. See the [root setup guide](../README.md#build-from-source).

## Main entry points

| Source | Responsibility |
|---|---|
| [LoginActivity](src/main/java/com/asrcodes/dontmissmom/LoginActivity.kt) | Launcher and local-protection access without sign-in. |
| [MainActivity](src/main/java/com/asrcodes/dontmissmom/MainActivity.kt) and [MainViewModel](src/main/java/com/asrcodes/dontmissmom/MainViewModel.kt) | Signed-in navigation and SOS state. |
| [DmmApplication](src/main/java/com/asrcodes/dontmissmom/DmmApplication.kt) | Extends the protection graph and gives active SOS presentation priority over intrusive warnings. |
| [FirebaseAppRepository](src/main/java/com/asrcodes/dontmissmom/FirebaseAppRepository.kt) | Account/contact/history access and authenticated HTTP requests. |
| [AppFirebaseMessagingService](src/main/java/com/asrcodes/dontmissmom/AppFirebaseMessagingService.kt) | Receives push messages for the host application. |

## Service boundaries

The host depends on `:protection`, Firebase Authentication, Firestore, FCM and Google Play services. Family SOS also needs the configured external HTTP backend; building this module does not deploy it or the separate `functions/` route.

[VoiceRecorderHelper](src/main/java/com/asrcodes/dontmissmom/VoiceRecorderHelper.kt) records an explicit five-second microphone memo and uploads it to the configured Cloudinary service. It does not capture call audio. Account, family, location and voice data belong to the cloud flows, separately from local protection history.

## Account and local entry routing

[LoginActivity](src/main/java/com/asrcodes/dontmissmom/LoginActivity.kt) routes incomplete first-launch setup through the location disclosure, signed-in profiles without a phone through [PhoneLinkActivity](src/main/java/com/asrcodes/dontmissmom/PhoneLinkActivity.kt), and completed profiles into family SOS. Choosing local protection opens the protection home page directly. That choice does not sign out an existing account; a saved selection prevents a late authentication callback from replacing the chosen screen.

[FirstLaunchPermissions](src/main/java/com/asrcodes/dontmissmom/FirstLaunchPermissions.kt) accepts approximate location. Declining optional permissions still allows sign-in and basic SOS after the disclosure step; a location attachment needs the corresponding consent and permission. Phone linking validates the entered format and waits for the repository update before continuing.

## SOS selection and retry status

The main screen can send to one trusted contact or the eligible circle after confirmation. Blocked contacts and recipients with a send already in progress are excluded. The offline fallback opens the SMS composer for one eligible contact and leaves sending to the user.

[MainViewModel](src/main/java/com/asrcodes/dontmissmom/MainViewModel.kt) deduplicates recipient IDs, captures the selected location and voice attachment, and retains an alert ID per pending recipient. A retry reuses that ID and attachment while rechecking contact eligibility. Pending state lives in the ViewModel; it is not a durable delivery outbox.

| Submission state | Interpretation |
|---|---|
| Accepted | The HTTP response reports success; this does not prove receipt on the other phone. |
| Unconfirmed | A timeout or uncertain response leaves acceptance unknown. |
| Rejected | A definitive failure was returned; retry availability depends on that failure. |

[AlertHttpCall](src/main/java/com/asrcodes/dontmissmom/AlertHttpCall.kt) performs one POST with a two-minute overall deadline and no automatic replay. The matching backend must honor the alert ID for idempotency when a user retries an unconfirmed request.

## Incoming alert and siren lifecycle

Push handling rejects alerts with an explicit send time older than five minutes. The foreground observer separately considers recent, unhandled alerts from existing unblocked contacts while the UI is started. [AlertDelivery](src/main/java/com/asrcodes/dontmissmom/AlertDelivery.kt) checks that the recipient matches the signed-in account and suppresses repeated nonblank alert IDs for five minutes when notifications are enabled.

If notifications are disabled, delivery attempts a receipt update and returns without starting the siren. Otherwise it posts an actionable notification and attempts the foreground service; Android settings and background restrictions still apply.

[EmergencySoundService](src/main/java/com/asrcodes/dontmissmom/EmergencySoundService.kt) limits each accepted start to five minutes, including vibration-only alerts. A replacement marks the previous alert interrupted; timeout records `timed_out` and leaves a quiet history notification. Stop, dismiss, call-back, map and voice actions end the siren. Shutdown releases playback, vibration, audio focus and the wake lock. Alarm volume is restored only if it still matches the service-set value, preserving later user changes. Receipt and siren status updates are asynchronous and do not prove that someone read the alert.

## Voice attachment handling

The attachment moves through empty, recording, uploading and ready states. Recording requires microphone permission; the UI checks connectivity before starting and disables SOS submission while the attachment is being prepared. [VoiceRecorderHelper](src/main/java/com/asrcodes/dontmissmom/VoiceRecorderHelper.kt) records a five-second 3GP memo, uploads it through the configured Cloudinary preset, and removes the temporary local recording after completion, failure or release.

The ViewModel ignores callbacks from superseded recording attempts. A pending SOS keeps its selected attachment for retry. After a completely successful send batch, automatic clearing of the matching selection requires that no pending alert still references it. Manually clearing the selection does not change attachments already captured by pending alerts. [IncomingAlert](src/main/java/com/asrcodes/dontmissmom/IncomingAlert.kt) accepts voice playback URLs only under the configured HTTPS host and upload path.

[VoiceRegistrationQueue](src/main/java/com/asrcodes/dontmissmom/VoiceRegistrationQueue.kt) stores pending registration work in account-keyed private preferences and retries on resume or connectivity changes. Registration supports backend ownership and cleanup bookkeeping; it does not itself confirm remote media deletion.

## Checks

From the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. For device checks, select a disposable emulator; instrumentation can reset state:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The default [DmmTestRunner](src/androidTest/java/com/asrcodes/dontmissmom/DmmTestRunner.kt) replaces guardian SMS and Google reputation transport with test implementations. These checks do not establish live carrier, provider or cloud delivery. See the [recorded release validation](../README.md#release-validation) for the results actually obtained.
