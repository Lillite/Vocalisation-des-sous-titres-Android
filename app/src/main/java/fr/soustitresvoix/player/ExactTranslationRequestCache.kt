package fr.soustitresvoix.player

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Optional local results, never a substitute for the durable work journal.
 * Keys contain the complete linguistic request, including exact history. Cue
 * identities/times stay with the caller, so a hit never removes an occurrence.
 */
internal class ExactTranslationRequestCache(private val directory: File) {
    fun key(engineId: String, sourceLanguage: String, targetLanguage: String, texts: List<String>,
        previousSources: List<String>, previousTranslations: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val encodedNumber = ByteArray(4)
        fun number(value: Int) {
            encodedNumber[0] = (value ushr 24).toByte(); encodedNumber[1] = (value ushr 16).toByte()
            encodedNumber[2] = (value ushr 8).toByte(); encodedNumber[3] = value.toByte()
            digest.update(encodedNumber)
        }
        fun field(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); number(bytes.size); digest.update(bytes) }
        fun fields(values: List<String>) { number(values.size); values.forEach(::field) }
        field("exact-local-translation-request-v1"); field(engineId); field(sourceLanguage); field(targetLanguage)
        fields(texts); fields(previousSources); fields(previousTranslations)
        val bytes = digest.digest()
        return CharArray(bytes.size * 2).also { result ->
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                result[index * 2] = HEX[value ushr 4]; result[index * 2 + 1] = HEX[value and 15]
            }
        }.concatToString()
    }

    fun read(key: String): List<String>? = try {
        require(KEY.matches(key))
        val path = file(key)
        AtomicFile(path).openRead().use { stream ->
            val size = path.length()
            if (size !in DIGEST_BYTES + 1L..MAX_ENTRY_BYTES.toLong()) return null
            // A single bounded allocation also rejects a file that grows after
            // the size check. Digest and parser share a view of the same body.
            val all = ByteArray(size.toInt())
            DataInputStream(stream).readFully(all)
            if (stream.read() != -1) return null
            val bodySize = all.size - DIGEST_BYTES
            val expected = all.copyOfRange(bodySize, all.size)
            val digest = MessageDigest.getInstance("SHA-256").apply { update(all, 0, bodySize) }.digest()
            if (!MessageDigest.isEqual(expected, digest)) return null
            val input = DataInputStream(ByteArrayInputStream(all, 0, bodySize))
            if (input.readInt() != MAGIC || input.readInt() != 1 || input.readUTF() != key) return null
            val count = input.readInt()
            if (count !in 1..TranslationRunner.MAX_BATCH_ITEMS) return null
            val values = ArrayList<String>(count)
            repeat(count) {
                val length = input.readInt()
                if (length !in 1..fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS * 4) return null
                val bytes = ByteArray(length).also { input.readFully(it) }
                val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                if (value.isBlank() || '\u0000' in value || value.length > fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS)
                    return null
                values += value
            }
            if (input.read() != -1) return null
            path.setLastModified(System.currentTimeMillis())
            values
        }
    } catch (_: Exception) { null }

    fun write(key: String, values: List<String>) {
        var stream: java.io.FileOutputStream? = null
        var atomic: AtomicFile? = null
        try {
            require(KEY.matches(key) && values.size in 1..TranslationRunner.MAX_BATCH_ITEMS)
            val body = ByteArrayOutputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(body, digest)).use { output ->
                output.writeInt(MAGIC); output.writeInt(1); output.writeUTF(key); output.writeInt(values.size)
                values.forEach { value ->
                    require(value.isNotBlank() && '\u0000' !in value && value.length <= fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS)
                    val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                    val length = buffer.remaining()
                    require(length in 1..fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS * 4)
                    if (body.size().toLong() + 4 + length + DIGEST_BYTES > MAX_ENTRY_BYTES) return
                    output.writeInt(length)
                    if (buffer.hasArray()) output.write(buffer.array(), buffer.arrayOffset() + buffer.position(), length)
                    else output.write(ByteArray(length).also { buffer.get(it) })
                }
            }
            if (!directory.isDirectory && !directory.mkdirs()) return
            val path = file(key)
            atomic = AtomicFile(path); stream = atomic.startWrite()
            body.writeTo(stream); stream.write(digest.digest())
            // finishWrite closes, syncs and commits the AtomicFile. This optional
            // cache does not acknowledge a durable result; the work journal does.
            atomic.finishWrite(stream); stream = null
            trim(path)
        } catch (_: Exception) { runCatching { atomic?.failWrite(stream) } }
    }

    fun clear() {
        directory.listFiles()?.filter { owned(it) }?.forEach { it.delete() }
    }

    private fun trim(pinned: File) {
        val entries = directory.listFiles()?.filter { owned(it) }.orEmpty()
        var bytes = entries.sumOf { it.length() }
        // Always inventory the real directory, including another instance's
        // files and interrupted writes. Recency and sorting are needed only
        // when that inventory actually exceeds the budget.
        if (bytes <= MAX_BYTES) return
        val groups = entries.groupBy { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .map { (base, members) -> Triple(base, members, members.maxOf { it.lastModified() }) }
            .sortedBy { it.third }
        for ((base, members) in groups) {
            if (bytes <= MAX_BYTES) break
            if (base != pinned.name) {
                val before = members.sumOf { it.length() }
                // A backup/new file without its base still belongs to this
                // evictable key. Remove its AtomicFile group as one unit.
                AtomicFile(File(directory, base)).delete()
                bytes -= before - members.sumOf { it.length() }
            }
        }
    }
    private fun file(key: String) = File(directory, "$key.request")
    private fun owned(file: File): Boolean {
        val name = file.name
        return (ENTRY.matches(name) || name.endsWith(".bak") && ENTRY.matches(name.removeSuffix(".bak")) ||
            name.endsWith(".new") && ENTRY.matches(name.removeSuffix(".new"))) && file.isFile
    }
    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 1024 * 1024
        private const val DIGEST_BYTES = 32
        private const val MAGIC = 0x53545652
        private const val HEX = "0123456789abcdef"
        private val KEY = Regex("[0-9a-f]{64}")
        private val ENTRY = Regex("[0-9a-f]{64}\\.request")
    }
}
