package fr.soustitresvoix.player

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Reads at most 64 KiB, never model tensors or an unbounded tokenizer array. */
internal object LocalGgufMetadataReader {
    const val MAX_HEADER_BYTES = 64 * 1024

    fun read(file: File, cancelled: () -> Boolean = { false }, respectInterruption: Boolean = true): LocalGgufMetadata {
        val buffer = ByteArray(MAX_HEADER_BYTES)
        var size = 0
        file.inputStream().use { input ->
            while (size < buffer.size) {
                if (cancelled() || respectInterruption && Thread.currentThread().isInterrupted)
                    throw InterruptedIOException("Préparation du modèle annulée.")
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count > 0) size += count
            }
        }
        return parse(buffer.copyOf(size))
    }

    internal fun parse(bytes: ByteArray): LocalGgufMetadata {
        val input = ByteBuffer.wrap(bytes, 0, minOf(bytes.size, MAX_HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        val fields = HashMap<String, String>()
        var translationTemplate = false
        fun count(): Long {
            if (input.remaining() < 8) throw EOFException()
            return input.long.also { if (it < 0) throw EOFException() }
        }
        fun text(): String {
            val length = count()
            if (length > input.remaining()) throw EOFException()
            val part = ByteArray(length.toInt())
            input.get(part)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(part)).toString()
        }
        fun scalarSize(type: Int) = when (type) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            10, 11, 12 -> 8
            else -> throw EOFException()
        }
        fun skip(type: Int, depth: Int = 0) {
            if (depth > 2) throw EOFException()
            when (type) {
                8 -> { val length = count(); if (length > input.remaining()) throw EOFException(); input.position(input.position() + length.toInt()) }
                9 -> {
                    if (input.remaining() < 12) throw EOFException()
                    val child = input.int
                    val length = count()
                    if (length > MAX_HEADER_BYTES) throw EOFException()
                    if (child !in listOf(8, 9)) {
                        val total = length * scalarSize(child)
                        if (total > input.remaining()) throw EOFException()
                        input.position(input.position() + total.toInt())
                    } else repeat(length.toInt()) { skip(child, depth + 1) }
                }
                else -> {
                    val length = scalarSize(type)
                    if (length > input.remaining()) throw EOFException()
                    input.position(input.position() + length)
                }
            }
        }
        try {
            if (input.remaining() < 24 || input.int != 0x46554747 || input.int !in 2..3) return LocalGgufMetadata()
            count() // Tensor count is intentionally not used to allocate memory.
            val entries = count()
            if (entries > MAX_HEADER_BYTES) return LocalGgufMetadata()
            repeat(entries.toInt()) {
                val key = text()
                if (input.remaining() < 4) throw EOFException()
                val type = input.int
                if (type == 8 && key in setOf("general.architecture", "general.name", "general.basename", "general.finetune", "tokenizer.chat_template")) {
                    val value = text()
                    if (key == "tokenizer.chat_template") {
                        translationTemplate = "You are a professional" in value && "translator" in value &&
                            "source_lang_code" in value && "target_lang_code" in value
                    } else fields[key] = value
                } else if (key == "general.file_type" && type == 4 && input.remaining() >= 4) {
                    fields[key] = input.int.toUInt().toString()
                } else skip(type)
            }
        } catch (_: IOException) { /* Later metadata can lie beyond the bounded sample. */ }
          catch (_: java.nio.BufferUnderflowException) { }
        fun safe(key: String, maximum: Int): String {
            val value = fields[key].orEmpty().filter { it.code >= 32 && it != '\u007f' }
            return if (value.codePointCount(0, value.length) <= maximum) value
                else value.substring(0, value.offsetByCodePoints(0, maximum))
        }
        return LocalGgufMetadata(safe("general.architecture", 128), safe("general.name", 256),
            safe("general.basename", 256), safe("general.finetune", 256), safe("general.file_type", 64), translationTemplate)
    }
}
