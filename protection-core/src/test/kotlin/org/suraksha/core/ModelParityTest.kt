package org.suraksha.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelParityTest {
    companion object {
        private fun resource(name: String) = requireNotNull(ModelParityTest::class.java.classLoader.getResourceAsStream(name)) {
            "Required exported asset missing: $name"
        }.bufferedReader().use { it.readText() }
        private val suffixes by lazy { PublicSuffixList(resource("public_suffix_list.dat")) }
        private val features by lazy { UrlFeatures(suffixes) }
        private val urlModel by lazy { UrlModel(resource("url_model.json")) }
        private val textModel by lazy { TextModel(resource("text_model.json")) }
        private val samples by lazy { Json.parseToJsonElement(resource("score_samples.json")).jsonObject.getValue("samples").jsonArray }
        private val engine by lazy { VerdictEngine.load(::resource) }
    }

    @Test fun allFiftyHeldOutRowsMatchPython() {
        assertEquals(50, samples.size)
        samples.forEachIndexed { index, item ->
            val row = item.jsonObject
            assertEquals("test", row.getValue("split").jsonPrimitive.content)
            val vector = features.extract(requireNotNull(UrlParser.parse(row.getValue("url").jsonPrimitive.content)))
            val expected = row.getValue("features").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            assertArrayEquals("Feature vector at sample $index", expected, vector, 0.000001)
            assertEquals("URL score at sample $index", row.getValue("url_score").jsonPrimitive.double, urlModel.score(vector), 1e-10)
            assertEquals("Text score at sample $index", row.getValue("text_score").jsonPrimitive.double,
                textModel.score(row.getValue("message").jsonPrimitive.content), 1e-10)
        }
    }

    @Test fun heldOutUrlsWereNotInsertedIntoBlocklist() {
        val blocklist = Blocklist(resource("blocklist.txt"))
        samples.forEach { row ->
            val url = requireNotNull(UrlParser.parse(row.jsonObject.getValue("url").jsonPrimitive.content))
            assertFalse("Test host leaked into blocklist", blocklist.contains(url.host))
        }
    }

    @Test fun normalLinksAndShortenersAreSilentWhileConcreteDeceptionAlarms() {
        listOf("https://youtube.com/watch?v=hello", "https://www.google.com/search?q=weather",
            "https://en.wikipedia.org/wiki/India").forEach {
            assertEquals(Level.SAFE, engine.evaluate(it, "").level)
        }
        assertEquals(Level.DANGEROUS, engine.evaluate("https://youtube.com.evil.example/verify", "").level)
        assertEquals(Level.SAFE, engine.evaluate("https://bit.ly/a", "").level)
    }

    @Test fun historicalAssetLabelsNeverOverrideTheCurrentClassifier() {
        val demos = Json.parseToJsonElement(resource("demo_samples.json")).jsonObject.getValue("samples").jsonArray
        assertTrue(demos.size >= 7)
        val pipeline = MessagePipeline(engine)
        demos.forEach { item ->
            val row = item.jsonObject
            val message = row.getValue("message").jsonPrimitive.content
            val results = pipeline.scan(message, "test", false)
            assertTrue(results.none { ReasonCode.DEMO_ONLY in it.verdict.reasonCodes ||
                ReasonCode.URL_MODEL in it.verdict.reasonCodes })
            if (row.getValue("kind").jsonPrimitive.content == "safe") {
                assertTrue(message, results.all { it.verdict.level == Level.SAFE })
            }
            // These were selected using the retired model. Their old label is not a verdict
            // override or an accuracy benchmark for the current conservative rule policy.
            val inputs = UrlExtractor.extract(message)
            results.forEach { result ->
                val input = inputs.firstOrNull { UrlParser.parse(it)?.host == result.domain }
                if (input != null) assertEquals(engine.evaluate(input, message), result.verdict)
            }
        }
    }

    @Test fun malformedLinkIsAnExplainedCautionWithoutEscalation() {
        val invalid = "https://example.com:70000"
        assertEquals(Level.SUSPICIOUS, engine.evaluate(invalid, "").level)
        assertEquals(setOf(ReasonCode.INVALID_URL), engine.evaluate(invalid, "").reasonCodes)
        val pressure = "Your account will be blocked. Share your OTP immediately to verify your account"
        val result = MessagePipeline(engine).scan("$pressure $invalid", "regression", false).single()
        assertEquals(Level.SUSPICIOUS, result.verdict.level)
        assertTrue(ReasonCode.INVALID_URL in result.verdict.reasonCodes)
        assertTrue(ReasonCode.CREDENTIAL_REQUEST in result.verdict.reasonCodes)
    }

    @Test fun allReasonsHavePlainLanguageTranslations() {
        ReasonCode.entries.forEach { code ->
            listOf("en", "hi", "pa").forEach { language -> assertTrue(ReasonMessages.get(code, language).length > 10) }
        }
    }

    @Test fun recordJvmLatencyWithoutClaimingPhoneTiming() {
        val rows = samples.map { it.jsonObject }
        repeat(10) {
            val row = rows[it]
            engine.evaluate(row.getValue("url").jsonPrimitive.content, row.getValue("message").jsonPrimitive.content)
        }
        val timings = rows.map { row ->
            val start = System.nanoTime()
            engine.evaluate(row.getValue("url").jsonPrimitive.content, row.getValue("message").jsonPrimitive.content)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val median = timings[25]
        val p95 = timings[47]
        val report = """{"platform":"desktop-jvm","samples":50,"median_ms":$median,"p95_ms":$p95,"phone_verified":false}"""
        File("build/reports/core-latency.json").apply { parentFile.mkdirs(); writeText(report) }
        println("Core JVM inference: median $median ms, p95 $p95 ms. Phone timing pending.")
    }
}
