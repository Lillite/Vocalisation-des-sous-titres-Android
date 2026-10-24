package fr.soustitresvoix.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.text.*
import fr.soustitresvoix.core.SpeechTools
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.TreeMap

/** Observe already-buffered subtitles; never decode a second copy of the video. */
@UnstableApi
class CueAheadFactory(private val onCue: (String?, CuesWithTiming) -> Unit) : ExtractorsFactory {
    private val limit = 100_000
    private data class Captured(val track: String?, val group: CuesWithTiming, val origins: List<SsaOccurrence>?)
    private val captured = ThreadLocal<Captured?>()
    @Volatile private var onOriginCue: ((String?, CuesWithTiming, List<SsaOccurrence>) -> Unit)? = null
    private val factory = DefaultExtractorsFactory().setSubtitleParserFactory(parsers(sidecar = false))
    var truncated = false; private set
    private var reports = 0
    private var reportedChars = 0L
    /** Configure before the factory is handed to a media source or an indexer. */
    fun setOccurrenceListener(listener: (String?, CuesWithTiming, List<SsaOccurrence>) -> Unit): CueAheadFactory {
        onOriginCue = listener
        return this
    }
    @Synchronized private fun report(track: String?, group: CuesWithTiming, origins: List<SsaOccurrence>? = null) {
        val characters = group.cues.sumOf { it.text?.length ?: 0 }
        if (characters == 0) return
        if (reports >= limit || reportedChars + characters > 12_000_000) { truncated = true; return }
        reports++; reportedChars += characters
        val annotated = onOriginCue
        if (origins != null && annotated != null) annotated(track, group, origins) else onCue(track, group)
    }
    @Synchronized private fun resetBudget() { reports = 0; reportedChars = 0 }
    override fun createExtractors(): Array<Extractor> = wrap(factory.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(factory.createExtractors(uri, responseHeaders))
    private fun wrap(items: Array<Extractor>): Array<Extractor> = items.map<Extractor, Extractor> { extractor ->
        object : Extractor by extractor {
            private val taps = mutableListOf<Tap>()
            override fun init(output: ExtractorOutput) {
                extractor.init(object : ExtractorOutput by output {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val original = output.track(id, type)
                        return if (type == C.TRACK_TYPE_TEXT) Tap(original).also { taps.add(it) } else original
                    }
                })
            }
            override fun seek(position: Long, timeUs: Long) {
                resetBudget(); taps.forEach { it.clear() }; extractor.seek(position, timeUs)
            }
        }
    }.toTypedArray()
    private inner class Tap(private val out: TrackOutput) : TrackOutput by out {
        private var cueFormat = false; private var track: String? = null
        private val bytes = ByteArrayOutputStream()
        private var overflow = false
        fun clear() { bytes.reset(); overflow = false }
        override fun format(format: Format) {
            clear(); cueFormat = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
            track = format.id; out.format(format)
        }
        override fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (cueFormat && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && !overflow) {
                if (bytes.size().toLong() + length <= 1_048_576) bytes.write(data.data, data.position, length)
                else { bytes.reset(); overflow = true; truncated = true }
            }
            out.sampleData(data, length, sampleDataPart)
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!cueFormat) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val buffer = ByteArray(length.coerceAtMost(8192)); val n = input.read(buffer, 0, buffer.size)
            if (n < 0) { if (!allowEndOfInput) throw EOFException(); return C.RESULT_END_OF_INPUT }
            sampleData(ParsableByteArray(buffer, n), n, sampleDataPart); return n
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!cueFormat) { out.sampleMetadata(timeUs, flags, size, offset, cryptoData); return }
            val b = bytes.toByteArray(); val start = b.size - offset - size
            if (!overflow && cryptoData == null && start >= 0 && size > 0) {
                try {
                    val group = CueDecoder().decode(timeUs, b, start, size)
                    report(track, group, originsFor(track, group))
                }
                catch (_: RuntimeException) { truncated = true }
            } else if (overflow || cryptoData != null || start < 0) {
                truncated = true
            }
            bytes.reset()
            if (!overflow && offset in 1..b.size) bytes.write(b, b.size - offset, offset)
            overflow = false
            // Publish the index before the sample becomes readable by TextRenderer.
            // Otherwise a fast renderer can post onCues ahead of this index callback.
            out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }
    private fun originsFor(track: String?, group: CuesWithTiming): List<SsaOccurrence>? {
        val source = captured.get() ?: return null
        val origins = source.origins ?: return null
        if (source.track != track || origins.size != group.cues.size ||
            source.group.durationUs != group.durationUs) return null
        val shift = try { Math.subtractExact(group.startTimeUs, source.group.startTimeUs) }
            catch (_: ArithmeticException) { return null }
        return origins.map { it.shifted(shift) ?: return null }
    }
    fun sidecarParsers(): SubtitleParser.Factory = parsers(sidecar = true)
    private fun parsers(sidecar: Boolean): SubtitleParser.Factory {
        val default = DefaultSubtitleParserFactory()
        return object : SubtitleParser.Factory by default {
            override fun create(format: Format): SubtitleParser {
                val parser = default.create(format)
                if (format.sampleMimeType == MimeTypes.TEXT_SSA) {
                    return SsaOccurrenceParser(parser, format, { truncated = true }) { group, origins, output ->
                        if (sidecar) {
                            report("external-subtitle", group, origins)
                            output.accept(group)
                        } else {
                            // SubtitleTranscodingTrackOutput calls sampleMetadata synchronously
                            // inside this consumer. Its absolute time includes the container offset.
                            val previous = captured.get()
                            captured.set(Captured(format.id, group, origins))
                            try { output.accept(group) } finally {
                                if (previous == null) captured.remove() else captured.set(previous)
                            }
                        }
                    }
                }
                if (!sidecar) return parser
                return object : SubtitleParser by parser {
                    override fun parse(data: ByteArray, offset: Int, length: Int, options: SubtitleParser.OutputOptions,
                        output: Consumer<CuesWithTiming>) {
                        parser.parse(data, offset, length, options, Consumer { timed ->
                            report("external-subtitle", timed); output.accept(timed)
                        })
                    }
                }
            }
        }
    }
}

/** Bounded optional index. Exhausting it never prevents live subtitle playback. */
@UnstableApi
class CueAheadIndex {
    var truncated = false; private set
    data class Item(val track: String?, val startUs: Long, val endUs: Long, val raw: String, val ordinal: Int = 0,
        val sourceToken: String? = null) {
        val identity: String get() = sourceToken?.let { "$track:$startUs:$endUs:$it" }
            ?: "$track:$startUs:$endUs:$ordinal:$raw"
    }
    private val frames = TreeMap<Long, MutableList<Item>>()
    private val unique = HashSet<Item>()
    private val trackSnapshots = mutableMapOf<String?, TrackSnapshot>()
    private data class TrackSnapshot(val items: List<Item>, val maxEnds: LongArray)
    private fun canon(text: String) = text.trim().replace(Regex("\\s+"), " ")
    private fun snapshot(track: String?): TrackSnapshot = trackSnapshots.getOrPut(track) {
        val items = frames.values.asSequence().flatten().filter { it.track == track }.toList()
        var max = Long.MIN_VALUE
        TrackSnapshot(items, LongArray(items.size) { i -> max = maxOf(max, items[i].endUs); max })
    }
    private fun active(track: String?, timeUs: Long): List<Item> {
        val s = snapshot(track); var lo = 0; var hi = s.items.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (s.items[mid].startUs <= timeUs) lo = mid+1 else hi=mid }
        val out = mutableListOf<Item>(); var i=lo-1
        while (i >= 0 && s.maxEnds[i] > timeUs) { if (s.items[i].endUs > timeUs) out += s.items[i]; i-- }
        return out.asReversed()
    }
    private var characters = 0
    private var cursorUs = 0L
    val count: Int @Synchronized get() = unique.size
    @Synchronized fun clear() { frames.clear(); unique.clear(); trackSnapshots.clear(); characters = 0; cursorUs = 0; truncated = false }
    @Synchronized fun add(track: String?, group: CuesWithTiming, origins: List<SsaOccurrence>? = null) {
        if (group.startTimeUs == C.TIME_UNSET || group.durationUs <= 0 || group.durationUs == C.TIME_UNSET) {
            if (group.cues.any { !it.text.isNullOrEmpty() }) truncated = true
            return
        }
        val end = group.startTimeUs + group.durationUs
        if (end < group.startTimeUs) { truncated = true; return }
        for ((ordinal, cue) in group.cues.withIndex()) {
            val raw = cue.text?.toString() ?: continue
            if (raw.length > 24_000) { truncated = true; continue }
            val origin = origins?.takeIf { it.size == group.cues.size }?.get(ordinal)?.takeIf {
                it.startUs <= group.startTimeUs && it.endUs >= end && it.endUs > it.startUs
            }
            if (origins != null && origin == null) truncated = true
            val item = if (origin == null) Item(track, group.startTimeUs, end, raw, ordinal)
                else Item(track, origin.startUs, origin.endUs, raw, origin.ordinal, origin.token)
            if (unique.add(item)) {
                trackSnapshots.remove(track)
                frames.getOrPut(item.startUs) { mutableListOf() }.add(item)
                characters += raw.length
            }
        }
        while (unique.size > 100_000 || characters > 12_000_000) {
            truncated = true
            val key = if (frames.firstKey() < cursorUs - 30_000_000L) frames.firstKey() else frames.lastKey()
            removeFrame(key)
        }
    }
    private fun removeFrame(key: Long) {
        frames.remove(key)?.forEach { unique.remove(it); characters -= it.raw.length; trackSnapshots.remove(it.track) }
    }
    @Synchronized fun selected(track: String?): List<Item> = frames.values.flatten().filter { it.track == track }
    @Synchronized fun future(track: String?, positionMs: Long, filters: SpeechTools.Filters, count: Int, seconds: Int = 60,
        textFor: (Item) -> String? = { it.raw }): List<String> {
        cursorUs = positionMs.coerceAtLeast(0) * 1000
        val futureEnd = cursorUs + seconds.coerceIn(5, 7200) * 1_000_000L
        if (futureEnd < cursorUs) return emptyList()
        return frames.subMap(cursorUs, true, futureEnd, false).values.asSequence().flatten()
            .filter { it.track == track }
            .mapNotNull { textFor(it) }
            .flatMap { SpeechTools.segments(SpeechTools.filter(it, filters)).asSequence() }
            .distinct().take(count.coerceIn(0, 100_000)).toList()
    }
    @Synchronized fun windowEnd(track: String?, text: String, positionMs: Long, filters: SpeechTools.Filters): Long? =
        active(track, positionMs.coerceAtLeast(0) * 1000 + 1000).asSequence()
            .filter {
                val filtered = SpeechTools.filter(it.raw, filters)
                val target = canon(text)
                target.isNotEmpty() && (canon(filtered) == target ||
                    SpeechTools.segments(filtered).any { segment -> canon(segment) == target })
            }
            .minOfOrNull { it.endUs / 1000 }
    @Synchronized fun nextStart(track: String?, positionMs: Long): Long? =
        frames.tailMap(positionMs.coerceAtLeast(0) * 1000, false).values.asSequence().flatten()
            .firstOrNull { it.track == track }?.startUs?.div(1000)
    @Synchronized fun planningWindow(track: String?, positionMs: Long, filters: SpeechTools.Filters): List<Pair<String, Long>> =
        planningItems(track, positionMs).map { SpeechTools.filter(it.raw, filters) to it.endUs / 1000 }
    @Synchronized fun planningItems(track: String?, positionMs: Long): List<Item> =
        frames.subMap(positionMs.coerceAtLeast(0) * 1000, false, (positionMs.coerceAtLeast(0) + 15_000) * 1000, true)
            .values.asSequence().flatten().filter { it.track == track && it.endUs / 1000 > positionMs }
            .take(16).toList()
    @Synchronized fun remaining(track: String?, text: String, positionMs: Long, filters: SpeechTools.Filters): Long? =
        windowEnd(track, text, positionMs, filters)?.minus(positionMs)
    /** Match occurrences once, so their identity and deadline cannot disagree. */
    @Synchronized fun resolved(track: String?, texts: List<String>, timeUs: Long, filters: SpeechTools.Filters): List<Item>? {
        val active = active(track, timeUs + 1000)
        val used = mutableSetOf<Item>(); val result = mutableListOf<Item>()
        for (text in texts) {
            val match = active.firstOrNull { it !in used && canon(SpeechTools.filter(it.raw, filters)) == canon(text) } ?: return null
            result.add(match); used.add(match)
        }
        return result
    }
    @Synchronized fun identities(track: String?, texts: List<String>, timeUs: Long, filters: SpeechTools.Filters): List<String>? =
        resolved(track, texts, timeUs, filters)?.map { it.identity }
}
