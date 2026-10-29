package fr.soustitresvoix.player

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
        fun number(value: Int) { digest.update(ByteBuffer.allocate(4).putInt(value).array()) }
        fun field(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); number(bytes.size); digest.update(bytes) }
        fun fields(values: List<String>) { number(values.size); values.forEach(::field) }
        field("exact-local-translation-request-v1"); field(engineId); field(sourceLanguage); field(targetLanguage)
        fields(texts); fields(previousSources); fields(previousTranslations)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun read(key: String): List<String>? = try {
        require(KEY.matches(key))
        val path = file(key)
        AtomicFile(path).openRead().use { stream ->
            if (path.length() !in 1..MAX_ENTRY_BYTES.toLong()) return null
            val all = stream.readBytes()
            if (all.size <= DIGEST_BYTES) return null
            val body = all.copyOfRange(0, all.size - DIGEST_BYTES)
            val expected = all.copyOfRange(all.size - DIGEST_BYTES, all.size)
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(body))) return null
            val input = DataInputStream(ByteArrayInputStream(body))
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
            val body = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC); output.writeInt(1); output.writeUTF(key); output.writeInt(values.size)
                    values.forEach { value ->
                        require(value.isNotBlank() && '\u0000' !in value && value.length <= fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS)
                        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                        val encoded = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        require(encoded.size in 1..fr.soustitresvoix.core.TranslationPlan.MAX_CUE_CHARACTERS * 4)
                        output.writeInt(encoded.size); output.write(encoded)
                    }
                }
            }.toByteArray()
            if (body.size + DIGEST_BYTES > MAX_ENTRY_BYTES) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val path = file(key)
            atomic = AtomicFile(path); stream = atomic.startWrite()
            stream.write(body); stream.write(MessageDigest.getInstance("SHA-256").digest(body))
            stream.flush(); stream.fd.sync(); atomic.finishWrite(stream); stream = null
            trim(path)
        } catch (_: Exception) { runCatching { atomic?.failWrite(stream) } }
    }

    fun clear() {
        directory.listFiles()?.filter { owned(it) }?.forEach { it.delete() }
    }

    private fun trim(pinned: File) {
        val entries = directory.listFiles()?.filter { it.isFile && ENTRY.matches(it.name) }.orEmpty()
        var bytes = entries.sumOf { it.length() }
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (bytes <= MAX_BYTES) break
            if (entry != pinned) {
                val length = entry.length()
                if (entry.delete()) bytes -= length
            }
        }
    }
    private fun file(key: String) = File(directory, "$key.request")
    private fun owned(file: File) = file.isFile && ENTRY.matches(file.name.removeSuffix(".bak").removeSuffix(".new"))
    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 1024 * 1024
        private const val DIGEST_BYTES = 32
        private const val MAGIC = 0x53545652
        private val KEY = Regex("[0-9a-f]{64}")
        private val ENTRY = Regex("[0-9a-f]{64}\\.request")
    }
}
