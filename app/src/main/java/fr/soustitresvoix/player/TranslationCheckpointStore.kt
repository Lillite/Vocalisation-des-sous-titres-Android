package fr.soustitresvoix.player

import android.util.AtomicFile
import fr.soustitresvoix.core.TranslationPlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Ordered results, appended and synced before publication. Chained digests bind
 * records to the exact plan and preceding records. A damaged suffix never makes
 * an unvalidated result resumable; its complete verified prefix stays available.
 */
internal class TranslationCheckpointStore(private val directory: File,
    private val maximumBytes: Long = TranslationRunner.MAX_CACHE_BYTES,
    private val sync: (FileDescriptor) -> Unit = { it.sync() }) {
    init { require(maximumBytes in 256..TranslationRunner.MAX_CACHE_BYTES) }
    data class Checkpoint(val texts: List<String>, val uncertainRequest: Boolean, val recoveredTail: Boolean = false)
    private data class Journal(val plan: String, val texts: ArrayList<String>, var uncertain: Boolean,
        var sequence: Long, var digest: ByteArray, var validBytes: Long, var physicalBytes: Long,
        var modified: Long, var totalCharacters: Long, val legacy: Boolean = false, var recoveredTail: Boolean = false)
    // This runner's worker is the sole writer. Keep its verified append state so
    // a new occurrence does not rescan/hash the complete preceding translation.
    private var current: Journal? = null

    @Synchronized fun read(plan: TranslationPlan): Checkpoint? = load(plan)?.also { current = it }
        ?.let { Checkpoint(it.texts.toList(), it.uncertain, it.recoveredTail) }

    /** Add a contiguous validated batch, or update only the remote-request marker. */
    @Synchronized fun append(plan: TranslationPlan, previousCount: Int, additions: List<String>, uncertain: Boolean): Boolean = try {
        if (previousCount !in 0..plan.cues.size || additions.size > plan.cues.size - previousCount) false
        else {
            validateAddition(plan, previousCount, additions)
            val path = file(plan)
            val journal = current?.takeIf { it.plan == plan.fingerprint && path.length() == it.physicalBytes &&
                path.lastModified() == it.modified && !File(path.path + ".bak").exists() }
                ?: load(plan)?.also { current = it }
            when {
                journal == null -> previousCount == 0 && replace(plan, additions, uncertain)
                journal.texts.size != previousCount -> false
                journal.legacy -> replace(plan, journal.texts + additions, uncertain)
                additions.isEmpty() && journal.uncertain == uncertain && !journal.recoveredTail -> {
                    // A previous attempt may have written a complete record but
                    // failed its sync. Re-reading valid bytes is not an ack.
                    RandomAccessFile(path, "rw").use { sync(it.fd) }
                    true
                }
                else -> {
                    val characters = journal.totalCharacters + additions.sumOf { it.length.toLong() }
                    val payload = payload(journal.sequence + 1, previousCount, additions, uncertain)
                    val digest = checksum(journal.digest, payload)
                    val end = journal.validBytes + 4 + payload.size + DIGEST_BYTES
                    if (characters > TranslationPlan.MAX_TRACK_CHARACTERS) false
                    else if (end > maximumBytes) replace(plan, journal.texts + additions, uncertain)
                    else {
                        RandomAccessFile(path, "rw").use { output ->
                            // Remove only a suffix following the verified prefix.
                            output.setLength(journal.validBytes); output.seek(journal.validBytes)
                            output.writeInt(payload.size); output.write(payload); output.write(digest)
                            sync(output.fd)
                        }
                        journal.texts.addAll(additions); journal.uncertain = uncertain; journal.sequence++
                        journal.digest = digest; journal.validBytes = end; journal.physicalBytes = end
                        journal.modified = path.lastModified(); journal.totalCharacters = characters; journal.recoveredTail = false
                        true
                    }
                }
            }
        }
    } catch (_: Exception) { current = null; false }

    /** Snapshot/reset path; a version-one journal is migrated atomically. */
    @Synchronized fun write(plan: TranslationPlan, texts: List<String>, uncertain: Boolean): Boolean = try {
        validateAddition(plan, 0, texts)
        val saved = load(plan)?.also { current = it }
        if (saved != null && saved.texts.size <= texts.size && saved.texts == texts.subList(0, saved.texts.size))
            append(plan, saved.texts.size, texts.subList(saved.texts.size, texts.size), uncertain)
        else replace(plan, texts, uncertain)
    } catch (_: Exception) { current = null; false }

    @Synchronized fun remove(plan: TranslationPlan) {
        AtomicFile(file(plan)).delete()
        if (current?.plan == plan.fingerprint) current = null
    }

    private fun replace(plan: TranslationPlan, texts: List<String>, uncertain: Boolean): Boolean {
        var output: java.io.FileOutputStream? = null
        val path = file(plan); val atomic = AtomicFile(path)
        return try {
            validateAddition(plan, 0, texts)
            val header = header(plan)
            val payload = payload(1, 0, texts, uncertain)
            val digest = checksum(checksum(ByteArray(0), header), payload)
            val size = header.size.toLong() + 4 + payload.size + DIGEST_BYTES
            if (size > maximumBytes) return false
            if (!directory.isDirectory && !directory.mkdirs()) return false
            output = atomic.startWrite()
            DataOutputStream(output).apply { write(header); writeInt(payload.size); write(payload); write(digest); flush() }
            // finishWrite syncs too, but does not report a failed sync. Require
            // this explicit operation to succeed before acknowledging the save.
            sync(output.fd)
            atomic.finishWrite(output); output = null
            // AtomicFile can log a failed rename without throwing. Creation,
            // migration and reset are rare: verify their published snapshot.
            val published = load(plan)
            if (published == null || published.legacy || published.recoveredTail || published.sequence != 1L ||
                published.validBytes != size || published.physicalBytes != size || published.uncertain != uncertain ||
                published.texts != texts || !MessageDigest.isEqual(published.digest, digest)) {
                current = null
                return false
            }
            current = published
            true
        } catch (_: Exception) { runCatching { atomic.failWrite(output) }; current = null; false }
    }

    private fun load(plan: TranslationPlan): Journal? = try {
        val path = file(plan)
        AtomicFile(path).openRead().use { stream ->
            val bytes = path.length()
            if (bytes !in 1..maximumBytes) return null
            val counted = CountedInput(stream.buffered()); val input = DataInputStream(counted)
            if (input.readInt() != MAGIC) return null
            when (input.readInt()) {
                1 -> readLegacy(plan, input, bytes, path.lastModified())
                VERSION -> {
                    if (input.readUTF() != plan.fingerprint || input.readInt() != plan.cues.size) return null
                    val header = header(plan)
                    if (counted.count != header.size.toLong()) return null
                    val journal = Journal(plan.fingerprint, ArrayList(), false, 0,
                        checksum(ByteArray(0), header), counted.count, bytes, path.lastModified(), 0)
                    while (counted.count < bytes) {
                        val record = try {
                            val length = input.readInt()
                            if (length !in MIN_PAYLOAD_BYTES..MAX_RECORD_BYTES ||
                                length.toLong() + DIGEST_BYTES > bytes - counted.count) throw java.io.IOException()
                            val body = ByteArray(length).also { input.readFully(it) }
                            val expected = ByteArray(DIGEST_BYTES).also { input.readFully(it) }
                            if (!MessageDigest.isEqual(expected, checksum(journal.digest, body))) throw java.io.IOException()
                            val contents = DataInputStream(ByteArrayInputStream(body))
                            if (contents.readLong() != journal.sequence + 1 || contents.readInt() != journal.texts.size)
                                throw java.io.IOException()
                            val uncertain = contents.readBoolean(); val count = contents.readInt()
                            if (count !in 0..plan.cues.size - journal.texts.size) throw java.io.IOException()
                            val additions = ArrayList<String>(count)
                            repeat(count) { additions += readText(contents) }
                            if (contents.read() != -1) throw java.io.IOException()
                            validateAddition(plan, journal.texts.size, additions)
                            if (journal.totalCharacters + additions.sumOf { it.length.toLong() } > TranslationPlan.MAX_TRACK_CHARACTERS)
                                throw java.io.IOException()
                            Triple(additions, uncertain, expected)
                        } catch (_: Exception) {
                            // The lost record might have marked a remote request
                            // before it was sent. Its charge state is now unknown.
                            journal.recoveredTail = true; journal.uncertain = true; break
                        }
                        journal.texts.addAll(record.first); journal.uncertain = record.second; journal.digest = record.third
                        journal.sequence++; journal.totalCharacters += record.first.sumOf { it.length.toLong() }
                        journal.validBytes = counted.count
                    }
                    if (journal.sequence == 0L) null else journal
                }
                else -> null
            }
        }
    } catch (_: Exception) { null }

    private fun readLegacy(plan: TranslationPlan, input: DataInputStream, bytes: Long, modified: Long): Journal? {
        if (input.readUTF() != plan.fingerprint) return null
        val uncertain = input.readBoolean(); val count = input.readInt()
        if (count !in 0..plan.cues.size) return null
        val texts = ArrayList<String>(count)
        var totalBytes = 0L
        repeat(count) {
            val value = readText(input); totalBytes += value.toByteArray(Charsets.UTF_8).size
            if (totalBytes > TranslationRunner.MAX_CACHE_BYTES) return null
            texts += value
        }
        val fingerprint = input.readUTF()
        if (input.read() != -1) return null
        validateAddition(plan, 0, texts)
        if (if (count == 0) fingerprint != "empty" else prefix(plan, texts).fingerprint != fingerprint) return null
        return Journal(plan.fingerprint, texts, uncertain, 0, ByteArray(0), bytes, bytes, modified,
            texts.sumOf { it.length.toLong() }, legacy = true)
    }

    private fun validateAddition(plan: TranslationPlan, first: Int, texts: List<String>) {
        require(first in 0..plan.cues.size && texts.size <= plan.cues.size - first)
        if (texts.isNotEmpty()) TranslationPlan.create(plan.cues.subList(first, first + texts.size),
            plan.sourceLanguage, plan.targetLanguage, plan.providerId).complete(texts)
    }
    private fun header(plan: TranslationPlan): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeInt(MAGIC); it.writeInt(VERSION); it.writeUTF(plan.fingerprint); it.writeInt(plan.cues.size) }
    }.toByteArray()
    private fun payload(sequence: Long, previousCount: Int, texts: List<String>, uncertain: Boolean): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLong(sequence); output.writeInt(previousCount); output.writeBoolean(uncertain); output.writeInt(texts.size)
                texts.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) }
            }
        }.toByteArray()
    private fun checksum(previous: ByteArray, payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .apply { update(previous); update(payload) }.digest()
    private fun readText(input: DataInputStream): String {
        val length = input.readInt(); require(length in 1..TranslationPlan.MAX_CUE_CHARACTERS * 4)
        val bytes = ByteArray(length).also { input.readFully(it) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private class CountedInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) count++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it > 0) count += it }
    }
    private fun file(plan: TranslationPlan) = File(directory, "${plan.fingerprint}.progress")
    private fun prefix(plan: TranslationPlan, texts: List<String>) = TranslationPlan.create(
        plan.cues.take(texts.size), plan.sourceLanguage, plan.targetLanguage, plan.providerId).complete(texts)
    private companion object {
        const val MAGIC = 0x53545650
        const val VERSION = 2
        const val DIGEST_BYTES = 32
        const val MIN_PAYLOAD_BYTES = 17
        const val MAX_RECORD_BYTES = 32 * 1024 * 1024
    }
}

data class TranslationRunPolicy(
    val maximumNewCharacters: Long = Long.MAX_VALUE,
    /** Local inference can be repeated after interruption without an uncertain remote charge. */
    val requestMayConsumeRemoteQuota: Boolean = true
) {
    init { require(maximumNewCharacters >= 0) }
}
