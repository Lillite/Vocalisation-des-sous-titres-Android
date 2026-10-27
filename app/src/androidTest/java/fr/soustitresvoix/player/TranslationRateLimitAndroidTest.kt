package fr.soustitresvoix.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Executes the release APK's translator and persistent runner on Android.
 * Controlled API responses, synthetic text only; no account or API quota used. */
@RunWith(AndroidJUnit4::class)
class TranslationRateLimitAndroidTest {
    private val config = TranslationProviderConfig(TranslationProvider.DEEPL_FREE, "synthetic-test-key")

    @Test fun temporaryRateLimitDoesNotTruncateOrDuplicateTheTrack() {
        val directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "rate-limit-").toFile()
        val calls = AtomicInteger()
        val accepted = mutableListOf<String>()
        val transport = object : TranslationHttpTransport {
            override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
                if(calls.incrementAndGet() == 2) return TranslationHttpResponse(429, "", 0)
                val values = JSONObject(request.body).getJSONArray("text")
                val output = JSONArray()
                for(i in 0 until values.length()) {
                    val source = values.getString(i); accepted += source
                    output.put(JSONObject().put("text", "Français $source"))
                }
                return TranslationHttpResponse(200, JSONObject().put("translations", output).toString())
            }
        }
        try {
            val plan = TranslationPlan.create((0 until 45).map {
                TranslationCue("occurrence-$it", it * 3_000_000L, it * 3_000_000L + 2_000_000L,
                    if(it in 19..21) "EXIT" else "Line $it")
            }, "en", "fr", config.provider.id)
            TranslationEngines.create(config, transport).use { engine ->
                val result = TranslationRunner(directory.resolve("cache"), directory.resolve("progress"))
                    .run(plan, engine, { false }) { _, _ -> }
                assertEquals(4, calls.get())
                assertEquals(plan.texts, accepted)
                assertEquals(plan.cues.map { it.id }, result.cues.map { it.id })
                assertEquals(plan.cues.map { it.startUs to it.endUs }, result.cues.map { it.startUs to it.endUs })
                assertEquals(plan.texts.map { "Français $it" }, result.cues.map { it.text })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun closingDuringRateLimitStopsTheWorkerWithoutAnotherRequest() {
        val rejected = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = object : TranslationHttpTransport {
            override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
                calls.incrementAndGet(); rejected.countDown()
                return TranslationHttpResponse(429, "", 10_000)
            }
        }
        val engine = TranslationEngines.create(config, transport)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cancelled = executor.submit<Boolean> {
                try { engine.translate(listOf("Synthetic subtitle"), "en", "fr") { false }; false }
                catch (_: TranslationCancelledException) { true }
            }
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            engine.close()
            assertTrue(cancelled.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { engine.close(); executor.shutdownNow() }
    }
}
