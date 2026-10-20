package fr.soustitresvoix.core

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil

/** Punctuation is retained for the TTS engine. No silence is removed from audio. */
object LinguisticSegments {
    const val VERSION = "fr-boundaries-4"
    private val abbreviations = setOf("m", "mm", "mme", "mmes", "mlle", "dr", "pr", "prof", "st", "ste", "mr", "mrs", "ms", "etc", "cf", "av", "art", "vol", "p", "pp", "fig", "n", "no")
    private val speaker = Regex("^\\s*(?:[-–—]\\s+|[\\p{Lu}][\\p{Lu}\\p{N} _'’.-]{0,35}:\\s+)")
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val paragraphs = mutableListOf<String>(); val current = StringBuilder()
        text.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
            if (line.isBlank() || speaker.containsMatchIn(line)) {
                if (current.isNotBlank()) { paragraphs += current.toString().trim(); current.setLength(0) }
            }
            if (line.isNotBlank()) { if (current.isNotEmpty()) current.append(' '); current.append(line.trim()) }
        }
        if (current.isNotBlank()) paragraphs += current.toString().trim()
        val output = mutableListOf<String>()
        for (p in paragraphs) {
            var start = 0; var i = 0
            while (i < p.length) {
                val c = p[i]
                if (c !in ".!?…。！？؟۔।॥") { i++; continue }
                val decimal = c == '.' && i > 0 && i + 1 < p.length && p[i-1].isDigit() && p[i+1].isDigit()
                val token = p.substring(start, i).takeLastWhile { it.isLetter() }.lowercase(Locale.ROOT)
                val abbreviation = c == '.' && (token in abbreviations || (token.length == 1 && token[0].isLetter()))
                var end = i + 1
                while (end < p.length && p[end] in ".!?…。！？؟۔।॥\"'’»”)]」』】）》") end++
                // French typography places a space before a closing guillemet.
                // Keep it with the sentence; never absorb the next opening quote.
                while (end < p.length) {
                    var closer = end
                    while (closer < p.length && p[closer].isWhitespace()) closer++
                    if (closer >= p.length || p[closer] !in "»”)]」』】）》") break
                    end = closer + 1
                    while (end < p.length && p[end] in ".!?…。！？؟۔।॥»”)]」』】）》") end++
                }
                val boundary = end == p.length || p[end].isWhitespace() || c in "。！？؟۔।॥"
                if (!decimal && !abbreviation && boundary) {
                    output += SubtitleText.splitForSpeech(p.substring(start, end), 1000)
                    start = end
                    while (start < p.length && p[start].isWhitespace()) start++
                    i = start
                } else i = end
            }
            if (start < p.length) output += SubtitleText.splitForSpeech(p.substring(start), 1000)
        }
        return output.filter { it.isNotBlank() }
    }
}

/** Duration model calibrated by *completed* waveforms or onStart/onDone intervals. */
class SpeechDurationModel {
    private val wordsPattern = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
    private val naturalDurations = LinkedHashMap<String, Long>()
    private var correction = 1.0
    var observations = 0; private set
    fun estimate(text: String, baseRate: Float): Long {
        val natural = naturalDurations.getOrPut(text) {
            val words = wordsPattern.findAll(text).count()
            val letters = text.count { it.isLetterOrDigit() }
            val punctuation = text.sumOf { when (it) { ',', ';', ':' -> 130L; '.', '!', '?', '…' -> 220L; else -> 0L } }
            maxOf(words * 330L, letters * 55L) + punctuation + 100L
        }
        // Forecasts run during playback; avoid rescanning identical dialogue at
        // every tick while keeping retained text strictly bounded.
        if (naturalDurations.size > 256) naturalDurations.remove(naturalDurations.keys.first())
        return (natural * correction / baseRate.coerceIn(0.6f, 2f)).toLong().coerceIn(150, 180_000)
    }
    fun observe(text: String, normalizedAudioMs: Long, baseRate: Float) {
        if (normalizedAudioMs !in 200..180_000 || text.length < 6) return
        val old = estimate(text, baseRate).toDouble()
        val ratio = (normalizedAudioMs / old).coerceIn(0.65, 1.6)
        correction = (correction * (0.8 + 0.2 * ratio)).coerceIn(0.5, 2.5); observations++
    }
    fun clear() { correction = 1.0; observations = 0; naturalDurations.clear() }
}

object AdaptiveTiming {
    data class Window(val durationMs: Long, val releaseMs: Long = 0, val deadlineMs: Long? = null)

    /** FIFO pressure until the next real idle gap; later bursts choose their own rate. */
    fun planFactor(jobs: List<Window>, maximum: Float, reserveMs: Long = 100): Float {
        val max = if (maximum.isFinite()) maximum.coerceIn(1f, 2.5f) else 1f
        val plan = jobs.take(32)
        if (plan.none { it.deadlineMs != null }) return 1f
        fun fits(speed: Double): Boolean {
            var finish = 0.0
            for ((index, job) in plan.withIndex()) {
                val release = job.releaseMs.coerceAtLeast(0).toDouble()
                // Once this queue drains before a later cue appears, accelerating
                // the current sentence cannot help that independent later cue.
                if (index > 0 && release > 0 && release >= finish) return true
                finish = maxOf(finish, release) +
                    job.durationMs.coerceIn(0, 180_000).toDouble() / speed
                if (job.deadlineMs != null && finish + reserveMs.coerceAtLeast(0) > job.deadlineMs.toDouble()) return false
            }
            return true
        }
        if (fits(1.0)) return 1f
        if (!fits(max.toDouble())) return max
        var low = 1.0; var high = max.toDouble()
        repeat(20) {
            val middle = (low + high) / 2
            if (fits(middle)) high = middle else low = middle
        }
        return high.toFloat().coerceIn(1f, max)
    }
    /** Reserve the budget for the remaining segments too, not just this fragment. */
    fun factor(durationMs: Long, remainingDurationMs: Long, availableMs: Long?, maximum: Float, reserveMs: Long = 100): Float {
        val max = if (maximum.isFinite()) maximum.coerceIn(1f, 2.5f) else 1f
        if (availableMs == null) return 1f
        if (availableMs <= reserveMs) return max
        val total = durationMs.coerceAtLeast(0).toDouble() + remainingDurationMs.coerceAtLeast(0).toDouble()
        return (total / (availableMs - reserveMs)).toFloat().coerceIn(1f, max)
    }
    fun forecastFactor(jobs: List<Pair<Long, Long>>, maximum: Float): Float {
        var total = 0L; var factor = 1f
        jobs.take(32).forEach { (duration, available) ->
            total = (total + duration.coerceIn(0, 180_000)).coerceAtMost(5_760_000)
            factor = maxOf(factor, factor(total, 0, available, maximum))
        }
        return factor
    }
}

/** Bounded samples: percentiles describe the last capacity observations. */
class LatencyWindow(private val capacity: Int = 512) {
    init { require(capacity in 1..8192) }
    private val values = ArrayDeque<Long>()
    @Synchronized fun add(ms: Long) { if (ms >= 0) { if (values.size == capacity) values.removeFirst(); values.addLast(ms) } }
    @Synchronized fun clear() = values.clear()
    @Synchronized fun summary(): String {
        if (values.isEmpty()) return "aucune mesure"
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return "n=${sorted.size}, p50=${percentile(.50)} ms, p95=${percentile(.95)} ms, p99=${percentile(.99)} ms, max=${sorted.last()} ms"
    }
}
