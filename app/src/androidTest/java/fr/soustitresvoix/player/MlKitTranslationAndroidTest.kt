package fr.soustitresvoix.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Validates the release APK integration without downloading Google models or
 * sending text. Linguistic quality and real model downloads need separate trials. */
@RunWith(AndroidJUnit4::class)
class MlKitTranslationAndroidTest {
    @Test fun officialSdkLanguageRegistryIsAvailableAndRejectsUnsupportedLanguages() {
        val languages = MlKitTranslationEngine.supportedLanguages()
        assertTrue(languages.containsAll(listOf("en", "fr", "es", "ja", "de")))
        assertEquals("fr", MlKitTranslationEngine.canonicalLanguage("fr"))
        assertNull(MlKitTranslationEngine.canonicalLanguage("zz"))
        assertNull(MlKitTranslationEngine.canonicalLanguage("auto"))
    }

    @Test fun localAdapterAndRunnerPreserveEveryOccurrenceAndTimingAcrossBatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "mlkit-runner-").toFile()
        val submitted = mutableListOf<String>()
        var preparations = 0
        val backend = object : MlKitTranslationBackend {
            override fun prepareModels(allowMobileDownload: Boolean): Task<Void> {
                assertFalse(allowMobileDownload); preparations++; return Tasks.forResult(null)
            }
            override fun translate(text: String): Task<String> {
                submitted += text; return Tasks.forResult("Français $text")
            }
            override fun close() { }
        }
        try {
            val plan = TranslationPlan.create((0 until 45).map { index ->
                TranslationCue("local-$index", index * 3_000_000L, index * 3_000_000L + 2_000_000L,
                    when {
                        index == 0 -> "Long synthetic subtitle ".repeat(250)
                        index in 19..21 -> "EXIT"
                        else -> "Synthetic line $index"
                    })
            }, "en", "fr", "google-local")
            MlKitTranslationEngine("google-local", false, { _, _ -> backend }).use { engine ->
                val prefixes = mutableListOf<Int>()
                val result = TranslationRunner(directory.resolve("cache"), directory.resolve("progress"))
                    .run(plan, engine, { false }, { _, _ -> }, TranslationRunPolicy(), { prefixes += it.size })
                assertEquals(plan.texts, submitted)
                assertEquals(1, preparations)
                assertEquals(plan.cues.map { it.id }, result.cues.map { it.id })
                assertEquals(plan.cues.map { it.startUs to it.endUs }, result.cues.map { it.startUs to it.endUs })
                assertEquals(plan.texts.map { "Français $it" }, result.cues.map { it.text })
                assertEquals(plan.cues.size, prefixes.last())
                assertTrue(prefixes.zipWithNext().all { (a, b) -> b > a })
            }
        } finally { directory.deleteRecursively() }
    }
}
