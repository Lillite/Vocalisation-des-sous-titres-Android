package fr.soustitresvoix.player

import android.util.AtomicFile
import fr.soustitresvoix.core.TranslatedTrack
import fr.soustitresvoix.core.TranslationPlan
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * One worker owns this runner and its private cache directory. Only a complete,
 * validated track is committed. A cache miss (including a corrupt entry) uses the
 * engine only within an explicitly requested run. DeepL may retry explicit
 * rate-limit rejections; uncertain network outcomes are never retried automatically.
 */
class TranslationRunner(private val directory: File,
    checkpointDirectory: File? = null, private val retainedDirectory: File? = null) {
    private val checkpoints = checkpointDirectory?.let { TranslationCheckpointStore(it) }
    private val exactRequests = ExactTranslationRequestCache(File(directory, "requests"))
    fun checkpointSummary(plan: TranslationPlan): Pair<Int, Boolean> = checkpoints?.read(plan)?.let { it.texts.size to it.uncertainRequest } ?: (0 to false)
    @Synchronized fun readExisting(plan: TranslationPlan, expectedResultFingerprint: String? = null): TranslatedTrack? =
        readRetained(plan, expectedResultFingerprint) ?: read(plan)?.takeIf { expectedResultFingerprint == null || it.fingerprint == expectedResultFingerprint }
    /** Explicit durable retention; never evicts another saved translation. */
    @Synchronized fun retain(plan: TranslationPlan, track: TranslatedTrack): Boolean {
        val destination = retainedDirectory ?: return false
        require(plan.complete(track.cues.map { it.text }).fingerprint == track.fingerprint)
        cacheWarning = null
        write(plan, track, destination, false)
        return cacheWarning == null
    }
    /** Each offline work owns its copy, so deleting it cannot unpin another work. */
    @Synchronized fun retainForWork(workKey: String, plan: TranslationPlan, track: TranslatedTrack): Boolean {
        if (!WORK_KEY.matches(workKey)) return false
        val root = retainedDirectory ?: return false
        val works = File(root, "works")
        val destination = File(works, workKey)
        if (!destination.isDirectory && workFolders().size >= MAX_RETAINED_WORKS) return false
        require(plan.complete(track.cues.map { it.text }).fingerprint == track.fingerprint)
        cacheWarning = null
        write(plan, track, destination, false)
        return cacheWarning == null
    }
    @Synchronized fun removeRetained(plan: TranslationPlan): Boolean = retainedDirectory?.let { folder ->
        val atomic = AtomicFile(file(plan, folder)); atomic.delete(); !file(plan, folder).exists()
    } ?: false
    /** Only owned private translation files of this exact work are removed. */
    @Synchronized fun removeRetainedForWork(workKey: String): Boolean {
        if (!WORK_KEY.matches(workKey)) return false
        val root = retainedDirectory ?: return false
        val folder = File(File(root, "works"), workKey)
        val entries = folder.listFiles()?.filter { it.isFile && owned(it) }.orEmpty()
        entries.forEach { entry ->
            val name = entry.name.removeSuffix(".bak").removeSuffix(".new")
            AtomicFile(File(folder, name)).delete()
        }
        val removed = folder.listFiles()?.none { it.isFile && owned(it) } ?: true
        if (folder.listFiles()?.isEmpty() == true) folder.delete()
        return removed
    }
    @Synchronized fun retainedStorageSummary(): Pair<Int, Long> {
        val files = retainedDirectory?.listFiles()?.filter { it.isFile && ENTRY.matches(it.name) }.orEmpty() +
            workFolders().flatMap { folder -> folder.listFiles()?.filter { it.isFile && ENTRY.matches(it.name) }.orEmpty() }
        return files.size to files.sumOf { it.length() }
    }
    private fun workFolders(): List<File> = retainedDirectory?.let { File(it, "works").listFiles() }
        ?.filter { it.isDirectory && WORK_KEY.matches(it.name) }?.take(MAX_RETAINED_WORKS).orEmpty()
    private fun readRetained(plan: TranslationPlan, expectedResultFingerprint: String? = null): TranslatedTrack? {
        fun matches(track: TranslatedTrack?) = track?.takeIf { expectedResultFingerprint == null || it.fingerprint == expectedResultFingerprint }
        matches(retainedDirectory?.let { read(plan, it) })?.let { return it }
        return workFolders().firstNotNullOfOrNull { matches(read(plan, it)) }
    }
    /** A cache write is optional; its failure must not discard a valid paid result. */
    var cacheWarning: String? = null
        private set

    @Synchronized fun run(plan: TranslationPlan, engine: TranslationEngine,
        cancelled: () -> Boolean, progress: (done: Int, total: Int) -> Unit): TranslatedTrack =
        run(plan, engine, cancelled, progress, TranslationRunPolicy())

    @Synchronized fun run(plan: TranslationPlan, engine: TranslationEngine,
        cancelled: () -> Boolean, progress: (done: Int, total: Int) -> Unit,
        policy: TranslationRunPolicy, validatedPrefix: (List<String>) -> Unit = NO_VALIDATED_PREFIX): TranslatedTrack {
        cacheWarning = null
        checkCancelled(cancelled)
        if (engine.id != plan.providerId) throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        // Validate the entire batching plan before sending even its first request.
        val batches = plan.batches(engine.maxBatchCharacters, engine.maxBatchItems)
        (readRetained(plan) ?: read(plan))?.let { track ->
            checkCancelled(cancelled)
            // A previously evictable result must gain the same durable retention
            // as a newly generated local track before it is offered again.
            if (engine is ContextualTranslationEngine && engine.requiresDurableResult)
                persistCompleted(plan, track, requireDurable = true, writeCache = false)
            progress(plan.cues.size, plan.cues.size)
            return track
        }
        checkCancelled(cancelled)
        progress(0, plan.cues.size)
        val restored = checkpoints?.read(plan)
        if (restored?.recoveredTail == true)
            cacheWarning = "Un journal interrompu ou endommagé a été récupéré jusqu’au dernier lot entièrement vérifié."
        val texts = ArrayList<String>(plan.cues.size).apply { restored?.texts?.let { addAll(it) } }
        // Only whole validated batch boundaries are resumable. No partial request is guessed.
        val validBoundary = texts.size == plan.cues.size || batches.any { it.firstIndex == texts.size }
        if (!validBoundary) texts.clear()
        if (texts.isNotEmpty()) {
            progress(texts.size, plan.cues.size)
            if (validatedPrefix !== NO_VALIDATED_PREFIX) validatedPrefix(texts.toList())
        }
        val remainingCharacters = plan.cues.drop(texts.size).sumOf { it.text.length.toLong() }
        if (remainingCharacters > policy.maximumNewCharacters) throw TranslationFailure(TranslationFailure.Reason.BUDGET)
        var translatedCharacters = texts.sumOf { it.length.toLong() }
        fun saveCheckpoint(additions: List<String>, uncertain: Boolean): Boolean = checkpoints?.let { store ->
            // The normal path writes only the additions. A failed prior append,
            // a reset or a legacy journal can still recover via an atomic snapshot.
            store.append(plan, texts.size - additions.size, additions, uncertain) || store.write(plan, texts, uncertain)
        } ?: true
        for (batch in batches) {
            if (batch.firstIndex < texts.size) continue
            checkCancelled(cancelled)
            // External calls need an uncertain-charge marker before sending.
            // Local work already saved this exact validated prefix after its
            // previous occurrence: do not rewrite/fsync it a second time. Every
            // newly validated occurrence is still saved below before publishing.
            if (policy.requestMayConsumeRemoteQuota && !saveCheckpoint(emptyList(), true))
                cacheWarning = "La progression n’a pas pu être enregistrée ; garde cette session ouverte."
            val part = TranslationPlan.create(
                plan.cues.subList(batch.firstIndex, batch.firstIndex + batch.texts.size),
                plan.sourceLanguage, plan.targetLanguage, plan.providerId)
            var exactKey: String? = null
            var exactHit = false
            val response = try {
                if (engine is ContextualTranslationEngine) {
                    val contextStart = engine.contextStartIndex(batch.firstIndex)
                    if (contextStart !in 0..batch.firstIndex)
                        throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
                    val sources = plan.cues.subList(contextStart, batch.firstIndex).map { it.text }
                    val translations = texts.subList(contextStart, batch.firstIndex).toList()
                    // Only explicitly opted-in local engines may reuse a whole
                    // linguistic request. Same text with different history is a miss.
                    if (engine.requiresDurableResult && engine.supportsExactRequestCache && !policy.requestMayConsumeRemoteQuota)
                        exactKey = exactRequests.key(engine.id, plan.sourceLanguage, plan.targetLanguage,
                            batch.texts, sources, translations)
                    val cached = exactKey?.let(exactRequests::read)?.also {
                        engine.validateExactRequestCacheHit(batch.texts, plan.sourceLanguage, plan.targetLanguage,
                            sources, translations, cancelled)
                    }?.takeIf { runCatching { part.complete(it) }.isSuccess }
                    if (cached != null) { exactHit = true; cached }
                    else engine.translateWithContext(batch.texts, plan.sourceLanguage, plan.targetLanguage,
                        sources, translations, cancelled).toList()
                } else engine.translate(batch.texts, plan.sourceLanguage, plan.targetLanguage, cancelled).toList()
            } catch (failure: TranslationFailure) {
                // An explicit refusal did not produce a billable translation. Preserve
                // the validated prefix without falsely marking the request as lost.
                if (failure.reason in setOf(TranslationFailure.Reason.RATE_LIMIT, TranslationFailure.Reason.QUOTA,
                    TranslationFailure.Reason.AUTHENTICATION, TranslationFailure.Reason.CONFIGURATION,
                    TranslationFailure.Reason.LANGUAGE, TranslationFailure.Reason.REDIRECT, TranslationFailure.Reason.GOOGLE_BLOCKED,
                    TranslationFailure.Reason.MODEL_DOWNLOAD, TranslationFailure.Reason.LOCAL_TRANSLATION)) {
                    if (!saveCheckpoint(emptyList(), false))
                        cacheWarning = "La progression n’a pas pu être enregistrée ; garde cette session ouverte." }
                throw failure
            }
            checkCancelled(cancelled)
            // Validate each response immediately, so an invalid first lot cannot
            // trigger further billable requests before whole-track validation.
            try { part.complete(response) }
            catch (_: IllegalArgumentException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
            texts.addAll(response)
            translatedCharacters += response.sumOf { it.length.toLong() }
            if (translatedCharacters > TranslationPlan.MAX_TRACK_CHARACTERS)
                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            if (!saveCheckpoint(response, false)) cacheWarning = "Un lot validé n’a pas pu être enregistré pour la reprise."
            if (exactHit) (engine as ContextualTranslationEngine).onExactRequestCacheHit()
            else exactKey?.let { exactRequests.write(it, response) }
            checkCancelled(cancelled)
            if (validatedPrefix !== NO_VALIDATED_PREFIX) validatedPrefix(texts.toList())
            progress(texts.size, plan.cues.size)
        }
        checkCancelled(cancelled)
        val result = try { plan.complete(texts) }
            catch (_: IllegalArgumentException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
        checkCancelled(cancelled)
        persistCompleted(plan, result,
            requireDurable = engine is ContextualTranslationEngine && engine.requiresDurableResult)
        checkCancelled(cancelled)
        return result
    }

    /**
     * Local work is expensive even without a billable request. Keep a durable
     * result (or the complete validated journal) before touching an evictable
     * cache. A cache eviction must never be the only reason to recompute a film.
     */
    private fun persistCompleted(plan: TranslationPlan, result: TranslatedTrack,
        requireDurable: Boolean, writeCache: Boolean = true) {
        var durable = false
        if (requireDurable) {
            val folder = retainedDirectory
            if (folder != null) {
                durable = read(plan, folder)?.fingerprint == result.fingerprint
                if (!durable) {
                    write(plan, result, folder, evict = false)
                    durable = read(plan, folder)?.fingerprint == result.fingerprint
                }
            }
            if (!durable) {
                val saved = checkpoints?.write(plan, result.cues.map { it.text }, false) == true
                cacheWarning = if (saved)
                    "La traduction complète est conservée dans le journal de reprise ; sa copie durable de résultat n’a pas pu être enregistrée."
                else "La traduction est complète, mais sa sauvegarde durable a échoué. Exporte le SRT avant de fermer cette session."
            }
        }
        // A verified retained local result is read before the cache on every
        // restore; a second, evictable copy adds I/O without a recovery benefit.
        if (writeCache && (!requireDurable || !durable)) write(plan, result)
        // For local work, successful cache commit is insufficient: only a
        // durable result allows deleting the non-evictable complete journal.
        if (if (requireDurable) durable else read(plan)?.fingerprint == result.fingerprint)
            checkpoints?.remove(plan)
    }

    /** Clears only files owned by this cache; imported subtitle files are untouched. */
    @Synchronized fun clear() {
        directory.listFiles()?.filter { owned(it) }?.forEach { it.delete() }
        exactRequests.clear()
    }

    private fun read(plan: TranslationPlan, folder: File = directory): TranslatedTrack? = try {
        val atomic = AtomicFile(file(plan, folder))
        // AtomicFile may restore a previous complete copy after process death.
        atomic.openRead().use { stream ->
            val base = file(plan, folder)
            if (base.length() !in 1..MAX_CACHE_BYTES) return null
            DataInputStream(stream.buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (input.readString(64) != plan.fingerprint) return null
                val resultFingerprint = input.readString(64)
                if (input.readInt() != plan.cues.size) return null
                val texts = ArrayList<String>(plan.cues.size)
                var total = 0L
                repeat(plan.cues.size) {
                    val text = input.readString(TranslationPlan.MAX_CUE_CHARACTERS * 4)
                    total += text.length
                    if (total > TranslationPlan.MAX_TRACK_CHARACTERS) return null
                    texts += text
                }
                if (input.read() != -1) return null
                val result = plan.complete(texts)
                if (result.fingerprint != resultFingerprint) return null
                base.setLastModified(System.currentTimeMillis())
                result
            }
        }
    } catch (_: Exception) { null }

    private fun write(plan: TranslationPlan, track: TranslatedTrack, folder: File = directory, evict: Boolean = true) {
        // Count UTF-8 bytes before touching AtomicFile, including every length
        // prefix and header. Large valid tracks can still play without caching.
        val size = 4L + 4 + 4 + 64 + 4 + 64 + 4 +
            track.cues.sumOf { 4L + it.text.toByteArray(Charsets.UTF_8).size }
        if (size > MAX_CACHE_BYTES) {
            cacheWarning = "La traduction complète est prête, mais dépasse la limite du cache local de 32 Mio. Elle reste disponible pour cette session."
            return
        }
        var atomic: AtomicFile? = null
        var stream: java.io.FileOutputStream? = null
        try {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException()
            atomic = AtomicFile(file(plan, folder))
            stream = atomic.startWrite()
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC); output.writeInt(VERSION)
            output.writeString(plan.fingerprint); output.writeString(track.fingerprint)
            output.writeInt(track.cues.size)
            track.cues.forEach { output.writeString(it.text) }
            output.flush()
            atomic.finishWrite(stream)
            stream = null
            if (evict) trim(file(plan, folder))
        } catch (_: Exception) {
            runCatching { atomic?.failWrite(stream) }
            cacheWarning = "La traduction complète est prête, mais sa sauvegarde locale a échoué. Elle reste disponible pour cette session ; une nouvelle session pourra nécessiter une nouvelle traduction."
        }
    }

    private fun trim(retain: File) {
        val entries = directory.listFiles()?.filter { it.isFile && owned(it) }.orEmpty()
            .sortedBy { it.lastModified() }
        var size = entries.sumOf { it.length() }
        for (entry in entries) {
            if (size <= MAX_CACHE_BYTES) break
            if (entry == retain) continue
            val length = entry.length()
            if (entry.delete()) size -= length
        }
        if (size > MAX_CACHE_BYTES) cacheWarning = "La traduction est prête ; certains anciens fichiers du cache n’ont pas pu être supprimés."
    }

    private fun file(plan: TranslationPlan, folder: File = directory) = File(folder, "${plan.fingerprint}.track")
    private fun owned(file: File) = ENTRY.matches(file.name) ||
        listOf(".bak", ".new").any { suffix -> file.name.endsWith(suffix) && ENTRY.matches(file.name.removeSuffix(suffix)) }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }

    private fun DataOutputStream.writeString(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.readString(maxBytes: Int): String {
        val length = readInt()
        if (length < 0 || length > maxBytes) throw IOException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    companion object {
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024
        const val MAX_BATCH_ITEMS = 20
        const val MAX_BATCH_CHARACTERS = 4000
        private const val MAGIC = 0x53545654
        private const val VERSION = 1
        private val ENTRY = Regex("[0-9a-f]{64}\\.track")
        private val WORK_KEY = Regex("[0-9a-f]{64}")
        private const val MAX_RETAINED_WORKS = 256
        internal val NO_VALIDATED_PREFIX: (List<String>) -> Unit = {}
    }
}
