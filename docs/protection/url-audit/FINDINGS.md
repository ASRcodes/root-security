# URL false-positive investigation

This read-only audit uses the exact deployed JSON model files and the source project's portable Python reference scorer. It does not visit URLs or execute APKs. Tests use URL strings; candidate domains and invented paths do not establish domain ownership or the existence of a page. The source project already contains 50 Python/Kotlin parity cases.

## Reproduction

These scripts record the original pre-fix source paths in the development workspace. Run `python -X utf8 audit_scoring.py` there or update those paths explicitly before reproducing the original baseline. It writes `baseline-scores.json` with source SHA-256 values, every feature vector, raw model scores, heuristic flags, and the current combined verdict. `audit_dataset.py` writes `dataset-structure-audit.json` from the raw training-source CSV without any network access.

| Input | URL score | Text score | Rule score | Final verdict |
|---|---:|---:|---:|---|
| `https://www.airtel.in/` | 0.940021 | 0 | 0 | Dangerous |
| `https://www.airtel.in` | 0.005477 | 0 | 0 | Safe |
| `https://www.goodmonk.in/` | 0.940021 | 0 | 0 | Dangerous |
| `https://www.goodmonk.in` | 0.005537 | 0 | 0 | Safe |
| `https://developer.android.com/studio/run/device` | 0.990603 | 0 | 0 | Dangerous |
| `https://docs.python.org/3/library/urllib.parse.html` | 1.0 | 0 | 0 | Dangerous |

See JSON for exact unrounded values. These reproduce real-model behavior, not the debug test override. The initial audit preceded the supplied campaign links. Subsequent Kotlin regression tests include both exact links; consult the current validation record for their results.

## Root causes

1. **Dataset structural bias.** Every one of the 134,850 legitimate URL rows in the local PhiUSIIL CSV has an empty path, no query, and HTTPS. The Tranco benign proxy builder likewise constructs `https://` plus a domain without a trailing slash. Meanwhile, phishing examples include 32,971 slash-only paths and 27,456 longer paths. A slash or ordinary product path becomes a misleading statistical clue. The local model places an HTTPS split at the root of 42 of 48 trees.
2. **Model-only danger escalation.** `VerdictEngine.kt` allows a URL score >=0.8 to produce Dangerous even when text and explicit rule scores are both zero. Therefore a biased numerical model score directly drives alarming UI.
3. **A small allowlist conceals failures.** Google, Amazon and Wikipedia examples look safe because the engine returns before URL-model scoring. The existing `normalTrustedLinksAreSilentAndDeceptiveDomainsAreNotTrusted` test uses allowlisted domains. Their raw model scores for normal links range around 0.94 to 1.0.
4. **An additional all-links-red debug mode.** `DemoScorer` deliberately declares every URL Dangerous. `KavachApplication` selects it for both manual and incoming notification checks when debug Demo mode is on. It defaults to false, but the UI exposes a toggle. This can independently produce the reported symptom and is unsuitable as evidence of real classification.
5. **Weak URL signals overstate certainty.** A shortener, IP literal, or internationalized host alone forces Suspicious. These are structural properties, not proof of malice. The text model also gives the neutral phrase `Please click this link` a 0.671 score, enough for Suspicious.

## Recommended remediation

- Stop allowing the current URL model alone to trigger Dangerous/full-screen alerts. Raising its threshold is insufficient because many benign deep links score 1.0.
- Use independent reputation evidence for known-danger alerts. Clearly distinguish a provider-confirmed threat, suspicious local wording, an unfamiliar link and an unavailable online check. A no-match response is not a guarantee of safety.
- Keep explicit, interpretable evidence such as deceptive brand placement and sensitive-data solicitation. Treat weak shape-only signals as informational/uncertain unless corroborated.
- Isolate the test-alert preview from live notification/manual classification. Label a provider test URL or preview as a test. Rehearse both benign and threat examples through the real pipeline and retain evidence of which path ran.
- Add benign regression coverage outside the allowlist: slash/no-slash equivalents, ordinary login/product/docs paths, UTM parameters, legitimate shorteners, IDNs and local IPs. Verify normal results remain silent.
- If retraining later, include representative benign deep links and queries, separate domains and time windows, test without allowlist shortcuts, and reserve an unseen deployment-like benign corpus. Existing high held-out metrics are misleading for real link traffic because train and test share the same source formatting bias.

No project files or model assets were changed during this audit.
