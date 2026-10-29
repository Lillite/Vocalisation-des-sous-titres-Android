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
import android.widget.CheckBox
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The synthetic benchmark is opt-in and never borrows personal subtitle text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufBenchmarkControlsRobolectricTest {
    private class Fixture(private val measured: Boolean = false, private val blockGeneration: Boolean = false) : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get().apply { setTheme(R.style.AppTheme) }
        init {
            activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).edit().clear()
                .putString("local-context", "synthetic private context must not enter a benchmark").commit()
            File(activity.filesDir, "local-diagnostic").deleteRecursively()
            if (measured) {
                val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
                LocalGgufModelStore(activity).importModel(ByteArrayInputStream(header), header.size.toLong())
            }
        }
        var otherBusy = false
        var modelPicks = 0
        var engines = 0
        val openedModes = Collections.synchronizedList(mutableListOf<String>())
        val inputs = Collections.synchronizedList(mutableListOf<String>())
        val generationEntered = CountDownLatch(1)
        val generationExited = CountDownLatch(1)
        private val releaseGeneration = CountDownLatch(1)
        val controls = LocalGgufBenchmarkControls(activity, { otherBusy }, { modelPicks++ }, benchmarkFactory = { changed ->
            LocalGgufBenchmark(activity, changed, engineFactory = { config ->
                engines++
                if (!measured) error("No model was selected")
                openedModes += config.localExecution
                object : TranslationEngine, LocalTranslationPerformanceSource {
                    override val id = "synthetic-benchmark-controls-test"
                    private var listener: ((LocalTranslationPerformance) -> Unit)? = null
                    private var calls = 0
                    override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) {
                        this.listener = listener
                    }
                    override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                        cancelled: () -> Boolean): List<String> {
                        assertEquals("en", sourceLanguage); assertEquals("fr", targetLanguage)
                        assertEquals("", config.localContext)
                        assertEquals(1, texts.size)
                        inputs += texts.single()
                        generationEntered.countDown()
                        try {
                            while (blockGeneration && !cancelled()) {
                                if (releaseGeneration.await(10, TimeUnit.MILLISECONDS)) break
                            }
                            if (cancelled()) throw TranslationCancelledException()
                            val gpu = config.localExecution == "OPENCL"
                            if (calls++ == 0) listener?.invoke(LocalTranslationPerformance(phase = "load",
                                requestedMode = config.localExecution, backend = config.localExecution, loadMs = 1,
                                openClTransport = if (gpu) "DIRECT" else ""))
                            listener?.invoke(LocalTranslationPerformance(requestedMode = config.localExecution,
                                backend = config.localExecution, device = "synthetic measured backend",
                                gpuObserved = gpu, gpuGraphNodes = if (gpu) 1 else 0,
                                promptMs = 2, generationMs = 3, endToEndMs = 5,
                                promptTokens = 4, promptEvaluatedTokens = 4, generatedTokens = 2,
                                openClTransport = if (gpu) "DIRECT" else ""))
                            return listOf("Traduction synthétique complète.")
                        } finally { generationExited.countDown() }
                    }
                    override fun close() = Unit
                }
            })
        })
        fun open(): AlertDialog {
            controls.show(); shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        override fun close() {
            controls.close(); releaseGeneration.countDown()
            if (blockGeneration && generationEntered.count == 0L) generationExited.await(2, TimeUnit.SECONDS)
            owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle()
        }
    }
    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private fun views(dialog: AlertDialog) = descendants(requireNotNull(dialog.window).decorView)
        private fun button(dialog: AlertDialog, label: String) = views(dialog).filterIsInstance<Button>().single { it.text.toString() == label }
        private fun text(dialog: AlertDialog) = views(dialog).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        private fun order(dialog: AlertDialog) = views(dialog).filterIsInstance<Spinner>()
            .single { it.contentDescription == LocalGgufBenchmarkControls.ORDER_LABEL }
        private fun reopening(dialog: AlertDialog) = views(dialog).filterIsInstance<CheckBox>().single()
        private fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(message, condition())
        }
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
            button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).performClick()
            assertTrue(text(panel).contains("Choisis un modèle GGUF"))
            assertEquals(0, f.engines)
            assertFalse(reopening(panel).isChecked)
            assertEquals(0, order(panel).selectedItemPosition)
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
            button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).performClick()
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

    @Test fun extendedSelectionRunsReverseOrderAndOptionalReopeningOnlyAfterExplicitStart() {
        Fixture(measured = true).use { f ->
            val panel = f.open()
            val preferences = f.activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE)
            val originalPreferences = preferences.all.toMap()
            order(panel).setSelection(1); reopening(panel).isChecked = true
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, f.engines)
            assertEquals(originalPreferences, preferences.all)
            button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).performClick()
            await("Synthetic measured series must finish") { !f.controls.busy }
            assertEquals(listOf("OPENCL", "OPENCL", "CPU", "CPU"), f.openedModes.toList())
            assertEquals(66, f.inputs.size)
            assertEquals(f.inputs.take(32), f.inputs.drop(33).take(32))
            assertEquals(f.inputs[0], f.inputs[32])
            assertEquals(f.inputs[33], f.inputs[65])
            assertEquals("synthetic private context must not enter a benchmark", preferences.getString("local-context", null))
            assertFalse(text(panel).contains("synthetic private context must not enter a benchmark"))
            assertTrue(button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).isEnabled)
            assertTrue(order(panel).isEnabled)
            assertTrue(reopening(panel).isEnabled)
        }
    }

    @Test fun runningSeriesBlocksConfigurationAndCanBeCopiedAndCancelledAfterPanelReopening() {
        Fixture(measured = true, blockGeneration = true).use { f ->
            var panel = f.open()
            reopening(panel).isChecked = true
            val staleStart = button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL)
            staleStart.performClick()
            await("Synthetic backend must have entered its first call") { f.generationEntered.count == 0L }
            assertTrue(f.controls.busy)
            assertFalse(views(panel).filterIsInstance<EditText>().single().isEnabled)
            assertTrue(views(panel).filterIsInstance<Spinner>().all { !it.isEnabled })
            assertFalse(reopening(panel).isEnabled)
            assertFalse(button(panel, "Comparer CPU puis GPU sur le texte intégré").isEnabled)
            assertFalse(button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).isEnabled)
            assertTrue(button(panel, "Annuler l’essai comparatif CPU / GPU").isEnabled)
            panel.dismiss()
            assertTrue(f.controls.busy)
            panel = f.open()
            assertFalse(order(panel).isEnabled)
            assertFalse(reopening(panel).isEnabled)
            assertTrue(reopening(panel).isChecked)
            staleStart.performClick()
            assertEquals(1, f.engines)
            button(panel, "Copier le rapport CPU / GPU").performClick()
            val report = f.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("Comparatif local CPU / GPU"))
            assertFalse(report.contains("synthetic private context must not enter a benchmark"))
            button(panel, "Annuler l’essai comparatif CPU / GPU").performClick()
            await("Cancelled series must release the current engine") { !f.controls.busy }
            assertEquals(listOf("CPU"), f.openedModes.toList())
            assertTrue(text(panel).contains("annulé", ignoreCase = true))
            assertTrue(button(panel, LocalGgufBenchmarkControls.VARIED_START_LABEL).isEnabled)
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
                val profile = views(panel).filterIsInstance<Spinner>()
                    .single { it.contentDescription == "Profil de traduction du modèle GGUF" }
                assertEquals("Profil de traduction du modèle GGUF", profile.contentDescription)
                assertEquals(3, views(panel).filterIsInstance<Spinner>().count())
                val contextPolicy = views(panel).filterIsInstance<Spinner>()
                    .single { it.contentDescription == "Contexte des sous-titres GGUF" }
                assertEquals(1, views(panel).filterIsInstance<TextView>().count { it.labelFor == contextPolicy.id })
                for (choice in views(panel).filterIsInstance<Spinner>()) {
                    val selected = choice.selectedView as? TextView ?: error("Readable selected choice required")
                    assertNull(selected.ellipsize); assertTrue(selected.maxLines > 1)
                    selected.layout?.let { layout ->
                        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                    }
                }
                assertEquals(1, views(panel).filterIsInstance<TextView>().count { it.labelFor == order(panel).id })
                assertEquals(LocalGgufBenchmarkControls.REOPEN_LABEL, reopening(panel).text.toString())
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
    @Test fun contextComparisonButtonsAreOptInGpuOnlyAndCopyEveryQualitySource() {
        Fixture(measured = true).use { f ->
            val panel = f.open()
            assertEquals(0, f.engines)
            assertTrue(button(panel, LocalGgufBenchmarkControls.CONTEXT_START_LABEL).isEnabled)
            assertTrue(button(panel, LocalGgufBenchmarkControls.QUALITY_START_LABEL).isEnabled)
            reopening(panel).isChecked = true
            order(panel).setSelection(1)
            button(panel, LocalGgufBenchmarkControls.QUALITY_START_LABEL).performClick()
            await("All three synthetic policy sessions must finish") { !f.controls.busy }
            assertEquals(listOf("OPENCL", "OPENCL", "OPENCL"), f.openedModes.toList())
            assertEquals(LocalGgufQualityCorpus.cues.map { it.source }.let { it + it + it }, f.inputs.toList())
            button(panel, "Copier le rapport CPU / GPU").performClick()
            val report = f.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.startsWith("Comparatif local GPU — stratégies de contexte"))
            assertTrue(report.contains(LocalGgufQualityCorpus.VERSION))
            assertEquals(LocalGgufQualityCorpus.cues.size * 3,
                Regex("Sortie sur le texte synthétique uniquement").findAll(report).count())
            assertFalse(report.contains("synthetic private context must not enter a benchmark"))
            assertFalse(report.contains("Réouverture demandée"))
            assertTrue(reopening(panel).isChecked)
        }
    }

}
