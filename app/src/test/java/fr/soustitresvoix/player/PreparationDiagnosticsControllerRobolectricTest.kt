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
import android.widget.TextView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Exercises the production controller through its panel and picker result APIs.
 * Every execution path is unrequested, refused or closed before a decoder/model
 * starts. These tests make no inference, codec, GPU or NPU success assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PreparationDiagnosticsControllerRobolectricTest {
    private class Fixture : AutoCloseable {
        private val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = host.get().apply { setTheme(R.style.AppTheme) }
        private val preferences = activity.getSharedPreferences("player", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean("narration", true).putBoolean("cache", false)
                .putInt("speed", 117).putString("diagnostic-test-sentinel", "unchanged").commit()
        }
        val beforePreferences: Map<String, *> = preferences.all.toMap()
        val directory = File(activity.filesDir, "local-diagnostic").apply { deleteRecursively() }
        val model = File(directory, "model.gguf")
        val builtInCopy = File(activity.cacheDir, "diagnostic-model.gguf").apply { delete() }
        var idle = true
        var idleChecks = 0
        var modelPickerLaunches = 0
        var videoPickerLaunches = 0
        val controller = PreparationDiagnosticsController(activity,
            playbackIdle = { idleChecks++; idle },
            launchModelPicker = { modelPickerLaunches++ },
            launchVideoPicker = { videoPickerLaunches++ })

        fun open(): AlertDialog {
            controller.show()
            shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
        }

        fun assertPreferencesUnchanged() = assertEquals(beforePreferences, preferences.all)

        fun assertNoModelWork() {
            assertFalse(model.exists())
            assertFalse(File(directory, "model-import.part").exists())
            assertFalse(builtInCopy.exists())
        }

        override fun close() {
            controller.close()
            host.pause().stop().destroy()
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

    private fun text(dialog: AlertDialog): String = descendants(requireNotNull(dialog.window).decorView)
        .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

    @Test fun constructionOpeningAndCloseArePassiveAndPreserveSettingsAndClipboard() {
        Fixture().use { f ->
            val clipboard = f.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Existing clip", "Explicit actions only"))
            val panel = f.open()
            assertTrue(text(panel).contains("Aucune mesure demandée."))
            assertTrue(text(panel).contains("Prêt. Aucun essai lancé."))
            assertEquals(0, f.idleChecks)
            assertEquals(0, f.modelPickerLaunches)
            assertEquals(0, f.videoPickerLaunches)
            f.assertNoModelWork()
            f.assertPreferencesUnchanged()

            f.controller.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(panel.isShowing)
            f.controller.show()
            f.controller.cancel()
            f.controller.onHostPaused()
            assertFalse(panel.isShowing)
            assertEquals(0, f.idleChecks)
            assertEquals(0, f.modelPickerLaunches)
            assertEquals(0, f.videoPickerLaunches)
            assertEquals("Explicit actions only", clipboard.primaryClip!!.getItemAt(0).text.toString())
            f.assertNoModelWork()
            f.assertPreferencesUnchanged()
        }
    }

    @Test fun activePlaybackRefusesVideoAndModelExecutionWithoutPausingOrChangingSettings() {
        Fixture().use { f ->
            f.idle = false
            val previousCopy = byteArrayOf(7, 8, 9, 10)
            f.model.writeBytes(previousCopy)
            val panel = f.open()
            val modelRun = button(panel, "Tester le modèle sur une phrase synthétique")
            assertTrue("The selected copy must be visible without trying to load it", modelRun.isEnabled)
            for (label in listOf("Tester la démonstration vidéo intégrée", "Tester le moteur CPU intégré",
                "Choisir une vidéo pour un essai de décodage", "Tester le modèle sur une phrase synthétique")) {
                val checksBefore = f.idleChecks
                button(panel, label).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Every execution action must consult the playback guard", f.idleChecks > checksBefore)
                assertTrue("The refusal should remain perceptible", text(panel).contains("Mets la lecture et les préparations en pause"))
            }
            assertFalse("The diagnostic must not change the source's reported playback state", f.idle)
            assertEquals(0, f.videoPickerLaunches)
            assertEquals(0, f.modelPickerLaunches)
            assertArrayEquals(previousCopy, f.model.readBytes())
            assertFalse(File(f.directory, "model-import.part").exists())
            assertFalse(f.builtInCopy.exists())
            f.assertPreferencesUnchanged()
        }
    }

    @Test fun pickerModelImportIsRefusedDuringPlaybackAndCannotReplaceEitherFile() {
        Fixture().use { f ->
            f.idle = false
            val source = File(f.activity.cacheDir, "synthetic-original-model.gguf").apply {
                writeBytes(byteArrayOf(11, 12, 13, 14, 15))
            }
            val previousCopy = byteArrayOf(41, 42, 43)
            f.model.writeBytes(previousCopy)
            val original = source.readBytes()
            val panel = f.open()
            button(panel, "Choisir un modèle GGUF local").performClick()
            assertEquals(1, f.modelPickerLaunches)
            f.controller.selectedModel(Uri.fromFile(source))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, f.idleChecks)
            assertTrue(text(panel).contains("Mets la lecture et les préparations en pause"))
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(previousCopy, f.model.readBytes())
            assertFalse(File(f.directory, "model-import.part").exists())

            // A refused result consumes the request. Replaying it after the
            // playback state changes must not import or launch anything.
            f.idle = true
            f.controller.selectedModel(Uri.fromFile(source))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, f.idleChecks)
            assertArrayEquals(previousCopy, f.model.readBytes())
            assertArrayEquals(original, source.readBytes())
            f.assertPreferencesUnchanged()
        }
    }

    @Test fun unrequestedAndPostClosePickerResultsAreIgnoredWithoutStartingWork() {
        Fixture().use { f ->
            val source = File(f.activity.cacheDir, "unrequested-synthetic-file.bin").apply {
                writeBytes(byteArrayOf(61, 62, 63))
            }
            val original = source.readBytes()
            val uri = Uri.fromFile(source)
            val panel = f.open()
            f.controller.selectedModel(uri)
            f.controller.selectedVideo(uri)
            f.controller.selectedModel(null)
            f.controller.selectedVideo(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, f.idleChecks)
            assertEquals(0, f.modelPickerLaunches)
            assertEquals(0, f.videoPickerLaunches)
            f.assertNoModelWork()

            button(panel, "Choisir un modèle GGUF local").performClick()
            assertEquals(1, f.modelPickerLaunches)
            f.controller.close()
            f.controller.selectedModel(uri)
            f.controller.selectedVideo(uri)
            f.controller.show()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(panel.isShowing)
            assertEquals(0, f.idleChecks)
            assertEquals(1, f.modelPickerLaunches)
            assertEquals(0, f.videoPickerLaunches)
            assertArrayEquals(original, source.readBytes())
            f.assertNoModelWork()
            f.assertPreferencesUnchanged()
        }
    }

    @Test fun explicitCopyDeletionRemovesCompleteAndOrphanedImportsWhilePreservingOriginal() {
        Fixture().use { f ->
            val original = File(f.activity.filesDir, "synthetic-original-outside-diagnostic.gguf").apply {
                writeBytes(byteArrayOf(71, 72, 73, 74))
            }
            val originalBytes = original.readBytes()
            val pendingImport = File(f.directory, "model-import.part")
            f.model.writeBytes(byteArrayOf(81, 82))
            pendingImport.writeBytes(byteArrayOf(91, 92, 93))
            val panel = f.open()
            val clear = button(panel, "Supprimer la copie du modèle importé")

            assertTrue(clear.isEnabled)
            clear.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(f.model.exists())
            assertFalse(pendingImport.exists())
            assertArrayEquals(originalBytes, original.readBytes())

            // A killed process may leave only the partial file. It must stay
            // removable even when there is no complete model to report ready.
            pendingImport.writeBytes(byteArrayOf(94, 95))
            assertTrue(clear.isEnabled)
            clear.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(f.model.exists())
            assertFalse(pendingImport.exists())
            assertArrayEquals(originalBytes, original.readBytes())
            assertTrue(text(panel).contains("import incomplet supprimés"))
            assertEquals(0, f.modelPickerLaunches)
            assertEquals(0, f.videoPickerLaunches)
            f.assertPreferencesUnchanged()
        }
    }

    @Test fun importingReplacementRemovesPreviousModelsExecutionResult() {
        Fixture().use { f ->
            val bytes = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
            val original = File(f.activity.cacheDir, "replacement-synthetic.gguf").apply { writeBytes(bytes) }
            @Suppress("UNCHECKED_CAST")
            val reports = PreparationDiagnosticsController::class.java.getDeclaredField("reports").let {
                it.isAccessible = true
                it.get(f.controller) as MutableMap<String, String>
            }
            reports["model"] = "Ancien modèle : 9,74 tokens par seconde — résultat périmé"
            val panel = f.open()
            assertTrue(text(panel).contains("résultat périmé"))
            button(panel, "Choisir un modèle GGUF local").performClick()
            f.controller.selectedModel(Uri.fromFile(original))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!text(panel).contains("Copie privée complète") && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(text(panel).contains("Copie privée complète : 40 octets"))
            assertFalse("An import must not present the old model's inference as the new model's result",
                text(panel).contains("résultat périmé"))
            assertTrue(text(panel).contains("lance un nouvel essai"))
            assertArrayEquals(bytes, original.readBytes())
            assertArrayEquals(bytes, f.model.readBytes())
            assertNotNull(LocalGgufModelStore(f.activity).current())
        }
    }

    @Test fun modelInUseCannotBeDeletedByTheDiagnostic() {
        Fixture().use { f ->
            f.model.writeBytes(byteArrayOf(1, 2, 3))
            val panel = f.open()
            LocalGgufModelStore(f.activity).acquireMutationLease().use {
                button(panel, "Supprimer la copie du modèle importé").performClick()
                assertTrue(f.model.exists())
                assertTrue(text(panel).contains("Attends sa fin avant de supprimer"))
            }
            button(panel, "Supprimer la copie du modèle importé").performClick()
            assertFalse(f.model.exists())
        }
    }
}
