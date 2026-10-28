package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.AudioCommandContext
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandPolicy
import fr.soustitresvoix.core.AudioCommandPort
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioCommandSnapshot
import fr.soustitresvoix.core.AudioDspObservation
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioDspSnapshot
import fr.soustitresvoix.core.AudioEqBand
import fr.soustitresvoix.core.AudioSettings
import fr.soustitresvoix.core.CapabilityStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Actual framework controls with the pure policy at a recording port. These
 * tests verify UI production and read-only presentation, never audible DSP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class AudioDspUiRobolectricTest {
    private class Port : AudioCommandPort {
        var context = AudioCommandContext(81, false, CapabilityStatus.CONDITIONAL,
            AudioSettings(.53f, .59f, 275, 875, 425, true), dspAvailability = CapabilityStatus.CONDITIONAL)
        var dsp = AudioDspSnapshot(0, context.requested.dsp, AudioDspObservation())
        val commands = mutableListOf<AudioCommandRequest>()
        override fun snapshot() = AudioCommandSnapshot(context, .53f, true, false, 12, dsp)
        override fun submit(request: AudioCommandRequest): AudioCommandDecision {
            commands += request
            return AudioCommandPolicy.validate(request, context).also { decision ->
                if (decision is AudioCommandDecision.Accepted) {
                    context = context.copy(requested = decision.requested)
                    if (decision.changed) dsp = dsp.copy(submittedRevision = dsp.submittedRevision + 1,
                        submittedSettings = decision.requested.dsp)
                }
            }
        }
    }

    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val port = Port()
        val host = LinearLayout(activity)
        val owner = LinearLayout(activity)
        val dialogs = mutableListOf<AlertDialog>()
        init {
            host.addView(owner)
            activity.setContentView(host)
            owner.addView(AudioDspControls.openButton(activity, owner, port, port.context.generation))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun open(): AlertDialog {
            owner.findViewById<Button>(R.id.audio_dsp_open).performClick()
            return latest()
        }
        fun child(parent: AlertDialog, id: Int): AlertDialog {
            requireNotNull(parent.findViewById<Button>(id)).performClick()
            return latest()
        }
        private fun latest(): AlertDialog {
            shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also {
                assertTrue(it.isShowing); dialogs += it
            }
        }
        override fun close() {
            dialogs.asReversed().forEach { it.dismiss() }
            controller.pause().stop().destroy()
        }
    }

    private fun apply(dialog: AlertDialog, value: String) {
        dialog.findViewById<EditText>(R.id.audio_numeric_input).setText(value)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun actualSettingsSoundEntryOpensExpertPanelsAndClosingSettingsDismissesTheirEditors() {
        RuntimeEnvironment.getApplication().getSharedPreferences("player", Activity.MODE_PRIVATE).edit()
            .clear().putBoolean("narration", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val dialogs = mutableListOf<AlertDialog>()
        fun openChild(parent: AlertDialog, buttonId: Int): AlertDialog {
            requireNotNull(parent.findViewById<Button>(buttonId)).performClick()
            // In PAUSED mode, show() does not finish attaching the new window.
            // Nested audio controls deliberately reject clicks before attachment.
            shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { child ->
                assertNotSame("The clicked control must open a new child dialog", parent, child)
                assertTrue(child.isShowing)
                assertTrue(requireNotNull(child.window).decorView.isAttachedToWindow)
                dialogs += child
            }
        }
        try {
            val activity = controller.get()
            val original = activity.session.audioCommands.snapshot().context.requested
            descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == "Réglages de la voix et du confort"
            }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val settings = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { dialogs += it }
            val panel = descendants(requireNotNull(settings.window).decorView).filterIsInstance<SettingsPanel>().single()
            panel.select(SettingsSection.SOUND)
            assertTrue(panel.isAttachedToWindow)
            val expert = openChild(settings, R.id.audio_dsp_open)
            assertTrue(settings.isShowing)
            assertEquals(original, activity.session.audioCommands.snapshot().context.requested)
            val band = openChild(expert, R.id.audio_dsp_band_3)
            val editor = openChild(band, R.id.audio_dsp_gain_edit)
            requireNotNull(editor.findViewById<EditText>(R.id.audio_numeric_input)).setText("2,5")
            settings.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            for (dialog in listOf(settings, expert, band, editor)) assertFalse(dialog.isShowing)
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(original, activity.session.audioCommands.snapshot().context.requested)
            assertFalse(activity.session.prefs.narration)
            assertFalse(activity.session.player.playWhenReady)
            assertNull(activity.session.machine.snapshot().speakingId)
            assertEquals(0, activity.session.machine.snapshot().pendingCount)
        } finally {
            dialogs.asReversed().forEach { it.dismiss() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun mainControlsKeepDefaultsInactiveAndApplyAllPreciseValuesWithReadOnlyRefresh() {
        Fixture().use { f ->
            val original = f.port.context.requested
            val panel = f.open()
            assertFalse(panel.findViewById<CheckBox>(R.id.audio_dsp_enabled).isChecked)
            assertFalse(panel.findViewById<CheckBox>(R.id.audio_dsp_mono).isChecked)
            assertTrue(panel.findViewById<CheckBox>(R.id.audio_dsp_limiter_enabled).isChecked)
            assertTrue(f.port.commands.isEmpty())
            for ((id, text) in listOf(R.id.audio_dsp_preamp_edit to "-3,125", R.id.audio_dsp_balance_edit to "-37,5",
                R.id.audio_dsp_ceiling_edit to "-2,75", R.id.audio_dsp_release_edit to "275")) {
                val editor = f.child(panel, id)
                apply(editor, text)
                assertFalse(editor.isShowing)
            }
            panel.findViewById<CheckBox>(R.id.audio_dsp_mono).performClick()
            panel.findViewById<CheckBox>(R.id.audio_dsp_enabled).performClick()
            assertEquals(original.copy(dsp = AudioDspSettings(enabled = true, preampDb = -3.125,
                balance = -.375, mono = true, limiterCeilingDb = -2.75, limiterReleaseMs = 275)), f.port.context.requested)
            assertEquals(6, f.port.commands.size)
            val count = f.port.commands.size
            panel.findViewById<Button>(R.id.audio_dsp_refresh).performClick()
            assertEquals(count, f.port.commands.size)
            assertTrue(panel.findViewById<TextView>(R.id.audio_dsp_state).text.contains("n’a pas encore été observée comme consommée"))
            assertTrue(f.port.commands.all { it.generation == 81L })
        }
    }

    @Test fun everyEqualizerBandEditsFrequencyGainAndQWithoutChangingItsNeighbours() {
        Fixture().use { f ->
            val panel = f.open()
            val ids = intArrayOf(R.id.audio_dsp_band_1, R.id.audio_dsp_band_2, R.id.audio_dsp_band_3,
                R.id.audio_dsp_band_4, R.id.audio_dsp_band_5)
            var expected = AudioDspSettings()
            for (index in 0 until 5) {
                val band = f.child(panel, ids[index])
                val frequency = 75.5 + index * 1000
                apply(f.child(band, R.id.audio_dsp_frequency_edit), frequency.toString().replace('.', ','))
                apply(f.child(band, R.id.audio_dsp_gain_edit), "2,75")
                apply(f.child(band, R.id.audio_dsp_q_edit), "0,75")
                band.findViewById<CheckBox>(R.id.audio_dsp_band_enabled).performClick()
                expected = expected.withBand(index, AudioEqBand(true, frequency, 2.75, .75))
                assertEquals(expected, f.port.context.requested.dsp)
                assertFalse("Band selection must not enable the global processor", f.port.context.requested.dsp.enabled)
                band.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(panel.findViewById<Button>(ids[index]).text.contains("sélectionnée"))
            }
            assertEquals(20, f.port.commands.size)
            assertEquals(.53f, f.port.context.requested.movieVolume, 0f)
            assertEquals(.59f, f.port.context.requested.duckVolume, 0f)
        }
    }

    @Test fun invalidDecimalBoundariesRemainInTheEditorAndCancellationMakesNoRequest() {
        Fixture().use { f ->
            val panel = f.open()
            val original = f.port.context.requested
            val editor = f.child(panel, R.id.audio_dsp_preamp_edit)
            for (value in listOf("12.000000000000000001", "-24.00000000000000001", "NaN", "6 dB", "1e1")) {
                apply(editor, value)
                assertTrue(editor.isShowing)
                assertEquals(value, editor.findViewById<EditText>(R.id.audio_numeric_input).text.toString())
                assertEquals(View.VISIBLE, editor.findViewById<TextView>(R.id.audio_numeric_error).visibility)
                assertTrue(f.port.commands.isEmpty())
                assertEquals(original, f.port.context.requested)
            }
            editor.findViewById<EditText>(R.id.audio_numeric_input).setText("-3,5")
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(editor.isShowing)
            assertTrue(f.port.commands.isEmpty())
            val band = f.child(panel, R.id.audio_dsp_band_1)
            val q = f.child(band, R.id.audio_dsp_q_edit)
            apply(q, "0.099999999999999999")
            assertTrue(q.isShowing)
            assertTrue(f.port.commands.isEmpty())
        }
    }

    @Test fun nestedPanelsNeverRenewTheirMediaTicketAndUnavailableActivationRestoresCheckbox() {
        Fixture().use { f ->
            val panel = f.open()
            val original = f.port.context.requested
            f.port.context = f.port.context.copy(dspAvailability = CapabilityStatus.UNAVAILABLE)
            panel.findViewById<CheckBox>(R.id.audio_dsp_enabled).performClick()
            assertFalse(panel.findViewById<CheckBox>(R.id.audio_dsp_enabled).isChecked)
            assertTrue(panel.findViewById<TextView>(R.id.audio_dsp_result).text.contains("indisponible"))
            f.port.context = f.port.context.copy(generation = 82, dspAvailability = CapabilityStatus.CONDITIONAL)
            panel.findViewById<Button>(R.id.audio_dsp_refresh).performClick()
            val band = f.child(panel, R.id.audio_dsp_band_3)
            val input = f.child(band, R.id.audio_dsp_frequency_edit)
            apply(input, "800,5")
            assertTrue(input.isShowing)
            assertTrue(input.findViewById<TextView>(R.id.audio_numeric_error).text.contains("autre ouverture"))
            assertEquals(original, f.port.context.requested)
            assertTrue(f.port.commands.all { it.generation == 81L })
            f.port.context = f.port.context.copy(closed = true)
            apply(input, "800,5")
            assertTrue(input.findViewById<TextView>(R.id.audio_numeric_error).text.contains("fermée"))
            assertEquals(original, f.port.context.requested)
        }
    }

    @Test fun targetedResetsAndGroupResetPreserveOtherFilmControls() {
        Fixture().use { f ->
            val otherFilm = f.port.context.requested
            val first = AudioEqBand(true, 77.5, 4.5, 2.5)
            val second = AudioEqBand(true, 555.5, -2.5, .7)
            val initial = AudioDspSettings(enabled = true, preampDb = -5.5, balance = .25, mono = true,
                limiterEnabled = false, limiterCeilingDb = -3.5, limiterReleaseMs = 375, band1 = first, band2 = second)
            f.port.context = f.port.context.copy(requested = otherFilm.copy(dsp = initial))
            val panel = f.open()
            val editor = f.child(panel, R.id.audio_dsp_preamp_edit)
            f.port.context = f.port.context.copy(requested = f.port.context.requested.copy(dsp = initial.copy(balance = -.35)))
            apply(editor, "-7,5")
            assertEquals(-.35, f.port.context.requested.dsp.balance, 0.0)
            panel.findViewById<Button>(R.id.audio_dsp_preamp_reset).performClick()
            assertEquals(0.0, f.port.context.requested.dsp.preampDb, 0.0)
            assertEquals(first, f.port.context.requested.dsp.band1)
            val band = f.child(panel, R.id.audio_dsp_band_1)
            band.findViewById<Button>(R.id.audio_dsp_gain_reset).performClick()
            assertEquals(first.copy(gainDb = 0.0), f.port.context.requested.dsp.band1)
            band.findViewById<Button>(R.id.audio_dsp_band_reset).performClick()
            assertEquals(AudioDspSettings().band1, f.port.context.requested.dsp.band1)
            assertEquals(second, f.port.context.requested.dsp.band2)
            band.dismiss()
            panel.findViewById<Button>(R.id.audio_dsp_reset).performClick()
            assertEquals(otherFilm, f.port.context.requested)
        }
    }

    @Test fun observationsDistinguishSubmissionConsumptionTransitionAndThePreviousPcmStream() {
        Fixture().use { f ->
            val target = AudioDspSettings(enabled = true, preampDb = -3.0)
            val consumed = target.copy(preampDb = -6.0)
            f.port.dsp = AudioDspSnapshot(7, target, AudioDspObservation(sampleRate = 16000, channelCount = 2,
                compatible = true, consumedRevision = 3, consumedSettings = consumed, appliedSettings = target,
                transitioning = true, skippedBandMask = 16, processedFrames = 100, limitedFrames = 5,
                clippedSamples = 2, outputPeak = .5))
            val panel = f.open()
            val state = panel.findViewById<TextView>(R.id.audio_dsp_state).text.toString()
            for (expected in listOf("Révision soumise : 7", "révision consommée : 3", "pas encore", "16000 Hz",
                "Transition de traitement observée : oui", "Bandes ignorées dans la cible observée : 5", "100 trames PCM",
                "2 échantillons écrêtés après arrondi ou invalides")) {
                assertTrue("Missing <$expected> in <$state>", state.contains(expected))
            }
            assertTrue(state.contains("-6,02 dBFS"))
            assertTrue(state.contains("flux précédent"))
            f.port.dsp = f.port.dsp.copy(observation = f.port.dsp.observation.copy(outputPeak = 0.0,
                consumedRevision = 7, transitioning = false, skippedBandMask = 0))
            panel.findViewById<Button>(R.id.audio_dsp_refresh).performClick()
            val refreshed = panel.findViewById<TextView>(R.id.audio_dsp_state).text.toString()
            assertTrue(refreshed.contains("silence numérique"))
            assertTrue(refreshed.contains("Transition de traitement observée : non"))
            assertFalse(refreshed.contains("n’a pas encore"))
            assertTrue(f.port.commands.isEmpty())
        }
    }

    @Test fun dismissingOriginalOwnerClosesEveryNestedPanelAndPreventsDelayedWrites() {
        Fixture().use { f ->
            val panel = f.open()
            val band = f.child(panel, R.id.audio_dsp_band_1)
            val editor = f.child(band, R.id.audio_dsp_gain_edit)
            editor.findViewById<EditText>(R.id.audio_numeric_input).setText("3,5")
            f.host.removeView(f.owner)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(panel.isShowing)
            assertFalse(band.isShowing)
            assertFalse(editor.isShowing)
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            band.findViewById<CheckBox>(R.id.audio_dsp_band_enabled).performClick()
            panel.findViewById<Button>(R.id.audio_dsp_reset).performClick()
            assertTrue(f.port.commands.isEmpty())
        }
    }

    @Test fun largeFontKeepsBandButtonsUnabridgedAndHelpScrollable() {
        val oldQualifiers = RuntimeEnvironment.getQualifiers()
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp")
            RuntimeEnvironment.setFontScale(2f)
            Fixture().use { f ->
                val panel = f.open()
                val view = panel.findViewById<LinearLayout>(R.id.audio_dsp_controls)
                val scroll = view.parent as ScrollView
                assertTrue(view.height > scroll.height)
                val lastBand = panel.findViewById<Button>(R.id.audio_dsp_band_5)
                assertNull(lastBand.ellipsize)
                assertTrue(lastBand.maxLines > 1)
                assertTrue(lastBand.text.contains("12000 Hz"))
                val band = f.child(panel, R.id.audio_dsp_band_5)
                val bandView = band.findViewById<LinearLayout>(R.id.audio_dsp_band_controls)
                assertTrue(bandView.height > (bandView.parent as ScrollView).height)
                val texts = descendants(bandView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.any { it.contains("moitié de la fréquence d’échantillonnage") })
                assertTrue(texts.any { it.contains("Q élevé") && it.contains("bande plus étroite") })
                assertTrue(band.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
                assertTrue(f.port.commands.isEmpty())
            }
        } finally {
            RuntimeEnvironment.setFontScale(oldScale)
            RuntimeEnvironment.setQualifiers(oldQualifiers)
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
