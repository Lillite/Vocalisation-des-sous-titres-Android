package fr.soustitresvoix.player

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.OfflineSpeechCue
import fr.soustitresvoix.core.OfflineSpeechLibrary
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android TTS file synthesis and durable restore, using only the installed synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    @Before fun resetPreferences() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }
    private fun create(): AndroidSpeech {
        var result: AndroidSpeech? = null
        instrument.runOnMainSync {
            val preferences = PlayerPreferences(context).apply {
                enginePackage = instrument.context.packageName; putBool("cache", true)
            }
            result = AndroidSpeech(context, preferences, { _, _ -> }, { }, { _, message -> fail(message) })
        }
        return requireNotNull(result)
    }
    @Test fun pinnedAudioSurvivesDisposableCacheRemovalAndNewSpeechClientWithoutResynthesis() {
        val media = "synthetic-offline-android-restore"
        val directory = File(context.filesDir, "offline-works-v1/${OfflineSpeechLibrary.workKey(media)}")
        directory.deleteRecursively()
        val cues = listOf(OfflineSpeechCue("sign", 0, 4000, "ENTRÉE SYNTHÉTIQUE"),
            OfflineSpeechCue("line-a", 1000, 2500, "La phrase est conservée intégralement."),
            OfflineSpeechCue("line-b", 3000, 4500, "La phrase est conservée intégralement."))
        var speech: AndroidSpeech? = create()
        try {
            waitFor("The installed fixture voice was not ready") { speech!!.isReady }
            instrument.runOnMainSync {
                speech!!.clearCache()
                speech!!.configureOfflineWork(media, cues, 6000, complete = true, title = "Démonstration synthétique")
                speech!!.prepareOffline()
            }
            waitFor("All pinned WAVs were not deeply verified") { speech!!.offlineStatus.ready && !speech!!.bulkPreparation }
            instrument.runOnMainSync {
                assertEquals(2, speech!!.offlineStatus.expectedSegments)
                assertEquals(2, speech!!.offlineStatus.readySegments)
                assertEquals(6000L, speech!!.offlineStatus.readyThroughMs)
                speech!!.clearCache(); speech!!.close()
            }
            speech = create()
            waitFor("The recreated fixture client was not ready") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.configureOfflineWork(media, cues, 6000, true, "Démonstration synthétique") }
            waitFor("Durable availability was not restored after recreating AndroidSpeech") { speech!!.offlineStatus.ready }
            instrument.runOnMainSync { speech!!.prepareOffline() }
            waitFor("Resuming a fully prepared work did not finish verification") { speech!!.offlineStatus.ready && !speech!!.bulkPreparation }
            instrument.runOnMainSync {
                assertEquals("Already pinned exact voice waveforms must not be synthesized again", 0, speech!!.preparedCount)
                assertNotNull(speech!!.measuredDurationMs("La phrase est conservée intégralement."))
                speech!!.removeOfflineVoices(OfflineSpeechLibrary.workKey(media))
            }
            waitFor("Removing pinned voices did not invalidate readiness") { !speech!!.offlineStatus.ready && speech!!.offlineStatus.readySegments == 0 }
        } finally { instrument.runOnMainSync { speech?.close() }; directory.deleteRecursively() }
    }
    @Test fun partialTranslatedSourceCannotBeDeclaredOfflineReadyAndSelectionResetIsImmediate() {
        val media = "synthetic-offline-android-partial"
        val directory = File(context.filesDir, "offline-works-v1/${OfflineSpeechLibrary.workKey(media)}")
        directory.deleteRecursively()
        val speech = create()
        try {
            waitFor("The installed fixture voice was not ready") { speech.isReady }
            instrument.runOnMainSync {
                speech.configureOfflineWork(media, listOf(OfflineSpeechCue("first", 0, 1000, "Première portion traduite.")),
                    4000, complete = false, title = "Traduction partielle synthétique")
                speech.prepareOffline()
            }
            waitFor("The partial source waveform was not prepared") {
                speech.offlineStatus.expectedSegments == 1 && speech.offlineStatus.readySegments == 1 && !speech.bulkPreparation
            }
            instrument.runOnMainSync {
                assertFalse(speech.offlineStatus.sourceComplete)
                assertFalse(speech.offlineStatus.ready)
                assertEquals(1000L, speech.offlineStatus.readyThroughMs)
                speech.clearOfflineSelection()
                assertFalse(speech.offlineStatus.ready)
                assertEquals("", speech.offlineStatus.mediaKey)
            }
        } finally { instrument.runOnMainSync { speech.close() }; directory.deleteRecursively() }
    }
    @Test fun cancellingWhileOfflineEstimateIsQueuedCannotStartSynthesisAfterwards() {
        val media = "synthetic-offline-android-cancel-estimate"
        val directory = File(context.filesDir, "offline-works-v1/${OfflineSpeechLibrary.workKey(media)}")
        directory.deleteRecursively()
        val speech = create(); val released = CountDownLatch(1); val entered = CountDownLatch(1)
        try {
            waitFor("The installed fixture voice was not ready") { speech.isReady }
            // Hold actual disk work, so Cancel occurs before its delayed result
            // returns. This tests the user-visible callback race deterministically.
            val executor = AndroidSpeech::class.java.getDeclaredField("io").run {
                isAccessible = true; get(speech) as ExecutorService
            }
            executor.execute { entered.countDown(); released.await(10, TimeUnit.SECONDS) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrument.runOnMainSync {
                speech.configureOfflineWork(media, listOf(OfflineSpeechCue("a", 0, 1000, "Estimation annulée avant synthèse.")), 2000)
                speech.prepareOffline()
                assertTrue(speech.offlinePreparationActive)
                speech.cancelPreparation()
                assertFalse(speech.offlinePreparationActive)
            }
            released.countDown()
            val drained = CountDownLatch(1); executor.execute { drained.countDown() }
            assertTrue(drained.await(15, TimeUnit.SECONDS))
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                assertFalse(speech.offlinePreparationActive)
                assertFalse(speech.bulkPreparation)
                assertEquals("A stale estimate must not generate actual fixture speech", 0, speech.preparedCount)
                assertFalse(speech.offlineStatus.ready)
            }
        } finally { released.countDown(); instrument.runOnMainSync { speech.close() }; directory.deleteRecursively() }
    }
}
