package org.suraksha.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Held-out measurement against public feeds the rules were never tuned on.
 * Opt-in: `-Pfeeds=<dir>` with openphish.txt, phishing_army.txt and top-1m.csv (Tranco).
 * The URLs are only parsed as strings; nothing is fetched.
 */
class FeedEvaluationTest {
    private fun resource(name: String) = requireNotNull(javaClass.classLoader.getResourceAsStream(name))
        .bufferedReader().use { it.readText() }

    @Test fun measureAgainstPublicFeeds() {
        val dir = System.getProperty("feeds")?.let(::File)
        assumeTrue("Set -Pfeeds=<dir> to run the held-out feed evaluation", dir?.isDirectory == true)
        val engine = VerdictEngine.load(::resource)
        fun level(url: String) = engine.evaluate(url, "").level
        val report = StringBuilder()
        fun rate(name: String, urls: List<String>, positive: Level) {
            val levels = urls.map(::level)
            val dangerous = levels.count { it == Level.DANGEROUS }
            val flagged = levels.count { it != Level.SAFE }
            report.appendLine("%-34s n=%6d  dangerous=%6.2f%%  any-warning=%6.2f%%".format(name, urls.size,
                100.0 * dangerous / urls.size, 100.0 * flagged / urls.size))
            if (positive == Level.SAFE) urls.zip(levels).filter { it.second == Level.DANGEROUS }.take(40)
                .forEach { report.appendLine("    false alarm: ${it.first}  ${engine.evaluate(it.first, "").reasonCodes}") }
        }
        File(dir, "openphish.txt").takeIf(File::isFile)?.readLines()?.filter { it.startsWith("http") }?.let {
            rate("OpenPhish live phishing URLs", it, Level.DANGEROUS)
        }
        File(dir, "phishing_army.txt").takeIf(File::isFile)?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }?.shuffled(kotlin.random.Random(7))?.take(20_000)?.let {
                rate("Phishing Army domains (homepage)", it.map { d -> "https://$d/" }, Level.DANGEROUS)
            }
        File(dir, "top-1m.csv").takeIf(File::isFile)?.readLines()?.map { it.substringAfter(',').trim() }?.let { domains ->
            rate("Tranco top 10k (homepage)", domains.take(10_000).map { "https://$it/" }, Level.SAFE)
            rate("Tranco top 10k (/account/login)", domains.take(10_000).map { "https://www.$it/account/login?next=%2Fhome" }, Level.SAFE)
            rate("Tranco 10k-100k sample (homepage)", domains.subList(10_000, 100_000).shuffled(kotlin.random.Random(7))
                .take(20_000).map { "https://$it/" }, Level.SAFE)
        }
        File("build/reports/feed-evaluation.txt").apply { parentFile.mkdirs(); writeText(report.toString()) }
        println(report)
    }
}
