package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttCueParser
import androidx.media3.extractor.text.webvtt.WebvttCueInfo
import androidx.media3.extractor.text.webvtt.WebvttParserUtil
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.PriorityQueue

/** Source-event identity for SubRip/WebVTT before index de-duplication.
 * Rendering is always the untouched Media3 output. SubRip exposes one output per
 * event, including truly identical simultaneous events. WebVTT copies automatic
 * line-position cues into overlapping intervals, so its original cue headers are
 * read with Media3's own cue parser solely to recover identity and lifetime.
 */
@UnstableApi
internal class TextOccurrenceParser(
    private val delegate: SubtitleParser,
    private val format: Format,
    private val onLimit: () -> Unit,
    private val emit: (CuesWithTiming, List<SsaOccurrence>?, Consumer<CuesWithTiming>) -> Unit
) : SubtitleParser {
    private class AnnotationLimit : RuntimeException()
    private data class Annotated(val group: CuesWithTiming, val origins: List<SsaOccurrence>?)
    private data class Key(val start: Long, val duration: Long, val texts: List<String?>)
    private data class VttEvent(val info: WebvttCueInfo, val ordinal: Int)
    override fun getCueReplacementBehavior() = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int,
        options: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
        val groups = mutableListOf<CuesWithTiming>()
        var characters = 0L
        try {
            delegate.parse(data, offset, length, SubtitleParser.OutputOptions.allCues(), Consumer { group ->
                characters += group.cues.sumOf { it.text?.length?.toLong() ?: 0L }
                if (groups.size >= 100_000 || characters > 12_000_000) throw AnnotationLimit()
                groups += group
            })
        } catch (_: AnnotationLimit) {
            onLimit(); delegate.reset()
            delegate.parse(data, offset, length, options, Consumer { emit(it, null, output) })
            return
        }
        val fingerprint = fingerprint(data, offset, length)
        val annotations = try {
            if (format.sampleMimeType == MimeTypes.TEXT_VTT) annotateVtt(groups, data, offset, length, fingerprint)
            else annotateSubrip(groups, fingerprint)
        } catch (_: RuntimeException) {
            onLimit(); groups.map { Annotated(it, null) }
        }
        if (options.startTimeUs == C.TIME_UNSET) {
            annotations.forEach { emit(it.group, it.origins, output) }
            return
        }
        // Let Media3 select and order seek output itself. Matching to the complete
        // parse keeps source ordinals stable even when earlier cues are omitted.
        val remaining = HashMap<Key, ArrayDeque<Annotated>>()
        annotations.forEach { remaining.getOrPut(key(it.group)) { ArrayDeque() }.addLast(it) }
        delegate.reset()
        delegate.parse(data, offset, length, options, Consumer { group ->
            val annotation = remaining[key(group)]?.pollFirst()
                ?: if (format.sampleMimeType == MimeTypes.TEXT_VTT) containingVttInterval(annotations, group) else null
            if (annotation == null) onLimit()
            emit(group, annotation?.origins, output)
        })
    }

    /** LegacySubtitleUtil splits the interval straddling a seek into an initial
     * interval at the requested time and, for outputAllCues, its earlier half at
     * the end. Both halves still describe the same original WebVTT events. */
    private fun containingVttInterval(annotations: List<Annotated>, group: CuesWithTiming): Annotated? {
        var low = 0
        var high = annotations.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (annotations[middle].group.startTimeUs <= group.startTimeUs) low = middle + 1 else high = middle
        }
        val candidate = annotations.getOrNull(low - 1) ?: return null
        return candidate.takeIf {
            group.durationUs > 0 && it.group.endTimeUs >= group.endTimeUs &&
                it.group.cues.map { cue -> cue.text?.toString() } == group.cues.map { cue -> cue.text?.toString() }
        }
    }

    private fun annotateSubrip(groups: List<CuesWithTiming>, fingerprint: String): List<Annotated> {
        var ordinal = 0
        return groups.map { group ->
            require(group.startTimeUs != C.TIME_UNSET && group.durationUs > 0 && group.endTimeUs > group.startTimeUs)
            val origins = group.cues.map {
                val event = ordinal++
                SsaOccurrence("subrip-event-v1:$fingerprint:$event", event, group.startTimeUs, group.endTimeUs)
            }
            Annotated(group, origins)
        }
    }

    private fun annotateVtt(groups: List<CuesWithTiming>, data: ByteArray, offset: Int,
        length: Int, fingerprint: String): List<Annotated> {
        val input = ParsableByteArray(data, offset + length).apply { position = offset }
        WebvttParserUtil.validateWebvttHeaderLine(input)
        while (!input.readLine().isNullOrEmpty()) { }
        val events = mutableListOf<VttEvent>()
        var sourceCharacters = 0L
        while (input.bytesLeft() > 0) {
            val start = input.position
            val line = input.readLine() ?: break
            if (line.isEmpty()) continue
            if (line == "STYLE" || line.startsWith("NOTE")) {
                while (!input.readLine().isNullOrEmpty()) { }
                continue
            }
            input.position = start
            // CSS changes spans, not text or source times. It is intentionally
            // left to the rendering delegate; this second pass creates no pixels.
            val info = WebvttCueParser.parseCue(input, emptyList())
            if (info != null) {
                sourceCharacters += info.cue.text?.length ?: 0
                if (events.size >= 100_000 || sourceCharacters > 12_000_000) throw AnnotationLimit()
                events += VttEvent(info, events.size)
            }
            check(input.position > start) { "WebVTT source parser did not advance" }
        }
        val byStart = events.sortedWith(compareBy<VttEvent> { it.info.startTimeUs }.thenBy { it.ordinal })
        val byEnd = PriorityQueue(compareBy<VttEvent> { it.info.endTimeUs }.thenBy { it.ordinal })
        val active = HashMap<Int, VttEvent>()
        var cursor = 0
        var lastTime = Long.MIN_VALUE
        return groups.map { group ->
            require(group.startTimeUs != C.TIME_UNSET && group.durationUs > 0 && group.endTimeUs > group.startTimeUs)
            require(group.startTimeUs >= lastTime)
            lastTime = group.startTimeUs
            while (cursor < byStart.size && byStart[cursor].info.startTimeUs <= group.startTimeUs) {
                val event = byStart[cursor++]; active[event.ordinal] = event; byEnd.add(event)
            }
            while (byEnd.isNotEmpty() && requireNotNull(byEnd.peek()).info.endTimeUs <= group.startTimeUs) {
                active.remove(byEnd.remove().ordinal)
            }
            val visible = active.values.filter { it.info.endTimeUs >= group.endTimeUs }
            // This is Media3 WebvttSubtitle's explicit-first / auto-start-order
            // rule. Ties preserve source order and therefore real duplicates.
            val ordered = visible.filter { it.info.cue.line != Cue.DIMEN_UNSET }.sortedBy { it.ordinal } +
                visible.filter { it.info.cue.line == Cue.DIMEN_UNSET }
                    .sortedWith(compareBy<VttEvent> { it.info.startTimeUs }.thenBy { it.ordinal })
            require(ordered.size == group.cues.size && ordered.map { it.info.cue.text?.toString() } ==
                group.cues.map { it.text?.toString() })
            Annotated(group, ordered.map { event ->
                SsaOccurrence("webvtt-event-v1:$fingerprint:${event.ordinal}", event.ordinal,
                    event.info.startTimeUs, event.info.endTimeUs)
            })
        }
    }

    private fun key(group: CuesWithTiming) = Key(group.startTimeUs, group.durationUs, group.cues.map { it.text?.toString() })
    private fun fingerprint(data: ByteArray, offset: Int, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(format.sampleMimeType.orEmpty().toByteArray(Charsets.UTF_8))
        format.initializationData.forEach { header ->
            digest.update("${header.size}:".toByteArray(Charsets.US_ASCII)); digest.update(header)
        }
        digest.update("$length:".toByteArray(Charsets.US_ASCII)); digest.update(data, offset, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
