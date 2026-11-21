# root-security — practical judge demo

Use **3.2-final** from the new root-security repository. First consult [Final submission](FINAL_SUBMISSION.md): do not reuse a previous build's pass counts or treat saved settings as live-service proof.

**Observed:** the production APK handled an emulator-generated Wikipedia SMS without a full-screen warning or warning notification. The full reserved SBI example reached Google Messages, the real notification listener and a labelled warning activity. A separate simulated-radio test reached Android's real call-screening callback and displayed the local reported-number warning. These are emulator results, not physical-carrier or live Google proof. All 33 distinct Android checks have passing final results across the initial API 36 run and targeted API 26 retries; this is accumulated evidence, not one clean full-suite run. See [the evidence record](FINAL_SUBMISSION.md) for the exact reports and limitations.

## Prepare the phone

- Keep the receiving phone unlocked for the first manual demonstration. If signed out, tap **Open protection without sign-in**, then open **Demo lab** from the local protection home. If already signed in, use **Protection → Demo lab**. This local entry does not sign in or enable family SOS; family SOS still needs an account. Automatic checks and Call Shield still need their Android permissions.
- For an incoming-message rehearsal, enable automatic protection and Android Notification access. Grant notification permission where required. A visible supported SMS/WhatsApp preview is necessary; keep its conversation closed. If using an overlay, grant Android draw-over-other-apps permission and enable the app's warning-overlay switch.
- Keep automatic guardian SMS off for public rehearsal. Reserved `.example` inputs and labelled previews suppress guardian actions. Manual checks never automatically contact a guardian; eligible incoming alerts use the separate consent/settings policy.
- Google checks are optional for the local demonstration. For the provider test, enable Safe Browsing API, configure a compatible restricted key directly in the app, and verify an actual lookup. Never put the key in a slide, chat, screenshot or Git.
- For Call Shield, use a supported Android 10+ phone, grant the call-screening role and enable caller cautions. Arrange a consenting helper with a number that is not saved in the receiving phone's contacts; Android normally excludes saved contacts from screening.

## Three-minute main demonstration

**0:00–0:20 — The problem.**

“A safety app must earn trust on ordinary messages. root-security combines understandable scam guidance with family help, while showing what is known and what is uncertain.”

**0:20–0:55 — Normal links.**

Load **normal Wikipedia**, inspect the shown input and expected behavior, then tap **Check this message**. Repeat one of the originally reported false positives if time allows:

```text
https://www.wikipedia.org/
i.airtel.in/rc349sil3
https://weurl.co/GDMNKS/oo8a70
```

Expected local behavior is no intrusive warning. If online checking is disabled or fails, show **Not checked**; do not call that a successful Google clean result. A completed **No known threat found** still does not guarantee destination safety. Short links are not expanded.

**0:55–1:25 — A cautious result.**

Load the harmless caution example and scan:

```text
https://account-support.example/login
```

Expected result: a caution, without a full-screen alarm. Explain that `.example` is a reserved example namespace and the ordinary local rules evaluate the input. The test marker labels the input; it does not force the verdict.

**1:25–2:05 — A local high-risk warning.**

Load the harmless high-risk example. Show the complete message and scan:

```text
Your SBI account will be blocked today. Update KYC immediately: https://sbi-verify.example/login
```

Expected result: an unconfirmed local high-risk warning with **Harmless example · local rule check**. Point out the combined impersonation/urgency/account-action clues. This is a real local-rule evaluation of a harmless test input—not a confirmed malicious website, live Google match or voice-clone detection. Guardian actions are unavailable for this example.

**2:05–2:35 — Help and privacy.**

Show the reason, language choice and local result history. Explain that protection history is encrypted domain/result/reason/time metadata; message bodies and full URL paths are not saved there. Family SOS uses separate cloud services and consent. Demonstrate a family-help/SOS action only with consenting demo recipients and observe the receiver before claiming delivery.

**2:35–3:00 — Close with evidence.**

“Local rules explain warning signs, optional Google reputation adds known threats, and the app helps a family choose the next step. These are the exact checks we ran; broader detection accuracy and delivery reliability still need field evaluation.”

Use the final recorded results, not an unmeasured percentage.

## Optional real Google provider test

Use the separate Google-provided fixture:

```text
https://testsafebrowsing.appspot.com/s/phishing.html
```

It needs an actual current Google threat match to demonstrate the provider warning and Google attribution. The domain is not a hardcoded dangerous result. **Not checked** means that lookup did not complete; it is not a successful detection demo. If no real match is observed, present the local reserved example accurately or use **Preview test alert**, explicitly saying “This is the alert-interface simulation.” The preview does not scan, save history or send guardian SMS.

## Actual incoming-message rehearsal

The production-APK emulator experiment has already exercised this notification path with simulated SMS: [normal/high-risk results](validation/emulator-sms-evidence.json) and [labelled warning UI](validation/emulator-sms-warning.xml). The high-risk case opened the warning activity directly; it did not also post a separate warning notification. A real phone/carrier rehearsal is still required for a physical-device claim.

1. Use **Copy example** or **Share example** for the reserved high-risk message. Sharing opens Android's chooser; the user chooses the app/recipient and sends. The app does not send a message automatically.
2. Have the consenting helper send that exact text to the target phone through a supported notification route. Keep the recipient conversation closed so Android posts a preview.
3. Observe what happens on that phone. The expected local warning must retain the harmless-example label. Repeat with the normal link and caution example.
4. Record whether the event was an actual notification, a manual scan, or a test harness. Background/lock-screen launch behavior varies with permissions and Android/device policy. A manual scan passing does not establish notification interception.

## Actual Call Shield rehearsal

1. Obtain the helper's consent. Use a number not saved in the receiving phone's contacts; do not delete personal contacts merely to create a demo.
2. In **Protection settings → Call Shield → Manage reported numbers**, enter that helper's number with its country code and choose **Add local report**. Say aloud that this is your temporary test report, not a number independently detected as fraudulent.
3. With caller cautions enabled and the Android call-screening role granted, have the helper place an actual cellular call. Observe **Number you reported** while the call rings normally. A banner also depends on the app overlay switch, Android overlay permission, unlocked state and emergency priority.
4. Remove the temporary report using **Manage reported numbers → Remove**. If repeating, an unfamiliar caller should receive cautious advice, not a verified-scam claim.
5. The **Preview a call caution (simulation)** dialog is only a local interface preview. It is not a call, a carrier event or a detection result. Call Shield does not hear, record, block or authenticate a voice.

## Disposable-emulator checks

These commands are for a disposable emulator only. Confirm the exact serial with `adb devices`; `emulator-5554` below is an example. Never target a user's physical phone with reset-capable test suites.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.asrcodes.dontmissmom.RootSecurityLinkRegressionTest,com.asrcodes.dontmissmom.ScamAlarmEndToEndTest,com.asrcodes.dontmissmom.CallShieldTest com.asrcodes.dontmissmom.test/com.asrcodes.dontmissmom.DmmTestRunner
```

The instrumentation uses fixture services and local entry points. It is not an actual carrier/WhatsApp event or live Google check. The dedicated live-provider runner is a separate explicit operation and must not be substituted with reset suites on a personal phone.

If using Android Emulator console events, label them **simulated radio events**:

```powershell
adb -s emulator-5554 emu sms send +15555550123 "Your SBI account will be blocked today. Update KYC immediately: https://sbi-verify.example/login"
adb -s emulator-5554 emu gsm call +15555550123
adb -s emulator-5554 emu gsm cancel +15555550123
```

These commands do not send an external SMS or make a real phone call. They also do not bypass the emulator's app setup, notification-preview rules or call-screening-role requirements. A successful simulated event does not establish carrier behavior on the Samsung phone.

## Explicit simulated-radio screening test

**Current result: passed in the emulator.** The [recorded test](validation/android-radio-test.txt) contains **ROOT_SECURITY_RADIO_OBSERVED** and **OK (1 test)**: the injected radio event reached Android's call-screening callback and produced the local reported-number notification. It does not test a carrier, a real caller, call audio or voice authenticity.

Use a disposable API 29+ emulator with the app and default offline test APK installed as above. The test grants its notification permission where needed, selects the call-screening role, enables caller cautions and adds the fictional local test report `+12025550142`. It changes emulator settings; do not run it on a personal phone.

In terminal 1, start the explicit test:

```powershell
adb -s emulator-5554 shell am instrument -w -r -e rootSecurityRadio true -e class com.asrcodes.dontmissmom.RootSecurityCallRadioTest com.asrcodes.dontmissmom.test/com.asrcodes.dontmissmom.DmmTestRunner
```

Wait for **ROOT_SECURITY_RADIO_READY +12025550142**. Within its 60-second wait, run this in terminal 2:

```powershell
adb -s emulator-5554 emu gsm call +12025550142
```

Record a pass only if terminal 1 prints **ROOT_SECURITY_RADIO_OBSERVED** and the JUnit test succeeds. READY alone is not evidence of a delivered callback. Cancel the simulated call afterward, including after a failure:

```powershell
adb -s emulator-5554 emu gsm cancel +12025550142
```

Report this result as **simulated Android radio/call-screening integration**. The consenting second-phone procedure above is still required to claim an actual incoming-call demonstration.
