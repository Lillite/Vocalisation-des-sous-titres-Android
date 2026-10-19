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

/** Observe already-buffered subtitles; never decode a second copy of the video. */
@UnstableApi
class CueAheadFactory(private val onCue: (String?, CuesWithTiming) -> Unit) : ExtractorsFactory {
    private val factory = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = wrap(factory.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(factory.createExtractors(uri, responseHeaders))
    private fun wrap(items: Array<Extractor>) = items.map { extractor ->
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
            override fun seek(position: Long, timeUs: Long) { taps.forEach { it.clear() }; extractor.seek(position, timeUs) }
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
                else { bytes.reset(); overflow = true }
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
            out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            if (!cueFormat) return
            val b = bytes.toByteArray(); val start = b.size - offset - size
            if (!overflow && cryptoData == null && start >= 0 && size > 0) {
                try { onCue(track, CueDecoder().decode(timeUs, b, start, size)) } catch (_: RuntimeException) { }
            }
            bytes.reset()
            if (!overflow && offset in 1..b.size) bytes.write(b, b.size - offset, offset)
            overflow = false
        }
    }
    fun sidecarParsers(): SubtitleParser.Factory {
        val default = DefaultSubtitleParserFactory()
        return object : SubtitleParser.Factory by default {
            override fun create(format: Format): SubtitleParser {
                val parser = default.create(format)
                return object : SubtitleParser by parser {
                    override fun parse(data: ByteArray, offset: Int, length: Int, options: SubtitleParser.OutputOptions,
                        output: Consumer<CuesWithTiming>) {
                        parser.parse(data, offset, length, options, Consumer { timed ->
                            onCue("external-subtitle", timed); output.accept(timed)
                        })
                    }
                }
            }
        }
    }
}

@UnstableApi
class CueAheadIndex {
    data class Item(val track: String?, val startUs: Long, val endUs: Long, val raw: String) {
        val identity: String get() = "$track:$startUs:$endUs:$raw"
    }
    private val items = mutableListOf<Item>()
    val count get() = items.size
    fun clear() { items.clear() }
    fun add(track: String?, group: CuesWithTiming) {
        if (group.startTimeUs == C.TIME_UNSET || group.durationUs <= 0 || group.durationUs == C.TIME_UNSET) return
        val end = group.startTimeUs + group.durationUs
        if (end < group.startTimeUs) return
        for (cue in group.cues) {
            val raw = cue.text?.toString() ?: continue
            if (raw.length > 24_000) continue
            val item = Item(track, group.startTimeUs, end, raw)
            if (item !in items) items.add(item)
        }
        items.sortBy { it.startUs }
        while (items.size > 512 || items.sumOf { it.raw.length } > 500_000) items.removeAt(0)
    }
    fun selected(track: String?): List<Item> = items.filter { it.track == track }
    fun future(track: String?, positionMs: Long, filters: SpeechTools.Filters, count: Int): List<String> =
        selected(track).asSequence().filter { it.startUs >= positionMs * 1000 && it.startUs < (positionMs + 60_000) * 1000 }
            .flatMap { SpeechTools.segments(SpeechTools.filter(it.raw, filters)).asSequence() }.distinct().take(count).toList()
    fun remaining(track: String?, text: String, positionMs: Long, filters: SpeechTools.Filters): Long? =
        selected(track).filter { it.startUs <= (positionMs + 200) * 1000 && it.endUs > positionMs * 1000 &&
            SpeechTools.filter(it.raw, filters).contains(text) }.minOfOrNull { it.endUs / 1000 - positionMs }
    fun identities(track: String?, texts: List<String>, timeUs: Long, filters: SpeechTools.Filters): List<String>? {
        val active = selected(track).filter { timeUs >= it.startUs - 1000 && timeUs < it.endUs }
        val used = mutableSetOf<String>(); val result = mutableListOf<String>()
        for (text in texts) {
            val match = active.firstOrNull { it.identity !in used && SpeechTools.filter(it.raw, filters) == text } ?: return null
            result.add(match.identity); used.add(match.identity)
        }
        return result
    }
}
