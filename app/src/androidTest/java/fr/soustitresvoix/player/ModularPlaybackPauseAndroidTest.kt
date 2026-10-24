package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.NarrationMachine
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Default production composition, real Media3 text rendering and Android TTS
 * IPC. The fixture synthesizes deterministic silence, not a human voice.
 * The interrupted replica must restart once; every later source occurrence,
 * including the two identical final replies, must still reach the service. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ModularPlaybackPauseAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private data class EngineEvent(val event: String, val text: String)

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0)
            .putInt("resumePolicy", NarrationMachine.ResumePolicy.REPLICA.ordinal).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    private fun fixture(name: String): File = File(instrument.targetContext.cacheDir, "modular-$name").also { file ->
        instrument.context.assets.open("ssa_nonloss/$name").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test fun pauseDuringRealTtsThenResumePreservesTheEntireSelectedAssTrack() {
        // These expectations come from the versioned fixture, never the parser's output.
        val panel = "Non-perte — panneau externe ; accès réservé."
        val repeat = "Non-perte — reprise externe ; encore une fois."
        val source = listOf(panel,
            "Non-perte — dialogue externe un ; Alice arrive.",
            "Non-perte — dialogue externe deux ; Bob répond.",
            "Non-perte — dialogue externe trois ; Alice repart.", repeat, repeat)
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        fun observed() = synchronized(events) { events.toList() }
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val activity = AtomicReference<MainActivity>()
        val interruptedId = AtomicReference<String>()
        val pauseRequested = AtomicBoolean(false)
        val pauseBarrier = AtomicReference<PlayerMessage>()
        val receiverFailure = AtomicReference<Throwable>()
        val context = instrument.targetContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.OBSERVATION_ACTION) return
                val text = intent.getStringExtra("text") ?: return
                val event = intent.getStringExtra("event") ?: return
                // Record every fixture request, including unexpected duplicates.
                events += EngineEvent(event, text)
                if (event == "received" && text == panel && pauseRequested.compareAndSet(false, true)) {
                    try {
                        // Pause on the main-thread IPC observation, not after an
                        // arbitrary delay which could miss the active utterance.
                        val session = requireNotNull(activity.get()).session
                        interruptedId.set(requireNotNull(session.machine.snapshot().speakingId) {
                            "The fixture utterance finished before its request was observed"
                        })
                        session.pause()
                        pauseBarrier.set((session.playback as Media3PlaybackBackend).exoPlayer
                            .createMessage { _, _ -> }.send())
                    } catch (failure: Throwable) { receiverFailure.set(failure) }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.OBSERVATION_ACTION), ContextCompat.RECEIVER_EXPORTED)
        var mediaFile: File? = null
        var subtitleFile: File? = null
        val preferences = PlayerPreferences(context)
        try {
            val video = fixture("ass_nonloss_video.mkv").also { mediaFile = it }
            val subtitles = fixture("ass_nonloss_external.ass").also { subtitleFile = it }
            preferences.apply {
                enginePackage = instrument.context.packageName
                voiceName = "fixture-fr"
                narration = true
                putBool("cache", false)
                setMode(NarrationMachine.Mode.COMFORT)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { errors += error.errorCodeName }
                    })
                    current.session.open(SavedMedia(Uri.fromFile(video).toString(), "Synthetic modular pause fixture",
                        subtitlePath = subtitles.path, subtitleMime = MimeTypes.TEXT_SSA))
                }
                waitFor("The production player, selected ASS track or actual fixture TTS did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady &&
                        session.selectedTextId() == "external-subtitle"
                }
                scenario.onActivity { current ->
                    assertFalse(current.session.prefs.cacheEnabled)
                    assertEquals(NarrationMachine.ResumePolicy.REPLICA, current.session.prefs.resumePolicy)
                    assertTrue("Preparing paused media must not speak", observed().isEmpty())
                    current.session.play()
                }
                waitFor("The real service did not receive the first panel while it could be interrupted") {
                    pauseBarrier.get() != null || receiverFailure.get() != null
                }
                receiverFailure.get()?.let { throw AssertionError("Could not pause the actual fixture utterance", it) }
                // Public Player state changes before its playback thread has
                // stopped the clock. Keep the same real barrier as the paused-frame test.
                assertTrue("The playback thread did not acknowledge the pause",
                    requireNotNull(pauseBarrier.get()).blockUntilDelivered(20_000))
                instrument.waitForIdleSync()
                scenario.onActivity { current ->
                    val session = current.session
                    val state = session.machine.snapshot()
                    assertFalse(state.wantsPlayback)
                    assertFalse(state.videoRequested)
                    assertNull(state.speakingId)
                    assertFalse(session.player.playWhenReady)
                    assertEquals(panel, session.machine.pendingTexts().first())
                    assertEquals("The interrupted request must not restart without explicit play",
                        listOf(panel), observed().filter { it.event == "received" }.map { it.text })
                    session.play()
                    assertNotNull(session.machine.snapshot().speakingId)
                    assertNotEquals("Resuming must allocate a fresh utterance ID",
                        interruptedId.get(), session.machine.snapshot().speakingId)
                }
                waitFor("The film did not finish with all post-pause narration drained", 45_000) {
                    val session = activity.get().session
                    val state = session.machine.snapshot()
                    session.player.playbackState == Player.STATE_ENDED && state.speakingId == null &&
                        state.pendingCount == 0 && !state.wantsPlayback
                }
                waitFor("The service did not synthesize all six resumed source occurrences") {
                    observed().count { it.event == "completed" } >= source.size
                }
                instrument.waitForIdleSync()
                val complete = observed()
                assertTrue("The real player failed: $errors", errors.isEmpty())
                assertEquals("Only the intentionally interrupted panel may restart; no later event may be lost or duplicated",
                    listOf(panel) + source, complete.filter { it.event == "received" }.map { it.text })
                // The first canceled request may already have finished synthesis
                // before playback stopped. Its completion is optional; the final
                // six successful synthesis reports must match the resumed source.
                assertEquals(source, complete.filter { it.event == "completed" }.map { it.text }.takeLast(source.size))
                assertTrue(complete.none { it.event == "injected_error" })
                scenario.onActivity { current ->
                    val state = current.session.machine.snapshot()
                    assertNull(state.speakingId)
                    assertEquals(0, state.pendingCount)
                    assertFalse(state.wantsPlayback)
                    assertFalse(state.videoRequested)
                    assertFalse(state.waitingForSpeech)
                    assertEquals(Player.STATE_ENDED, current.session.player.playbackState)
                    assertFalse(current.session.player.playWhenReady)
                    assertEquals(0, current.session.speech.hits)
                    assertEquals(0, current.session.speech.preparedCount)
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
            preferences.enginePackage = ""
            mediaFile?.delete()
            subtitleFile?.delete()
        }
    }
}
