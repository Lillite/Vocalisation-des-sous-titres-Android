package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Identity of one source SSA event, before its Cue is copied into display intervals. */
@UnstableApi
data class SsaOccurrence(
    val token: String,
    val ordinal: Int,
    val startUs: Long,
    val endUs: Long
) {
    fun shifted(offsetUs: Long): SsaOccurrence? = try {
        copy(startUs = Math.addExact(startUs, offsetUs), endUs = Math.addExact(endUs, offsetUs))
    } catch (_: ArithmeticException) { null }
}

/**
 * Media3's SSA parser reuses the same Cue object for one event across all display
 * intervals. Capture that identity before CueEncoder serializes separate copies.
 * Text, positions, spans and the display intervals themselves are never changed.
 *
 * Parsing all intervals first is deliberate: the first interval must already know
 * the event's real end, and a seek must not change the event token. Only SSA is
 * wrapped; other subtitle formats retain their existing parser path.
 */
@UnstableApi
internal class SsaOccurrenceParser(
    private val delegate: SubtitleParser,
    private val format: Format,
    private val onLimit: () -> Unit,
    private val emit: (CuesWithTiming, List<SsaOccurrence>?, Consumer<CuesWithTiming>) -> Unit
) : SubtitleParser {
    private data class Event(val cue: Cue, val ordinal: Int, var startUs: Long, var endUs: Long)
    private class AnnotationLimit : RuntimeException()

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()

    override fun parse(data: ByteArray, offset: Int, length: Int,
        options: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
        val groups = mutableListOf<CuesWithTiming>()
        var characters = 0L
        var limited = false
        // These are the same bounds as the optional index. A limit disables the
        // annotation only; the real parser's entire display output is retained.
        try {
            delegate.parse(data, offset, length, SubtitleParser.OutputOptions.allCues(), Consumer { group ->
                characters += group.cues.sumOf { it.text?.length?.toLong() ?: 0L }
                if (groups.size >= 100_000 || characters > 12_000_000) throw AnnotationLimit()
                groups += group
            })
        } catch (_: AnnotationLimit) {
            onLimit()
            groups.clear()
            delegate.reset()
            delegate.parse(data, offset, length, options, Consumer { emit(it, null, output) })
            return
        }
        val events = IdentityHashMap<Cue, Event>()
        var serial = 0
        if (!limited) for (group in groups) {
            if (group.startTimeUs == C.TIME_UNSET || group.durationUs <= 0 ||
                group.endTimeUs < group.startTimeUs) { limited = true; onLimit(); break }
            for (cue in group.cues) {
                val event = events[cue]
                if (event == null) events[cue] = Event(cue, serial++, group.startTimeUs, group.endTimeUs)
                else { event.startUs = minOf(event.startUs, group.startTimeUs); event.endUs = maxOf(event.endUs, group.endTimeUs) }
            }
        }
        val packet = if (limited) "" else packetFingerprint(data, offset, length)
        fun publish(group: CuesWithTiming) {
            val origins = if (limited) null else group.cues.map { cue ->
                val event = requireNotNull(events[cue])
                SsaOccurrence("ssa-event-v1:$packet:${event.ordinal}", event.ordinal, event.startUs, event.endUs)
            }
            emit(group, origins, output)
        }
        // This is the exact SSA 1.11.1 output selection: a straddling interval is
        // emitted too (end >= requested time). An event's token uses the full parse.
        if (options.startTimeUs == C.TIME_UNSET) groups.forEach(::publish)
        else {
            groups.filter { it.endTimeUs >= options.startTimeUs }.forEach(::publish)
            if (options.outputAllCues) groups.filter { it.endTimeUs < options.startTimeUs }.forEach(::publish)
        }
    }

    private fun packetFingerprint(data: ByteArray, offset: Int, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (header in format.initializationData) {
            digest.update("${header.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(header)
        }
        digest.update("$length:".toByteArray(Charsets.US_ASCII))
        digest.update(data, offset, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
