package fr.soustitresvoix.player

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withSpinnerText
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioDspSnapshot
import fr.soustitresvoix.core.AudioEqBand
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matcher

/** Actual Media3 decoding and installed processor, using only a generated stereo WAV.
 * Output observations prove numerical processing, not speaker/headphone acoustics.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExpertAudioAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", false).putInt("orientation", 0)
            .putString("engine", instrument.context.packageName).putString("voiceName", "fixture-fr").commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun syntheticStereo(): File {
        val sampleRate = 48_000
        val frames = sampleRate * 24
        val dataBytes = frames * 4
        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        output.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        output.putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (frame in 0 until frames) {
            val wave = sin(2 * PI * 440 * frame / sampleRate)
            output.putShort((6000 * wave).toInt().toShort())
            output.putShort((3000 * wave).toInt().toShort())
        }
        return File(instrument.targetContext.cacheDir, "expert-audio-synthetic.wav").also { it.writeBytes(output.array()) }
    }

    @Test fun settingsSoundSectionOpensExpertAudioAndCancelRestoresItsEdits() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original = AudioDspSettings()
            scenario.onActivity { original = it.session.audioCommands.snapshot().context.requested.dsp }
            onView(withText("Réglages de la voix et du confort")).perform(scrollTo(), click())
            onView(withContentDescription("Choisir une rubrique de réglages"))
                .inRoot(isDialog()).perform(scrollTo(), click())
            // The native Spinner opens a PopupWindow above the settings dialog.
            // Its rows must be matched in that root, not the unfocused activity.
            onData(equalTo("Son")).inRoot(isPlatformPopup()).perform(click())
            onView(withContentDescription("Choisir une rubrique de réglages"))
                .inRoot(isDialog()).check(matches(withSpinnerText("Son")))
            onView(withId(R.id.audio_dsp_open)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.audio_dsp_enabled)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.audio_dsp_band_3)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.audio_dsp_band_controls)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.audio_dsp_gain_edit)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.audio_numeric_input)).inRoot(isDialog())
                .check(matches(isDisplayed())).perform(replaceText("2,5"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            scenario.onActivity {
                assertEquals(original.withBand(2, original.band(2).copy(gainDb = 2.5)),
                    it.session.audioCommands.snapshot().context.requested.dsp)
                assertFalse(it.session.prefs.narration)
                assertFalse(it.session.player.playWhenReady)
            }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.audio_dsp_open)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Annuler modifications")).inRoot(isDialog()).perform(click())
            scenario.onActivity {
                assertEquals(original, it.session.audioCommands.snapshot().context.requested.dsp)
                assertFalse(it.session.prefs.narration)
                assertFalse(it.session.player.playWhenReady)
                assertNull(it.session.machine.snapshot().speakingId)
                assertEquals(0, it.session.machine.snapshot().pendingCount)
            }
        }
    }

    @Test fun installedProcessorChangesPcmAndRestoresBaselineWithoutChangingTransport() {
        val file = syntheticStereo()
        val activity = AtomicReference<MainActivity>()
        fun state(): AudioDspSnapshot = requireNotNull(activity.get().session.audioCommands.snapshot().dsp)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.open(SavedMedia(Uri.fromFile(file).toString(), "Synthetic stereo DSP validation"))
                }
                waitFor("Synthetic stereo source was not ready") {
                    activity.get().session.player.playbackState == Player.STATE_READY
                }
                var processor: MovieDsp? = null
                scenario.onActivity { current -> processor = current.session.movieAudio.dsp; current.session.play() }
                waitFor("PCM16 frames did not reach the installed expert processor") {
                    state().observation.processedFrames > 4096 && state().observation.outputPeak != null
                }
                var baseline = 0.0
                scenario.onActivity {
                    val snapshot = state()
                    assertEquals(true, snapshot.observation.compatible)
                    assertEquals(48_000, snapshot.observation.sampleRate)
                    assertEquals(2, snapshot.observation.channelCount)
                    assertEquals(AudioDspSettings(), snapshot.observation.consumedSettings)
                    baseline = requireNotNull(snapshot.observation.outputPeak)
                    assertEquals(6000.0 / 32768.0, baseline, 2.0 / 32768.0)
                }

                fun apply(settings: AudioDspSettings): Long {
                    var revision = -1L
                    scenario.onActivity { current ->
                        val session = current.session
                        val narration = session.machine.snapshot()
                        val queue = session.machine.pendingTexts()
                        val playing = session.player.playWhenReady
                        val requested = session.audioCommands.snapshot().context
                        val result = session.audioCommands.submit(AudioCommandRequest(requested.generation, AudioCommand.SetDsp(settings)))
                        assertTrue(result is AudioCommandDecision.Accepted)
                        assertEquals(narration, session.machine.snapshot())
                        assertEquals(queue, session.machine.pendingTexts())
                        assertEquals(playing, session.player.playWhenReady)
                        assertTrue(playing)
                        assertSame(processor, session.movieAudio.dsp)
                        revision = state().submittedRevision
                    }
                    return revision
                }
                fun settle(revision: Long) {
                    waitFor("Submitted DSP revision was not consumed and settled") {
                        val observed = state().observation
                        observed.consumedRevision == revision && !observed.transitioning
                    }
                    // Exclude the block that contained the crossfade from the peak oracle.
                    var settledFrames = 0L
                    instrument.runOnMainSync { settledFrames = state().observation.processedFrames }
                    waitFor("No complete block arrived after the DSP transition") {
                        state().observation.processedFrames > settledFrames + 4096
                    }
                }
                val halfGain = AudioDspSettings(enabled = true, preampDb = -6.020599913279624, limiterEnabled = false)
                settle(apply(halfGain))
                scenario.onActivity {
                    assertEquals(baseline / 2, requireNotNull(state().observation.outputPeak), 2.0 / 32768.0)
                    assertEquals(0L, state().observation.clippedSamples)
                }
                val limited = AudioDspSettings(enabled = true, limiterCeilingDb = -12.0,
                    band3 = AudioEqBand(true, 440.0, 6.020599913279624, 1.0))
                settle(apply(limited))
                scenario.onActivity {
                    val observed = state().observation
                    assertTrue(observed.limitedFrames > 0)
                    assertTrue(requireNotNull(observed.outputPeak) <= 8230.0 / 32768.0)
                    assertTrue(requireNotNull(observed.outputPeak) > baseline)
                    assertEquals(0L, observed.clippedSamples)
                    assertEquals(0, observed.skippedBandMask)
                }
                settle(apply(limited.copy(enabled = false)))
                scenario.onActivity { current ->
                    assertEquals(baseline, requireNotNull(state().observation.outputPeak), 1.0 / 32768.0)
                    assertFalse(state().observation.transitioning)
                    assertTrue(current.session.player.playWhenReady)
                    assertNull(current.session.machine.snapshot().speakingId)
                    assertEquals(0, current.session.machine.snapshot().pendingCount)
                    assertSame(processor, current.session.movieAudio.dsp)
                    current.session.pause()
                }
            }
        } finally { file.delete() }
    }

    @Test fun preciseAndExpertControlsKeepErrorsCancellationAndOriginalTicketWithoutRequestingSpeech() {
        val context = instrument.targetContext
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("narration", true).commit()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == FixtureSpeechService.ALL_REQUESTS_ACTION)
                    intent.getStringExtra("text")?.let { requests += it }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION),
            ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = AtomicReference<MainActivity>()
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.open(SavedMedia("android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Synthetic expert audio UI fixture"))
                    current.setFullscreen(true)
                }
                waitFor("Paused demonstration and fixture TTS did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady
                }
                fun press(id: Int) = onView(withId(id)).inRoot(isDialog()).perform(scrollTo(), click())
                fun type(value: String) = onView(withId(R.id.audio_numeric_input)).inRoot(isDialog())
                    .perform(replaceText(value), closeSoftKeyboard())
                fun applyNumber(id: Int, value: String) {
                    press(id); type(value)
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                }
                onView(withText("Volumes rapides")).perform(scrollTo(), click())
                press(R.id.audio_movie_edit)
                type("100,0000000001")
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                onView(withId(R.id.audio_numeric_error)).inRoot(isDialog()).perform(scrollTo())
                    .check(matches(isDisplayed())).check(matches(withText(containsString("100"))))
                onView(withId(R.id.audio_numeric_input)).inRoot(isDialog()).check(matches(withText("100,0000000001")))
                screenshot("expert-audio-precise-invalid")
                scenario.onActivity { assertEquals(1f, it.session.prefs.movieVolume, 0f) }
                type("37,5")
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                applyNumber(R.id.audio_attack_edit, "275")
                scenario.onActivity {
                    assertEquals(.375f, it.session.prefs.movieVolume, 0f)
                    assertEquals(275, it.session.audioCommands.snapshot().context.requested.attackMs)
                }
                press(R.id.audio_dsp_open)
                press(R.id.audio_dsp_enabled)
                onView(withId(R.id.audio_dsp_enabled)).inRoot(isDialog()).perform(scrollTo())
                screenshot("expert-dsp-overview")
                applyNumber(R.id.audio_dsp_preamp_edit, "-7,5")
                applyNumber(R.id.audio_dsp_balance_edit, "-25,5")
                press(R.id.audio_dsp_mono)
                applyNumber(R.id.audio_dsp_ceiling_edit, "-3.5")
                applyNumber(R.id.audio_dsp_release_edit, "275")
                press(R.id.audio_dsp_band_3)
                press(R.id.audio_dsp_band_enabled)
                applyNumber(R.id.audio_dsp_frequency_edit, "1250,25")
                applyNumber(R.id.audio_dsp_gain_edit, "3.75")
                applyNumber(R.id.audio_dsp_q_edit, "1,25")
                press(R.id.audio_dsp_gain_edit)
                type("9")
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                onView(withId(R.id.audio_dsp_band_enabled)).inRoot(isDialog()).perform(scrollTo())
                screenshot("expert-dsp-band")
                var retained = AudioDspSettings()
                scenario.onActivity { current ->
                    retained = current.session.audioCommands.snapshot().context.requested.dsp
                    assertEquals(AudioDspSettings(enabled = true, preampDb = -7.5, balance = -.255, mono = true,
                        limiterCeilingDb = -3.5, limiterReleaseMs = 275,
                        band3 = AudioEqBand(true, 1250.25, 3.75, 1.25)), retained)
                    assertFalse(current.session.player.playWhenReady)
                    assertNull(current.session.machine.snapshot().speakingId)
                    assertTrue(requests.isEmpty())
                }
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                press(R.id.audio_dsp_refresh)
                onView(withId(R.id.audio_dsp_state)).inRoot(isDialog()).perform(revealStart())
                    .check(matches(isDisplayed())).check(matches(withText(containsString("Révision soumise"))))
                screenshot("expert-dsp-observations")
                press(R.id.audio_dsp_preamp_edit)
                type("-9")
                scenario.onActivity { it.session.open(requireNotNull(it.session.media)) }
                waitFor("Reopened source was not ready for stale dialog check") {
                    activity.get().session.player.playbackState == Player.STATE_READY
                }
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                onView(withId(R.id.audio_numeric_error)).inRoot(isDialog()).perform(scrollTo())
                    .check(matches(withText(containsString("autre ouverture"))))
                onView(withId(R.id.audio_numeric_input)).inRoot(isDialog()).check(matches(withText("-9")))
                screenshot("expert-dsp-stale-editor")
                scenario.onActivity { current ->
                    assertEquals(retained, current.session.audioCommands.snapshot().context.requested.dsp)
                    assertFalse(current.session.player.playWhenReady)
                    assertNull(current.session.machine.snapshot().speakingId)
                    assertEquals(0, current.session.machine.snapshot().pendingCount)
                    assertTrue(requests.isEmpty())
                }
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                instrument.waitForIdleSync()
                assertTrue("Editing film audio caused an actual TTS synthesis request", requests.isEmpty())
            }
        } finally { context.unregisterReceiver(receiver) }
    }

    /** The report is taller than the viewport. ScrollToAction requires nearly
     * the whole view to be visible, so reveal its first paragraph explicitly.
     * Assertions still inspect the entire report text and actual visibility.
     */
    private fun revealStart(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
        override fun getDescription() = "Reveal the start of a scrollable audio observation report"
        override fun perform(uiController: UiController, view: View) {
            val visibleHeight = minOf(view.height, (160 * view.resources.displayMetrics.density).toInt())
            view.requestRectangleOnScreen(Rect(0, 0, view.width, visibleHeight), true)
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        for (command in listOf("mkdir -p /sdcard/Download/cinema03-tests",
            "screencap -p /sdcard/Download/cinema03-tests/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
    }
}
