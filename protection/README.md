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

## Notification ingestion limits

[KavachNotificationListener](src/main/kotlin/org/suraksha/kavach/notifications/KavachNotificationListener.kt) accepts a fixed set of supported messaging/mail packages plus the current default SMS app. It ignores group summaries and this application's own notifications. It uses the latest messaging-style message when available, otherwise big text or preview text.

| Bound | Behavior |
|---|---|
| Preview text | At most 16,384 characters enter processing. |
| Pending previews | The transient queue holds 32 items and drops the oldest when full. |
| Extracted URLs | At most 24 distinct URLs are considered. |
| Recent scans | The core retains up to 256 fingerprints for a 60-second duplicate window. |

Manual checks bypass duplicate suppression. Text without a URL produces a result only when local analysis finds a warning. Online reputation is requested before core duplicate suppression, so duplicate notifications are not guaranteed to avoid all network work; the reputation cache is a separate control.

## Reputation batching and cache expiry

[OnlineUrlChecker](src/main/kotlin/org/suraksha/kavach/reputation/OnlineUrlChecker.kt) checks the first six distinct eligible links in a batch. Links outside that bound remain Not checked. Missing hash prefixes are combined into one bounded request; a returned prefix alone is insufficient for a threat finding because the full hash is compared locally.

[ReputationCache](src/main/kotlin/org/suraksha/kavach/reputation/ReputationCache.kt) holds at most 512 prefix records. Provider cache durations are honored up to a 30-minute cap for records with matches and a 24-hour cap for records without matches; shorter provider durations expire sooner.

Changing the key or disabling online checks invalidates pending answers, cancels the active request and clears cached results. Disabling preserves the encrypted key for later use; removing the key deletes it. Saving a key confirms local configuration, while a completed lookup is needed to establish that the service is reachable and accepts the configuration.

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
