package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExactTranslationRequestCacheRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val policy = TranslationRunPolicy(requestMayConsumeRemoteQuota = false)
    private fun plan(texts: List<String>, edition: Int = 0, provider: String = "local-exact-v4-fixture") = TranslationPlan.create(
        texts.mapIndexed { index, text -> TranslationCue("edition-$edition-$index", index * 1000L + edition,
            index * 1000L + edition + 2000, text) }, "en", "fr", provider)
    private class Engine(override val id: String = "local-exact-v4-fixture", private val isolated: Boolean = true,
        override val supportsExactRequestCache: Boolean = true, private val onHit: () -> Unit = {},
        private val rejectCachedRequest: Boolean = false) : ContextualTranslationEngine {
        override val requiresDurableResult = true
        override val maxBatchItems = 1
        override val maxBatchCharacters = TranslationPlan.MAX_CUE_CHARACTERS
        var calls = 0; var hits = 0; var validations = 0
        override fun contextStartIndex(occurrenceIndex: Int) = if (isolated) occurrenceIndex else (occurrenceIndex - 2).coerceAtLeast(0)
        override fun onExactRequestCacheHit() { hits++; onHit() }
        override fun validateExactRequestCacheHit(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean) {
            validations++
            if (cancelled()) throw TranslationCancelledException()
            if (rejectCachedRequest) throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        }
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> = throw AssertionError("Contextual path required")
        override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
            calls++; return texts.map { "Traduit $it" }
        }
        override fun close() = Unit
    }

    @Test fun identicalRequestsReuseResultsWhileEveryRepeatedOccurrenceKeepsItsOwnIdentityAndTiming() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val runner = TranslationRunner(cache, progress, retained)
        val source = plan(List(3) { "Again 😀" }); val first = Engine()
        val result = runner.run(source, first, { false }, { _, _ -> }, policy)
        assertEquals(1, first.calls); assertEquals(2, first.hits)
        assertEquals(2, first.validations)
        assertEquals(source.cues.map { Triple(it.id, it.startUs, it.endUs) }, result.cues.map { Triple(it.id, it.startUs, it.endUs) })
        assertEquals(List(3) { "Traduit Again 😀" }, result.cues.map { it.text })
        val other = plan(source.texts, edition = 50); val second = Engine()
        val restored = TranslationRunner(cache, progress, retained).run(other, second, { false }, { _, _ -> }, policy)
        assertEquals(0, second.calls); assertEquals(3, second.hits)
        assertEquals(3, second.validations)
        assertEquals(other.cues.map { it.id }, restored.cues.map { it.id })
        runner.clear()
        assertTrue(File(cache, "requests").listFiles().orEmpty().isEmpty())
        val afterClear = Engine()
        runner.run(plan(source.texts, edition = 100), afterClear, { false }, { _, _ -> }, policy)
        assertEquals(1, afterClear.calls); assertEquals(2, afterClear.hits)
    }

    @Test fun changedHistoricalSourcesOrTranslationsAreDifferentRequests() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val first = plan(listOf("First", "Again")); val firstEngine = Engine(isolated = false)
        TranslationRunner(cache, progress, retained).run(first, firstEngine, { false }, { _, _ -> }, policy)
        assertEquals(2, firstEngine.calls)
        val changedSource = plan(listOf("Other", "Again"), 1); val secondEngine = Engine(isolated = false)
        TranslationRunner(cache, progress, retained).run(changedSource, secondEngine, { false }, { _, _ -> }, policy)
        assertEquals(2, secondEngine.calls); assertEquals(0, secondEngine.hits)
        val changedTranslation = plan(first.texts, 2)
        assertTrue(TranslationCheckpointStore(progress).write(changedTranslation, listOf("Modification utilisateur"), false))
        val thirdEngine = Engine(isolated = false)
        val result = TranslationRunner(cache, progress, retained).run(changedTranslation, thirdEngine, { false }, { _, _ -> }, policy)
        assertEquals(1, thirdEngine.calls); assertEquals(0, thirdEngine.hits)
        assertEquals("Modification utilisateur", result.cues.first().text)
    }

    @Test fun optOutAndRemoteQuotaPolicyNeverUseTheLocalRequestCache() {
        for (optIn in listOf(false, true)) {
            val cache = temporary.newFolder(); val source = plan(List(2) { "Again" })
            val engine = Engine(supportsExactRequestCache = optIn)
            val selectedPolicy = if (optIn) TranslationRunPolicy(requestMayConsumeRemoteQuota = true) else policy
            TranslationRunner(cache, temporary.newFolder(), temporary.newFolder()).run(source, engine, { false }, { _, _ -> }, selectedPolicy)
            assertEquals(2, engine.calls); assertEquals(0, engine.hits)
            assertFalse(File(cache, "requests").exists())
        }
    }

    @Test fun corruptedOrStructurallyInvalidCachedResultsAreRecomputed() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val source = plan(listOf("Hello"))
        TranslationRunner(cache, progress, retained).run(source, Engine(), { false }, { _, _ -> }, policy)
        val requestDirectory = File(cache, "requests")
        val entry = requestDirectory.listFiles()!!.single()
        val corrupt = entry.readBytes(); corrupt[0] = (corrupt[0].toInt() xor 1).toByte(); entry.writeBytes(corrupt)
        val second = Engine()
        TranslationRunner(cache, progress, retained).run(plan(source.texts, 1), second, { false }, { _, _ -> }, policy)
        assertEquals(1, second.calls); assertEquals(0, second.hits)
        assertEquals(0, second.validations)
        val requests = ExactTranslationRequestCache(requestDirectory)
        val key = requests.key(source.providerId, "en", "fr", source.texts, emptyList(), emptyList())
        requests.write(key, listOf("One", "Extra result"))
        val third = Engine()
        val result = TranslationRunner(cache, progress, retained).run(plan(source.texts, 2), third, { false }, { _, _ -> }, policy)
        assertEquals(1, third.calls); assertEquals(0, third.hits)
        assertEquals(1, third.validations)
        assertEquals(listOf("Traduit Hello"), result.cues.map { it.text })
        val refused = Engine(rejectCachedRequest = true)
        val refusedPlan = plan(source.texts, 3)
        val refusedRunner = TranslationRunner(cache, progress, retained)
        val prefixes = mutableListOf<List<String>>()
        try {
            refusedRunner.run(refusedPlan, refused, { false }, { _, _ -> }, policy) { prefixes += it }
            fail("A request rejected by the engine cannot accept a cached response")
        } catch (failure: TranslationFailure) {
            assertEquals(TranslationFailure.Reason.CONFIGURATION, failure.reason)
        }
        assertEquals(1, refused.validations); assertEquals(0, refused.calls); assertEquals(0, refused.hits)
        assertTrue(prefixes.isEmpty()); assertNull(refusedRunner.readExisting(refusedPlan))
        assertEquals(0 to false, refusedRunner.checkpointSummary(refusedPlan))
    }

    @Test fun cancellationDuringCacheHitNotificationCannotPublishOrActivateItsResult() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val original = plan(listOf("Hello"))
        TranslationRunner(cache, progress, retained).run(original, Engine(), { false }, { _, _ -> }, policy)
        var cancelled = false
        val source = plan(original.texts, 1)
        val engine = Engine(onHit = { cancelled = true }); val prefixes = mutableListOf<List<String>>()
        val runner = TranslationRunner(cache, progress, retained)
        try {
            runner.run(source, engine, { cancelled }, { _, _ -> }, policy) { prefixes += it }
            fail("Cancelled cache hit must not complete")
        } catch (_: TranslationCancelledException) { }
        assertEquals(0, engine.calls); assertEquals(1, engine.hits)
        assertTrue(prefixes.isEmpty()); assertNull(runner.readExisting(source))
        assertEquals(1 to false, runner.checkpointSummary(source))
    }

    @Test fun boundedCacheEvictsAndClearsOnlyOwnedEntriesAndKeysSeparateEveryInputBoundary() {
        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = requests.key("engine-a", "en", "fr", listOf("ab", "c"), listOf("past"), listOf("passé"))
        assertNotEquals(key, requests.key("engine-a", "en", "fr", listOf("a", "bc"), listOf("past"), listOf("passé")))
        assertNotEquals(key, requests.key("engine-b", "en", "fr", listOf("ab", "c"), listOf("past"), listOf("passé")))
        assertNotEquals(key, requests.key("engine-a", "de", "fr", listOf("ab", "c"), listOf("past"), listOf("passé")))
        assertNotEquals(key, requests.key("engine-a", "en", "fr", listOf("ab", "c"), listOf("past"), listOf("autre")))
        val old = File(directory, "${"a".repeat(64)}.request")
        RandomAccessFile(old, "rw").use { it.setLength(ExactTranslationRequestCache.MAX_BYTES) }
        old.setLastModified(1)
        val oldSiblings = listOf(".bak", ".new").map { suffix ->
            File(directory, old.name + suffix).apply {
                RandomAccessFile(this, "rw").use { it.setLength(1024 * 1024) }
                setLastModified(1)
            }
        }
        // Interrupted AtomicFile writes can leave only a backup/new file for a
        // key. They still occupy the request cache's advertised disk budget.
        repeat(10) { index ->
            val name = "%064x.request".format(index + 1) + if (index % 2 == 0) ".bak" else ".new"
            RandomAccessFile(File(directory, name), "rw").use { it.setLength(1024 * 1024) }
        }
        val unrelated = File(directory, "keep-original.txt").apply { writeText("Untouched") }
        val lookalike = File(directory, "${"f".repeat(64)}.request.bak.keep").apply { writeText("Unrelated suffix") }
        requests.write(key, listOf("Bonjour"))
        assertFalse(old.exists()); assertEquals(listOf("Bonjour"), requests.read(key))
        assertTrue("An evicted AtomicFile key must not leave its backup or new file", oldSiblings.none { it.exists() })
        assertTrue("Backups and new files count even when their base file is missing",
            directory.listFiles()!!.filter { it != unrelated && it != lookalike }.sumOf { it.length() } <= ExactTranslationRequestCache.MAX_BYTES)
        requests.clear()
        assertEquals("Untouched", unrelated.readText())
        assertEquals("Unrelated suffix", lookalike.readText())
        assertEquals(setOf(unrelated.name, lookalike.name), directory.listFiles()!!.map { it.name }.toSet())
        assertNull(requests.read(key))
    }
}
