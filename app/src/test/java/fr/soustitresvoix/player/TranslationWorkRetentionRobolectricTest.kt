package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationWorkRetentionRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val plan get() = TranslationPlan.create(listOf(
        TranslationCue("first", 0, 1_000_000, "Source one"),
        TranslationCue("second", 2_000_000, 3_000_000, "Source two")
    ), "en", "fr", "fixture")
    private val firstKey = "a".repeat(64)
    private val secondKey = "b".repeat(64)

    @Test fun deletingOneWorkKeepsAnotherWorkAndOriginalFilesEvenForTheSameSourcePlan() {
        val cache = temporary.newFolder(); val retained = temporary.newFolder()
        val runner = TranslationRunner(cache, retainedDirectory = retained)
        val source = plan
        val first = source.complete(listOf("Version une", "Deux"))
        val second = source.complete(listOf("Version conservée ailleurs", "Deux"))
        assertTrue(runner.retainForWork(firstKey, source, first))
        assertTrue(runner.retainForWork(secondKey, source, second))
        val original = File(retained, "original.srt").apply { writeText("source conservée") }
        val export = File(File(retained, "works/$firstKey"), "export.srt").apply { writeText("export indépendant") }
        assertEquals(2, runner.retainedStorageSummary().first)
        assertEquals(first.fingerprint, runner.readExisting(source, first.fingerprint)?.fingerprint)
        assertEquals(second.fingerprint, runner.readExisting(source, second.fingerprint)?.fingerprint)
        assertNull(runner.readExisting(source, "f".repeat(64)))
        assertTrue(runner.removeRetainedForWork(firstKey))
        assertEquals(second.fingerprint, TranslationRunner(cache, retainedDirectory = retained).readExisting(source)?.fingerprint)
        assertEquals("source conservée", original.readText())
        assertEquals("export indépendant", export.readText())
        assertEquals(1, runner.retainedStorageSummary().first)
        assertTrue(runner.removeRetainedForWork(secondKey))
        assertNull(runner.readExisting(source))
    }

    @Test fun workCopiesRestoreLocallyWithoutProviderAndRemoveIncludesAtomicBackups() {
        val cache = temporary.newFolder(); val retained = temporary.newFolder()
        val source = plan
        val result = source.complete(listOf("Première traduction", "Deuxième traduction"))
        val runner = TranslationRunner(cache, retainedDirectory = retained)
        assertTrue(runner.retainForWork(firstKey, source, result))
        val base = File(retained, "works/$firstKey/${source.fingerprint}.track")
        assertTrue(base.renameTo(File(base.path + ".bak")))
        var calls = 0
        val provider = object : TranslationEngine {
            override val id = "fixture"
            override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                cancelled: () -> Boolean): List<String> { calls++; throw AssertionError("No provider request expected") }
            override fun close() = Unit
        }
        val restored = TranslationRunner(cache, retainedDirectory = retained)
            .run(source, provider, { false }) { _, _ -> }
        assertEquals(result.fingerprint, restored.fingerprint)
        assertEquals(0, calls)
        base.copyTo(File(base.path + ".bak"))
        assertTrue(runner.removeRetainedForWork(firstKey))
        assertFalse(base.exists())
        assertFalse(File(base.path + ".bak").exists())
        assertNull(runner.readExisting(source))
    }

    @Test fun separateManualRetentionSurvivesWorkDeletionAndUnsafeKeysCannotReachOutsideWork() {
        val cache = temporary.newFolder(); val retained = temporary.newFolder()
        val source = plan; val result = source.complete(listOf("Une", "Deux"))
        val runner = TranslationRunner(cache, retainedDirectory = retained)
        assertTrue(runner.retain(source, result))
        assertTrue(runner.retainForWork(firstKey, source, result))
        assertFalse(runner.retainForWork("../outside", source, result))
        assertFalse(runner.removeRetainedForWork("../outside"))
        assertTrue(runner.removeRetainedForWork(firstKey))
        assertEquals(result.fingerprint, runner.readExisting(source)?.fingerprint)
        assertEquals(1, runner.retainedStorageSummary().first)
        assertTrue(runner.removeRetained(source))
        assertNull(runner.readExisting(source))
    }
}
