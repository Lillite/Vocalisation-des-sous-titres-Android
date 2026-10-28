package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** The synthetic benchmark is opt-in and never borrows personal subtitle text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufBenchmarkControlsRobolectricTest {
    private class Fixture : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get().apply { setTheme(R.style.AppTheme) }
        init {
            activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).edit().clear()
                .putString("local-context", "synthetic private context must not enter a benchmark").commit()
            File(activity.filesDir, "local-diagnostic").deleteRecursively()
        }
        var otherBusy = false
        var modelPicks = 0
        var engines = 0
        val controls = LocalGgufBenchmarkControls(activity, { otherBusy }, { modelPicks++ }, benchmarkFactory = { changed ->
            LocalGgufBenchmark(activity, changed, engineFactory = { engines++; error("No model was selected") })
        })
        fun open(): AlertDialog {
            controls.show(); shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        override fun close() { controls.close(); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private fun views(dialog: AlertDialog) = descendants(requireNotNull(dialog.window).decorView)
        private fun button(dialog: AlertDialog, label: String) = views(dialog).filterIsInstance<Button>().single { it.text.toString() == label }
        private fun text(dialog: AlertDialog) = views(dialog).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    }

    @Test fun openingIsPassiveAndCopySharesOnlyTheFixedSyntheticReportOnExplicitAction() {
        Fixture().use { f ->
            val clipboard = f.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Existing", "previous user clip"))
            val panel = f.open()
            assertEquals(0, f.engines)
            assertEquals(0, f.modelPicks)
            assertFalse(f.controls.busy)
            assertEquals("previous user clip", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(text(panel).contains("La durée est inconnue"))
            assertTrue(text(panel).contains(LocalGgufBenchmark.SOURCE))
            assertFalse(text(panel).contains("synthetic private context must not enter a benchmark"))
            button(panel, "Comparer CPU puis GPU sur le texte intégré").performClick()
            assertTrue(text(panel).contains("Choisis un modèle GGUF"))
            assertEquals(0, f.engines)
            button(panel, "Copier le rapport CPU / GPU").performClick()
            val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("Comparatif local CPU / GPU"))
            assertTrue(report.contains("Aucun modèle n’a été exécuté"))
            assertFalse(report.contains("synthetic private context must not enter a benchmark"))
            assertEquals(0, f.engines)
        }
    }

    @Test fun aBusyPreparationAndPostClosePickerCannotStartAnotherModel() {
        Fixture().use { f ->
            val panel = f.open()
            f.otherBusy = true
            button(panel, "Comparer CPU puis GPU sur le texte intégré").performClick()
            assertTrue(text(panel).contains("Termine ou annule les autres préparations"))
            button(panel, "Choisir un modèle GGUF local pour traduire").performClick()
            assertEquals(0, f.modelPicks)
            f.otherBusy = false
            val choose = button(panel, "Choisir un modèle GGUF local pour traduire")
            choose.performClick(); assertEquals(1, f.modelPicks)
            panel.dismiss(); f.controls.selectedModel(Uri.parse("content://stale.synthetic/model.gguf"))
            val next = f.open()
            choose.performClick()
            assertTrue(next.isShowing)
            assertEquals(1, f.modelPicks)
            assertEquals(0, f.engines)
            assertFalse(f.controls.busy)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun benchmarkRemainsScrollableAndItsControlsDoNotTruncateAtDoubleFontScale() {
        val qualifiers = RuntimeEnvironment.getQualifiers()
        val scale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp"); RuntimeEnvironment.setFontScale(2f)
            Fixture().use { f ->
                val panel = f.open()
                val decor = requireNotNull(panel.window).decorView
                val density = f.activity.resources.displayMetrics.density
                val width = (320 * density).toInt(); val height = (480 * density).toInt()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, width, height)
                val scroll = views(panel).filterIsInstance<ScrollView>().single { it.tag == LocalGgufBenchmarkControls.SCROLL_TAG }
                assertTrue(scroll.getChildAt(0).height > scroll.height)
                val input = views(panel).filterIsInstance<EditText>().single()
                assertEquals("4", input.text.toString())
                assertEquals(1, views(panel).filterIsInstance<TextView>().count { it.labelFor == input.id })
                val profile = views(panel).filterIsInstance<Spinner>().single()
                assertEquals("Profil de traduction du modèle GGUF", profile.contentDescription)
                for (action in descendants(scroll).filterIsInstance<Button>()) {
                    assertNull(action.ellipsize); assertTrue(action.maxLines > 1)
                    assertTrue(action.minHeight >= (56 * density).toInt())
                    val layout = requireNotNull(action.layout)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                }
                assertEquals(0, f.engines)
                assertEquals(0, f.modelPicks)
            }
        } finally { RuntimeEnvironment.setFontScale(scale); RuntimeEnvironment.setQualifiers(qualifiers) }
    }
}
