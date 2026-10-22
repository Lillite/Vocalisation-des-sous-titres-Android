package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.ImageSettings
import fr.soustitresvoix.core.NarrationMachine
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual image controls, Media3 submissions and speech-service IPC. The existing
 * SDR PixelCopy test proves rendered changes separately. This test preserves the
 * default audio fades and observes every actual synthesis request, including any
 * accidental voice preview caused by an image command. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ImageCommandsAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    private data class PausedState(
        val narration: NarrationMachine.Snapshot,
        val queue: List<String>,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val playbackState: Int,
        val volume: Float,
        val media: MediaItem?,
        val tracks: TrackSelectionParameters,
        val generation: Long,
        val screenBrightness: Float,
        val preferences: Map<String, *>
    )

    private fun state(activity: MainActivity): PausedState {
        val session = activity.session
        return PausedState(session.machine.snapshot(), session.machine.pendingTexts(), session.player.currentPosition,
            session.player.playWhenReady, session.player.playbackState, session.player.volume,
            session.player.currentMediaItem, session.player.trackSelectionParameters,
            session.imageCommands.snapshot().context.generation, activity.window.attributes.screenBrightness,
            activity.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap())
    }

    private fun awaitSubmitted(activity: AtomicReference<MainActivity>, contrast: Float, brightness: Float) {
        // ExoPlayer's thread barrier alone cannot drain a future 32 ms effect
        // update. Wait for the actual session-owned submission acknowledgement.
        waitFor("The requested image correction was not submitted to the real video output") {
            val snapshot = activity.get().session.imageCommands.snapshot()
            snapshot.lastSubmitted?.let {
                it.generation == snapshot.context.generation && it.contrast == contrast && it.imageBrightness == brightness
            } == true
        }
    }

    @Test fun realImageControlsPreservePausedPlaybackAndOnlyExplicitPreviewsReachTheVoiceEngine() {
        val preview = "Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film."
        val requests = Collections.synchronizedList(mutableListOf<String>())
        fun observed() = synchronized(requests) { requests.toList() }
        val context = instrument.targetContext
        val preferences = PlayerPreferences(context).apply {
            enginePackage = instrument.context.packageName
            voiceName = "fixture-fr"
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.ALL_REQUESTS_ACTION) return
                intent.getStringExtra("text")?.let { requests += it }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = AtomicReference<MainActivity>()
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.open(SavedMedia(
                        "android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Synthetic image-command fixture"))
                    current.setFullscreen(true)
                }
                waitFor("The actual video and fixture voice did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady && session.video.format != null
                }
                scenario.onActivity { current ->
                    assertTrue("Paused preparation must not synthesize", observed().isEmpty())
                    assertFalse(current.session.imageCommands.snapshot().context.hdrProtected)
                    current.session.previewVoice()
                }
                waitFor("The first explicit voice preview did not reach the engine and finish") {
                    observed().isNotEmpty() && activity.get().session.machine.snapshot().speakingId == null
                }
                assertEquals(listOf(preview), observed())

                // Open the real tool through its scrollable cinema controls;
                // no direct hidden-view click substitutes for the user's action.
                onView(withText("Image / zoom")).perform(scrollTo(), click())
                Closeable {
                    onView(withText("Fermer")).inRoot(isDialog()).perform(click())
                }.use {
                    onView(withText("Image et cadrage")).inRoot(isDialog()).check(matches(isDisplayed()))
                    var barrier: PlayerMessage? = null
                    scenario.onActivity { current ->
                        assertFalse(current.session.player.playWhenReady)
                        barrier = (current.session.playback as Media3PlaybackBackend).exoPlayer
                            .createMessage { _, _ -> }.send()
                    }
                    assertTrue("The playback thread did not acknowledge the paused baseline",
                        requireNotNull(barrier).blockUntilDelivered(20_000))
                    instrument.waitForIdleSync()
                    // TTS completion precedes its delayed film-volume recovery.
                    // Preserve the defaults and wait for the actual target before
                    // attributing any later change to an image control.
                    waitFor("The film volume did not recover after the voice preview") {
                        val session = activity.get().session
                        session.player.volume == session.prefs.movieVolume
                    }
                    var baseline: PausedState? = null
                    scenario.onActivity { current ->
                        baseline = state(current)
                        assertFalse(requireNotNull(baseline).narration.wantsPlayback)
                        assertNull(requireNotNull(baseline).narration.speakingId)
                        assertEquals(ImageSettings(), current.session.imageCommands.snapshot().context.requested)
                    }
                    fun assertPreserved(expected: ImageSettings) {
                        scenario.onActivity { current ->
                            assertEquals(expected, current.session.imageCommands.snapshot().context.requested)
                            assertEquals("An image control changed playback, voice, volume, screen brightness or preferences",
                                baseline, state(current))
                            assertEquals("An image control triggered a synthesis request", listOf(preview), observed())
                            assertNull(current.session.player.playerError)
                        }
                    }

                    onView(withText("Contraste léger + / original (SDR seulement)")).inRoot(isDialog())
                        .perform(scrollTo(), click())
                    awaitSubmitted(activity, .12f, 0f)
                    assertPreserved(ImageSettings(.12f, 0f, false))

                    onView(withText("Éclaircir légèrement / original (SDR seulement)")).inRoot(isDialog())
                        .perform(scrollTo(), click())
                    awaitSubmitted(activity, .12f, .06f)
                    assertPreserved(ImageSettings(.12f, .06f, false))

                    val comparison = "Comparer à l’original : désactiver les traitements"
                    onView(withText(comparison)).inRoot(isDialog()).perform(scrollTo(), click())
                    awaitSubmitted(activity, 0f, 0f)
                    onView(withText(comparison)).inRoot(isDialog()).check(matches(isChecked()))
                    assertPreserved(ImageSettings(.12f, .06f, true))

                    onView(withText(comparison)).inRoot(isDialog()).perform(click())
                    awaitSubmitted(activity, .12f, .06f)
                    onView(withText(comparison)).inRoot(isDialog()).check(matches(not(isChecked())))
                    assertPreserved(ImageSettings(.12f, .06f, false))
                }
                // Bound the actual IPC observation with a second positive
                // control. Image actions themselves may never add a request.
                scenario.onActivity { current -> current.session.previewVoice() }
                waitFor("The second explicit voice preview did not reach the engine and finish") {
                    observed().size >= 2 && activity.get().session.machine.snapshot().speakingId == null
                }
                instrument.waitForIdleSync()
                assertEquals("Only the two explicit previews may reach the actual speech service",
                    listOf(preview, preview), observed())
                scenario.onActivity { current ->
                    assertFalse(current.session.player.playWhenReady)
                    assertFalse(current.session.machine.snapshot().wantsPlayback)
                    assertNull(current.session.player.playerError)
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
            preferences.enginePackage = ""
        }
    }
}
