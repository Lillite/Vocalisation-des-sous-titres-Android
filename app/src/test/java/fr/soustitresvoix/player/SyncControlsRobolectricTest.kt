package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import fr.soustitresvoix.core.SubtitleTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncControlsRobolectricTest {
    private class Fixture(initial: SubtitleTimeline = SubtitleTimeline(globalOffsetMs = 100, narrationOffsetMs = -50)) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var current = initial
        var active = true
        var previews = 0
        val changes = mutableListOf<SubtitleTimeline>()
        val dialog = SyncControls(controller.get(), { current }, { current = it; changes += it },
            { 60_000 }, { previews++ }, { active }).show()!!
        override fun close() { dialog.dismiss(); controller.pause().stop().destroy() }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun views(dialog: AlertDialog) = descendants(dialog.window!!.decorView)
    private fun field(dialog: AlertDialog, label: String) = views(dialog).filterIsInstance<EditText>()
        .single { it.contentDescription?.toString() == label }
    private fun button(dialog: AlertDialog, value: String) = views(dialog).filterIsInstance<Button>()
        .single { it.text.toString() == value }
    private fun messages(dialog: AlertDialog) = views(dialog).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

    @Test fun draftIsLocalAndExplicitPreviewIsRevertedOnCancellation() {
        Fixture().use { fixture ->
            val initial = fixture.current
            field(fixture.dialog, "Décalage global des sous-titres").setText("1500")
            assertTrue(fixture.changes.isEmpty())
            button(fixture.dialog, "Prévisualiser sur la réplique courante — laisse en pause").performClick()
            assertEquals(1500L, fixture.current.globalOffsetMs)
            assertEquals(1, fixture.previews)
            fixture.dialog.cancel()
            assertEquals(initial, fixture.current)
        }
    }

    @Test fun positiveConfirmationPersistsIndependentOffsetsWithoutAnAutomaticPreview() {
        Fixture().use { fixture ->
            field(fixture.dialog, "Décalage global des sous-titres").setText("-1250")
            field(fixture.dialog, "Décalage propre à la narration").setText("250")
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.dialog.isShowing)
            assertEquals(-1250L, fixture.current.globalOffsetMs)
            assertEquals(250L, fixture.current.narrationOffsetMs)
            assertEquals(0, fixture.previews)
        }
    }

    @Test fun invalidDraftLeavesTheDialogOpenAndTheOriginalMappingUntouched() {
        Fixture().use { fixture ->
            val initial = fixture.current
            field(fixture.dialog, "Décalage propre à la narration").setText("60001")
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(fixture.dialog.isShowing)
            assertEquals(initial, fixture.current)
            assertTrue(messages(fixture.dialog).contains("hors limites"))
            assertTrue(fixture.changes.isEmpty())
        }
    }

    @Test fun driftAnchorsAndExtraPortionAreEditableAndValidatedBeforeCommit() {
        Fixture().use { fixture ->
            button(fixture.dialog, "Ajouter deux repères pour corriger la dérive").performClick()
            button(fixture.dialog, "Ajouter un repère pour une portion").performClick()
            val sourceFields = views(fixture.dialog).filterIsInstance<EditText>()
                .filter { it.contentDescription == "Temps original du sous-titre, en millisecondes" }
            val videoFields = views(fixture.dialog).filterIsInstance<EditText>()
                .filter { it.contentDescription == "Temps correspondant dans la vidéo, en millisecondes" }
            assertEquals(3, sourceFields.size)
            videoFields[1].setText("63000")
            videoFields[2].setText("128000")
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(3, fixture.current.anchors.size)
            assertEquals(63_000L, fixture.current.anchors[1].videoMs)
            assertEquals(128_000L, fixture.current.anchors[2].videoMs)
        }
    }

    @Test fun expiredMediaCannotApplyOrRollbackIntoANewSession() {
        Fixture().use { fixture ->
            field(fixture.dialog, "Décalage global des sous-titres").setText("1500")
            button(fixture.dialog, "Prévisualiser sur la réplique courante — laisse en pause").performClick()
            fixture.active = false
            val newMedia = SubtitleTimeline(globalOffsetMs = 800)
            fixture.current = newMedia
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(newMedia, fixture.current)
            fixture.dialog.cancel()
            assertEquals(newMedia, fixture.current)
            assertEquals(1, fixture.changes.size)
        }
    }

    @Test fun sceneEditPortionHasPreciseSignedValuesAndPreviewCancellationRestoresIt() {
        val original = SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10_000, 20_000, 5000)))
        Fixture(original).use { fixture ->
            field(fixture.dialog, "Début de la portion originale, en millisecondes").setText("12000")
            field(fixture.dialog, "Fin de la portion originale, en millisecondes").setText("28000")
            field(fixture.dialog, "Décalage de cette portion, en millisecondes").setText("-3200")
            button(fixture.dialog, "Prévisualiser sur la réplique courante — laisse en pause").performClick()
            assertEquals(listOf(SubtitleTimeline.Portion(12_000, 28_000, -3200)), fixture.current.portions)
            assertEquals(1, fixture.previews)
            fixture.dialog.cancel()
            assertEquals(original, fixture.current)
        }
    }

    @Test fun overlappingSourcePortionsAreRefusedUntilTheUserRemovesTheConflictingRow() {
        Fixture().use { fixture ->
            button(fixture.dialog, "Ajouter une portion avec décalage").performClick()
            button(fixture.dialog, "Ajouter une portion avec décalage").performClick()
            val starts = views(fixture.dialog).filterIsInstance<EditText>()
                .filter { it.contentDescription == "Début de la portion originale, en millisecondes" }
            val ends = views(fixture.dialog).filterIsInstance<EditText>()
                .filter { it.contentDescription == "Fin de la portion originale, en millisecondes" }
            val offsets = views(fixture.dialog).filterIsInstance<EditText>()
                .filter { it.contentDescription == "Décalage de cette portion, en millisecondes" }
            starts[0].setText("10000"); ends[0].setText("20000"); offsets[0].setText("-1250")
            starts[1].setText("15000"); ends[1].setText("30000")
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(fixture.dialog.isShowing)
            assertTrue(fixture.changes.isEmpty())
            assertTrue(messages(fixture.dialog).contains("ne pas se chevaucher"))
            views(fixture.dialog).filterIsInstance<Button>().filter { it.text == "Retirer cette portion" }[1].performClick()
            fixture.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(fixture.dialog.isShowing)
            assertEquals(listOf(SubtitleTimeline.Portion(10_000, 20_000, -1250)), fixture.current.portions)
            assertEquals(0, fixture.previews)
        }
    }
}
