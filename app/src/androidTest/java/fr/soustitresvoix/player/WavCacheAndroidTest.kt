package fr.soustitresvoix.player

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WavCacheAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun clearPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 20_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    @Test fun invalidLegacyPcmWithMatchingDigestIsEvictedBeforeDirectFallbackOfTheSameReply() {
        val done = CountDownLatch(1)
        val completions = Collections.synchronizedList(mutableListOf<String>())
        val error = AtomicReference<String?>(null)
        var speech: AndroidSpeech? = null
        val text = "Le panneau reste intelligible, même si son ancien fichier vocal est invalide."
        val id = "invalid-pcm-same-reply"
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext).apply {
                enginePackage = instrument.context.packageName
                putBool("cache", true)
            }
            speech = AndroidSpeech(instrument.targetContext, prefs, { _, _ -> },
                { utterance -> completions.add(utterance); done.countDown() },
                { _, message -> error.set(message); done.countDown() })
        }
        try {
            waitFor("The installed fixture TTS was not ready") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.clearCache(); speech!!.prepareBatch(listOf(text)) }
            waitFor("The real synthesis did not finish publication") {
                speech!!.preparedCount == 1 && !speech!!.bulkPreparation
            }
            val directory = File(instrument.targetContext.cacheDir, "speech-v3")
            val files = directory.listFiles { file -> file.extension == "wav" }.orEmpty()
            assertEquals("This isolated preparation should publish exactly one WAV", 1, files.size)
            val audio = files.single()
            val bytes = audio.readBytes()
            var offset = 12
            var foundFormat = false
            while (offset + 8 <= bytes.size) {
                val size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int)
                assertTrue("The real fixture returned a malformed chunk", size <= bytes.size - offset - 8)
                if (String(bytes, offset, 4, Charsets.US_ASCII) == "fmt ") {
                    assertTrue(size >= 16)
                    // Recreate an entry accepted by the previous validator:
                    // plausible byte rate and payload, but impossible channel count.
                    bytes[offset + 10] = 0
                    bytes[offset + 11] = 0
                    foundFormat = true
                    break
                }
                offset += 8 + size.toInt() + (size % 2).toInt()
            }
            assertTrue("No PCM format chunk was produced by the real fixture", foundFormat)
            audio.writeBytes(bytes)
            val digest = File(directory, "${audio.nameWithoutExtension}.sha256")
            digest.writeText(MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })

            instrument.runOnMainSync { speech!!.speak(id, text) }
            assertTrue("The original reply did not finish using direct TTS", done.await(15, TimeUnit.SECONDS))
            instrument.waitForIdleSync()
            assertNull(error.get())
            assertEquals(listOf(id), completions.toList())
            instrument.runOnMainSync {
                assertEquals("Invalid PCM must be rejected before MediaPlayer is invoked", 0, speech!!.hits)
                assertEquals(1, speech!!.misses)
                assertTrue(speech!!.timingSource.contains("directe"))
            }
            assertFalse("The invalid legacy recording must be evicted", audio.exists())
            assertFalse("Its digest must be evicted with it", digest.exists())
        } finally {
            instrument.runOnMainSync {
                speech?.close()
                PlayerPreferences(instrument.targetContext).enginePackage = ""
            }
        }
    }
}
