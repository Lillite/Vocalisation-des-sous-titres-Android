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
 * engine only within an explicitly requested run; there are no automatic retries.
 */
class TranslationRunner(private val directory: File) {
    /** A cache write is optional; its failure must not discard a valid paid result. */
    var cacheWarning: String? = null
        private set

    @Synchronized fun run(plan: TranslationPlan, engine: TranslationEngine,
        cancelled: () -> Boolean, progress: (done: Int, total: Int) -> Unit): TranslatedTrack {
        cacheWarning = null
        checkCancelled(cancelled)
        if (engine.id != plan.providerId) throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        // Validate the entire batching plan before sending even its first request.
        val batches = plan.batches(MAX_BATCH_CHARACTERS, MAX_BATCH_ITEMS)
        read(plan)?.let { track ->
            checkCancelled(cancelled)
            progress(plan.cues.size, plan.cues.size)
            return track
        }
        checkCancelled(cancelled)
        progress(0, plan.cues.size)
        val texts = ArrayList<String>(plan.cues.size)
        var translatedCharacters = 0L
        for (batch in batches) {
            checkCancelled(cancelled)
            val response = engine.translate(batch.texts, plan.sourceLanguage, plan.targetLanguage, cancelled).toList()
            checkCancelled(cancelled)
            // Validate each response immediately, so an invalid first lot cannot
            // trigger further billable requests before whole-track validation.
            val part = TranslationPlan.create(
                plan.cues.subList(batch.firstIndex, batch.firstIndex + batch.texts.size),
                plan.sourceLanguage, plan.targetLanguage, plan.providerId)
            try { part.complete(response) }
            catch (_: IllegalArgumentException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
            texts.addAll(response)
            translatedCharacters += response.sumOf { it.length.toLong() }
            if (translatedCharacters > TranslationPlan.MAX_TRACK_CHARACTERS)
                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            progress(texts.size, plan.cues.size)
        }
        checkCancelled(cancelled)
        val result = try { plan.complete(texts) }
            catch (_: IllegalArgumentException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
        checkCancelled(cancelled)
        write(plan, result)
        checkCancelled(cancelled)
        return result
    }

    /** Clears only files owned by this cache; imported subtitle files are untouched. */
    @Synchronized fun clear() {
        directory.listFiles()?.filter { owned(it) }?.forEach { it.delete() }
    }

    private fun read(plan: TranslationPlan): TranslatedTrack? = try {
        val atomic = AtomicFile(file(plan))
        // AtomicFile may restore a previous complete copy after process death.
        atomic.openRead().use { stream ->
            val base = file(plan)
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

    private fun write(plan: TranslationPlan, track: TranslatedTrack) {
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
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            atomic = AtomicFile(file(plan))
            stream = atomic.startWrite()
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC); output.writeInt(VERSION)
            output.writeString(plan.fingerprint); output.writeString(track.fingerprint)
            output.writeInt(track.cues.size)
            track.cues.forEach { output.writeString(it.text) }
            output.flush()
            atomic.finishWrite(stream)
            stream = null
            trim(file(plan))
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

    private fun file(plan: TranslationPlan) = File(directory, "${plan.fingerprint}.track")
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
    }
}
