package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
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

    @Test fun optimizedKeysAndSerializedEntriesRemainByteCompatibleWithVersionOne() {
        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = requests.key("engine-a", "en", "fr", listOf("ab", "c"), listOf("past"), listOf("passé"))
        // Independently generated using length-prefixed UTF-8 and big-endian
        // int32 counts. Existing request-cache files must keep the same names.
        assertEquals("09a55b22916ea2878edc3dc33c13aa8391cf74330527317432e176e1597b8d45", key)
        val values = listOf("Bonjour 😀", "東京 — l’œil", "Encore\nune fois")
        val legacy = versionOneBytes(key, values)
        val entry = File(directory, "$key.request")
        entry.writeBytes(legacy)
        assertEquals(values, requests.read(key))
        requests.write(key, values)
        assertArrayEquals(legacy, entry.readBytes())
        assertEquals(setOf(entry.name), directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun everyTruncatedEntryAndAnOversizedFileAreCacheMisses() {
        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = "a".repeat(64); val entry = File(directory, "$key.request")
        val valid = versionOneBytes(key, listOf("Résultat 😀"))
        for (end in valid.indices) {
            entry.writeBytes(valid.copyOf(end))
            assertNull("A truncated entry at byte $end cannot be reused", requests.read(key))
        }
        entry.writeBytes(valid + 0)
        assertNull(requests.read(key))
        RandomAccessFile(entry, "rw").use { it.setLength(1024 * 1024L + 1) }
        assertNull(requests.read(key))
        entry.writeBytes(valid)
        assertEquals(listOf("Résultat 😀"), requests.read(key))
    }

    @Test fun signedButMalformedBodyCannotBypassStructureUnicodeOrExactEndChecks() {
        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = "b".repeat(64); val entry = File(directory, "$key.request")
        val wrongVersion = versionOneBytes(key, listOf("Valide")).dropLast(32).toByteArray().apply { this[7] = 2 }
        val wrongCount = versionOneBytes(key, listOf("Valide")).dropLast(32).toByteArray().apply { this[77] = 2 }
        val badUnicode = versionOneBytes(key, listOf("Valide")).dropLast(32).toByteArray().apply { this[82] = 0xff.toByte() }
        val trailingBody = versionOneBytes(key, listOf("Valide")).dropLast(32).toByteArray() + 0
        for (body in listOf(wrongVersion, wrongCount, badUnicode, trailingBody)) {
            entry.writeBytes(body + MessageDigest.getInstance("SHA-256").digest(body))
            assertNull(requests.read(key))
        }
        entry.writeBytes(versionOneBytes("c".repeat(64), listOf("Autre requête")))
        assertNull(requests.read(key))
    }

    @Test fun rejectedOversizedOrInvalidReplacementLeavesItsExistingEntryIntact() {
        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = "d".repeat(64)
        requests.write(key, listOf("À conserver"))
        val saved = File(directory, "$key.request").readBytes()
        for (values in listOf(List(20) { "漢".repeat(TranslationPlan.MAX_CUE_CHARACTERS) },
            listOf("\uD800"), listOf("\u0000"), emptyList())) {
            requests.write(key, values)
            assertArrayEquals(saved, File(directory, "$key.request").readBytes())
        }
        assertEquals(listOf("À conserver"), requests.read(key))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun anotherInstanceAndNewOrphanFilesAreInventoriedAfterEarlierWrites() {
        val directory = temporary.newFolder(); val first = ExactTranslationRequestCache(directory)
        first.write("1".repeat(64), listOf("Premier"))
        ExactTranslationRequestCache(directory).write("2".repeat(64), listOf("Deuxième"))
        val orphan = File(directory, "${"3".repeat(64)}.request.new")
        RandomAccessFile(orphan, "rw").use { it.setLength(ExactTranslationRequestCache.MAX_BYTES) }
        orphan.setLastModified(1)
        val unrelated = File(directory, "source.srt").apply { writeText("À conserver") }
        first.write("4".repeat(64), listOf("Dernier"))
        assertFalse(orphan.exists())
        assertEquals(listOf("Dernier"), first.read("4".repeat(64)))
        assertEquals("À conserver", unrelated.readText())
        assertTrue(directory.listFiles()!!.filter { it != unrelated }.sumOf { it.length() } <= ExactTranslationRequestCache.MAX_BYTES)
    }

    private fun versionOneBytes(key: String, values: List<String>): ByteArray {
        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x53545652); output.writeInt(1); output.writeUTF(key); output.writeInt(values.size)
                values.forEach { text -> val encoded = text.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) }
            }
        }.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    @Test fun localResourceChecksApplyOnlyToNewInferenceAndNeverBlockVerifiedCachedOccurrences() {
        val cache = temporary.newFolder(); val progress = temporary.newFolder(); val retained = temporary.newFolder()
        val runner = TranslationRunner(cache, progress, retained)
        val first = Engine()
        var checks = 0
        val result = runner.runGuarded(plan(listOf("Again", "Again")), first, { false }, { _, _ -> }, policy,
            beforeRequest = { checks++ })
        assertEquals(2, result.cues.size)
        assertEquals(1, checks)
        assertEquals(1, first.calls)
        val second = Engine()
        val cached = runner.runGuarded(plan(listOf("Again", "Again"), 1), second, { false }, { _, _ -> }, policy,
            beforeRequest = { throw AssertionError("Cached occurrences require no model resource wait") })
        assertEquals(2, cached.cues.size)
        assertEquals(0, second.calls)
    }

    @Test fun cancellationAtAResourceBoundaryPreventsAnInferenceAndLeavesTheSourceResumable() {
        val runner = TranslationRunner(temporary.newFolder(), temporary.newFolder(), temporary.newFolder())
        val source = plan(listOf("First", "Second"))
        val first = Engine(); var cancelled = false
        try {
            runner.runGuarded(source, first, { cancelled }, { _, _ -> }, policy,
                beforeRequest = { cancelled = true })
            fail("A cancellation after the guard must be checked before model invocation")
        } catch (_: TranslationCancelledException) { }
        assertEquals(0, first.calls)
        val resumed = Engine()
        assertEquals(2, runner.run(source, resumed, { false }, { _, _ -> }, policy).cues.size)
        assertEquals(2, resumed.calls)
    }

}
