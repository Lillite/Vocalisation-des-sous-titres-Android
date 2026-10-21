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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.NarrationMachine
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 playback -> PlaybackSession -> Android TTS IPC.
 * The fixture returns deterministic PCM silence; this checks requests, callback
 * completion and queue/video state, not the prosody of a human-sounding voice.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SsaNonLossPlaybackAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private data class EngineEvent(val event: String, val text: String)

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
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

    private fun fixture(name: String): File = File(instrument.targetContext.cacheDir, name).also { file ->
        instrument.context.assets.open("ssa_nonloss/$name").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test fun externalAssInComfortSpeaksOnePersistentPanelAndEveryDistinctDialogue() =
        verify(external = true, mode = NarrationMachine.Mode.COMFORT)

    @Test fun embeddedAssInAdaptiveSpeaksOnePersistentPanelAndEveryDistinctDialogue() =
        verify(external = false, mode = NarrationMachine.Mode.ADAPTIVE)

    private fun verify(external: Boolean, mode: NarrationMachine.Mode) {
        val source = if (external) "externe" else "interne"
        // Literal source expectations, never derived from parser/index output.
        val panel = "Non-perte — panneau $source ; accès réservé."
        val dialogues = listOf(
            "Non-perte — dialogue $source un ; Alice arrive.",
            "Non-perte — dialogue $source deux ; Bob répond.",
            "Non-perte — dialogue $source trois ; Alice repart."
        )
        val repeat = "Non-perte — reprise $source ; encore une fois."
        val expected = listOf(panel) + dialogues + listOf(repeat, repeat)
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        fun observed() = synchronized(events) { events.toList() }
        val displays = Collections.synchronizedList(mutableListOf<List<String>>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val activity = AtomicReference<MainActivity>()
        val context = instrument.targetContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.OBSERVATION_ACTION) return
                val text = intent.getStringExtra("text") ?: return
                val event = intent.getStringExtra("event") ?: return
                // Observe every fixture request during the run, including any
                // unexpected repetition; do not filter to the expected six texts.
                events += EngineEvent(event, text)
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.OBSERVATION_ACTION), ContextCompat.RECEIVER_EXPORTED)
        var mediaFile: File? = null
        var subtitleFile: File? = null
        val preferences = PlayerPreferences(context)
        try {
            val sourceVideo = fixture(if (external) "ass_nonloss_video.mkv" else "ass_nonloss_embedded.mkv")
                .also { mediaFile = it }
            val sourceSubtitle = if (external) fixture("ass_nonloss_external.ass").also { subtitleFile = it } else null
            preferences.apply {
                enginePackage = instrument.context.packageName
                voiceName = "fixture-fr"
                narration = true
                putBool("cache", false)
                setMode(mode)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.player.addListener(object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) {
                            displays += cueGroup.cues.mapNotNull { it.text?.toString() }
                        }
                        override fun onPlayerError(error: PlaybackException) { errors += error.errorCodeName }
                    })
                    current.session.open(SavedMedia(Uri.fromFile(sourceVideo).toString(), "Synthetic ASS IPC fixture",
                        subtitlePath = sourceSubtitle?.path, subtitleMime = sourceSubtitle?.let { MimeTypes.TEXT_SSA }))
                }
                waitFor("The real player/text track or fixture TTS service did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady &&
                        session.selectedTextId() != null &&
                        (!external || session.selectedTextId() == "external-subtitle")
                }
                scenario.onActivity { current ->
                    assertFalse(current.session.prefs.cacheEnabled)
                    if (!external) assertNotEquals("external-subtitle", current.session.selectedTextId())
                    assertTrue("Paused preparation must not speak any cue", observed().isEmpty())
                    current.session.play()
                }
                waitFor("The synthetic film did not finish with its entire narration queue drained", 45_000) {
                    val session = activity.get().session
                    val state = session.machine.snapshot()
                    session.player.playbackState == Player.STATE_ENDED && state.speakingId == null &&
                        state.pendingCount == 0 && !state.wantsPlayback
                }
                waitFor("The actual service did not report completion for all six requests") {
                    observed().count { it.event == "completed" } >= expected.size
                }
                instrument.waitForIdleSync()
                val completeEvents = observed()
                assertTrue("The real player failed: $errors", errors.isEmpty())
                assertEquals("Every source event must reach the actual TTS service in order, without drops or duplicate panels",
                    expected, completeEvents.filter { it.event == "received" }.map { it.text })
                assertEquals("Every submitted text must receive actual successful fixture synthesis completion",
                    expected, completeEvents.filter { it.event == "completed" }.map { it.text })
                assertTrue("The fixture injected an unexpected engine failure",
                    completeEvents.none { it.event == "injected_error" })
                val frames = synchronized(displays) { displays.toList() }
                assertTrue("The persistent panel was not observed across multiple real renderer updates",
                    frames.count { panel in it } >= 4)
                for (dialogue in dialogues) assertTrue("No real display group contained the panel and this dialogue: $dialogue",
                    frames.any { panel in it && dialogue in it })
                assertTrue("The repeated text was not observed in both real display appearances",
                    frames.count { repeat in it } >= 2)
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
                    assertFalse(current.session.speech.bulkPreparation)
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
