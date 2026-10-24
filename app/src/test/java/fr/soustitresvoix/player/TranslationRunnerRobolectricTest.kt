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
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationRunnerRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan(count: Int = 3, source: String = "en", provider: String = "fixture") = TranslationPlan.create(
        (0 until count).map { TranslationCue("occurrence-$it", it * 1_000_000L, (it + 1) * 1_000_000L, "Hello $it") },
        source, "fr", provider)
    private class Engine(override val id: String = "fixture",
        val response: (List<String>) -> List<String> = { it.map { text -> "Français $text" } }) : TranslationEngine {
        var calls = 0
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> { calls++; return response(texts) }
        override fun close() { }
    }
    private fun expectFailure(block: () -> Unit): TranslationFailure {
        try { block(); fail("Expected a rejected translation") }
        catch (error: TranslationFailure) { return error }
        throw AssertionError()
    }
    private fun directory() = temporary.newFolder()

    @Test fun completeTrackPreservesRepeatedAndSimultaneousOccurrencesAndRestoresWithoutRequests() {
        val plan = TranslationPlan.create(listOf(
            TranslationCue("sign", 0, 90_000_000, "EXIT"),
            TranslationCue("first", 1_000_000, 3_000_000, "Hello"),
            TranslationCue("second", 1_000_000, 3_000_000, "Hello"),
            TranslationCue("repeat", 9_000_000, 11_000_000, "Hello")), "en", "fr", "fixture")
        val folder = directory()
        val engine = Engine()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = TranslationRunner(folder).run(plan, engine, { false }) { done, total -> progress += done to total }
        assertEquals(1, engine.calls)
        assertEquals(plan.cues.map { it.id to (it.startUs to it.endUs) }, result.cues.map { it.id to (it.startUs to it.endUs) })
        assertEquals("Français Hello", result.textFor("repeat", "Hello"))
        val forbidden = Engine(response = { throw AssertionError("Cache hit must avoid a request") })
        val restored = TranslationRunner(folder).run(plan, forbidden, { false }) { _, _ -> }
        assertEquals(result.fingerprint, restored.fingerprint)
        assertEquals(0, forbidden.calls)
        assertEquals(listOf(0 to 4, 4 to 4), progress)
    }

    @Test fun batchesAreBoundedAndProgressCountsOccurrences() {
        val engine = Engine(response = { assertTrue(it.size <= 20); assertTrue(it.sumOf(String::length) <= 4000); it.map { "Traduit $it" } })
        val progress = mutableListOf<Int>()
        TranslationRunner(directory()).run(plan(43), engine, { false }) { done, _ -> progress += done }
        assertEquals(3, engine.calls)
        assertEquals(listOf(0, 20, 40, 43), progress)
    }

    @Test fun missingExtraAndBlankResponsesRejectBeforeAnyNextBatchAndNeverCache() {
        for (response in listOf<(List<String>) -> List<String>>(
            { it.dropLast(1) }, { it + "Extra" }, { it.map { " " } }, { it.map { "\uD800" } })) {
            val folder = directory()
            val engine = Engine(response = response)
            val error = expectFailure { TranslationRunner(folder).run(plan(21), engine, { false }) { _, _ -> } }
            assertEquals(TranslationFailure.Reason.RESPONSE, error.reason)
            assertEquals(1, engine.calls)
            assertEquals(0, folder.listFiles().orEmpty().size)
        }
    }

    @Test fun cancellationBeforeRunAndAfterResponseCannotPublishOrCachePartialTrack() {
        val folder = directory()
        val runner = TranslationRunner(folder)
        val before = Engine()
        try { runner.run(plan(), before, { true }) { _, _ -> }; fail() }
        catch (_: TranslationCancelledException) { }
        assertEquals(0, before.calls)
        var cancelled = false
        val during = Engine(response = { cancelled = true; it.map { "Traduit" } })
        try { runner.run(plan(21), during, { cancelled }) { _, _ -> }; fail() }
        catch (_: TranslationCancelledException) { }
        assertEquals(1, during.calls)
        assertEquals(0, folder.listFiles().orEmpty().size)
    }

    @Test fun corruptedWrongFingerprintAndTrailingCacheBytesAreRejectedAndReplaced() {
        val plan = plan()
        for (corrupt in listOf<(File) -> Unit>(
            { it.writeBytes(byteArrayOf(1, 2, 3)) },
            { file -> val bytes = file.readBytes(); bytes[12] = 'x'.code.toByte(); file.writeBytes(bytes) },
            { file -> val bytes = file.readBytes(); bytes[bytes.lastIndex] = 'X'.code.toByte(); file.writeBytes(bytes) },
            { it.appendBytes(byteArrayOf(1)) })) {
            val folder = directory()
            TranslationRunner(folder).run(plan, Engine(), { false }) { _, _ -> }
            corrupt(folder.listFiles()!!.single())
            val engine = Engine()
            TranslationRunner(folder).run(plan, engine, { false }) { _, _ -> }
            assertEquals(1, engine.calls)
            val noNetwork = Engine(response = { throw AssertionError("Repaired cache") })
            TranslationRunner(folder).run(plan, noNetwork, { false }) { _, _ -> }
            assertEquals(0, noNetwork.calls)
        }
    }

    @Test fun atomicBackupAfterInterruptedCommitRestoresCompleteTrackWithoutNetwork() {
        val folder = directory()
        val plan = plan()
        TranslationRunner(folder).run(plan, Engine(), { false }) { _, _ -> }
        val base = folder.listFiles()!!.single()
        val backup = File(folder, "${base.name}.bak")
        base.copyTo(backup)
        base.writeText("Interrupted replacement")
        val engine = Engine(response = { throw AssertionError("Complete backup should be recovered") })
        val restored = TranslationRunner(folder).run(plan, engine, { false }) { _, _ -> }
        assertEquals("Français Hello 0", restored.textFor("occurrence-0", "Hello 0"))
        assertEquals(0, engine.calls)
        assertFalse(backup.exists())
    }

    @Test fun languageProviderOrSourceChangeNeverReusesAnotherTrack() {
        val folder = directory()
        val runner = TranslationRunner(folder)
        runner.run(plan(), Engine(), { false }) { _, _ -> }
        val language = Engine()
        runner.run(plan(source = "de"), language, { false }) { _, _ -> }
        assertEquals(1, language.calls)
        val provider = Engine(id = "other")
        runner.run(plan(provider = "other"), provider, { false }) { _, _ -> }
        assertEquals(1, provider.calls)
        val changed = TranslationPlan.create(plan().cues.map { it.copy(text = "Changed ${it.text}") }, "en", "fr", "fixture")
        val source = Engine()
        runner.run(changed, source, { false }) { _, _ -> }
        assertEquals(1, source.calls)
    }

    @Test fun oversizedOccurrenceAndWrongEngineAreRejectedBeforeAnyRequest() {
        val oversized = TranslationPlan.create(listOf(TranslationCue("large", 0, 1, "x".repeat(4001))), "en", "fr", "fixture")
        val engine = Engine()
        try { TranslationRunner(directory()).run(oversized, engine, { false }) { _, _ -> }; fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, engine.calls)
        val other = Engine("other")
        assertEquals(TranslationFailure.Reason.CONFIGURATION,
            expectFailure { TranslationRunner(directory()).run(plan(), other, { false }) { _, _ -> } }.reason)
        assertEquals(0, other.calls)
    }

    @Test fun cacheWriteFailureRetainsCompleteResultAndReportsWarning() {
        val file = temporary.newFile()
        val runner = TranslationRunner(file)
        val result = runner.run(plan(), Engine(), { false }) { _, _ -> }
        assertEquals(3, result.cues.size)
        assertNotNull(runner.cacheWarning)
    }

    @Test fun clearOnlyDeletesOwnedCacheFilesAndKeepsImportedSubtitles() {
        val folder = directory()
        val original = File(folder, "original.srt").apply { writeText("Original synthetic fixture") }
        val runner = TranslationRunner(folder)
        runner.run(plan(), Engine(), { false }) { _, _ -> }
        assertEquals(2, folder.listFiles()!!.size)
        runner.clear()
        assertEquals(listOf(original), folder.listFiles()!!.toList())
    }

    @Test fun cacheBudgetAlsoCountsAbandonedAtomicFiles() {
        val folder = directory()
        val stale = File(folder, "${"a".repeat(64)}.track.new")
        RandomAccessFile(stale, "rw").use { it.setLength(TranslationRunner.MAX_CACHE_BYTES) }
        stale.setLastModified(1)
        val runner = TranslationRunner(folder)
        runner.run(plan(), Engine(), { false }) { _, _ -> }
        assertFalse(stale.exists())
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= TranslationRunner.MAX_CACHE_BYTES)
    }
}
