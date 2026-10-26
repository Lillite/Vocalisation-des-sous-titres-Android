package fr.soustitresvoix.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Conservative, opt-in filters. No guessing of speakers or dialogue semantics. */
object SpeechTools {
    data class Filters(val descriptions: Boolean = false, val speakers: Boolean = false,
        val songs: Boolean = false, val notes: Boolean = false)
    private val speaker = Regex("^([\\p{Lu}][\\p{Lu}\\p{N} _'’.-]{0,35}):[ \\t]+")
    private val note = Regex("^(?:\\[)?(?:NDT|N[.]D[.]T[.]|NOTE DU TRADUCTEUR|NOTE DE TRADUCTION)\\s*:", RegexOption.IGNORE_CASE)
    fun filter(raw: String, f: Filters): String = SubtitleText.clean(raw, f.descriptions).lines().mapNotNull { line ->
        if (f.songs && (line.startsWith("♪") || line.startsWith("♫"))) null
        else if (f.notes && note.containsMatchIn(line)) null
        // Keep an explicit speaker boundary after removing its label. A single
        // wrapped line break otherwise joins two different speakers downstream.
        else if (f.speakers && speaker.containsMatchIn(line)) "\n" + speaker.replaceFirst(line, "") else line
    }.joinToString("\n").trim()

    /** Sentence-sized replay segments, then engine-safe chunks. Keeps all words. */
    fun segments(text: String, languageTag: String? = null): List<String> = LinguisticSegments.split(text, languageTag)
    fun adaptiveFactor(durationMs: Long?, availableMs: Long?, maximum: Float): Float {
        if (durationMs == null || availableMs == null || availableMs <= 0 || durationMs <= 0) return 1f
        return (durationMs.toDouble() / availableMs).toFloat().coerceIn(1f, maximum.coerceIn(1f, 1.6f))
    }
    fun key(configuration: String, text: String): String = MessageDigest.getInstance("SHA-256")
        .digest((configuration + "\u0000" + text).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Bounded WAV inspection: no decoding or loading a whole recording into memory. */
object WavInfo {
    data class Info(val durationMs: Long, val bytes: Long)
    fun read(file: File): Info? = try {
        RandomAccessFile(file, "r").use { r ->
            fun u16(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
            fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
            fun tag(): String { val b = ByteArray(4); r.readFully(b); return String(b, Charsets.US_ASCII) }
            val length = r.length()
            if (length < 44 || length > 16L * 1024 * 1024 || tag() != "RIFF") return null
            val riffSize = u32()
            val riffEnd = 8 + riffSize
            if (riffSize < 4 || riffEnd > length || tag() != "WAVE") return null
            var byteRate = 0L; var blockAlign = 0; var dataSize = -1L; var chunks = 0
            var foundFormat = false
            while (r.filePointer < riffEnd) {
                if (riffEnd - r.filePointer < 8 || ++chunks > 64) return null
                val name = tag(); val size = u32(); val body = r.filePointer
                if (size > riffEnd - body) return null
                // Android FileSynthesisCallback omits the final pad byte for
                // odd-length PCM8 output. Accept complete terminal data while
                // still requiring padding before any following chunk.
                val next = if (name == "data" && body + size == riffEnd) riffEnd
                    else body + size + size % 2
                if (next > riffEnd) return null
                if (name == "fmt ") {
                    if (foundFormat || size < 16 || size == 17L) return null
                    val encoding = u16(); val channels = u16(); val sampleRate = u32()
                    byteRate = u32(); blockAlign = u16(); val bits = u16()
                    when (encoding) {
                        1 -> if (bits != 8 && bits != 16 && bits != 24 && bits != 32) return null
                        3 -> if (bits != 32 && bits != 64) return null
                        else -> return null
                    }
                    if (channels == 0 || sampleRate == 0L ||
                        blockAlign.toLong() != channels.toLong() * (bits / 8) ||
                        byteRate != sampleRate * blockAlign) return null
                    // WAVEFORMATEX may add a cbSize and optional format bytes.
                    // Keep fmt16, fmt18 and larger valid chunks without assuming
                    // that the next chunk or PCM data starts at byte 44.
                    if (size >= 18 && u16().toLong() > size - 18) return null
                    foundFormat = true
                } else if (name == "data") {
                    if (dataSize >= 0) return null
                    dataSize = size
                }
                r.seek(next)
            }
            if (!foundFormat || dataSize <= 0 || dataSize % blockAlign != 0L) null
            else Info(dataSize * 1000 / byteRate, length)
        }
    } catch (_: Exception) { null }
}

/** Disk operations must run on the owner's single I/O executor. */
class SpeechDiskCache(private val directory: File, private val limitBytes: () -> Long) {
    data class Entry(val file: File, val durationMs: Long)
    private var initialized = false
    fun initialize() {
        if (initialized) return
        directory.mkdirs()
        directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
        directory.listFiles()?.filter { it.extension == "sha256" && !File(directory, "${it.nameWithoutExtension}.wav").isFile }?.forEach { it.delete() }
        initialized = true; trim()
    }
    fun get(key: String): Entry? {
        initialize()
        val file = File(directory, "$key.wav")
        if (!file.isFile) return null
        val info = WavInfo.read(file) ?: run { file.delete(); File(directory, "$key.sha256").delete(); return null }
        val digestFile = File(directory, "$key.sha256")
        if (!digestFile.isFile || digestFile.readText() != digest(file)) {
            file.delete(); digestFile.delete(); return null
        }
        file.setLastModified(System.currentTimeMillis())
        return Entry(file, info.durationMs)
    }
    fun temporary(): File { initialize(); return File.createTempFile("synth-", ".part", directory) }
    fun put(key: String, temporary: File): Entry? {
        val info = WavInfo.read(temporary) ?: run { temporary.delete(); return null }
        val target = File(directory, "$key.wav")
        if (!temporary.renameTo(target)) { temporary.delete(); return null }
        val meta = File.createTempFile("digest-", ".part", directory)
        meta.writeText(digest(target))
        if (!meta.renameTo(File(directory, "$key.sha256"))) { meta.delete(); target.delete(); return null }
        trim(protect = target.name)
        return Entry(target, info.durationMs)
    }
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(16384)
            while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun size(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0
    fun trim(protect: String? = null) {
        val files = directory.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= limitBytes().coerceIn(16L shl 20, 8192L shl 20)) break
            if (file.name != protect) { val n = file.length(); if (file.delete()) { size -= n; File(directory, "${file.nameWithoutExtension}.sha256").delete() } }
        }
    }
    fun clear() { directory.listFiles()?.filter { it.extension in listOf("wav", "part", "sha256") }?.forEach { it.delete() } }
}
