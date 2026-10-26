package fr.soustitresvoix.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Collections

/** A subtitle-time to video-time mapping with a conservative inverse. Occurrence identities and
 * text are deliberately outside this class: correcting timing never merges or
 * removes two occurrences. Anchors describe a continuous, strictly increasing
 * piecewise-linear map. Independent source portions add an offset for scene
 * edits without stretching or deleting the text around a cut. Negative jumps
 * may overlap other portions; each occurrence still retains its full interval. */
class SubtitleTimeline(
    val globalOffsetMs: Long = 0,
    val narrationOffsetMs: Long = 0,
    anchors: List<Anchor> = emptyList(),
    portions: List<Portion> = emptyList()
) {
    data class Anchor(val sourceMs: Long, val videoMs: Long)
    /** Half-open source interval, before global/anchor corrections. */
    data class Portion(val startMs: Long, val endMs: Long, val offsetMs: Long)
    data class Interval(val startMs: Long, val endMs: Long)

    val anchors: List<Anchor> = Collections.unmodifiableList(anchors.toList())
    val portions: List<Portion> = Collections.unmodifiableList(portions.toList())
    private val sourceBoundaries: List<Long> by lazy {
        (listOf(0L, MAX_TIME_MS) + this.portions.flatMap { listOf(it.startMs, it.endMs) }).distinct().sorted()
    }

    init {
        require(globalOffsetMs in -MAX_TIME_MS..MAX_TIME_MS) { "Décalage des sous-titres hors limites." }
        require(narrationOffsetMs in -MAX_NARRATION_OFFSET_MS..MAX_NARRATION_OFFSET_MS) { "Décalage de narration hors limites." }
        require(this.anchors.size <= MAX_ANCHORS) { "Trop de points de synchronisation." }
        require(this.anchors.size != 1) { "Deux points sont nécessaires pour corriger la dérive." }
        this.anchors.forEach { anchor ->
            require(anchor.sourceMs in 0..MAX_TIME_MS && anchor.videoMs in 0..MAX_TIME_MS) {
                "Les repères doivent être positifs et dans les limites de la vidéo."
            }
        }
        this.anchors.zipWithNext().forEach { (a, b) ->
            require(b.sourceMs > a.sourceMs && b.videoMs > a.videoMs) {
                "Les temps source et vidéo doivent tous les deux augmenter strictement."
            }
        }
        require(this.portions.size <= MAX_PORTIONS) { "Trop de corrections par portions." }
        this.portions.forEach { portion ->
            require(portion.startMs in 0 until MAX_TIME_MS && portion.endMs in 1..MAX_TIME_MS &&
                portion.endMs > portion.startMs) { "Chaque portion doit avoir un début positif ou nul et une fin supérieure, dans les limites." }
            require(portion.offsetMs in -MAX_TIME_MS..MAX_TIME_MS) { "Décalage de portion hors limites." }
        }
        this.portions.zipWithNext().forEach { (a, b) ->
            require(b.startMs >= a.endMs) { "Les portions doivent être ordonnées et ne pas se chevaucher dans la piste originale." }
        }
    }

    val identity: Boolean get() = globalOffsetMs == 0L && narrationOffsetMs == 0L &&
        anchors.all { it.sourceMs == it.videoMs } && portions.all { it.offsetMs == 0L }

    fun copy(
        globalOffsetMs: Long = this.globalOffsetMs,
        narrationOffsetMs: Long = this.narrationOffsetMs,
        anchors: List<Anchor> = this.anchors,
        portions: List<Portion> = this.portions
    ) = SubtitleTimeline(globalOffsetMs, narrationOffsetMs, anchors, portions)

    fun subtitleTimeMs(sourceMs: Long): Long = mappedTime(sourceMs, globalOffsetMs + portionOffsetAt(sourceMs))

    fun narrationTimeMs(sourceMs: Long): Long = mappedTime(sourceMs, globalOffsetMs + narrationOffsetMs + portionOffsetAt(sourceMs))

    private fun mappedTime(sourceMs: Long, offsetMs: Long): Long = if (anchors.isEmpty()) addBounded(sourceMs, offsetMs) else bounded(
        transform(BigDecimal.valueOf(sourceMs), inverse = false).add(BigDecimal.valueOf(offsetMs)))

    /** Inverse used to find an original position for a video position. The
     * original unclipped map is inverted, so offsets are not subtracted twice.
     * Scene-edit overlaps choose the earliest source, gaps the next boundary.
     * To retrieve every active occurrence, query mapped occurrence intervals. */
    fun sourceTimeMs(videoMs: Long, narration: Boolean = false): Long {
        val baseOffset = globalOffsetMs + if (narration) narrationOffsetMs else 0
        fun inverse(offset: Long): Long {
            if (anchors.isEmpty()) return addBounded(videoMs, -offset)
            return bounded(transform(BigDecimal.valueOf(videoMs).subtract(BigDecimal.valueOf(offset)), inverse = true))
        }
        if (portions.isEmpty()) return inverse(baseOffset)
        // Scene edits can create gaps and overlapping mapped portions. Resolve
        // overlaps to the earliest original position; never pretend a cut
        // deleted its source occurrences. Playback enumerates mapped intervals.
        var earliest: Long? = null
        var nextBoundary: Pair<Long, Long>? = null
        sourceBoundaries.zipWithNext().forEach { (start, end) ->
            val offset = baseOffset + portionOffsetAt(start)
            val candidate = inverse(offset)
            if (candidate >= start && (candidate < end || end == MAX_TIME_MS && candidate == end)) {
                earliest = earliest?.coerceAtMost(candidate) ?: candidate
            }
            val videoStart = mappedTime(start, offset)
            if (videoStart >= videoMs && (nextBoundary == null || videoStart < nextBoundary!!.first)) {
                nextBoundary = videoStart to start
            }
        }
        return earliest ?: nextBoundary?.second ?: MAX_TIME_MS
    }

    /** A negative corrected interval is clipped at zero, but never becomes a
     * zero-length occurrence. A very short interval can round to one millisecond. */
    fun mapInterval(startMs: Long, endMs: Long, narration: Boolean = false): Interval {
        require(endMs > startMs) { "Une occurrence doit posséder une durée positive." }
        // An occurrence crossing a cut uses its start's portion for both ends.
        // This preserves all its words and duration instead of collapsing or
        // stretching it at a discontinuity.
        val offset = globalOffsetMs + (if (narration) narrationOffsetMs else 0) + portionOffsetAt(startMs)
        val start = mappedTime(startMs, offset).coerceAtMost(MAX_TIME_MS - 1)
        val end = mappedTime(endMs, offset)
        return Interval(start, end.coerceAtLeast(start + 1))
    }

    private fun portionOffsetAt(sourceMs: Long): Long {
        var low = 0
        var high = portions.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (portions[middle].startMs <= sourceMs) low = middle + 1 else high = middle
        }
        val portion = portions.getOrNull(low - 1)
        return if (portion != null && sourceMs < portion.endMs) portion.offsetMs else 0
    }

    private fun transform(value: BigDecimal, inverse: Boolean): BigDecimal {
        if (anchors.isEmpty()) return value
        fun from(a: Anchor) = BigDecimal.valueOf(if (inverse) a.videoMs else a.sourceMs)
        fun to(a: Anchor) = BigDecimal.valueOf(if (inverse) a.sourceMs else a.videoMs)
        var first = anchors.first()
        var second = anchors[1]
        if (value >= from(anchors.last())) {
            first = anchors[anchors.lastIndex - 1]
            second = anchors.last()
        } else if (value > from(first)) {
            var low = 1
            var high = anchors.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (value <= from(anchors[middle])) high = middle else low = middle + 1
            }
            first = anchors[low - 1]
            second = anchors[low]
        }
        val fraction = value.subtract(from(first)).divide(from(second).subtract(from(first)), PRECISION)
        return to(first).add(fraction.multiply(to(second).subtract(to(first)), PRECISION))
    }

    override fun equals(other: Any?): Boolean = other is SubtitleTimeline &&
        globalOffsetMs == other.globalOffsetMs && narrationOffsetMs == other.narrationOffsetMs && anchors == other.anchors && portions == other.portions
    override fun hashCode(): Int = 31 * (31 * (31 * globalOffsetMs.hashCode() + narrationOffsetMs.hashCode()) + anchors.hashCode()) + portions.hashCode()
    override fun toString(): String = "SubtitleTimeline(offset=$globalOffsetMs ms, narration=$narrationOffsetMs ms, anchors=${anchors.size}, portions=${portions.size})"

    companion object {
        /** Ten years comfortably bounds media durations and prevents numeric overflow. */
        const val MAX_TIME_MS = 315_576_000_000L
        const val MAX_NARRATION_OFFSET_MS = 60_000L
        const val MAX_ANCHORS = 64
        const val MAX_PORTIONS = 64
        private val PRECISION = MathContext.DECIMAL128
        private val MAXIMUM = BigDecimal.valueOf(MAX_TIME_MS)
        /** Offsets are bounded far below Long limits, so comparisons avoid an
         * overflowing addition even for corrupt/extreme extractor timestamps. */
        private fun addBounded(value: Long, offset: Long): Long = when {
            value < -offset -> 0
            value > MAX_TIME_MS - offset -> MAX_TIME_MS
            else -> value + offset
        }
        private fun bounded(value: BigDecimal): Long = value.max(BigDecimal.ZERO).min(MAXIMUM)
            .setScale(0, RoundingMode.HALF_UP).toLong()
    }
}
