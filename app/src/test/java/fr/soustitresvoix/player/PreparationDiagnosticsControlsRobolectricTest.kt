package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
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

/** Real framework controls only. No probe, decoder, model, media or voice is faked
 * into a successful diagnostic by these presentation tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PreparationDiagnosticsControlsRobolectricTest {
    private class Fixture : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        val events = mutableListOf<String>()
        val controls = PreparationDiagnosticsControls(activity, PreparationDiagnosticsControls.Actions(
            refreshDevice = { events += "refresh" },
            runDemo = { events += "demo" },
            chooseVideo = { events += "video" },
            chooseModel = { events += "model" },
            runModel = { events += "run-model" },
            cancel = { events += "cancel" },
            close = { events += "close" },
            clearModel = { events += "clear-model" },
            copyReport = { events += "copy-report" },
            runBuiltinModel = { events += "builtin-model" }
        ))
        fun open(): AlertDialog = controls.show().also {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(it.isShowing)
        }
        override fun close() {
            controls.dismiss()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun button(dialog: AlertDialog, label: String): Button =
        descendants(requireNotNull(dialog.window).decorView).filterIsInstance<Button>()
            .single { it.text.toString() == label }

    @Test fun openingAndUpdatingNeverLaunchAProbeAndOnlyExplicitEnabledActionsDispatch() {
        Fixture().use { f ->
            f.controls.update("Rapport de test synthétique", false, false, "Aucun modèle choisi.")
            val dialog = f.open()
            assertTrue(f.events.isEmpty())
            val run = button(dialog, "Tester le modèle sur une phrase synthétique")
            val clear = button(dialog, "Supprimer la copie du modèle importé")
            assertFalse(run.isEnabled)
            assertTrue("An incomplete import must remain removable without a ready model", clear.isEnabled)
            run.performClick()
            assertTrue(f.events.isEmpty())
            for (label in listOf("Actualiser les mesures du téléphone", "Tester la démonstration vidéo intégrée", "Tester le moteur CPU intégré",
                "Choisir une vidéo pour un essai de décodage", "Choisir un modèle GGUF local")) {
                button(dialog, label).performClick()
            }
            assertEquals(listOf("refresh", "demo", "builtin-model", "video", "model"), f.events)
            f.controls.update("Modèle choisi pour un essai local", false, true, "Prêt.")
            assertTrue(run.isEnabled)
            run.performClick()
            assertEquals(listOf("refresh", "demo", "builtin-model", "video", "model", "run-model"), f.events)
            assertTrue(clear.isEnabled)
            clear.performClick()
            f.controls.update("Copie supprimée, original conservé", false, false, "Aucun modèle importé.")
            assertTrue(clear.isEnabled)
            clear.performClick()
            button(dialog, "Copier le rapport de diagnostic").performClick()
            assertEquals(listOf("refresh", "demo", "builtin-model", "video", "model", "run-model", "clear-model", "clear-model", "copy-report"), f.events)
            val texts = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().toList()
            assertTrue(texts.any { it.text.contains("CPU uniquement") && it.text.contains("GPU et le NPU ne sont pas exécutés") })
            assertTrue(texts.any { it.text.contains("deux fonctions indépendantes") && it.text.contains("aucune vidéo") })
            assertEquals(1, texts.count { it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE })
            assertTrue(texts.any { it.text.contains("copie privée") && it.text.contains("fichier original") })
            assertTrue(texts.any { it.text.contains("import incomplet") && it.text.contains("même sans modèle prêt") })
            assertTrue(texts.any { it.text.contains("modèle miniature synthétique") && it.text.contains("grand modèle importé") })
            val report = texts.single { it.text.toString() == "Copie supprimée, original conservé" }
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, report.accessibilityLiveRegion)
            assertTrue(report.isTextSelectable)
        }
    }

    @Test fun busyStateKeepsCancellationAndCloseAvailableAndStaleViewsCannotDispatch() {
        Fixture().use { f ->
            val first = f.open()
            val oldRefresh = button(first, "Actualiser les mesures du téléphone")
            val oldModel = button(first, "Tester le modèle sur une phrase synthétique")
            val builtin = button(first, "Tester le moteur CPU intégré")
            val clear = button(first, "Supprimer la copie du modèle importé")
            f.controls.update("Mesures précédentes conservées", true, true, "Chargement du modèle en cours.")
            assertFalse(oldRefresh.isEnabled)
            assertFalse(oldModel.isEnabled)
            assertFalse(builtin.isEnabled)
            assertFalse(clear.isEnabled)
            assertTrue(button(first, "Copier le rapport de diagnostic").isEnabled)
            oldRefresh.performClick()
            oldModel.performClick()
            builtin.performClick()
            clear.performClick()
            assertTrue(f.events.isEmpty())
            assertTrue(button(first, "Annuler l’essai en cours").isEnabled)
            assertTrue(button(first, "Fermer le diagnostic").isEnabled)
            button(first, "Annuler l’essai en cours").performClick()
            assertEquals(listOf("cancel"), f.events)
            assertTrue("Annuler requests cancellation; it must not secretly close or restart a probe", first.isShowing)
            f.controls.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("cancel"), f.events)
            f.controls.update("Annulé", false, true, "Essai annulé.")
            val second = f.open()
            oldRefresh.performClick()
            oldModel.performClick()
            assertEquals("Detached buttons cannot act on a reopened panel", listOf("cancel"), f.events)
            button(second, "Actualiser les mesures du téléphone").performClick()
            assertEquals(listOf("cancel", "refresh"), f.events)
            assertFalse(button(second, "Annuler l’essai en cours").isEnabled)
        }
    }

    @Test fun userCloseAndAndroidBackNotifyTheOwnerOnceWhileProgrammaticDismissIsSilent() {
        Fixture().use { f ->
            val first = f.open()
            val close = button(first, "Fermer le diagnostic")
            close.performClick()
            close.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(first.isShowing)
            assertEquals(listOf("close"), f.events)
            val second = f.open()
            second.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("close", "close"), f.events)
            val third = f.open()
            f.controls.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(third.isShowing)
            assertEquals(listOf("close", "close"), f.events)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun largeTextKeepsLongReportsScrollableAndActionsUnabridgedInAShortWindow() {
        val oldQualifiers = RuntimeEnvironment.getQualifiers()
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp")
            RuntimeEnvironment.setFontScale(2f)
            Fixture().use { f ->
                val report = (1..30).joinToString("\n") { "Mesure synthétique $it : valeur et origine explicitement indiquées." }
                f.controls.update(report, true, true, "Chargement synthétique en cours.")
                val dialog = f.open()
                val decor = requireNotNull(dialog.window).decorView
                val density = f.activity.resources.displayMetrics.density
                val width = (320 * density).toInt()
                val height = (480 * density).toInt()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
                val scroll = descendants(decor).filterIsInstance<ScrollView>()
                    .single { it.tag == PreparationDiagnosticsControls.SCROLL_TAG }
                assertTrue(scroll.width > 0 && scroll.height > 0)
                assertTrue("Detailed diagnostic content must really scroll", scroll.getChildAt(0).height > scroll.height)
                val reportView = descendants(scroll).filterIsInstance<TextView>().single { it.text.toString() == report }
                assertEquals(2f, reportView.resources.configuration.fontScale, 0f)
                assertTrue(reportView.textSize > 18 * density)
                val layout = requireNotNull(reportView.layout)
                assertTrue(layout.lineCount > 30)
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                // AlertDialog also owns unused GONE framework buttons. Check
                // every exposed action, including those below the scroll fold.
                val controls = descendants(decor).filterIsInstance<Button>().filter { it.isShown }.toList()
                assertEquals("All diagnostic actions must remain exposed", 10, controls.size)
                for (control in controls) {
                    val description = "Button '${control.text}', shown=${control.isShown}, visibility=${control.visibility}, id=${control.id}, minHeight=${control.minHeight}, height=${control.height}"
                    assertNull(description, control.ellipsize)
                    assertTrue(description, control.maxLines > 1)
                    assertTrue(description, control.minHeight >= (56 * density).toInt())
                    assertTrue(description, control.height >= (56 * density).toInt())
                }
                val cancel = button(dialog, "Annuler l’essai en cours")
                val close = button(dialog, "Fermer le diagnostic")
                for (control in listOf(cancel, close)) {
                    val rectangle = android.graphics.Rect()
                    control.getDrawingRect(rectangle)
                    (decor as ViewGroup).offsetDescendantRectToMyCoords(control, rectangle)
                    assertTrue("Diagnostic action outside window: $rectangle", rectangle.left >= 0 && rectangle.right <= decor.width &&
                        rectangle.top >= 0 && rectangle.bottom <= decor.height)
                }
                assertTrue(f.events.isEmpty())
            }
        } finally {
            RuntimeEnvironment.setFontScale(oldScale)
            RuntimeEnvironment.setQualifiers(oldQualifiers)
        }
    }
}
