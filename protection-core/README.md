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

## JVM tests

From the repository root with JDK 17:

```powershell
.\gradlew.bat :protection-core:test
```

Tests use bundled assets and [exported parity samples](parity/score_samples.json). No Android device or live reputation service is needed. They cover URL false positives, model parity, reputation policy and caller/file/guardian rules.

`FeedEvaluationTest` is optional and skipped unless `-Pfeeds` points to a local feed directory. It processes already downloaded URLs as strings. Parity tests show agreement with exported calculations; they are not a population-wide detection-accuracy measurement. See the [release validation](../README.md#release-validation) for recorded results.
