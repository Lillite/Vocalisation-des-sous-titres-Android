package fr.soustitresvoix.player

import android.util.AtomicFile
import fr.soustitresvoix.core.TranslationPlan
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Durable validated prefixes. An uncertain request is never described as unbilled. */
internal class TranslationCheckpointStore(private val directory: File) {
    data class Checkpoint(val texts: List<String>, val uncertainRequest: Boolean)
    fun read(plan: TranslationPlan): Checkpoint? = try {
        val file = file(plan)
        AtomicFile(file).openRead().use { stream ->
            if (file.length() !in 1..TranslationRunner.MAX_CACHE_BYTES) return null
            DataInputStream(stream.buffered()).use { input ->
            if (input.readInt() != 0x53545650 || input.readInt() != 1 || input.readUTF() != plan.fingerprint) return null
            val uncertain = input.readBoolean()
            val count = input.readInt()
            if (count !in 0..plan.cues.size) return null
            val texts = ArrayList<String>(count)
            var total = 0L
            repeat(count) {
                val length = input.readInt()
                if (length !in 1..(TranslationPlan.MAX_CUE_CHARACTERS * 4)) return null
                total += length
                if (total > TranslationRunner.MAX_CACHE_BYTES) return null
                val bytes = ByteArray(length); input.readFully(bytes)
                texts += Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
            val fingerprint = input.readUTF()
            if (input.read() != -1) return null
            if (count > 0 && prefix(plan, texts).fingerprint != fingerprint) return null
            if (count == 0 && fingerprint != "empty") return null
            Checkpoint(texts.toList(), uncertain)
        } }
    } catch (_: Exception) { null }
    fun write(plan: TranslationPlan, texts: List<String>, uncertain: Boolean): Boolean {
        var output: java.io.FileOutputStream? = null
        val atomic = AtomicFile(file(plan))
        return try {
            val fingerprint = if (texts.isEmpty()) "empty" else prefix(plan, texts).fingerprint
            if (texts.sumOf { 4L + it.toByteArray(Charsets.UTF_8).size.toLong() } + 256 > TranslationRunner.MAX_CACHE_BYTES) return false
            if (!directory.isDirectory && !directory.mkdirs()) return false
            output = atomic.startWrite()
            DataOutputStream(output).apply {
                writeInt(0x53545650); writeInt(1); writeUTF(plan.fingerprint); writeBoolean(uncertain)
                writeInt(texts.size)
                texts.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
                writeUTF(fingerprint); flush()
            }
            atomic.finishWrite(output); output = null; true
        } catch (_: Exception) { runCatching { atomic.failWrite(output) }; false }
    }
    fun remove(plan: TranslationPlan) { AtomicFile(file(plan)).delete() }
    private fun file(plan: TranslationPlan) = File(directory, "${plan.fingerprint}.progress")
    private fun prefix(plan: TranslationPlan, texts: List<String>) = TranslationPlan.create(
        plan.cues.take(texts.size), plan.sourceLanguage, plan.targetLanguage, plan.providerId).complete(texts)
}

data class TranslationRunPolicy(
    val maximumNewCharacters: Long = Long.MAX_VALUE,
    /** Local inference can be repeated after interruption without an uncertain remote charge. */
    val requestMayConsumeRemoteQuota: Boolean = true
) {
    init { require(maximumNewCharacters >= 0) }
}
