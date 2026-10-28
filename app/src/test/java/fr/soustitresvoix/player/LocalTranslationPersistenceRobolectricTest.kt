package fr.soustitresvoix.player

import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.TranslatedSubtitleExport
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
class LocalTranslationPersistenceRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan(provider: String = "local-fixture") = TranslationPlan.create(listOf(
        TranslationCue("sign", 0, 90_000_000, "EXIT"),
        TranslationCue("first", 1_234_000, 4_567_000, "Hello Élodie 😀"),
        TranslationCue("simultaneous", 1_234_000, 4_567_000, "Hello Élodie 😀"),
        TranslationCue("repeat", 8_000_000, 10_000_000, "Hello Élodie 😀")
    ), "en", "fr", provider)

    private class Engine(override val id: String = "local-fixture",
        private val action: (Int, List<String>) -> List<String> = { _, texts -> texts.map { "Traduit $it" } }
    ) : ContextualTranslationEngine {
        override val maxBatchItems = 1
        override val maxBatchCharacters = TranslationPlan.MAX_CUE_CHARACTERS
        override val requiresDurableResult = true
        val contexts = mutableListOf<Pair<List<String>, List<String>>>()
        val inputs = mutableListOf<List<String>>()
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> = throw AssertionError("Contextual request required")
        override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
            contexts += previousSources.toList() to previousTranslations.toList()
            inputs += texts.toList()
            return action(inputs.size, texts)
        }
        override fun close() = Unit
    }

    @Test fun validatedContextsAreAlignedBoundedAndReconstructedAfterAnInterruptedLocalRequest() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val original = plan()
        val first = Engine(action = { call, texts ->
            if (call == 4) throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROCESS)
            texts.map { "Traduit $it" }
        })
        try {
            TranslationRunner(cache, progress, retained).run(original, first, { false }, { _, _ -> },
                TranslationRunPolicy(requestMayConsumeRemoteQuota = false))
            fail("Interrupted request cannot complete the track")
        } catch (_: TranslationFailure) { }
        assertEquals(3 to false, TranslationRunner(cache, progress, retained).checkpointSummary(original))
        assertEquals(emptyList<String>() to emptyList<String>(), first.contexts[0])
        assertEquals(listOf("EXIT") to listOf("Traduit EXIT"), first.contexts[1])
        assertEquals(original.texts.take(2), first.contexts[2].first)
        assertEquals(original.texts.subList(1, 3), first.contexts[3].first)
        assertEquals(2, first.contexts[3].first.size)

        val resumed = Engine()
        val prefixes = mutableListOf<Int>()
        val result = TranslationRunner(cache, progress, retained).run(original, resumed, { false }, { _, _ -> },
            TranslationRunPolicy(requestMayConsumeRemoteQuota = false)) { prefixes += it.size }
        assertEquals(listOf(original.texts.last()), resumed.inputs.single())
        assertEquals(first.contexts.last(), resumed.contexts.single())
        assertEquals(listOf(3, 4), prefixes)
        assertEquals(original.cues.map { it.id to (it.startUs to it.endUs) }, result.cues.map { it.id to (it.startUs to it.endUs) })
        assertEquals(3, result.cues.count { it.text == "Traduit Hello Élodie 😀" })
        assertEquals(0 to false, TranslationRunner(cache, progress, retained).checkpointSummary(original))
    }

    @Test fun localCompleteResultSurvivesCacheEvictionAndRestoresWithoutRunningTheModel() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val original = plan()
        val runner = TranslationRunner(cache, progress, retained)
        val result = runner.run(original, Engine(), { false }, { _, _ -> }, TranslationRunPolicy(requestMayConsumeRemoteQuota = false))
        assertNull(runner.cacheWarning)
        assertTrue(retained.listFiles().orEmpty().isNotEmpty())
        assertTrue("A durable local result must not need a duplicate cache copy", cache.listFiles().orEmpty().isEmpty())
        runner.clear()
        val engine = Engine(action = { _, _ -> throw AssertionError("Durable result must not recompute") })
        val restored = TranslationRunner(cache, progress, retained).run(original, engine, { false }) { _, _ -> }
        assertEquals(result.fingerprint, restored.fingerprint)
        assertTrue(engine.inputs.isEmpty())
    }

    @Test fun eachLocalOccurrenceIsDurableBeforePublicationWithoutWritingAnEmptyUncertainRequest() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val original = plan()
        val checkpoint = TranslationRunner(cache, progress, retained)
        var published = 0
        val engine = Engine(action = { call, texts ->
            if (call == 1) assertTrue("No unvalidated local request needs a disk record", progress.listFiles().orEmpty().isEmpty())
            assertEquals(call - 1 to false, checkpoint.checkpointSummary(original))
            texts.map { "Traduit $it" }
        })
        checkpoint.run(original, engine, { false }, { _, _ -> },
            TranslationRunPolicy(requestMayConsumeRemoteQuota = false)) { texts ->
            published++
            assertEquals(published, texts.size)
            assertEquals(published to false, TranslationRunner(cache, progress, retained).checkpointSummary(original))
        }
        assertEquals(original.cues.size, published)
        assertEquals(0 to false, checkpoint.checkpointSummary(original))
        assertTrue(retained.listFiles().orEmpty().isNotEmpty())
    }

    @Test fun aCachedLocalResultIsPromotedBeforeItCanBeEvicted() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val original = plan()
        val ordinary = object : TranslationEngine {
            override val id = original.providerId
            override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                cancelled: () -> Boolean) = texts.map { "Traduit $it" }
            override fun close() = Unit
        }
        TranslationRunner(cache, progress).run(original, ordinary, { false }) { _, _ -> }
        val local = Engine(action = { _, _ -> throw AssertionError("Cache promotion must not compute") })
        val runner = TranslationRunner(cache, progress, retained)
        val result = runner.run(original, local, { false }) { _, _ -> }
        runner.clear()
        assertEquals(result.fingerprint, TranslationRunner(cache, progress, retained).readExisting(original)?.fingerprint)
        assertTrue(local.inputs.isEmpty())
    }

    @Test fun failedDurableRetentionKeepsTheCompleteJournalEvenWhenAnEvictableCacheWasWritten() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val invalidRetained = temporary.newFile()
        val original = plan()
        val runner = TranslationRunner(cache, progress, invalidRetained)
        val result = runner.run(original, Engine(), { false }, { _, _ -> }, TranslationRunPolicy(requestMayConsumeRemoteQuota = false))
        assertNotNull(runner.cacheWarning)
        assertEquals(original.cues.size to false, runner.checkpointSummary(original))
        runner.clear()
        val engine = Engine(action = { _, _ -> throw AssertionError("Complete journal must be reusable") })
        val restored = TranslationRunner(cache, progress, temporary.newFolder()).run(original, engine, { false }, { _, _ -> },
            TranslationRunPolicy(0, requestMayConsumeRemoteQuota = false))
        assertEquals(result.fingerprint, restored.fingerprint)
        assertTrue(engine.inputs.isEmpty())
    }

    @Test fun cancellationAndRejectedTruncationNeverCommitTheUnvalidatedOccurrence() {
        for (cancelAfterResponse in listOf(true, false)) {
            val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
            val original = plan()
            var cancelled = false
            val engine = Engine(action = { call, texts ->
                if (call == 2) {
                    if (cancelAfterResponse) cancelled = true
                    else throw TranslationFailure(TranslationFailure.Reason.LOCAL_TRUNCATED)
                }
                texts.map { "Traduit $it" }
            })
            val prefixes = mutableListOf<Int>()
            val runner = TranslationRunner(cache, progress, retained)
            try {
                runner.run(original, engine, { cancelled }, { _, _ -> },
                    TranslationRunPolicy(requestMayConsumeRemoteQuota = false)) { prefixes += it.size }
                fail("Unvalidated second occurrence cannot complete")
            } catch (_: java.io.IOException) { }
            assertEquals(listOf(1), prefixes)
            assertEquals(1 to false, runner.checkpointSummary(original))
            assertNull(runner.readExisting(original))
            assertTrue(retained.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun changedModelIdentityCannotReusePrefixContextOrCompleteResult() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val first = plan("local-gguf-v1-" + "a".repeat(64))
        val second = plan("local-gguf-v1-" + "b".repeat(64))
        val interrupted = Engine(first.providerId) { call, texts ->
            if (call == 2) throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROCESS)
            texts.map { "Premier $it" }
        }
        try { TranslationRunner(cache, progress, retained).run(first, interrupted, { false }, { _, _ -> },
            TranslationRunPolicy(requestMayConsumeRemoteQuota = false)) }
        catch (_: TranslationFailure) { }
        val engine = Engine(second.providerId)
        val runner = TranslationRunner(cache, progress, retained)
        assertEquals(0 to false, runner.checkpointSummary(second))
        runner.run(second, engine, { false }, { _, _ -> }, TranslationRunPolicy(requestMayConsumeRemoteQuota = false))
        assertEquals(second.cues.size, engine.inputs.size)
        assertTrue(engine.contexts.first().first.isEmpty())
        assertEquals(1 to false, runner.checkpointSummary(first))
    }

    @Test fun exportKeepsUnicodeOverlapsRepeatedOccurrencesAndLeavesTheSourceImmutable() {
        val original = plan()
        val snapshot = original.cues.toList()
        val track = TranslationRunner(temporary.newFolder(), temporary.newFolder(), temporary.newFolder())
            .run(original, Engine(), { false }) { _, _ -> }
        val srt = TranslatedSubtitleExport.encode(track.cues, SubtitleExportFormat.SRT)
        assertTrue(srt.contains("00:00:00,000 --> 00:01:30,000"))
        assertEquals(2, Regex("00:00:01,234 --> 00:00:04,567").findAll(srt).count())
        assertEquals(3, Regex("Traduit Hello Élodie 😀").findAll(srt).count())
        assertEquals(4, Regex(" --> ").findAll(srt).count())
        assertEquals(snapshot, original.cues)
    }
}
