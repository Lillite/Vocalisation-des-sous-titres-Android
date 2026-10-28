package fr.soustitresvoix.player

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
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
}
