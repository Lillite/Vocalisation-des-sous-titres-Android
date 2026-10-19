package fr.soustitresvoix.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeechCacheInstrumentationTest {
    @Test fun androidEngineProducesCachedWavAndPlaybackCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1); val finished = CountDownLatch(1)
        var failure: String? = null; lateinit var speech: AndroidSpeech
        instrumentation.runOnMainSync {
            val prefs = PlayerPreferences(instrumentation.targetContext)
            prefs.enginePackage = instrumentation.context.packageName
            prefs.voiceName = "test-fr"; prefs.cacheEnabled = true
            speech = AndroidSpeech(instrumentation.targetContext, prefs,
                onAvailability = { ok, _ -> if (ok) ready.countDown() },
                onDone = { if (it == "integration") finished.countDown() },
                onFailure = { _, message -> failure = message; finished.countDown() })
        }
        try {
            assertTrue("Synthetic Android TTS engine did not initialize", ready.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { speech.prefetch(listOf("Bonjour. Voici la seconde phrase.")) }
            var entries = 0
            val until = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < until && entries < 2) {
                instrumentation.runOnMainSync { entries = speech.cacheEntries }; SystemClock.sleep(100)
            }
            assertEquals("synthesizeToFile did not prepare both sentence segments", 2, entries)
            instrumentation.runOnMainSync { speech.speak("integration", "Bonjour. Voici la seconde phrase.") }
            assertTrue("Cached audio did not finish", finished.await(15, TimeUnit.SECONDS))
            assertNull(failure)
            instrumentation.runOnMainSync {
                assertEquals(2, speech.cacheHits)
                assertTrue(speech.cacheBytes <= 64L * 1024 * 1024)
                assertEquals(2, speech.synthCount)
            }
        } finally { instrumentation.runOnMainSync { speech.close() } }
    }
}
