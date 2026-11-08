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
class SourceWindowTranslationRunnerRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Request(val current: String, val previous: List<String>, val following: List<String>)
    private class Engine : ContextualTranslationEngine {
        override val id = "source-window-fixture-v1"
        override val maxBatchItems = 1
        override val requiresDurableResult = true
        override val supportsExactRequestCache = true
        override val includesPreviousTranslations = false
        override val followingContextItems = 2
        val requests = mutableListOf<Request>()
        var hits = 0
        override fun onExactRequestCacheHit() { hits++ }
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String, cancelled: () -> Boolean): List<String> =
            throw AssertionError("Source context must go through the shared runner")
        override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> =
            throw AssertionError("Future context must not be discarded")
        override fun translateWithSurroundingContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, followingSources: List<String>, cancelled: () -> Boolean): List<String> {
            assertTrue(previousTranslations.isEmpty())
            requests += Request(texts.single(), previousSources, followingSources)
            return texts.map { "Traduit $it" }
        }
        override fun close() = Unit
    }
    private fun plan(texts: List<String>, edition: Int = 0) = TranslationPlan.create(texts.mapIndexed { index, text ->
        // Repetitions and simultaneous/overlapping occurrences are deliberately distinct.
        TranslationCue("$edition-$index", if (index < 3) 0 else 1_000_000, 9_000_000, text)
    }, "en", "fr", Engine().id)
    private val policy = TranslationRunPolicy(requestMayConsumeRemoteQuota = false)

    @Test fun sourceWindowsSurviveCheckpointResumeAndPublishEveryOccurrenceInOrder() {
        val source = plan(listOf("Sign", "Again", "Again", "Next", "End"))
        val runner = TranslationRunner(temporary.newFolder(), temporary.newFolder(), temporary.newFolder())
        var cancelled = false
        val first = Engine()
        try {
            runner.run(source, first, { cancelled }, { _, _ -> }, policy) { if (it.size == 2) cancelled = true }
            fail("The stopped task cannot finish")
        } catch (_: TranslationCancelledException) { }
        assertEquals(2 to false, runner.checkpointSummary(source))
        assertEquals(Request("Sign", emptyList(), listOf("Again", "Again")), first.requests.first())
        val resumed = Engine(); val prefixes = mutableListOf<List<String>>()
        val track = runner.run(source, resumed, { false }, { _, _ -> }, policy) { prefixes += it }
        assertEquals(Request("Again", listOf("Sign", "Again"), listOf("Next", "End")), resumed.requests.first())
        assertEquals(source.cues.map { it.id to (it.startUs to it.endUs) }, track.cues.map { it.id to (it.startUs to it.endUs) })
        assertEquals(source.texts.map { "Traduit $it" }, track.cues.map { it.text })
        assertEquals(listOf(2, 3, 4, 5), prefixes.map { it.size })
    }

    @Test fun futureSourcesParticipateInExactCacheKeysWithoutSharingGeneratedNeighbours() {
        val runner = TranslationRunner(temporary.newFolder(), temporary.newFolder(), temporary.newFolder())
        val original = listOf("A", "B", "C", "D", "E")
        runner.run(plan(original), Engine(), { false }, { _, _ -> }, policy)
        val same = Engine()
        runner.run(plan(original, 1), same, { false }, { _, _ -> }, policy)
        assertEquals(0, same.requests.size); assertEquals(5, same.hits)
        val changed = Engine()
        runner.run(plan(listOf("A", "B", "C", "Changed", "E"), 2), changed, { false }, { _, _ -> }, policy)
        // Only the first request has the same current source and all four source neighbours.
        assertEquals(1, changed.hits)
        assertEquals(listOf("B", "C", "Changed", "E"), changed.requests.map { it.current })
        val keys = ExactTranslationRequestCache(temporary.newFolder())
        assertNotEquals(keys.key("fixture", "en", "fr", listOf("A"), emptyList(), emptyList(), listOf("B")),
            keys.key("fixture", "en", "fr", listOf("A"), emptyList(), emptyList(), listOf("Changed")))
    }
}
