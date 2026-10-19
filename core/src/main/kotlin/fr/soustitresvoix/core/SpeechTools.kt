package fr.soustitresvoix.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

/** Conservative, opt-in filters. No guessing of speakers or dialogue semantics. */
object SpeechTools {
    data class Filters(val descriptions: Boolean = false, val speakers: Boolean = false,
        val songs: Boolean = false, val notes: Boolean = false)
    private val speaker = Regex("^([\\p{Lu}][\\p{Lu}\\p{N} _'’.-]{0,35}):[ \\t]+")
    private val note = Regex("^(?:\\[)?(?:NDT|N[.]D[.]T[.]|NOTE DU TRADUCTEUR|NOTE DE TRADUCTION)\\s*:", RegexOption.IGNORE_CASE)
    fun filter(raw: String, f: Filters): String = SubtitleText.clean(raw, f.descriptions).lines().mapNotNull { line ->
        if (f.songs && (line.startsWith("♪") || line.startsWith("♫"))) null
        else if (f.notes && note.containsMatchIn(line)) null
        else if (f.speakers) speaker.replaceFirst(line, "") else line
    }.joinToString("\n").trim()

    /** Sentence-sized replay segments, then engine-safe chunks. Keeps all words. */
    fun segments(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val iterator = BreakIterator.getSentenceInstance(Locale.FRENCH)
        iterator.setText(text)
        var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out += SubtitleText.splitForSpeech(text.substring(start, end), 1000)
            start = end; end = iterator.next()
        }
        return out
    }
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
            fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
            fun tag(): String { val b = ByteArray(4); r.readFully(b); return String(b, Charsets.US_ASCII) }
            if (r.length() < 44 || r.length() > 16L * 1024 * 1024 || tag() != "RIFF") return null
            u32(); if (tag() != "WAVE") return null
            var byteRate = 0L; var dataSize = 0L; var chunks = 0
            while (r.filePointer + 8 <= r.length() && chunks++ < 64) {
                val name = tag(); val size = u32(); val body = r.filePointer
                if (size > r.length() - body) return null
                if (name == "fmt " && size >= 16) {
                    val encoding = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
                    if (encoding !in listOf(1, 3)) return null
                    r.skipBytes(6); byteRate = u32()
                }
                if (name == "data") dataSize = size
                if (dataSize > 0 && byteRate > 0) break
                r.seek(body + size + size % 2)
            }
            if (byteRate <= 0 || dataSize <= 0) null else Info(dataSize * 1000 / byteRate, r.length())
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
        initialized = true; trim()
    }
    fun get(key: String): Entry? {
        initialize()
        val file = File(directory, "$key.wav")
        if (!file.isFile) return null
        val info = WavInfo.read(file) ?: run { file.delete(); return null }
        file.setLastModified(System.currentTimeMillis())
        return Entry(file, info.durationMs)
    }
    fun temporary(): File { initialize(); return File.createTempFile("synth-", ".part", directory) }
    fun put(key: String, temporary: File): Entry? {
        val info = WavInfo.read(temporary) ?: run { temporary.delete(); return null }
        val target = File(directory, "$key.wav")
        if (!temporary.renameTo(target)) { temporary.delete(); return null }
        trim(protect = target.name)
        return Entry(target, info.durationMs)
    }
    fun size(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0
    fun trim(protect: String? = null) {
        val files = directory.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= limitBytes().coerceIn(16L shl 20, 256L shl 20)) break
            if (file.name != protect) { val n = file.length(); if (file.delete()) size -= n }
        }
    }
    fun clear() { directory.listFiles()?.filter { it.extension in listOf("wav", "part") }?.forEach { it.delete() } }
}
