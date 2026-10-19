package fr.soustitresvoix.core

import java.security.MessageDigest
import java.util.Locale

/** Pure planning: no inference about characters, no dialogue rewriting. */
object SpeechPlanning {
    data class Filters(val descriptions: Boolean = false, val speakers: Boolean = false,
        val songs: Boolean = false, val notes: Boolean = false)
    data class Segment(val text: String, val start: Int)
    private val speaker = Regex("^[-–—]?\\s*[\\p{Lu}][\\p{Lu}\\p{M} .’'_-]{1,35}:\\s*")
    private val note = Regex("^(?:\\[)?(?:NDT|N[.]D[.]T[.]|NOTE DU TRADUCTEUR|TRANSLATOR'?S NOTE)\\s*[:：].*", RegexOption.IGNORE_CASE)
    fun filter(raw: String, options: Filters): String = SubtitleText.clean(raw, options.descriptions)
        .lines().mapNotNull { line ->
            when {
                options.songs && (line.trimStart().startsWith('♪') || line.trimStart().startsWith('♫')) -> null
                options.notes && note.matches(line.trim()) -> null
                else -> if (options.speakers) speaker.replaceFirst(line, "") else line
            }
        }.filter { it.isNotBlank() }.joinToString("\n")

    /** Sentence boundaries, then bounded word chunks. The offsets are exact, not estimated. */
    fun segments(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            val hard = (start + 280).coerceAtMost(text.length)
            var end = (start until hard).firstOrNull { i ->
                (text[i] in ".!?…" && (i + 1 == text.length || text[i + 1].isWhitespace())) || text[i] == '\n'
            }?.plus(1) ?: hard
            if (end == hard && hard < text.length) {
                val word = (hard - 1 downTo start + 1).firstOrNull { text[it].isWhitespace() }
                if (word != null) end = word
            }
            if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            if (end <= start) end = (start + 2).coerceAtMost(text.length)
            val part = text.substring(start, end).trimEnd()
            if (part.isNotEmpty()) result += Segment(part, start)
            start = end
        }
        return result
    }
    fun factor(durationMs: Long, availableMs: Long?, maximum: Float): Float {
        val max = if (maximum.isFinite()) maximum.coerceIn(1f, 1.8f) else 1f
        if (availableMs == null || availableMs <= 0 || durationMs <= 0) return 1f
        return (durationMs.toDouble() / availableMs.coerceAtLeast(250)).toFloat().coerceIn(1f, max)
    }
    fun estimatedMs(text: String, rate: Float): Long =
        (text.length * 65.0 / rate.coerceIn(0.6f, 2f)).toLong().coerceAtLeast(300)
    fun cacheKey(engine: String, version: Long, voice: String, rate: Float, text: String): String {
        val material = listOf("v2", engine, version.toString(), voice, rate.toString(), text)
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
    }
}

/** Timeline is only a look-ahead aid. Media3 remains the authority for spoken cues. */
data class TimedCaption(val startMs: Long, val endMs: Long, val text: String)
object SubtitleTimeline {
    private val timing = Regex("(\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{1,3}")
    fun time(raw: String): Long? {
        val bits = raw.trim().replace(',', '.').split(':')
        if (bits.size !in 2..3) return null
        val seconds = bits.last().toDoubleOrNull() ?: return null
        val minutes = bits[bits.size - 2].toIntOrNull() ?: return null
        val hours = if (bits.size == 3) bits[0].toIntOrNull() ?: return null else 0
        if (!seconds.isFinite() || seconds !in 0.0..<60.0 || minutes !in 0..59 || hours !in 0..999) return null
        return (hours * 3_600_000L + minutes * 60_000L + (seconds * 1000).toLong())
    }
    fun parse(text: String): List<TimedCaption> {
        require(text.length <= 8 * 1024 * 1024)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = ArrayList<TimedCaption>()
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var events = false
        var i = 0
        while (i < lines.size && out.size < 50_000) {
            val line = lines[i].trim()
            if (line.startsWith("[")) events = line.equals("[Events]", true)
            if (events && line.startsWith("Format:", true)) format = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
            if (events && line.startsWith("Dialogue:", true)) {
                val parts = line.substringAfter(':').split(',', limit = format.size)
                fun field(name: String) = format.indexOf(name).takeIf { it >= 0 }?.let { parts.getOrNull(it)?.trim() }
                val a = field("start")?.let(::time); val b = field("end")?.let(::time)
                if (a != null && b != null && b > a) out += TimedCaption(a, b, field("text") ?: "")
            } else if ("-->" in line) {
                val ts = timing.findAll(line).map { time(it.value) }.toList()
                if (ts.size >= 2 && ts[0] != null && ts[1] != null && ts[1]!! > ts[0]!!) {
                    val content = mutableListOf<String>(); i++
                    while (i < lines.size && lines[i].isNotBlank()) { content += lines[i]; i++ }
                    out += TimedCaption(ts[0]!!, ts[1]!!, content.joinToString("\n"))
                }
            }
            i++
        }
        return out.sortedBy { it.startMs }
    }
}
