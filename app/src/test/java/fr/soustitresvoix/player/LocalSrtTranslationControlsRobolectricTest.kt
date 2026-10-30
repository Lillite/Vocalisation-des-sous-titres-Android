package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import androidx.media3.common.util.UnstableApi
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
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real local preparation form: no video, model inference, provider or network is needed. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalSrtTranslationControlsRobolectricTest {
    private class Fixture(private val capturedConfigurations: MutableList<TranslationProviderConfig>? = null,
        private val controlledEngine: ((TranslationProviderConfig) -> TranslationEngine)? = null) : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get().apply { setTheme(R.style.AppTheme) }
        init {
            activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).edit().clear().commit()
            for (directory in listOf("local-diagnostic", "local-srt-source-v1", "local-srt-progress-v1", "local-srt-results-v1"))
                File(activity.filesDir, directory).deleteRecursively()
            activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
        var otherBusy = false
        var subtitlePicks = 0
        var modelPicks = 0
        var exports = 0
        val controls = LocalSrtTranslationControls(activity, { otherBusy }, { subtitlePicks++ }, { modelPicks++ },
            onExport = { _, _, _ -> exports++ },
            jobFactory = { context, changed ->
                if (controlledEngine != null) LocalSrtTranslationJob(context, onChanged = changed, engineFactory = controlledEngine)
                else if (capturedConfigurations == null) LocalSrtTranslationJob(context, onChanged = changed)
                else LocalSrtTranslationJob(context, onChanged = changed, engineFactory = { config ->
                    capturedConfigurations += config
                    object : TranslationEngine {
                        override val id = TranslationEngines.translationIdentity(config)
                        override val maxBatchItems = 1
                        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                            cancelled: () -> Boolean) = texts.map { "Traduction synthétique : $it" }
                        override fun close() = Unit
                    }
                })
            })
        fun open(): AlertDialog {
            controls.show()
            shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        fun ready() = await { !controls.busy }
        override fun close() { controls.close(); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Small private-source restoration must finish", condition())
        }
        private fun views(dialog: AlertDialog) = descendants(requireNotNull(dialog.window).decorView)
        private fun button(dialog: AlertDialog, label: String) = views(dialog).filterIsInstance<Button>().single { it.text.toString() == label }
        private fun text(dialog: AlertDialog) = views(dialog).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        private fun field(dialog: AlertDialog, prefix: String): EditText {
            val label = views(dialog).filterIsInstance<TextView>().single { it.text.toString().startsWith(prefix) && it.labelFor != View.NO_ID }
            return requireNotNull(dialog.findViewById(label.labelFor))
        }
    }

    @Test fun standalonePanelOpensWithoutVideoAndDoesNotPickImportTranslateOrExport() {
        Fixture().use { f ->
            val panel = f.open(); f.ready()
            assertTrue(panel.isShowing)
            assertTrue(text(panel).contains("sans ouvrir de vidéo et sans OCR"))
            assertEquals(0, f.subtitlePicks)
            assertEquals(0, f.modelPicks)
            assertEquals(0, f.exports)
            assertFalse(f.controls.busy)
            assertFalse(File(f.activity.filesDir, "local-diagnostic/model.gguf").exists())
            button(panel, "Exporter le SRT traduit en SRT").performClick()
            assertTrue(text(panel).contains("Termine d’abord la traduction complète"))
            button(panel, "Traduire le SRT hors ligne / reprendre").performClick()
            assertTrue(text(panel).contains("Choisis un modèle GGUF"))
            assertEquals(0, f.exports)
        }
    }

    @Test fun onlyRequestedPickerResultsAreAcceptedAndClosedPanelActionsCannotPickAgain() {
        Fixture().use { f ->
            val panel = f.open(); f.ready()
            f.controls.selectedSubtitle(Uri.parse("content://unrequested.synthetic/subtitles.srt"))
            f.controls.selectedModel(Uri.parse("content://unrequested.synthetic/model.gguf"))
            assertFalse(f.controls.busy)
            val choose = button(panel, "Choisir le fichier SRT à traduire hors ligne")
            choose.performClick()
            assertEquals(1, f.subtitlePicks)
            panel.dismiss()
            f.controls.selectedSubtitle(Uri.parse("content://stale.synthetic/subtitles.srt"))
            val reopened = f.open(); f.ready()
            choose.performClick()
            assertEquals(1, f.subtitlePicks)
            assertTrue(reopened.isShowing)
            assertFalse(f.controls.busy)
            button(reopened, "Choisir le fichier SRT à traduire hors ligne").performClick()
            assertEquals(2, f.subtitlePicks)
            f.controls.selectedSubtitle(null)
            assertFalse(f.controls.busy)
        }
    }

    @Test fun localModelSelectionIsRefusedWhileAnotherPreparationOwnsTheResources() {
        Fixture().use { f ->
            val panel = f.open(); f.ready(); f.otherBusy = true
            button(panel, "Choisir un modèle GGUF local pour traduire").performClick()
            assertEquals(0, f.modelPicks)
            assertTrue(text(panel).contains("Termine ou annule la préparation en cours"))
            button(panel, "Traduire le SRT hors ligne / reprendre").performClick()
            assertTrue(text(panel).contains("Termine ou annule l’autre préparation"))
            assertFalse(f.controls.busy)
        }
    }

    @Test fun confirmedExampleSavesTheExactLocalContextAndBothFormsRestoreItsCacheIdentity() {
        val configurations = mutableListOf<TranslationProviderConfig>()
        Fixture(configurations).use { f ->
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
                .putInt(3).putLong(0).putLong(0).array()
            val model = LocalGgufModelStore(f.activity).importModel(ByteArrayInputStream(header), header.size.toLong())
            val panel = f.open(); f.ready()
            field(panel, "Langue originale des sous-titres").setText("ja")
            button(panel, "Charger l’exemple SRT anglais intégré").performClick()
            f.ready()
            assertEquals("en", field(panel, "Langue originale des sous-titres").text.toString())
            assertTrue(text(panel).contains("exemple-traduction-en.srt"))
            assertEquals(0, f.subtitlePicks)
            assertTrue(configurations.isEmpty())
            val context = "Jean et Zoé sont frère et sœur. Conserver leurs noms."
            field(panel, "GGUF local : contexte de l’œuvre").setText(context)
            field(panel, "GGUF local : threads de calcul").setText("3")
            field(panel, "Couches GPU").setText("8")
            field(panel, "Lot logique").setText("128")
            field(panel, "Micro-lot").setText("32")
            views(panel).filterIsInstance<android.widget.CheckBox>().single {
                it.text.toString().startsWith("Cache d’attention locale compact")
            }.isChecked = true
            views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Exécution du modèle GGUF" }.setSelection(1)
            views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Profil de traduction du modèle GGUF" }.setSelection(2)
            views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Contexte des sous-titres GGUF" }.setSelection(2)
            shadowOf(Looper.getMainLooper()).idle()
            button(panel, "Traduire le SRT hors ligne / reprendre").performClick()
            val confirmation = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertNotSame(panel, confirmation)
            assertTrue(text(confirmation).contains("Aucun réseau ni téléchargement de modèle"))
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            f.ready()
            assertEquals(1, configurations.size)
            val used = configurations.single()
            assertEquals(context, used.localContext)
            assertEquals(3, used.localThreads)
            assertEquals("CPU", used.localExecution)
            assertEquals(LocalGgufProfile.HY_MT, used.localProfile)
            assertEquals(LocalGgufContextPolicy.BLOCKS_SIX, used.localContextPolicy)
            assertEquals(LocalGgufLoadSettings(8, 128, 32, true), used.localLoadSettings)
            assertEquals(model.sha256, used.localModel?.sha256)
            assertTrue(text(panel).contains("Occurrences validées : 8 / 8"))
            assertEquals(context, f.activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).getString("local-context", null))
            button(panel, "Consulter les indices linguistiques de la traduction complète").performClick()
            val review = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(text(review).contains("Vérification heuristique — en vers fr"))
            assertTrue(text(review).contains("ne prouve pas la fidélité linguistique"))
            val clipboard = f.activity.getSystemService(ClipboardManager::class.java)
            assertNull("Review text must only be copied after an explicit action", clipboard.primaryClip)
            review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            // AlertDialog dispatches this listener through its main Handler.
            shadowOf(Looper.getMainLooper()).idle()
            val copied = requireNotNull(clipboard.primaryClip)
            assertTrue(copied.getItemAt(0).text.toString().contains("Vérification heuristique — en vers fr"))
            assertEquals(true, copied.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            assertEquals(0, f.exports)

            panel.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = f.open(); f.ready()
            assertEquals(context, field(reopened, "GGUF local : contexte de l’œuvre").text.toString())
            assertEquals("3", field(reopened, "GGUF local : threads de calcul").text.toString())
            val restored = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
                localModel = LocalGgufModelStore(f.activity).current(),
                localThreads = field(reopened, "GGUF local : threads de calcul").text.toString().toInt(),
                localContext = field(reopened, "GGUF local : contexte de l’œuvre").text.toString(),
                localExecution = "CPU", localProfile = LocalGgufProfile.HY_MT,
                localContextPolicy = LocalGgufContextPolicy.BLOCKS_SIX,
                localLoadSettings = LocalGgufLoadSettings(
                    field(reopened, "Couches GPU").text.toString().toInt(),
                    field(reopened, "Lot logique").text.toString().toInt(),
                    field(reopened, "Micro-lot").text.toString().toInt(),
                    views(reopened).filterIsInstance<android.widget.CheckBox>().single {
                        it.text.toString().startsWith("Cache d’attention locale compact")
                    }.isChecked))
            assertEquals(1, views(reopened).filterIsInstance<Spinner>().single { it.contentDescription == "Exécution du modèle GGUF" }.selectedItemPosition)
            assertEquals(2, views(reopened).filterIsInstance<Spinner>().single { it.contentDescription == "Profil de traduction du modèle GGUF" }.selectedItemPosition)
            assertEquals(2, views(reopened).filterIsInstance<Spinner>().single { it.contentDescription == "Contexte des sous-titres GGUF" }.selectedItemPosition)
            assertEquals(TranslationEngines.translationIdentity(used), TranslationEngines.translationIdentity(restored))
            reopened.dismiss()

            val playback = PlaybackSession(f.activity, PlayerPreferences(f.activity), {}, { _, _ -> }, {})
            val readerControls = SubtitleTranslationControls(f.activity, playback)
            try {
                readerControls.show()
                val reader = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertEquals(context, field(reader, "GGUF local : contexte de l’œuvre").text.toString())
                assertEquals("3", field(reader, "GGUF local : threads de calcul").text.toString())
                assertEquals("8", field(reader, "Couches GPU").text.toString())
                assertEquals("128", field(reader, "Lot logique").text.toString())
                assertEquals("32", field(reader, "Micro-lot").text.toString())
                assertTrue(views(reader).filterIsInstance<android.widget.CheckBox>().single {
                    it.text.toString().startsWith("Cache d’attention locale compact")
                }.isChecked)
                assertEquals(1, views(reader).filterIsInstance<Spinner>().single { it.contentDescription == "Exécution du modèle GGUF" }.selectedItemPosition)
                assertEquals(2, views(reader).filterIsInstance<Spinner>().single { it.contentDescription == "Profil de traduction du modèle GGUF" }.selectedItemPosition)
                assertEquals(2, views(reader).filterIsInstance<Spinner>().single { it.contentDescription == "Contexte des sous-titres GGUF" }.selectedItemPosition)
            } finally { readerControls.close(); playback.close() }
        }
    }

    @Test fun qualityExampleIsExplicitAndDoesNotStartInferenceOrExport() {
        val configurations = mutableListOf<TranslationProviderConfig>()
        Fixture(configurations).use { f ->
            val panel = f.open(); f.ready()
            field(panel, "Langue originale des sous-titres").setText("ja")
            button(panel, "Charger le SRT de contrôle de fidélité — phrases inédites").performClick()
            f.ready()
            assertEquals("en", field(panel, "Langue originale des sous-titres").text.toString())
            assertTrue(text(panel).contains("qualite-traduction-en.srt"))
            assertTrue(configurations.isEmpty())
            assertEquals(0, f.subtitlePicks)
            assertEquals(0, f.modelPicks)
            assertEquals(0, f.exports)
            assertFalse(button(panel, "Consulter les dernières mesures du moteur local").isEnabled)
            button(panel, "Consulter les indices linguistiques de la traduction complète").performClick()
            assertTrue(text(panel).contains("Termine d’abord la traduction complète de ce SRT"))
            assertTrue(configurations.isEmpty())
        }
    }

    @Test fun performanceUpdatesLeaveTheLiveStatusAndFocusStableWhileDetailsRemainConsultable() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observations = AtomicReference<((LocalTranslationPerformance) -> Unit)?>(null)
        Fixture(controlledEngine = { config ->
            object : TranslationEngine, LocalTranslationPerformanceSource {
                override val id = TranslationEngines.translationIdentity(config)
                override val maxBatchItems = 1
                override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) { observations.set(listener) }
                override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                    cancelled: () -> Boolean): List<String> {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    if (cancelled()) throw TranslationCancelledException()
                    return texts.map { "Traduction synthétique : $it" }
                }
                override fun close() = Unit
            }
        }).use { f ->
            try {
                val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
                LocalGgufModelStore(f.activity).importModel(ByteArrayInputStream(header), header.size.toLong())
                val panel = f.open(); f.ready()
                button(panel, "Charger l’exemple SRT anglais intégré").performClick(); f.ready()
                button(panel, "Traduire le SRT hors ligne / reprendre").performClick()
                requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                await { entered.count == 0L && text(panel).contains("Occurrences validées : 0 / 8") }
                val status = views(panel).filterIsInstance<TextView>().single { it.tag == LocalSrtTranslationControls.STATUS_TAG }
                val progress = views(panel).filterIsInstance<TextView>().single { it.tag == LocalSrtTranslationControls.PROGRESS_TAG }
                val initial = status.text.toString()
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, progress.accessibilityLiveRegion)
                val input = field(panel, "Langue originale des sous-titres")
                input.requestFocus()
                val measures = button(panel, "Consulter les dernières mesures du moteur local")
                assertTrue("The attempt report must be available before the first successful measurement", measures.isEnabled)
                measures.performClick()
                val initialDetails = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(text(initialDetails).contains("Préparation locale commencée"))
                assertTrue(text(initialDetails).contains("Aucun chargement ou calcul réussi"))
                assertFalse(text(initialDetails).contains("GPU réellement observé : oui"))
                initialDetails.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                input.requestFocus()
                for (tokens in 1..3) {
                    requireNotNull(observations.get()).invoke(LocalTranslationPerformance(backend = "OPENCL",
                        device = "Adreno synthétique", gpuObserved = true, generationMs = 10, generatedTokens = tokens))
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(initial, status.text.toString())
                    assertTrue(input.hasFocus())
                    assertFalse(status.text.toString().contains("Adreno"))
                }
                assertTrue(measures.isEnabled)
                measures.performClick()
                val details = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(text(details).contains("Adreno synthétique"))
                details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                button(panel, "Annuler la traduction du SRT — conserver la progression").performClick()
                assertTrue(status.text.toString().contains("Arrêt demandé"))
                release.countDown()
                f.ready()
                assertTrue(status.text.toString().contains("arrêtée", ignoreCase = true))
                assertEquals(0, f.exports)
            } finally { release.countDown() }
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun standaloneControlsKeepLabelsAndAllActionsReadableAtDoubleFontScale() {
        val oldQualifiers = RuntimeEnvironment.getQualifiers()
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp")
            RuntimeEnvironment.setFontScale(2f)
            Fixture().use { f ->
                val panel = f.open(); f.ready()
                val decor = requireNotNull(panel.window).decorView
                val density = f.activity.resources.displayMetrics.density
                val width = (320 * density).toInt(); val height = (480 * density).toInt()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
                val scroll = views(panel).filterIsInstance<ScrollView>().single { it.tag == LocalSrtTranslationControls.SCROLL_TAG }
                assertTrue(scroll.getChildAt(0).height > scroll.height)
                val inputs = views(panel).filterIsInstance<EditText>().toList()
                assertEquals(6, inputs.size)
                for (input in inputs) {
                    assertEquals(1, views(panel).filterIsInstance<TextView>().count { it.labelFor == input.id })
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                    assertEquals(2f, input.resources.configuration.fontScale, 0f)
                    assertTrue(input.textSize >= 18 * density)
                }
                val actions = descendants(scroll).filterIsInstance<Button>().toList()
                assertEquals(17, actions.size)
                for (action in actions) {
                    assertNull(action.ellipsize)
                    assertTrue(action.maxLines > 1)
                    assertTrue("Action '${action.text}' has an insufficient touch target", action.minHeight >= (56 * density).toInt())
                    val layout = requireNotNull(action.layout)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                }
                assertEquals(0, f.subtitlePicks)
                assertEquals(0, f.modelPicks)
                assertEquals(0, f.exports)
            }
        } finally {
            RuntimeEnvironment.setFontScale(oldScale); RuntimeEnvironment.setQualifiers(oldQualifiers)
        }
    }
}
