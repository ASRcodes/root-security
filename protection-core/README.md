# Portable protection core

`:protection-core` is a pure Kotlin/JVM module targeting Java 17. It owns parsing, local verdicts, caller/file policies and shared examples. Android services, permissions, persistence and network transport belong to the [protection library](../protection/README.md).

## Source map

Sources live in [org/suraksha/core](src/main/kotlin/org/suraksha/core).

| Files | Responsibility |
|---|---|
| `VerdictEngine.kt`, `RiskSignals.kt`, `Verdict.kt` | Combine evidence into levels and explainable reason codes. |
| `UrlParser.kt`, `UrlExtractor.kt`, `DomainLists.kt` | Parse and extract URLs and work with domain/suffix data. |
| `TextModel.kt` | Evaluate exported character n-gram TF-IDF/logistic-regression assets. |
| `MessagePipeline.kt` | Bound input, deduplicate recent scans and return result metadata. |
| `SafeBrowsingUrls.kt`, `SafeBrowsingResponse.kt`, `ReputationPolicy.kt` | Hash/canonicalization helpers, response validation and reputation expiry policy; no network calls. |
| `CallRisk.kt`, `FileRules.kt`, `GuardianPolicy.kt` | Caller advice, file indicators and guardian eligibility rules. |
| `DemoExamples.kt`, `ReasonMessages.kt` | Shared harmless examples and translated explanations. |

## Model boundaries

The engine loads bundled [model and domain assets](../protection/src/main/assets). The legacy ExtraTrees URL model remains available for diagnostic and parity work, but its score does not determine current URL verdicts. Text-model output remains part of message analysis.

The core does not open links or resolve redirects. Local high-risk findings remain unconfirmed, and a provider no-match neither guarantees safety nor erases corroborated local evidence.

## Pipeline input and duplicate suppression

[MessagePipeline](src/main/kotlin/org/suraksha/core/MessagePipeline.kt) processes at most the first 16,384 UTF-16 code units. [UrlExtractor](src/main/kotlin/org/suraksha/core/UrlExtractor.kt) trims surrounding punctuation, supplies `https://` for bare addresses and returns at most 24 distinct links. Blank input produces no results.

Recent-scan fingerprints combine source, URL and bounded message text. The in-memory cache holds at most 256 fingerprints for 60 seconds; clearing the pipeline clears that memory, and callers can use `deduplicate = false` for repeat manual checks. Suppression happens before local scoring and the optional per-URL transform inside this pipeline. The Android host can perform reputation work before invoking it.

A `ScanResult` contains the parsed host, verdict and timestamp rather than the complete message or URL. Text-only input goes through text evaluation: a safe result is omitted, a warning has an empty domain, and the per-URL transform is not called.

## Shared examples and reason labels

[DemoExamples](src/main/kotlin/org/suraksha/core/DemoExamples.kt) supplies ordinary classifier input strings, not hard-coded verdicts. [FinalDemoPolicyTest](src/test/kotlin/org/suraksha/core/FinalDemoPolicyTest.kt) checks that normal examples stay quiet and that reserved-domain examples exercise the expected warning levels.

The example marker applies after classification when the parsed host is `example` or ends in `.example`; it preserves the score and level, including a safe level. Text containing `.example` only in a path, query or username does not qualify. Labels distinguish test input, synthetic previews (`DEMO_ONLY`), provider fixtures (`PROVIDER_TEST`) and unconfirmed local risk (`LOCAL_RISK`).

[ReasonMessages](src/main/kotlin/org/suraksha/core/ReasonMessages.kt) provides English, Hindi and Punjabi explanations, with English fallback for unsupported languages. Provider/test labels precede specific local reasons, followed by no-match, not-checked and file-scan context. Keep those distinctions when presenting a verdict in a new UI.

## Optional feed evaluation

[FeedEvaluationTest](src/test/kotlin/org/suraksha/core/FeedEvaluationTest.kt) evaluates already downloaded strings with empty message context; it does not open the supplied URLs.

| Local file | Expected input and sampling |
|---|---|
| `openphish.txt` | URL lines beginning with `http`; all matching lines are evaluated. |
| `phishing_army.txt` | Nonblank, non-comment domains; up to 20,000 are sampled with seed 7 and converted to HTTPS homepages. |
| `top-1m.csv` | Headerless rank/domain rows, at least 100,000 rows; tests the first 10,000 homepages and synthetic login paths plus a 20,000-domain sample from row positions 10,001-100,000. |

From the repository root, using a directory containing the selected nonempty feeds:

```powershell
.\gradlew.bat :protection-core:test --tests org.suraksha.core.FeedEvaluationTest "-Pfeeds=C:\path\to\feeds" --rerun-tasks
```

Without a valid directory the test skips; missing individual files are omitted. The report at `protection-core/build/reports/feed-evaluation.txt` contains sample counts, dangerous and any-warning rates, and up to 40 potential false alarms per Tranco case. It is a descriptive URL-only evaluation, with no pass/fail accuracy threshold, live site verification or online-reputation measurement.

## JVM tests

From the repository root with JDK 17:

```powershell
.\gradlew.bat :protection-core:test
```

Tests use bundled assets and [exported parity samples](parity/score_samples.json). No Android device or live reputation service is needed. They cover URL false positives, model parity, reputation policy and caller/file/guardian rules.

`FeedEvaluationTest` is optional and skipped unless `-Pfeeds` points to a local feed directory. It processes already downloaded URLs as strings. Parity tests show agreement with exported calculations; they are not a population-wide detection-accuracy measurement. See the [release validation](../README.md#release-validation) for recorded results.
