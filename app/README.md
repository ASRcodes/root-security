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
