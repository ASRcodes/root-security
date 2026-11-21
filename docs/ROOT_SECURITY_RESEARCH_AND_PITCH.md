# root-security
## Your all-in-one family safety companion

**Research and presentation brief — 21 November 2026.** This is a positioning and demo guide, not a security certification. Product comparisons below use primary sources available on this date. Current hardening work is separated from behavior already demonstrated; update the readiness table from actual build and device results before presenting.

## 1. What is worth building

The useful problem is the gap between receiving a worrying message and knowing what to do next. A parent may need a calm explanation, help from someone they trust, or an emergency response. A warning that appears on every recharge or shopping link breaks that trust.

root-security combines the existing family emergency app with a protection workspace: manual message/link checking, supported notification-preview checks, understandable warning reasons, English/Hindi/Punjabi presentation and warning audio, local protection history, and access to family help. The host application already contains trusted contacts and SOS workflows. Optional guardian SMS in the protection feature is a separate opt-in flow; it does not automatically become the trusted-circle SOS system.

The product hypothesis is **“understand the risk, choose the next step, reach someone you trust.”** That is a stronger direction than adding more alarming screens. It should complement the phone's existing protections.

My subjective assessment, based on the inspected prototype rather than user studies:

| Dimension | Rating | Reason |
| --- | --- | --- |
| Concept | **8/10** | A coherent everyday-safety and emergency-help experience addresses a recognizable family need. |
| Potential social benefit | **8/10** | Clear local-language guidance and timely family support could help; benefits depend on reliability, consent and adoption. |
| Current MVP readiness | **6/10** | The false-positive policy is corrected, 111 JVM tests pass, and the prior 3.1 candidate passed 16 unique emulator checks plus four local phone regressions. The final installed build's three live Google requests received HTTP 403 and remained Not checked. Live detection and a real incoming-message warning still require verification. |
| Distinctiveness today | **5/10** | Major products already offer scam detection and family protection. Execution and the combined experience must earn differentiation. |

These are judgment calls, not measured accuracy or forecasts. The application is worth developing if reduced false alarms, useful guidance and dependable emergency delivery remain the priorities.

## 2. What competitors already do

| Product | Verified overlap | What this means for the pitch |
| --- | --- | --- |
| Google Messages / Android | Google describes on-device scam detection, known-malicious-link checks and expanding chat-notification scam alerts. Its October 2026 update also describes optional enhanced protection using Private AI Compute and on-device Gemini Nano link checks. | Do not claim that on-device or cross-app scam detection is new. Device, country and rollout availability varies. [Google's official update](https://blog.google/security/new-android-scam-protections/). |
| Bitdefender Scamio | Users can submit messages, links, screenshots and QR codes for assessment and advice. The support page describes free access through web and messaging channels. | A paste-and-check assistant alone is not a differentiator. Demonstrate the connection from a warning to practical family support. [Scamio support](https://www.bitdefender.com/consumer/support/answer/97320/). |
| Norton Genie / Scam Protection | Norton's current offering includes text scam detection, website protection and Genie advice, with plan and platform qualifications. | Do not present “AI detects scam messages” as a unique invention or imply root-security has comparable antivirus coverage. [Norton's product page](https://us.norton.com/feature/ai-scam-protection). |
| Airtel fraud protection | Airtel describes malicious-site blocking across communication platforms, automatically enabled for its mobile and broadband customers. A blocked visit receives an explanation. | Network-level link protection already exists in the target market. root-security's opportunity is its app experience and family response, not claiming to be India's first link detector. [Airtel's launch announcement](https://www.airtel.in/press-release/05-2025/airtel-launches-fraud-detection-solution-a-first-in-the-world/). |
| Truecaller Family Protection | Trusted family groups, administrator-managed protection and Android intervention during suspected fraud calls are documented. Availability depends on region and operating system. | Family involvement is also established. Compare specific workflows instead of saying no competitor protects families. [Truecaller's official FAQ](https://support.truecaller.com/support/solutions/articles/81000419396-faq-family-protection). |

The defensible differentiation is a **combination to validate**: a clear SOS pathway alongside cautious link checks; local protection history; accessible language and audio; and visible uncertainty when the app cannot establish a threat. None of these should be described as a proven market advantage before users compare the experience with alternatives.

## 3. What detection should actually mean

The original URL classifier was an Extra Trees model over URL structure; its assets remain only for diagnostics and parity tests in v3.1. The active local text classifier uses character TF-IDF features and logistic regression to supply cautious hints. URL threat decisions use optional Google Safe Browsing reputation with exact full-hash matching on the phone. Google's API does not disclose a specific internal model architecture, and root-security does not run an LLM for these verdicts.

The audit reproduced the user's two false positives: `i.airtel.in/rc349sil3` and `https://weurl.co/GDMNKS/oo8a70`. Both received very high URL-model scores despite no contributing text or heuristic risk. Broader testing found ordinary documentation and shopping paths affected too. The training data's legitimate URLs overwhelmingly lacked paths; even adding a trailing slash changed some scores dramatically. A score near 1 was therefore not dependable evidence of fraud.

**The response is a change in authority, not brand exceptions.** The v3.1 local engine no longer lets that model or a dated bundled domain list declare a URL dangerous. Shorteners, long paths, IP addresses or uncommon suffixes alone do not justify a warning. Stronger impersonation clues or high text-model scores may support a quiet caution. Local model output alone cannot trigger the full-screen danger route.

| Approach | Appropriate role | Limit / decision |
| --- | --- | --- |
| Google Safe Browsing v5 `hashes.search` | Current known-threat reputation with hash prefixes computed on the device. | Implemented with canonicalization and exact full-hash matching after the prefix response. No match means no listed threat found, not guaranteed safety. |
| Safe Browsing `urls.search` | Simpler direct URL lookup. | Sends the actual URL; does not meet the preferred URL-minimization design. |
| Downloaded pretrained URL classifier | Candidate for offline hints after independent evaluation. | A model file is not automatically an API service, continuously updated intelligence or calibrated proof of danger. |
| Existing bundled model | Reproducible diagnostic and research baseline. | Quarantined from URL verdict authority until representative retraining and evaluation justify reuse. |

Google documents four-byte hash-prefix requests, an optional IP-hiding relay, and the additional implementation complexity. Direct requests still expose the client's IP to the provider. Safe Browsing is restricted to non-commercial use; a commercial launch must assess Google's Web Risk offering and applicable terms. [Safe Browsing v5 overview](https://developers.google.com/safe-browsing/reference).

One available candidate is **PhishScout-v2**, whose author publishes an MIT-licensed LightGBM/ONNX model. Its card explicitly identifies URL-only coverage, regional/language limitations and the need to retrain for changing campaigns; it lists no hosted inference provider. The card also contains inconsistent size/feature descriptions, which should be resolved before adoption. Its self-reported benchmark is not root-security's performance. Evaluate the artifact, license, feature parity and benign deep links before considering it. [Author's model card](https://huggingface.co/saidutta69/PhishScout-v2).

The chosen MVP approach is therefore local caution plus optional fresh reputation. It does not inspect destination content or resolve every redirect, and known-threat lists can miss new attacks. File/APK checking remains a limited risk checker, not a full antivirus or automatic malware-removal system.

## 4. Privacy and implementation status

Use the specific statement **“Protection history stays on your device.”** Do not say “everything in the whole application stays on the phone.” Existing sign-in and SOS delivery use cloud services; emergency voice/location sharing has a different data flow.

The implemented reputation integration sends hash prefixes, not raw URLs or message bodies, and stores the optional user-supplied key encrypted on the device. Encryption at rest does not make a shared key safe to embed in an APK. Hashing reduces disclosure but is not anonymity. Show provider status and consent clearly, and keep emergency permissions separate from link-check permissions.

| Item | Status at this brief's preparation |
| --- | --- |
| Combined protection and family-emergency application | Existing integration; earlier build/emulator evidence is recorded in `docs/PROTECTION_INTEGRATION.md`. Those results apply to that earlier build. |
| Root cause of the false positives | Reproduced offline using the deployed assets; diagnostic reports are retained in `docs/protection/url-audit/`. |
| Conservative local URL/text verdict policy | Implemented and regression-tested: both reported URLs remain quiet locally. Current JVM test totals and physical-phone checks are recorded in `docs/protection/root-security-validation.json`; these are not accuracy benchmarks. |
| Optional Safe Browsing, encrypted-key settings, honest provider-status UI | Implemented and built; eight Android emulator tests cover controlled provider responses, key encryption, failures and warning UI. Seven host integration checks and the sample-prefill check also passed. Those 16 checks preceded the final diagnostic-message update; eight additional JVM tests cover that update. A key is configured on the phone. Initial requests timed out; the final three requests all received HTTP 403 and returned Not checked. |
| Removal/isolation of the old “every link is dangerous” debug behavior | Removed from production scanning. The legacy toggle is a no-op, sample buttons only fill input, and the explicit preview is labelled as a simulation. |
| Physical phone, carrier SMS, background delivery, release readiness | The final v3.1 update is installed on the Samsung SM-M317F (API 31), preserving the saved key and app data. Four local detection regressions passed before the diagnostic update. The sample-prefill physical UI test lost foreground focus and is incomplete. Live Google requests received HTTP 403 without a recognized specific error reason; API enablement, key restrictions and project access need review. No real Google threat match, incoming-message warning or carrier SMS delivery is established. |

## 5. A credible three-minute judge demo

**Readiness gate:** install the final tested APK; confirm automatic protection is active and the necessary Android notification/overlay settings are available; configure the optional provider; verify the benign and official test cases beforehand. Use test phones and consenting demo recipients. The old global override is removed; use only the separately labelled alert preview for a simulation. Rehearse the exact incoming-notification route because background launches depend on Android permissions and device behavior.

1. **0:00–0:30 — Start with the failure you fixed.** “A recharge link should not frighten my parent. We found a data-bias problem and changed what evidence can trigger an alarm.” Send or paste each of the two reported links as plain text, without navigating to them. Expect no local danger warning. Show whether the online check completed; do not call either destination independently certified safe.
2. **0:30–1:10 — Demonstrate a real reputation result.** Submit `https://testsafebrowsing.appspot.com/s/phishing.html` through the same checker/notification route. This is Google's published phishing test URL, not a real victim site. Explain that this demonstrates a threat-list integration, not novel AI discovery. Only describe the full-screen behavior as working after the configured phone actually shows it. Google's repository lists this sample for testing. [Google's official test samples](https://github.com/google/webrisk#sample-urls).
3. **1:10–1:35 — Demonstrate uncertainty.** Turn online checks off in the app and scan an ordinary URL. The result should disclose that online checking is unavailable, without converting that failure into “safe” or a red alarm. A fabricated `.example` address can illustrate an impersonation caution, but should not be sold as a genuine threat-intelligence hit.
4. **1:35–2:15 — Show what the family can do next.** Change the warning language, explain one reason and open the guardian-call action. Separately show the existing trusted-circle/SOS flow using consenting demo accounts. Do not accidentally send real SMS or SOS while rehearsing; explain that these are separate actions with separate consent.
5. **2:15–3:00 — Close with evidence and limits.** Show local protection history and the current test report. State what passed, what was simulated and what remains unverified. If the key/network prevents the live reputation demo, disclose that and use a visibly labeled UI preview; never present a forced verdict as live detection.

## 6. Pitch and judging rubric

### A 60-second pitch that fits the current evidence

“A family safety app has to earn trust on ordinary days before anyone will trust it in an emergency. root-security brings message and link checks together with trusted contacts and SOS in one Android app.

“Our prototype exposed a real problem: legitimate recharge and shopping links were being flagged. We traced it to bias in the URL model's training data. We changed the design so a weak prediction cannot produce a full-screen alarm, and integrated an optional threat-reputation service that receives hash prefixes instead of complete URLs.

“The experience we are building is simple: understand the warning in a familiar language, see what is known and what is uncertain, and reach someone you trust. Protection history stays locally; emergency sharing is separate and explicit.

“We are not claiming to replace Google or invent scam detection. We are testing whether this combination makes safety easier for families. Our next proof is fewer false alarms, clearer decisions and reliable help on real phones.”

| Judge criterion | Show evidence | Avoid claiming |
| --- | --- | --- |
| Problem understanding | Original false-positive examples and a parent-friendly scenario. | Unsupported market size or fabricated victim statistics. |
| Technical depth | Model bias diagnosis, provider/local verdict separation, failure-state behavior. | “99% accurate” from unrelated model-card or training metrics. |
| User benefit | Understandable reason, local-language audio, clear next action. | That adding languages establishes equal detection quality in each language. |
| Privacy | Protection data map and separate emergency-sharing flow. | Whole-app offline operation, anonymity, or zero cloud data. |
| Reliability | Fresh build/test artifact and the exact physical-phone demo. | Guaranteed prevention, guaranteed full-screen launch or production readiness. |
| Differentiation | A coherent family support and emergency experience, assessed against existing products. | “World's first,” universal link interception, cloned-voice detection or full antivirus. |

Before a pilot, measure false alarms per benign message/link, missed-threat rate, warning delay, comprehension by language, successful help requests and battery impact on representative phones. Keep domains and campaigns separated across evaluation splits and include ordinary short links, queries and deep paths. Report denominators, dates and failure cases. These are the evidence requirements for the next version, not results already achieved.
