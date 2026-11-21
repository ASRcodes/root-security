# root-security 3.2-final — hackathon prerelease

**Your all-in-one family safety companion.** This prerelease provides a **debug-signed Android APK** for demonstration and evaluation. The application ID remains `com.asrcodes.dontmissmom`; updating an existing installation requires a matching signing certificate. Android 8/API 26 is the minimum; Call Shield needs a supported Android 10/API 29+ call-screening role.

What's included:

- **Local entry without an account:** choose **Open protection without sign-in** after any first-launch permission screens. Family SOS still requires sign-in. Notification checks and Call Shield retain their separate Android permission requirements.
- **A clear three-result demo:** normal link, caution and unconfirmed local high-risk warning. Example buttons only fill the input. Copy and Share are explicit actions; every scan uses the real checker.
- **Calmer link rules:** weak URL-format clues alone do not justify an intrusive threat warning. The biased legacy URL forest remains outside verdict authority. Local rule findings are distinguished from Google reputation results.
- **Managed caller cautions:** unfamiliar or international caller IDs supplied by Android receive cautious advice. Stronger wording requires the user's local report; reports can be added, removed or cleared. Android normally excludes saved contacts and hidden/restricted numbers from screening. Calls ring normally. This is not call-audio analysis, cloned-voice detection, recording or automatic blocking.
- **Labelled harmless examples:** reserved `.example` inputs show **Harmless example · local rule check**, with guardian actions suppressed. The separate alert preview is explicitly a simulation.

Start with this normal input:

```text
https://www.wikipedia.org/
```

Then scan the caution input `https://account-support.example/login`. For the local high-risk demonstration, use the complete message:

```text
Your SBI account will be blocked today. Update KYC immediately: https://sbi-verify.example/login
```

These examples exercise the ordinary local rules; their labels do not force verdicts. A quiet local result is not a safety certificate. If an online lookup fails or is disabled, the result remains **Not checked**. A completed Google no-match also does not guarantee safety.

**Verification scope:** controlled online integration tests use fixture responses; they do not establish a successful live Google lookup. Emulator radio events are simulations, not carrier calls or messages. Live Google detection, physical-phone/carrier behavior and deployed cloud SOS delivery remain unverified here. Full-screen presentation depends on Android permissions and device policy. Consult the final evidence record for the exact artifact and checks actually completed; no pending test pass counts are claimed in these notes.

Follow the [demo script](https://github.com/ASRcodes/root-security/blob/v3.2-final/docs/DEMO_SCRIPT.md) and [final submission/evidence record](https://github.com/ASRcodes/root-security/blob/v3.2-final/docs/FINAL_SUBMISSION.md) before presenting.
