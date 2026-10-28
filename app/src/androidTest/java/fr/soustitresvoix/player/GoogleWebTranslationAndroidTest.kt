package fr.soustitresvoix.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real release adapter and checkpoints, using synthetic controlled HTTP
 * responses. This suite never calls Google or consumes a translation quota. */
@RunWith(AndroidJUnit4::class)
class GoogleWebTranslationAndroidTest {
    private val config = TranslationProviderConfig(TranslationProvider.GOOGLE_WEB)
    private fun noDelayPacing(): GoogleRequestPacing {
        var clock = 0L
        return GoogleRequestPacing({ clock }, { clock += it }, { 0 })
    }
    private fun source(request: TranslationHttpRequest): String = URLDecoder.decode(
        request.uri.rawQuery.split('&').first { it.startsWith("q=") }.substring(2), "UTF-8")
    private fun body(original: String, translated: String): String =
        JSONArray().put(JSONArray().put(JSONArray().put(translated).put(original))).toString()

    @Test fun cachedRepeatedSignsStillProduceEveryOccurrenceWithOriginalTimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "google-track-").toFile()
        val requests = mutableListOf<String>()
        val transport = object : TranslationHttpTransport {
            override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse = error("GET required")
            override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
                val text = source(request); requests += text
                return TranslationHttpResponse(200, body(text, "Français $text"))
            }
        }
        try {
            val plan = TranslationPlan.create((0 until 45).map { index ->
                TranslationCue("google-$index", index * 3_000_000L, index * 3_000_000L + 2_000_000L,
                    if (index in 19..21) "EXIT 😀" else "Synthetic line $index")
            }, "en", "fr", TranslationEngines.translationIdentity(config))
            GoogleWebTranslationEngine(config, transport, noDelayPacing()).use { engine ->
                val prefixes = mutableListOf<Int>()
                val result = TranslationRunner(directory.resolve("cache"), directory.resolve("progress"))
                    .run(plan, engine, { false }, { _, _ -> }, TranslationRunPolicy(), { prefixes += it.size })
                assertEquals(plan.texts.distinct(), requests)
                assertEquals(plan.cues.map { it.id }, result.cues.map { it.id })
                assertEquals(plan.cues.map { it.startUs to it.endUs }, result.cues.map { it.startUs to it.endUs })
                assertEquals(plan.texts.map { "Français $it" }, result.cues.map { it.text })
                assertEquals((1..45).toList(), prefixes)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteSourceEchoCannotCompleteAPrefixAndResumeKeepsTheEarlierText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "google-resume-").toFile()
        val requests = mutableListOf<String>()
        var incomplete = true
        val transport = object : TranslationHttpTransport {
            override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse = error("GET required")
            override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
                val text = source(request); requests += text
                return TranslationHttpResponse(200, body(if (incomplete && text == "Second") "Sec" else text, "Français $text"))
            }
        }
        try {
            val plan = TranslationPlan.create(listOf("First", "Second", "Last").mapIndexed { index, text ->
                TranslationCue("cue-$index", index * 3_000_000L, index * 3_000_000L + 2_000_000L, text)
            }, "en", "fr", TranslationEngines.translationIdentity(config))
            val runner = TranslationRunner(directory.resolve("cache"), directory.resolve("progress"))
            GoogleWebTranslationEngine(config, transport, noDelayPacing()).use { engine ->
                val prefixes = mutableListOf<Int>()
                try {
                    runner.run(plan, engine, { false }, { _, _ -> }, TranslationRunPolicy(), { prefixes += it.size })
                    fail("A missing source tail must never activate a complete translation")
                } catch (error: TranslationFailure) { assertEquals(TranslationFailure.Reason.RESPONSE, error.reason) }
                assertEquals(listOf(1), prefixes)
                assertEquals(1, runner.checkpointSummary(plan).first)
                assertNull(runner.readExisting(plan))
                incomplete = false
                val result = runner.run(plan, engine, { false }) { _, _ -> }
                assertEquals(listOf("First", "Second", "Second", "Last"), requests)
                assertEquals(plan.texts.map { "Français $it" }, result.cues.map { it.text })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun closingDuringServerRequestedWaitCancelsWithoutAnotherGoogleRequest() {
        val requested = CountDownLatch(1)
        val count = AtomicInteger()
        val transport = object : TranslationHttpTransport {
            override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse = error("GET required")
            override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
                count.incrementAndGet(); requested.countDown()
                return TranslationHttpResponse(429, "", 60_000)
            }
        }
        val engine = GoogleWebTranslationEngine(config, transport, GoogleRequestPacing())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                try { engine.translate(listOf("Synthetic subtitle"), "en", "fr") { false }; false }
                catch (_: TranslationCancelledException) { true }
            }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            engine.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally { engine.close(); worker.shutdownNow() }
    }
}
