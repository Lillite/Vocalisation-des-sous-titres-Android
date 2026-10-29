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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationResumeRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val plan get() = TranslationPlan.create((0 until 43).map {
        TranslationCue("id-$it", it * 5_000_000L, it * 5_000_000L + 2_000_000L, "Source $it")
    }, "en", "fr", "fixture")
    private class Engine(private val failAt: Int? = null) : TranslationEngine {
        override val id = "fixture"
        var calls = 0
        val inputs = mutableListOf<String>()
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String, cancelled: () -> Boolean): List<String> {
            calls++; if (calls == failAt) throw TranslationFailure(TranslationFailure.Reason.NETWORK)
            inputs.addAll(texts); return texts.map { "Traduit $it" }
        }
        override fun close() { }
    }
    @Test fun validatedBatchSurvivesNewRunnerAndUncertainRequestIsExplicit() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder()
        val original = plan
        val first = Engine(2)
        try { TranslationRunner(cache, progress).run(original, first, { false }) { _, _ -> }; fail() }
        catch (_: TranslationFailure) { }
        val runner = TranslationRunner(cache, progress)
        assertEquals(20 to true, runner.checkpointSummary(original))
        val resumed = Engine()
        val prefixes = mutableListOf<Int>()
        val track = runner.run(original, resumed, { false }, { _, _ -> }, TranslationRunPolicy()) { prefixes += it.size }
        assertEquals(2, resumed.calls)
        assertFalse(resumed.inputs.contains("Source 0"))
        assertEquals(43, track.cues.size)
        assertEquals(listOf(20, 40, 43), prefixes)
        assertEquals(0 to false, runner.checkpointSummary(original))
    }
    @Test fun newCharacterBudgetRejectsBeforeAnyRequestButCacheHitIsFree() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder()
        val runner = TranslationRunner(cache, progress)
        val denied = Engine()
        try { runner.run(plan, denied, { false }, { _, _ -> }, TranslationRunPolicy(1)); fail() }
        catch (_: TranslationFailure) { }
        assertEquals(0, denied.calls)
        runner.run(plan, Engine(), { false }) { _, _ -> }
        val hit = Engine()
        assertEquals(43, runner.run(plan, hit, { false }, { _, _ -> }, TranslationRunPolicy(0)).cues.size)
        assertEquals(0, hit.calls)
    }
    @Test fun durableSavedEditWinsOverEvictableOriginalCacheAndSurvivesItsDeletion() {
        val cache = temporary.newFolder(); val retained = temporary.newFolder()
        val runner = TranslationRunner(cache, retainedDirectory = retained)
        val source = plan
        val original = runner.run(source, Engine(), { false }) { _, _ -> }
        val edited = source.complete(original.cues.mapIndexed { i, value -> if (i == 0) "Correction personnelle" else value.text })
        assertTrue(runner.retain(source, edited))
        assertEquals(edited.fingerprint, runner.readExisting(source)?.fingerprint)
        runner.clear()
        val noCalls = Engine()
        val restored = TranslationRunner(cache, retainedDirectory = retained).run(source, noCalls, { false }) { _, _ -> }
        assertEquals("Correction personnelle", restored.cues.first().text)
        assertEquals(0, noCalls.calls)
    }
    @Test fun optionsIdentityCannotCrossResumeAndDamagedSuffixKeepsItsVerifiedPrefix() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder()
        val source = plan
        try { TranslationRunner(cache, progress).run(source, Engine(2), { false }) { _, _ -> } }
        catch (_: TranslationFailure) { }
        val altered = TranslationPlan.create(source.cues, "en", "fr", "fixture-options")
        assertEquals(0 to false, TranslationRunner(cache, progress).checkpointSummary(altered))
        progress.listFiles()!!.single().appendText("corrupt")
        assertEquals(20 to true, TranslationRunner(cache, progress).checkpointSummary(source))
    }
    @Test fun failedCompleteCacheWriteKeepsFullJournalReusableWithoutAnApiCall() {
        val invalidCache = temporary.newFile(); val progress = temporary.newFolder()
        val source = plan
        val first = TranslationRunner(invalidCache, progress)
        assertEquals(43, first.run(source, Engine(), { false }) { _, _ -> }.cues.size)
        assertNotNull(first.cacheWarning)
        val replacement = TranslationRunner(temporary.newFolder(), progress)
        assertEquals(43 to false, replacement.checkpointSummary(source))
        val engine = Engine()
        assertEquals(43, replacement.run(source, engine, { false }, { _, _ -> }, TranslationRunPolicy(0)).cues.size)
        assertEquals(0, engine.calls)
    }
    @Test fun checkpointAtomicBackupRestoresWhenBaseIsMissingAfterInterruption() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder()
        val source = plan
        try { TranslationRunner(cache, progress).run(source, Engine(2), { false }) { _, _ -> } }
        catch (_: TranslationFailure) { }
        val base = progress.listFiles()!!.single()
        assertTrue(base.renameTo(java.io.File(progress, base.name + ".bak")))
        assertEquals(20 to true, TranslationRunner(cache, progress).checkpointSummary(source))
    }

    @Test fun singleOccurrenceEngineResumesExactlyAfterLastValidatedCueIncludingRepeatedTexts() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder()
        val source = TranslationPlan.create((0 until 7).map {
            TranslationCue("same-$it", it * 2_000_000L, it * 2_000_000L + 1_000_000, "Again")
        }, "en", "fr", "single")
        fun engine(failAt: Int?, inputs: MutableList<String>) = object : TranslationEngine {
            override val id = "single"
            override val maxBatchItems = 1
            override val maxBatchCharacters = 100
            private var calls = 0
            override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String, cancelled: () -> Boolean): List<String> {
                assertEquals(1, texts.size)
                if (++calls == failAt) throw TranslationFailure(TranslationFailure.Reason.NETWORK)
                inputs.addAll(texts)
                return texts.map { "Encore" }
            }
            override fun close() = Unit
        }
        val firstInputs = mutableListOf<String>()
        try { TranslationRunner(cache, progress).run(source, engine(4, firstInputs), { false }) { _, _ -> }; fail() }
        catch (_: TranslationFailure) { }
        assertEquals(3 to true, TranslationRunner(cache, progress).checkpointSummary(source))
        val nextInputs = mutableListOf<String>()
        val track = TranslationRunner(cache, progress).run(source, engine(null, nextInputs), { false }) { _, _ -> }
        assertEquals(3, firstInputs.size)
        assertEquals(4, nextInputs.size)
        assertEquals(source.cues.map { it.id }, track.cues.map { it.id })
        assertEquals(List(7) { "Encore" }, track.cues.map { it.text })
    }

    @Test fun engineSpecificCharacterLimitRejectsTheWholePlanBeforeSendingItsFirstCue() {
        val source = TranslationPlan.create(listOf(
            TranslationCue("short", 0, 1, "Hi"), TranslationCue("long", 1, 2, "x".repeat(11))
        ), "en", "fr", "bounded")
        var calls = 0
        val engine = object : TranslationEngine {
            override val id = "bounded"
            override val maxBatchItems = 1
            override val maxBatchCharacters = 10
            override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String, cancelled: () -> Boolean): List<String> {
                calls++; return texts
            }
            override fun close() = Unit
        }
        try { TranslationRunner(temporary.newFolder()).run(source, engine, { false }) { _, _ -> }; fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

}
