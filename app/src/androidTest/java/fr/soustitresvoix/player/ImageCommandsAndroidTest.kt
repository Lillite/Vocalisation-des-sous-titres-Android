package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
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

    /** Application-thread notifications from the real renderer, not pixels or
     * a fake rendering acknowledgement. The listener is installed before open. */
    private class RenderedFrames : Player.Listener {
        var count = 0L; private set
        override fun onRenderedFirstFrame() { count++ }
    }

    private data class ImageSubmissionCheckpoint(val frames: Long, val sequence: Long?)

    private fun checkpoint(activity: AtomicReference<MainActivity>, frames: RenderedFrames): ImageSubmissionCheckpoint {
        var checkpoint: ImageSubmissionCheckpoint? = null
        instrument.runOnMainSync {
            checkpoint = ImageSubmissionCheckpoint(frames.count,
                activity.get().session.imageCommands.snapshot().lastSubmitted?.sequence)
        }
        return requireNotNull(checkpoint)
    }

    private fun awaitSubmitted(
        activity: AtomicReference<MainActivity>, contrast: Float, brightness: Float,
        baseline: PausedState, frames: RenderedFrames, before: ImageSubmissionCheckpoint,
        assertSpeechUnchanged: () -> Unit
    ) {
        assertEquals("The image-command baseline must be ready and paused", Player.STATE_READY, baseline.playbackState)
        assertFalse(baseline.playWhenReady)
        fun assertPausedInvariant(current: MainActivity) {
            val actual = state(current)
            // The existing CinemaVideoRenderer flushes the video sink when
            // replacing effects. READY may briefly become BUFFERING while a
            // fresh paused frame is prepared; no other field may change.
            assertTrue("Unexpected playback state while submitting image corrections: ${actual.playbackState}",
                actual.playbackState == Player.STATE_READY || actual.playbackState == Player.STATE_BUFFERING)
            assertEquals("Image submission changed voice, transport, audio, preferences or screen brightness",
                baseline, actual.copy(playbackState = baseline.playbackState))
            assertNull(current.session.player.playerError)
            assertSpeechUnchanged()
        }
        // The 32 ms coalescing delay must finish before a playback-thread
        // barrier can cover the actual effect request.
        waitFor("The image correction ($contrast, $brightness) was not submitted to the real video output") {
            val current = activity.get()
            assertPausedInvariant(current)
            val snapshot = current.session.imageCommands.snapshot()
            snapshot.lastSubmitted?.let {
                it.generation == snapshot.context.generation && it.contrast == contrast && it.imageBrightness == brightness
            } == true
        }
        var barrier: PlayerMessage? = null
        instrument.runOnMainSync {
            val current = activity.get()
            assertPausedInvariant(current)
            barrier = (current.session.playback as Media3PlaybackBackend).exoPlayer
                .createMessage { _, _ -> }.send()
        }
        assertTrue("The playback thread did not acknowledge the submitted image correction",
            requireNotNull(barrier).blockUntilDelivered(20_000))
        // A playback-thread barrier can precede its next doSomeWork pass.
        // READY alone could therefore still describe the old frame. The pinned
        // renderer flushes its sink for a changed plan and reports the next
        // first frame even in pause. Require that real notification as well.
        // An identical plan has no new sequence and must not invent a frame wait.
        waitFor("The paused video did not report a fresh frame and READY after image correction ($contrast, $brightness)") {
            val current = activity.get()
            assertPausedInvariant(current)
            val submitted = requireNotNull(current.session.imageCommands.snapshot().lastSubmitted)
            val freshFrame = submitted.sequence == before.sequence || frames.count > before.frames
            freshFrame && current.session.player.playbackState == Player.STATE_READY
        }
        instrument.runOnMainSync {
            val current = activity.get()
            assertPausedInvariant(current)
            // Keep the original complete-state equality, including readiness.
            assertEquals(baseline, state(current))
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
                val renderedFrames = RenderedFrames()
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.player.addListener(renderedFrames)
                    current.session.open(SavedMedia(
                        "android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Synthetic image-command fixture"))
                    current.setFullscreen(true)
                }
                waitFor("The actual video and fixture voice did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady && session.video.format != null && renderedFrames.count > 0
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
                    fun changeImage(contrast: Float, brightness: Float, action: () -> Unit) {
                        val before = checkpoint(activity, renderedFrames)
                        action()
                        awaitSubmitted(activity, contrast, brightness, requireNotNull(baseline), renderedFrames, before) {
                            assertEquals("An image submission triggered actual synthesis", listOf(preview), observed())
                        }
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

                    changeImage(.12f, 0f) {
                        onView(withText("Contraste léger + / original (SDR seulement)")).inRoot(isDialog())
                            .perform(scrollTo(), click())
                    }
                    assertPreserved(ImageSettings(.12f, 0f, false))

                    changeImage(.12f, .06f) {
                        onView(withText("Éclaircir légèrement / original (SDR seulement)")).inRoot(isDialog())
                            .perform(scrollTo(), click())
                    }
                    assertPreserved(ImageSettings(.12f, .06f, false))

                    val comparison = "Comparer à l’original : désactiver les traitements"
                    changeImage(0f, 0f) {
                        onView(withText(comparison)).inRoot(isDialog()).perform(scrollTo(), click())
                    }
                    onView(withText(comparison)).inRoot(isDialog()).check(matches(isChecked()))
                    assertPreserved(ImageSettings(.12f, .06f, true))

                    changeImage(.12f, .06f) {
                        onView(withText(comparison)).inRoot(isDialog()).perform(scrollTo(), click())
                    }
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

    private fun captureExpertScreenshot(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        // Only the synthetic demonstration on the disposable CI emulator is
        // captured. The existing device script archives this directory.
        for (command in listOf("mkdir -p /sdcard/Download/cinema03-tests",
            "screencap -p /sdcard/Download/cinema03-tests/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun expertPrecisionErrorsComparisonAndResetPreserveRealPlaybackAndVoiceRequests() {
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
                if (intent?.action == FixtureSpeechService.ALL_REQUESTS_ACTION) {
                    intent.getStringExtra("text")?.let { requests += it }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = AtomicReference<MainActivity>()
                val renderedFrames = RenderedFrames()
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.player.addListener(renderedFrames)
                    current.session.open(SavedMedia("android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Synthetic expert-image fixture"))
                    current.setFullscreen(true)
                }
                waitFor("The actual video and fixture voice did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.video.format != null && session.speech.isReady && renderedFrames.count > 0
                }
                scenario.onActivity { current ->
                    assertTrue("Opening a paused synthetic video synthesized text", observed().isEmpty())
                    current.session.previewVoice()
                }
                waitFor("The first explicit positive-control preview did not finish") {
                    observed().isNotEmpty() && activity.get().session.machine.snapshot().speakingId == null
                }
                assertEquals(listOf(preview), observed())
                waitFor("The film volume did not actually recover after TTS") {
                    val session = activity.get().session
                    session.player.volume == session.prefs.movieVolume
                }
                onView(withText("Image / zoom")).perform(scrollTo(), click())
                Closeable { onView(withText("Fermer")).inRoot(isDialog()).perform(click()) }.use {
                    onView(withText("Image et cadrage")).inRoot(isDialog()).check(matches(isDisplayed()))
                    var barrier: PlayerMessage? = null
                    scenario.onActivity { current ->
                        barrier = (current.session.playback as Media3PlaybackBackend).exoPlayer
                            .createMessage { _, _ -> }.send()
                    }
                    assertTrue("The paused baseline was not acknowledged by ExoPlayer",
                        requireNotNull(barrier).blockUntilDelivered(20_000))
                    instrument.waitForIdleSync()
                    var baseline: PausedState? = null
                    scenario.onActivity { current ->
                        baseline = state(current)
                        assertEquals(ImageSettings(), current.session.imageCommands.snapshot().context.requested)
                        assertFalse(requireNotNull(baseline).narration.wantsPlayback)
                        assertNull(requireNotNull(baseline).narration.speakingId)
                    }
                    fun changeImage(contrast: Float, brightness: Float, action: () -> Unit) {
                        val before = checkpoint(activity, renderedFrames)
                        action()
                        awaitSubmitted(activity, contrast, brightness, requireNotNull(baseline), renderedFrames, before) {
                            assertEquals("An image submission triggered actual synthesis", listOf(preview), observed())
                        }
                    }
                    fun assertPreserved(expected: ImageSettings) {
                        scenario.onActivity { current ->
                            assertEquals(expected, current.session.imageCommands.snapshot().context.requested)
                            assertEquals("An expert image action changed voice, transport, audio, preferences or screen brightness",
                                baseline, state(current))
                            assertEquals("An expert image action triggered actual synthesis", listOf(preview), observed())
                            assertNull(current.session.player.playerError)
                        }
                    }
                    fun press(id: Int) = onView(withId(id)).inRoot(isDialog()).perform(scrollTo(), click())
                    fun reportContains(id: Int, value: String) {
                        onView(withId(id)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
                            .check { view, missing ->
                                if (missing != null) throw missing
                                assertTrue("The visible report did not explain $value",
                                    (view as TextView).text.toString().contains(value, ignoreCase = true))
                            }
                    }
                    fun enter(id: Int, value: String) {
                        press(id)
                        var editorOpen = true
                        Closeable {
                            if (editorOpen) onView(withText("Annuler")).inRoot(isDialog()).perform(click())
                        }.use {
                            onView(withId(R.id.image_adjustment_input)).inRoot(isDialog())
                                .perform(replaceText(value), closeSoftKeyboard())
                            onView(withText("Appliquer")).inRoot(isDialog()).perform(click())
                            editorOpen = false
                        }
                    }

                    reportContains(R.id.image_contrast_value, "0,00")
                    captureExpertScreenshot("expert-image-parameters")
                    press(R.id.image_contrast_edit)
                    var invalidEditorOpen = true
                    Closeable {
                        if (invalidEditorOpen) onView(withText("Annuler")).inRoot(isDialog()).perform(click())
                    }.use {
                        onView(withId(R.id.image_adjustment_input)).inRoot(isDialog())
                            .perform(replaceText("0,3000000001"), closeSoftKeyboard())
                        onView(withText("Appliquer")).inRoot(isDialog()).perform(click())
                        onView(withId(R.id.image_adjustment_input)).inRoot(isDialog())
                            .check(matches(withText("0,3000000001")))
                        onView(withId(R.id.image_adjustment_error)).inRoot(isDialog())
                            .check(matches(isDisplayed())).check { view, missing ->
                                if (missing != null) throw missing
                                assertTrue("The invalid number has no visible explanation", (view as TextView).text.isNotBlank())
                            }
                        assertPreserved(ImageSettings())
                        captureExpertScreenshot("expert-image-invalid-number")
                        changeImage(.125f, 0f) {
                            onView(withId(R.id.image_adjustment_input)).inRoot(isDialog())
                                .perform(replaceText("+0,125"), closeSoftKeyboard())
                            onView(withText("Appliquer")).inRoot(isDialog()).perform(click())
                            invalidEditorOpen = false
                        }
                    }
                    assertPreserved(ImageSettings(.125f, 0f))
                    reportContains(R.id.image_contrast_value, "0,125")

                    changeImage(.125f, -.175f) { enter(R.id.image_brightness_edit, "−0.175") }
                    assertPreserved(ImageSettings(.125f, -.175f))
                    changeImage(.135f, -.175f) { press(R.id.image_contrast_increase) }
                    assertPreserved(ImageSettings(.135f, -.175f))
                    changeImage(.135f, -.185f) { press(R.id.image_brightness_decrease) }
                    assertPreserved(ImageSettings(.135f, -.185f))
                    press(R.id.image_adjustment_refresh)
                    reportContains(R.id.image_adjustment_requested, "0,135")
                    reportContains(R.id.image_adjustment_submitted, "0,135")
                    assertPreserved(ImageSettings(.135f, -.185f))

                    changeImage(0f, 0f) { press(R.id.image_original_comparison) }
                    onView(withId(R.id.image_original_comparison)).inRoot(isDialog()).check(matches(isChecked()))
                    assertPreserved(ImageSettings(.135f, -.185f, true))
                    press(R.id.image_contrast_increase)
                    assertPreserved(ImageSettings(.145f, -.185f, true))
                    press(R.id.image_adjustment_refresh)
                    reportContains(R.id.image_adjustment_status, "comparaison")
                    reportContains(R.id.image_adjustment_requested, "0,145")
                    reportContains(R.id.image_adjustment_submitted, "0,00")
                    captureExpertScreenshot("expert-image-comparison-report")
                    press(R.id.image_adjustments_reset)
                    assertPreserved(ImageSettings(0f, 0f, true))
                    onView(withId(R.id.image_original_comparison)).inRoot(isDialog())
                        .perform(scrollTo()).check(matches(isChecked()))
                    changeImage(0f, 0f) { press(R.id.image_original_comparison) }
                    assertPreserved(ImageSettings())
                    onView(withId(R.id.image_original_comparison)).inRoot(isDialog()).check(matches(not(isChecked())))

                    press(R.id.image_contrast_edit)
                    onView(withId(R.id.image_adjustment_input)).inRoot(isDialog())
                        .perform(replaceText("0,30"), closeSoftKeyboard())
                    onView(withText("Annuler")).inRoot(isDialog()).perform(click())
                    press(R.id.image_adjustment_refresh)
                    assertPreserved(ImageSettings())
                }
                scenario.onActivity { current -> current.session.previewVoice() }
                waitFor("The second explicit positive-control preview did not finish") {
                    observed().size >= 2 && activity.get().session.machine.snapshot().speakingId == null
                }
                instrument.waitForIdleSync()
                assertEquals("Only the two explicitly requested previews may reach the actual engine",
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
