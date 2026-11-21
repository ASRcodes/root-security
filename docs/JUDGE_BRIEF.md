# root-security 3.2-final — judge brief

**Pitch:** “root-security brings scam guidance and family SOS into one Android app. It explains why something looks risky, distinguishes local suspicion from provider evidence, and helps the user decide what to do next.”

This is a hackathon prototype with a debug-signed demonstration APK. Use [Final submission](FINAL_SUBMISSION.md) for current artifact and verification status and [Demo script](DEMO_SCRIPT.md) for exact inputs. Do not convert unit-test totals into a detection-accuracy percentage or present fixture/emulator results as live-service proof.

## Actual stack and architecture

| Layer | Current implementation |
|---|---|
| Android platform | Kotlin 2.2.20, Java 17, AGP 8.13.2; minimum API 26, compile/target API 36. Call screening needs a supported API 29+ role. |
| Host application: `app` | Activities, XML/Material UI, ViewModels, LiveData and coroutines; sign-in, trusted family contacts, SOS, FCM and navigation. |
| Android protection: `protection` | Compose/Material 3 and StateFlow; manual/share input, notification listener, warning UI, caller screening and Android permissions. Room with SQLCipher stores encrypted history; Android Keystore backs secret storage. |
| Portable logic: `protection-core` | Pure Kotlin URL parsing, explainable rules, text scoring, caller/file rules and shared harmless demo inputs. |
| Local models | Character 3–5-gram TF-IDF plus logistic regression for text. The old URL ExtraTrees model is retained for diagnostic/parity work and does not decide URL verdicts. No LLM is used in this path. |
| Optional reputation | Google Safe Browsing: send four-byte URL-hash prefixes over HTTPS and compare returned full hashes locally. A failed lookup remains **Not checked**. |
| Family services | Firebase Authentication, Firestore and FCM; configured SOS HTTP service and Cloudinary voice upload. `functions/` contains a separate Node.js 22 Firebase Functions path. Building the APK does not deploy these services. |

Build definitions: [versions](../gradle/libs.versions.toml), [app](../app/build.gradle.kts), [protection](../protection/build.gradle.kts), [core](../protection-core/build.gradle.kts), [Functions](../functions/package.json). Model implementation: [TextModel](../protection-core/src/main/kotlin/org/suraksha/core/TextModel.kt) and [VerdictEngine](../protection-core/src/main/kotlin/org/suraksha/core/VerdictEngine.kt).

The application has three separate paths:

1. **Messages/links:** manual input, Android share or a supported visible notification preview → local parsing/rules/text scoring → optional reputation → quiet result, caution or warning → encrypted result metadata. Android permissions, background policy and SOS priority constrain warning delivery. See [protection orchestration](../protection/src/main/kotlin/org/suraksha/kavach/KavachApplication.kt).
2. **Calls:** Android supplies caller ID → local caller rules and the user's reported-number list → advisory notification/permitted banner. Calls continue normally; audio is not analyzed. See [screening service](../protection/src/main/kotlin/org/suraksha/kavach/calls/ScamCallScreeningService.kt).
3. **Family SOS:** signed-in user initiates an emergency action → configured cloud/service flow → trusted receiver. Receiver delivery and playback require separate end-to-end verification.

Local protection is available through **Open protection without sign-in**. Family SOS still needs an account. “Local history” does not mean the whole app is offline: protection history is encrypted domain/result/reason/time metadata, caller reports are separate private SharedPreferences, Google sees request/IP metadata, and family features use cloud services. See [privacy boundaries](../README.md#data-and-privacy-boundaries).

## The next six hours

These are **proposed next steps, not completed work or promised features**. Existing key-entry and permission screens already exist; successful live-provider, physical-carrier and deployed-SOS proof remain separate tasks unless the final evidence record is updated.

| Time | Priority and achievable output |
|---|---|
| 0:00–1:00 | **Verify Google integration.** Check the intended Cloud project/API/key restrictions and run a real normal lookup plus Google's official fixture. Record response state and provider attribution without exposing the key. If unavailable, document the actual failure and retain the honest local demo. |
| 1:00–2:00 | **Rehearse on the target phone.** With a consenting helper, test normal/caution/reserved high-risk incoming messages and a call from a temporarily locally reported number. Verify previews, permissions, ringing and dismissal; remove the temporary report. Record actual device behavior. |
| 2:00–3:00 | **Verify family SOS with two consenting accounts.** Confirm Firebase/signing configuration and the configured service/media deployment. Observe the receiver's alert, optional location and audio playback. If setup cannot be completed, label the dependency unverified rather than claiming delivery. |
| 3:00–4:30 | **Measure errors on a small labelled set.** Include ordinary commercial/deep/short links, ambiguous account notices and credential-pressure messages across supported languages. Record false positives and missed threats separately. Fix only reproducible rules with regression cases; do not claim the set represents all scams. |
| 4:30–5:15 | **Fix the most visible remaining friction.** Review permission explanations, text scaling, contrast and language/audio behavior on the demo phone. Triage relevant lint findings; avoid a large redesign or rushed model retraining. |
| 5:15–6:00 | **Freeze and rehearse.** Record the exact APK hash/revision, update the evidence record, repeat the three-minute script and keep a clearly labelled emulator/recorded fallback. Publish only verified claims and keep credentials outside Git. |

After the hackathon: representative field evaluation, improved redirect/destination assessment, production credential/deployment design, more-device reliability testing and commercial threat-service licensing are future work. They are not capabilities established by this submission.

## Eight likely judge questions

1. **What is different from antivirus or caller-ID apps?**  
   Our implementation combines understandable message/link advice, caller cautions and family SOS in one workflow. Its emphasis is explicit uncertainty and actionable family support. We are not claiming a new category, a novel classifier or universal superiority over existing products.

2. **Which AI model actually runs? Did you build Google's model?**  
   The local text model is TF-IDF plus logistic regression, supported by explainable Kotlin rules. The legacy URL forest does not control verdicts because of its benign-link bias. Google reputation is an optional external service; its internal models are not ours.

3. **Is the scary demo hardcoded? Does it prove live Google detection?**  
   Reserved `.example` inputs go through the normal local classifier; the test marker is added without forcing the risk level. The full SBI example combines several local clues and remains labelled harmless/unconfirmed. A separate Google fixture requires an actual provider match. **Preview test alert** is explicitly a UI simulation.

4. **How accurate is it, and why were Airtel/Good Monk flagged before?**  
   Earlier URL scoring overreacted to benign link structure. Current rules keep weak formatting clues from becoming intrusive warnings, and the exact reported links have regressions. That is evidence of fixing specific failures, not a measured population-wide accuracy rate. Representative false-positive and missed-threat evaluation is still needed.

5. **Does “private” mean nothing leaves the phone?**  
   No. Local message text is not sent to Google or saved in protection history. Reputation sends hash prefixes and exposes normal network metadata. History is encrypted, while locally reported phone numbers are private app preferences. Account, family, location and voice features use separate cloud flows with consent.

6. **Can Call Shield detect cloned voices or prove a caller is a scammer?**  
   No. It reads caller ID supplied by Android, uses local rules and user reports, and lets calls ring. Unfamiliar/international numbers receive caution; stronger wording identifies the user's own report. Caller ID can be spoofed, and Android normally excludes saved contacts and hidden/restricted numbers from screening.

7. **Will it intercept every WhatsApp/SMS message and always launch a full-screen alert?**  
   No. Automatic scanning depends on supported visible notification previews and granted access. Hidden previews, foreground conversations, device policies and Android background restrictions affect delivery. Manual/share checks remain useful, but passing a manual scan does not prove every notification route works.

8. **Is SOS dependable if the network or backend fails?**  
   SOS relies on the configured account, service, push and media infrastructure. Local scam checks can still work offline, but that does not make cloud emergency delivery offline. We must observe the receiving device before claiming delivery/playback; service acceptance alone is insufficient. It is a support tool, not a guaranteed replacement for emergency services.
