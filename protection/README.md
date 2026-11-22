# Android protection library

`:protection` provides Compose screens, notification checks, selected file/APK checks, caller cautions, warning delivery, guardian support and local history. The installable `:app` module consumes this library. Its Gradle `api` dependency exposes `:protection-core` to the host.

## Main entry points

| Source | Responsibility |
|---|---|
| [KavachApplication](src/main/kotlin/org/suraksha/kavach/KavachApplication.kt) | Wires models, storage, settings, reputation, warnings and family helpers. |
| [MainActivity](src/main/kotlin/org/suraksha/kavach/MainActivity.kt) | Hosts protection pages in Compose. |
| [KavachNotificationListener](src/main/kotlin/org/suraksha/kavach/notifications/KavachNotificationListener.kt) | Handles supported visible previews after notification access is granted. |
| [ScamCallScreeningService](src/main/kotlin/org/suraksha/kavach/calls/ScamCallScreeningService.kt) | Uses caller ID and local reports when the screening role is held. |
| [OnlineUrlChecker](src/main/kotlin/org/suraksha/kavach/reputation/OnlineUrlChecker.kt) | Performs optional Google reputation requests. |

## Behavior and persistence

Preview text passes through bounded memory; the listener does not read complete conversations. Room with SQLCipher stores domain, risk level, reasons and time, capped at 500 records with selectable 1-, 7- or 30-day retention. Keystore-backed secret storage is separate from caller reports in private preferences, which are capped at 200.

Reputation sends four-byte URL-hash prefixes, not complete URLs or message bodies. Failed lookups remain Not checked. Call Shield is advisory and allows ringing; it does not record or analyze voice.

[GuardianManager](src/main/kotlin/org/suraksha/kavach/family/GuardianManager.kt) sends a generic carrier SMS only when configured, enabled, permitted and eligible. Manual message checks and test-labelled findings do not trigger it. Android permissions, background policy and host SOS priority constrain warnings. File checks cover selected indicators rather than complete antivirus scanning.

## Checks

From the repository root:

```powershell
.\gradlew.bat :protection-core:test :protection:testDebugUnitTest :protection:lintDebug
```

Use a disposable emulator for instrumentation that can reset protection state:

```powershell
.\gradlew.bat :protection:connectedDebugAndroidTest
```

The library [KavachTestRunner](src/androidTest/kotlin/org/suraksha/kavach/KavachTestRunner.kt) replaces SMS sending but does not itself replace online reputation transport. Host integration tests are under `app/src/androidTest/`. Available commands are separate from the [release results](../README.md#release-validation) already recorded.
