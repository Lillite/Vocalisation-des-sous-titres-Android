package fr.soustitresvoix.player

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Spinner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selection, ownership and fingerprinting checks; these never load a native model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufModelControlsRobolectricTest {
    private class Fixture : AutoCloseable {
        private val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        init { activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE).edit().clear().commit() }
        val directory = File(activity.filesDir, "local-diagnostic").apply { deleteRecursively(); mkdirs() }
        var preparationBusy = false
        var launches = 0
        var changed = 0
        val messages = mutableListOf<String>()
        val controls = LocalGgufModelControls(activity, { preparationBusy }, { launches++ },
            onChanged = { changed++ }, onMessage = { messages += it })
        val root = controls.view().also { activity.setContentView(it) }
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text.toString() == label }
        fun text() = descendants(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        override fun close() { controls.close(); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    companion object {
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The explicit small background operation must finish", condition())
    }

    @Test fun openingAndUnrequestedPickerResultsArePassive() {
        Fixture().use { f ->
            f.controls.selected(Uri.parse("content://unrequested.synthetic/model.gguf"))
            f.controls.selected(null)
            assertNull(f.controls.model)
            assertFalse(f.controls.busy)
            assertEquals(0, f.launches)
            assertEquals(0, f.changed)
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
            assertTrue(f.text().contains("Aucun téléchargement de modèle"))
        }
    }

    @Test fun busyTranslationRefusesBothModelReplacementAndLegacyPreparation() {
        Fixture().use { f ->
            val original = byteArrayOf(1, 2, 3, 4)
            File(f.directory, "model.gguf").writeBytes(original)
            // Recreate visible controls after writing the legacy file so the
            // explicit prepare action is exposed, without preparing it itself.
            f.controls.close()
            f.preparationBusy = true
            val other = LocalGgufModelControls(f.activity, { f.preparationBusy }, { f.launches++ }, {}, { f.messages += it })
            try {
                val view = other.view()
                for (label in listOf("Choisir un modèle GGUF local pour traduire", "Préparer le modèle GGUF déjà importé dans le diagnostic"))
                    descendants(view).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
                assertEquals(0, f.launches)
                assertFalse(other.busy)
                assertNull(other.model)
                assertEquals(2, f.messages.size)
                assertTrue(f.messages.all { it.contains("Termine ou annule") })
                assertArrayEquals(original, File(f.directory, "model.gguf").readBytes())
                assertFalse(File(f.directory, "model.sha256").exists())
            } finally { other.close() }
        }
    }

    @Test fun closingOrSwitchingProviderConsumesPendingPickerResultsAndDetachedActions() {
        Fixture().use { f ->
            val choose = f.button("Choisir un modèle GGUF local pour traduire")
            choose.performClick()
            assertEquals(1, f.launches)
            f.controls.setActive(false)
            f.controls.selected(Uri.parse("content://stale.synthetic/model.gguf"))
            assertFalse(f.controls.busy)
            f.controls.setActive(true)
            choose.performClick()
            assertEquals(2, f.launches)
            f.controls.close()
            f.controls.selected(Uri.parse("content://postclose.synthetic/model.gguf"))
            choose.performClick()
            assertEquals(2, f.launches)
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun explicitLegacyPreparationComputesIdentityWithoutChangingOrLoadingWeights() {
        Fixture().use { f ->
            f.controls.close()
            val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
                .putInt(3).putLong(0).putLong(0).array()
            val model = File(f.directory, "model.gguf").apply { writeBytes(bytes) }
            val controls = LocalGgufModelControls(f.activity, { false }, { f.launches++ }, { f.changed++ }, { f.messages += it })
            try {
                val root = controls.view()
                assertNull(controls.model)
                descendants(root).filterIsInstance<Button>().single {
                    it.text.toString() == "Préparer le modèle GGUF déjà importé dans le diagnostic"
                }.performClick()
                await { !controls.busy }
                val prepared = requireNotNull(controls.model)
                assertEquals(24L, prepared.bytes)
                assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }, prepared.sha256)
                assertArrayEquals(bytes, model.readBytes())
                assertEquals(1, f.changed)
                assertEquals(0, f.launches)
                val text = descendants(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(text.contains("Le chargement sera vérifié lors de la traduction"))
                assertFalse(text.contains(model.absolutePath))
            } finally { controls.close() }
        }
    }

    @Test fun executionAndProfileChoicesAreExplicitPersistedAndNeverStartAModel() {
        Fixture().use { f ->
            val execution = descendants(f.root).filterIsInstance<Spinner>().single { it.contentDescription == "Exécution du modèle GGUF" }
            val profile = descendants(f.root).filterIsInstance<Spinner>().single { it.contentDescription == "Profil de traduction du modèle GGUF" }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("AUTO", f.controls.execution)
            assertEquals(LocalGgufProfile.AUTO, f.controls.profile)
            execution.setSelection(2); profile.setSelection(2)
            // Configuration snapshots must see the selected item even before
            // AdapterView delivers a deferred selection callback.
            assertEquals("OPENCL", f.controls.execution)
            assertEquals(LocalGgufProfile.HY_MT, f.controls.profile)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("OPENCL", f.controls.execution)
            assertEquals(LocalGgufProfile.HY_MT, f.controls.profile)
            f.controls.saveChoices()
            assertFalse(f.controls.busy)
            assertEquals(0, f.launches)
            assertEquals(0, f.changed)
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
            val restored = LocalGgufModelControls(f.activity, { false }, { f.launches++ }, {}, {})
            try {
                restored.view()
                assertEquals("OPENCL", restored.execution)
                assertEquals(LocalGgufProfile.HY_MT, restored.profile)
            } finally { restored.close() }
        }
    }

    @Test fun everyPublishedProfileIsOfferedOnceWithItsReadableCanonicalLabelAndSelectionIsPassive() {
        Fixture().use { f ->
            val spinner = descendants(f.root).filterIsInstance<Spinner>().single {
                it.contentDescription == "Profil de traduction du modèle GGUF"
            }
            val profiles = LocalGgufProfile.entries
            val parent = f.root as ViewGroup
            assertEquals(profiles.size, spinner.count)
            assertEquals(profiles.size, (0 until spinner.count).map { spinner.getItemAtPosition(it).toString() }.distinct().size)
            assertTrue(profiles.containsAll(listOf(LocalGgufProfile.INDEX_TRANSLATE, LocalGgufProfile.MODEL_TEMPLATE)))
            for ((index, profile) in profiles.withIndex()) {
                assertEquals(profile.label, spinner.getItemAtPosition(index).toString())
                spinner.setSelection(index)
                assertEquals("Snapshots must see explicit selection before a deferred AdapterView callback", profile, f.controls.profile)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(profile, f.controls.profile)
                f.controls.saveChoices()
                assertEquals(profile.name, f.activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
                    .getString("local-profile", null))
                for (row in listOf(spinner.adapter.getView(index, null, parent),
                    spinner.adapter.getDropDownView(index, null, parent))) {
                    val text = row as TextView
                    assertEquals(profile.label, text.text.toString())
                    assertTrue(text.textSize + .1f >= 18f * f.activity.resources.displayMetrics.scaledDensity)
                    assertNull(text.ellipsize)
                    assertTrue(text.maxLines > 1)
                    assertTrue(text.minimumHeight >= (56 * f.activity.resources.displayMetrics.density).toInt())
                }
            }
            assertNull(f.controls.model)
            assertFalse(f.controls.busy)
            assertEquals(0, f.launches)
            assertEquals(0, f.changed)
            assertTrue(f.messages.isEmpty())
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
            assertTrue(f.text().contains("format de conversation reconnu dans le GGUF"))
            assertTrue(f.text().contains("son absence ou une incompatibilité est signalée"))
            assertTrue(f.text().contains("Les gabarits personnalisés et les architectures absentes du moteur peuvent rester incompatibles"))
        }
    }

    @Test fun legacyAndNewProfilePreferencesRestoreByNameWithoutImportOrAutomaticExecution() {
        Fixture().use { f ->
            f.controls.close()
            val preferences = f.activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
            for (stored in LocalGgufProfile.entries.map { it.name } + "UNKNOWN_FUTURE_PROFILE") {
                preferences.edit().putString("local-profile", stored).commit()
                val expected = LocalGgufProfile.entries.firstOrNull { it.name == stored } ?: LocalGgufProfile.AUTO
                val controls = LocalGgufModelControls(f.activity, { false }, { f.launches++ }, { f.changed++ }, { f.messages += it })
                try {
                    val view = controls.view()
                    val spinner = descendants(view).filterIsInstance<Spinner>().single {
                        it.contentDescription == "Profil de traduction du modèle GGUF"
                    }
                    assertEquals(expected, controls.profile)
                    assertEquals(expected.label, spinner.selectedItem.toString())
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(expected, controls.profile)
                    assertEquals("Opening the form must not rewrite a stored choice", stored, preferences.getString("local-profile", null))
                    assertNull(controls.model)
                    assertFalse(controls.busy)
                } finally { controls.close() }
            }
            assertEquals(0, f.launches)
            assertEquals(0, f.changed)
            assertTrue(f.messages.isEmpty())
            assertTrue(f.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellationAfterACommittedPreparationAdoptsTheCurrentDescriptorInsteadOfTheOldHash() {
        Fixture().use { f ->
            f.controls.close()
            fun header(tensors: Long) = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(tensors).putLong(0).array()
            val store = LocalGgufModelStore(f.activity)
            val originalHeader = header(0)
            val old = store.importModel(ByteArrayInputStream(originalHeader), originalHeader.size.toLong())
            val controls = LocalGgufModelControls(f.activity, { false }, {}, { f.changed++ }, { f.messages += it })
            try {
                val root = controls.view()
                assertEquals(old.sha256, controls.model?.sha256)
                // Represents another explicit import replacing the shared
                // diagnostic file while this form still shows its old choice.
                val replacementHeader = header(1)
                val privateFile = File(f.directory, "model.gguf")
                privateFile.writeBytes(replacementHeader)
                assertTrue(privateFile.setLastModified(old.modifiedMillis + 2_000))
                descendants(root).filterIsInstance<Button>().single {
                    it.text.toString() == "Préparer le modèle GGUF déjà importé dans le diagnostic"
                }.performClick()
                val deadline = System.nanoTime() + 5_000_000_000L
                var committed = store.current()
                // Deliberately do not drain the main looper: the worker commits
                // its descriptor before the form receives its success callback.
                while ((committed == null || committed.sha256 == old.sha256) && System.nanoTime() < deadline) {
                    Thread.sleep(5); committed = store.current()
                }
                val current = requireNotNull(committed)
                assertNotEquals(old.sha256, current.sha256)
                assertTrue(controls.busy)
                controls.cancel()
                await { !controls.busy }
                assertEquals(current.sha256, controls.model?.sha256)
                assertEquals(store.current()?.sha256, controls.model?.sha256)
                assertEquals(1, f.changed)
                assertTrue(f.messages.last().contains("préparé avant l’annulation"))
                assertArrayEquals(replacementHeader, privateFile.readBytes())
            } finally { controls.close() }
        }
    }
    @Test fun contextChoiceIsExplicitPersistedAndDisabledDuringAnImport() {
        Fixture().use { f ->
            val choice = descendants(f.root).filterIsInstance<Spinner>().single { it.contentDescription == "Contexte des sous-titres GGUF" }
            assertEquals(LocalGgufContextPolicy.RECENT_TWO, f.controls.contextPolicy)
            choice.setSelection(2)
            assertEquals(LocalGgufContextPolicy.BLOCKS_SIX, f.controls.contextPolicy)
            f.controls.saveChoices()
            f.controls.setActive(false)
            assertFalse(choice.isEnabled)
            val restored = LocalGgufModelControls(f.activity, { false }, {}, {}, {})
            try {
                restored.view()
                assertEquals(LocalGgufContextPolicy.BLOCKS_SIX, restored.contextPolicy)
                assertFalse(restored.busy)
                assertEquals(0, f.launches)
                assertTrue(f.directory.listFiles().orEmpty().isEmpty())
            } finally { restored.close() }
        }
    }

}
