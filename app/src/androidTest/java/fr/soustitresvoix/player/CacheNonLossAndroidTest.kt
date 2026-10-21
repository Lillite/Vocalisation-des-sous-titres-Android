package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android TTS service IPC; deterministic synthetic PCM, no human voice claims. */
@RunWith(AndroidJUnit4::class)
class CacheNonLossAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private data class EngineEvent(val event: String, val text: String)
    private enum class Failure { MISSING, TRUNCATED, ASYNC_ENGINE_ERROR }

    @Before fun clearPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    @Test fun missingPreparedFileRetainsTheEntireTextAndOriginalReplyId() = verify(Failure.MISSING)

    @Test fun truncatedPreparedFileWithMatchingDigestRetainsTheEntireTextAndOriginalReplyId() =
        verify(Failure.TRUNCATED)

    @Test fun asynchronousPreparationErrorRetainsTheEntireTextAndOriginalReplyId() =
        verify(Failure.ASYNC_ENGINE_ERROR)

    private fun verify(failure: Failure) {
        val label = when (failure) {
            Failure.MISSING -> "cache manquant"
            Failure.TRUNCATED -> "cache tronqué"
            Failure.ASYNC_ENGINE_ERROR -> "erreur asynchrone"
        }
        val text = "${FixtureSpeechService.NONLOSS_PREFIX}$label : « Stop ! » — Pourquoi ? " +
            "M. Dupont répond : 3,14 ; d’accord… Puis : fin."
        val id = "nonloss-${failure.name.lowercase()}"
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        fun observed() = synchronized(events) { events.toList() }
        val completions = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val finished = CountDownLatch(1)
        val context = instrument.targetContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.OBSERVATION_ACTION) return
                val received = intent.getStringExtra("text") ?: return
                val event = intent.getStringExtra("event") ?: return
                if (received == text) events.add(EngineEvent(event, received))
            }
        }
        // The fixture engine has its own APK/UID. Explicit broadcasts target only
        // the application under test; the receiver lives only for this test.
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.OBSERVATION_ACTION), ContextCompat.RECEIVER_EXPORTED)
        var speech: AndroidSpeech? = null
        val preferences = PlayerPreferences(context)
        try {
            instrument.runOnMainSync {
                preferences.enginePackage = instrument.context.packageName
                preferences.putBool("cache", true)
                speech = AndroidSpeech(context, preferences, { _, _ -> },
                    { utterance -> completions.add(utterance); finished.countDown() },
                    { utterance, message -> failures.add(utterance to message); finished.countDown() })
            }
            waitFor("The actual fixture TTS service did not initialize") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.clearCache(); speech!!.prepareBatch(listOf(text)) }
            val directory = File(context.cacheDir, "speech-v3")
            if (failure == Failure.ASYNC_ENGINE_ERROR) {
                waitFor("The accepted preparation did not report its real engine callback error") {
                    observed().any { it.event == "injected_error" } && !speech!!.bulkPreparation &&
                        speech!!.preparationStatus.contains("Préparation indisponible")
                }
                instrument.runOnMainSync { assertEquals(0, speech!!.preparedCount) }
                assertTrue("Failed synthesis must not publish a prepared WAV",
                    directory.listFiles { file -> file.extension == "wav" }.orEmpty().isEmpty())
            } else {
                waitFor("Preparation did not finish both actual synthesis and cache publication") {
                    speech!!.preparedCount == 1 && !speech!!.bulkPreparation &&
                        observed().count { it.event == "completed" } == 1
                }
                val files = directory.listFiles { file -> file.extension == "wav" }.orEmpty()
                assertEquals("Exactly one complete synthetic recording was expected", 1, files.size)
                val audio = files.single()
                when (failure) {
                    Failure.MISSING -> assertTrue("The cached file could not be removed", audio.delete())
                    Failure.TRUNCATED -> {
                        val bytes = audio.readBytes()
                        assertTrue("Fixture WAV was unexpectedly too short", bytes.size > 64)
                        val truncated = bytes.copyOf(64)
                        audio.writeBytes(truncated)
                        // Recompute the digest so this exercises WAV validation,
                        // not only the independent corruption/hash check.
                        File(directory, "${audio.nameWithoutExtension}.sha256").writeText(
                            MessageDigest.getInstance("SHA-256").digest(truncated)
                                .joinToString("") { "%02x".format(it) })
                    }
                    Failure.ASYNC_ENGINE_ERROR -> error("Already handled")
                }
            }
            assertEquals("Preparation must have submitted one complete text to the service",
                listOf(text), observed().filter { it.event == "received" }.map { it.text })
            assertTrue("Preparation failure must not finish an unrelated live reply", completions.isEmpty())
            assertTrue("Preparation is not a foreground voice failure", failures.isEmpty())

            instrument.runOnMainSync { speech!!.speak(id, text) }
            assertTrue("The same reply did not finish through direct synthesis", finished.await(15, TimeUnit.SECONDS))
            waitFor("The direct retry's complete text was not observed in the real engine process") {
                observed().count { it.event == "received" } == 2 &&
                    observed().count { it.event == "completed" } == (if (failure == Failure.ASYNC_ENGINE_ERROR) 1 else 2)
            }
            instrument.waitForIdleSync()
            assertTrue("The direct fallback failed: $failures", failures.isEmpty())
            assertEquals("Completion must retain the caller's original reply identity", listOf(id), completions.toList())
            assertEquals("No punctuation, character, or tail of the subtitle may be lost",
                listOf(text, text), observed().filter { it.event == "received" }.map { it.text })
            instrument.runOnMainSync {
                assertEquals("Invalid or absent prepared audio must never count as a hit", 0, speech!!.hits)
                assertEquals(1, speech!!.misses)
                assertTrue(speech!!.timingSource.contains("directe"))
            }
        } finally {
            instrument.runOnMainSync { speech?.close(); preferences.enginePackage = "" }
            context.unregisterReceiver(receiver)
        }
    }
}
