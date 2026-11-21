# root-security 3.1: URL checks and honest demonstration

## What changed

The old local URL classifier was unreliable on ordinary links with paths. For example, the same Airtel site scored about 0.005 without a trailing slash and 0.940 with one. The two reported campaign links scored about 0.99 despite no independent suspicious indicators. The training data had benign examples concentrated at HTTPS domain roots; path syntax became a shortcut for its prediction. Its score is not a calibrated probability of fraud.

The URL forest and stale bundled domain lists no longer authorize warnings. The assets remain intact for reproducible diagnostics, but production verdicts ignore that model's score. Strong local impersonation or scam-language patterns produce a quiet, unconfirmed caution. Weak signals such as a shortener, long path, tracking query, or login page do not establish a scam.

Fresh, exact Google Safe Browsing hash matches can produce a full-screen URL warning. A successful lookup with no match says **No known threat found**, not **Safe**. Disabled checks, absent keys, unsupported URLs, malformed replies, connection problems and exhausted quotas say **Not checked**. These states never produce an intrusive URL alarm.

For technical questions: the local message classifier uses character 3–5-gram TF-IDF features and logistic regression. It produces cautious hints. URL threat decisions now use Google Safe Browsing v5 reputation, with full-hash matching on the phone; the API does not disclose a specific Google model architecture. The retired URL forest is a 48-tree ExtraTrees classifier over 25 URL features. We do not claim to have trained Google’s service or to be running an LLM for link verdicts.

The global debug switch that made every link dangerous is retired. Demo sample buttons only fill the editor. The separate alert preview is visibly labelled as a simulation and does not run detection, create history or send guardian SMS. Manual message/file checks do not send guardian SMS automatically. Automatic guardian messaging still requires its own explicit setup and is restricted to real background dangerous results; Google test pages and EICAR fixtures are excluded.

## Set up the Google key

1. Open [Google Cloud Console](https://console.cloud.google.com/) and select or create a project.
2. In **APIs & Services → Library**, enable **Safe Browsing API** (also labelled “Safe Browsing APIs” in some screens).
3. In **APIs & Services → Credentials**, choose **Create credentials → API key**.
4. Edit the key. Under **API restrictions**, restrict it to Safe Browsing API and save. Do not configure a browser HTTP-referrer restriction for this native REST client. Application restrictions need to be compatible with the selected API/client; a rejected restriction results in Not checked, never a clean result.
5. On the phone open **Protection → Protection settings → Online URL checks**, enter the key, and choose **Save and enable**. The app does not echo the saved key into the field.
6. Run an ordinary URL check, then the Google test page below. Saving a key is configuration, not proof that it is enabled correctly or accepted by Google. Read the lookup result.

Do not put the key into chat, a screenshot, Git, a shared APK or the presentation. This prototype uses a key supplied by the device owner, encrypted using Android Keystore. It is not a design for hiding a shared developer credential inside a production mobile app. Google's [setup guide](https://developers.google.com/safe-browsing/v4/get-started) and [key guidance](https://docs.cloud.google.com/docs/authentication/api-keys-best-practices) are authoritative. The implementation uses v5 even though Google's general key setup guide is under v4.

If the app says **Enable Safe Browsing API**, enable that API in the Google Cloud project that owns the key. If it says **Allow Safe Browsing API in the key's API restrictions**, add Safe Browsing to that key's allowed APIs. An Android, website-referrer or IP restriction message identifies a separate client restriction to review. Do not replace a working key merely because a request timed out. Unknown errors retain a generic Not checked status. These messages use only documented, allowlisted Google error codes; response messages, project identifiers and credentials are never displayed. [Google's error-code reference](https://docs.cloud.google.com/java/docs/reference/proto-google-common-protos/latest/com.google.api.ErrorReason).

## Privacy and limitations

- The phone derives SHA-256 hashes of canonical URL expressions. Only the first four bytes of each hash are sent in an HTTPS request to `safebrowsing.googleapis.com`. Returned full hashes are compared locally. A matching prefix alone is not evidence of a threat.
- The API key goes in the `x-goog-api-key` header. The transport does not receive message text or complete URLs. It does not visit submitted sites or follow their redirects. Google can see the request's IP address, timing and key; hash prefixes reduce disclosure but do not provide anonymity.
- Online response caches are bounded and memory-only. The encrypted local history contains domain, verdict, reason codes and timestamp, not message bodies, full paths or URL query tokens. Existing retention controls and the 500-record cap remain.
- This privacy statement concerns the protection module. Family SOS separately uses Firebase and media/cloud delivery; it is not an entirely offline application.
- Shortened links are checked as submitted. An unlisted short link can redirect to an unsafe destination. No-match is never a safety guarantee. Newly created threats, unlisted scams, hidden notifications and messages outside supported previews can be missed.
- Unsupported URL canonicalization, including IPv6 and certain ambiguous internationalized domains, is reported as Not checked. Large multi-link messages can be only partially checked; the result reports that limitation.
- Cached positive answers are reused only within their provider cache lifetime, capped at 30 minutes. Displayed Google findings expire 30 minutes after the actual lookup. Historical warnings identify the scan time. Expired records and legacy local-model danger records cannot replay as current verified threats.
- Safe Browsing is for non-commercial use unless Google grants a separate agreement. Revisit the service choice for commercialization; Web Risk is a separate product. See [Google's terms](https://developers.google.com/safe-browsing/terms).

## A three-minute demonstration

Prepare notification access and, if demonstrating background interruption, draw-over-other-apps access through the app's setup screen. Android controls whether a warning may appear on top of another app; hidden notification previews cannot be scanned. Keep automatic guardian SMS disabled during a public demo unless a consenting test recipient has explicitly agreed.

1. Show **root-security — Your all-in-one family safety companion**. Explain that SOS delivery and scam checks share one family-oriented experience.
2. Open the debug **Demo lab**. Select the Airtel sample `i.airtel.in/rc349sil3`, then press **Check message**. Repeat with `https://weurl.co/GDMNKS/oo8a70`. Successful live lookups with no match should stay quiet. If Google returns a real match, display the result honestly; never override it because a URL looks familiar.
3. Select Google's harmless fixture `https://testsafebrowsing.appspot.com/s/phishing.html` and scan. A current live Google match should open the warning with **Advisory provided by Google**, an uncertainty notice and a Google test-page label. Do not visit real malicious websites to manufacture a demo. The test domain does not hard-code a dangerous verdict.
4. For the incoming-message demonstration, have a consenting helper send that same Google fixture to the connected phone through a supported SMS or WhatsApp notification. Keep the receiving conversation closed so Android posts a preview. Do not claim this live incoming flow was tested until it has actually been observed on the target phone.
5. Pause online checks and scan an ordinary link. Show **Not checked** and explain why silence must not be confused with a successful safety assessment. Re-enable checks afterward.
6. If the venue network/API is unavailable, use **Preview test alert** and say, “This is the alert-interface simulation; the live reputation lookup is unavailable.” Do not present that preview as detection.

Suggested judge explanation: “We found and corrected a false-positive failure in our original URL model. Now local patterns stay cautious, verified threat intelligence controls intrusive URL alerts, and unavailable checks remain visibly unknown. Our work is the Android integration, privacy boundary and family response experience; Google supplies the URL reputation.”

## Verification record

See `docs/protection/root-security-validation.json` for the final build and device results. Unit tests use deterministic fixtures; a mocked response proves handling, not live provider detection. A connected phone and installed build do not by themselves prove the Google API integration works. A real key and an observed live lookup are required for that claim.

Normal instrumentation uses the isolated offline `DmmTestRunner`. The separate human-authorized live check is built with `-ProotSecurityLiveChecks=true`; its restricted runner can execute only the three approved manual URL checks and the attributed Google test-warning verification. It uses the saved app key internally and logs only domain/verdict/status metadata. It does not run reset tests, send guardian SMS or trigger SOS.

Protocol reference: [v5 hashes.search](https://developers.google.com/safe-browsing/reference/rest/v5/hashes/search), [URL canonicalization](https://developers.google.com/safe-browsing/reference/URLs.and.Hashing), [Google's harmless test site](https://testsafebrowsing.appspot.com/).
