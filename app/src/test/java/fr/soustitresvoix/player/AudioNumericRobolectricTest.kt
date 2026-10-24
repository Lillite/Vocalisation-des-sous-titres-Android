package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import fr.soustitresvoix.core.AudioCommandContext
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandPolicy
import fr.soustitresvoix.core.AudioCommandPort
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioCommandSnapshot
import fr.soustitresvoix.core.AudioSettings
import fr.soustitresvoix.core.CapabilityStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.RobolectricTestRunner

/** Real Android controls under Robolectric, with a recording port and the real
 * pure command policy. Playback and actual TTS IPC are tested in the session and
 * device suites; this fixture proves presentation and command production only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioNumericRobolectricTest {
    private class RecordingPort : AudioCommandPort {
        var context = AudioCommandContext(41, false, CapabilityStatus.CONDITIONAL, AudioSettings(.6f, .25f))
        val commands = mutableListOf<AudioCommandRequest>()
        override fun snapshot() = AudioCommandSnapshot(context, context.requested.movieVolume,
            context.requested.spectralDucking, false, 0)
        override fun submit(request: AudioCommandRequest): AudioCommandDecision {
            commands += request
            return AudioCommandPolicy.validate(request, context).also { decision ->
                if (decision is AudioCommandDecision.Accepted) context = context.copy(requested = decision.requested)
            }
        }
    }

    private class Fixture : AutoCloseable {
        val controller: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity: Activity = controller.get()
        val port = RecordingPort()
        val controls = AudioSettingsControls(activity, port)
        val scroll = ScrollView(activity).apply { addView(controls.view) }
        init { activity.setContentView(scroll); shadowOf(Looper.getMainLooper()).idle() }
        fun edit(id: Int): Pair<AlertDialog, EditText> {
            assertTrue(requireNotNull(controls.view.findViewById<Button>(id)).performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(dialog.isShowing)
            return dialog to requireNotNull(dialog.findViewById(R.id.audio_numeric_input))
        }
        override fun close() {
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    private fun apply(dialog: AlertDialog, input: EditText, text: String) {
        input.setText(text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun allFiveEditorsApplyExactValuesAndPreserveOtherFieldsAndTheOriginalTicket() {
        Fixture().use { f ->
            var expected = f.port.context.requested
            val edits = listOf(
                Triple(R.id.audio_movie_edit, "37,5", expected.copy(movieVolume = .375f)),
                Triple(R.id.audio_duck_edit, "19.125", expected.copy(movieVolume = .375f, duckVolume = .19125f)),
                Triple(R.id.audio_attack_edit, "275", expected.copy(movieVolume = .375f, duckVolume = .19125f, attackMs = 275)),
                Triple(R.id.audio_release_edit, "875", expected.copy(movieVolume = .375f, duckVolume = .19125f, attackMs = 275, releaseMs = 875)),
                Triple(R.id.audio_hold_edit, "425", expected.copy(movieVolume = .375f, duckVolume = .19125f, attackMs = 275, releaseMs = 875, holdMs = 425))
            )
            for ((id, text, settings) in edits) {
                val (dialog, input) = f.edit(id)
                val label = requireNotNull(dialog.findViewById<TextView>(R.id.audio_numeric_label))
                assertEquals(input.id, label.labelFor)
                assertTrue(label.text.contains("Plage"))
                apply(dialog, input, text)
                assertFalse(dialog.isShowing)
                expected = settings
                assertEquals(expected, f.port.context.requested)
            }
            assertEquals(5, f.port.commands.size)
            assertTrue(f.port.commands.all { it.generation == 41L })
            assertEquals("Volume du film : 37,5 %", f.controls.view.findViewById<TextView>(R.id.audio_movie_value).text.toString())
            assertEquals("Son du film pendant la voix : 19,125 %", f.controls.view.findViewById<TextView>(R.id.audio_duck_value).text.toString())
            val (reopened, input) = f.edit(R.id.audio_movie_edit)
            assertEquals("37,5", input.text.toString())
            reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(reopened.isShowing)
            assertEquals(5, f.port.commands.size)
        }
    }

    @Test fun rejectedNumericTextRemainsVisibleWithoutSubmittingAndCancelKeepsSettings() {
        Fixture().use { f ->
            val original = f.port.context.requested
            val (dialog, input) = f.edit(R.id.audio_movie_edit)
            for (text in listOf("100.000000000000000001", "-0.000000000000000001", "37,5 %", "1e2", "NaN", "1 000", "9".repeat(25))) {
                apply(dialog, input, text)
                assertTrue("Invalid input must keep the editor open", dialog.isShowing)
                assertEquals(text, input.text.toString())
                val error = requireNotNull(dialog.findViewById<TextView>(R.id.audio_numeric_error))
                assertEquals(View.VISIBLE, error.visibility)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, error.accessibilityLiveRegion)
                assertTrue(error.text.contains("refusée"))
                assertTrue(f.port.commands.isEmpty())
                assertEquals(original, f.port.context.requested)
                f.controls.refresh()
                assertEquals(text, input.text.toString())
            }
            input.setText("37,5")
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            // AlertController posts the standard negative-button dismissal to
            // its Handler. The paused test looper must execute that UI action.
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(f.port.commands.isEmpty())
            assertEquals(original, f.port.context.requested)
        }
    }

    @Test fun integerErrorsCanBeCorrectedAndImeAppliesOnlyOneCommand() {
        Fixture().use { f ->
            val (dialog, input) = f.edit(R.id.audio_attack_edit)
            for (text in listOf("275,5", "275.0", "2001", "-1")) {
                apply(dialog, input, text)
                assertTrue(dialog.isShowing)
                assertTrue(f.port.commands.isEmpty())
            }
            input.setText("275")
            input.onEditorAction(EditorInfo.IME_ACTION_DONE)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals(275, f.port.context.requested.attackMs)
            assertEquals(1, f.port.commands.size)
            // A queued action arriving after the dialog has closed is harmless.
            input.onEditorAction(EditorInfo.IME_ACTION_DONE)
            assertEquals(1, f.port.commands.size)
        }
    }

    @Test fun stalePanelAndClosedSessionRefuseEditorsWithoutRenewingTheTicket() {
        Fixture().use { f ->
            val original = f.port.context.requested
            val (dialog, input) = f.edit(R.id.audio_movie_edit)
            f.port.context = f.port.context.copy(generation = 42)
            f.controls.refresh()
            apply(dialog, input, "37,5")
            assertTrue(dialog.isShowing)
            assertEquals("37,5", input.text.toString())
            val refusal = dialog.findViewById<TextView>(R.id.audio_numeric_error).text
            assertTrue(refusal.contains("autre ouverture"))
            assertTrue(refusal.contains("Ferme les fenêtres de réglages jusqu’au lecteur, puis rouvre les réglages audio."))
            assertEquals(41L, f.port.commands.single().generation)
            assertEquals(original, f.port.context.requested)
            dialog.dismiss()
            // Even opening a new editor from the obsolete parent keeps ticket 41.
            val (later, laterInput) = f.edit(R.id.audio_hold_edit)
            apply(later, laterInput, "425")
            assertTrue(later.isShowing)
            assertEquals(41L, f.port.commands.last().generation)
            f.port.context = f.port.context.copy(closed = true)
            apply(later, laterInput, "425")
            assertTrue(later.findViewById<TextView>(R.id.audio_numeric_error).text.contains("fermée"))
            assertEquals(original, f.port.context.requested)
        }
    }

    @Test fun oneFieldCommitUsesLatestCompanionValuesAndQuickStepsAndResetsRemainAvailable() {
        Fixture().use { f ->
            val (dialog, input) = f.edit(R.id.audio_movie_edit)
            f.port.context = f.port.context.copy(requested = f.port.context.requested.copy(duckVolume = .18f, holdMs = 725))
            apply(dialog, input, "37,5")
            assertEquals(.18f, f.port.context.requested.duckVolume, 0f)
            assertEquals(725, f.port.context.requested.holdMs)
            val increase = descendants(f.controls.view).filterIsInstance<Button>().single {
                it.contentDescription?.toString() == "Volume du film : augmenter de cinq points de pourcentage"
            }
            increase.performClick()
            assertEquals(.43f, f.port.context.requested.movieVolume, 0f)
            f.controls.view.findViewById<Button>(R.id.audio_movie_reset).performClick()
            assertEquals(1f, f.port.context.requested.movieVolume, 0f)
            assertEquals(.18f, f.port.context.requested.duckVolume, 0f)
            assertEquals(725, f.port.context.requested.holdMs)
        }
    }

    @Test fun detachedOwnerClosesItsEditorAndDelayedActionsCannotWrite() {
        Fixture().use { f ->
            val (dialog, input) = f.edit(R.id.audio_movie_edit)
            input.setText("37,5")
            f.scroll.removeView(f.controls.view)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            input.onEditorAction(EditorInfo.IME_ACTION_DONE)
            assertTrue(f.port.commands.isEmpty())
            assertEquals(.6f, f.port.context.requested.movieVolume, 0f)
        }
    }

    // This assertion depends on actual glyph widths and line wrapping. Legacy
    // graphics shadows fake these metrics and cannot prove text accessibility.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun largeTextKeepsTheInputHelpAndPersistentErrorInsideScrollableContent() {
        val previous = RuntimeEnvironment.getQualifiers()
        val configuration = RuntimeEnvironment.getApplication().resources.configuration
        val oldScale = configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp")
            RuntimeEnvironment.setFontScale(2f)
            Fixture().use { f ->
                // The generic test Activity is not declared in the manifest;
                // create its editor with the same theme as MainActivity.
                f.activity.setTheme(R.style.AppTheme)
                val (dialog, input) = f.edit(R.id.audio_movie_edit)
                apply(dialog, input, "101")
                // Qualifiers and font scale configure resources; an explicit
                // window measurement also establishes the viewport under the
                // paused Robolectric looper before inspecting scroll geometry.
                val decor = requireNotNull(dialog.window).decorView
                val density = f.activity.resources.displayMetrics.density
                val width = (320 * density).toInt()
                val height = (480 * density).toInt()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
                val error = dialog.findViewById<TextView>(R.id.audio_numeric_error)
                val scroll = generateSequence(error.parent) { it.parent }.filterIsInstance<ScrollView>().first()
                val help = descendants(scroll).filterIsInstance<TextView>().single { it.text.contains("Annuler conserve") }
                val layout = requireNotNull(help.layout)
                val metrics = "fontScale=${help.resources.configuration.fontScale}, density=$density, " +
                    "inputSize=${input.textSize}, helpSize=${help.textSize}, helpLines=${layout.lineCount}, " +
                    "inputHeight=${input.height}, inputMinHeight=${input.minHeight}, " +
                    "helpLineHeight=${help.lineHeight}, help=${help.width} × ${help.height}, " +
                    "content=${scroll.getChildAt(0).height}, viewport=${scroll.width} × ${scroll.height}"
                println("Numeric editor large-font geometry: $metrics")
                assertEquals("Activity must receive the enlarged font configuration", 2f,
                    f.activity.resources.configuration.fontScale, 0f)
                assertEquals("Dialog text must receive the enlarged font configuration", 2f,
                    help.resources.configuration.fontScale, 0f)
                assertTrue("Input must use enlarged SP text: $metrics", input.textSize > 20 * density)
                assertTrue("Help must use enlarged SP text: $metrics", help.textSize > 18 * density)
                assertTrue("Long numeric help must wrap at the narrow viewport: $metrics", layout.lineCount > 1)
                assertTrue("Expected a nonzero numeric-editor viewport, found ${scroll.width} × ${scroll.height}",
                    scroll.width > 0 && scroll.height > 0)
                assertTrue("Detailed numeric help should scroll: $metrics",
                    scroll.getChildAt(0).height > scroll.height)
                val minimumTouchHeight = (56 * density).toInt()
                // TextView.minHeight is the constraint configured by the
                // editor; View.minimumHeight is a different framework field.
                // Also check the measured result, which determines touch area.
                assertTrue("Input must retain its 56dp minimum: $metrics", input.minHeight >= minimumTouchHeight)
                assertTrue("Measured input must offer at least 56dp height: $metrics", input.height >= minimumTouchHeight)
                assertTrue(help.text.contains("24 caractères"))
                assertTrue("Help must fit the viewport width", help.width <= scroll.width)
                for (line in 0 until layout.lineCount) assertEquals("Numeric help must not be ellipsized", 0, layout.getEllipsisCount(line))
                assertEquals(View.VISIBLE, error.visibility)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isShown)
                assertTrue(f.port.commands.isEmpty())
            }
        } finally {
            RuntimeEnvironment.setFontScale(oldScale)
            RuntimeEnvironment.setQualifiers(previous)
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
