package fr.soustitresvoix.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.extractor.text.webvtt.WebvttCueParser
import androidx.media3.extractor.text.webvtt.WebvttParserUtil
import java.io.IOException
import java.net.URI
import kotlin.math.abs

/** Index plain WebVTT renditions without loading the video segments. A finite
 * playlist, an unambiguous timeline and all requested tracks are required. */
@UnstableApi
internal class FiniteWebSubtitleIndex(
    private val load: (uri: Uri, offset: Long, length: Long, maxBytes: Int) -> Document,
    private val checkActive: () -> Unit
) {
    data class Document(val uri: Uri, val bytes: ByteArray)
    private data class Segment(val uri: Uri, val startUs: Long, val durationUs: Long,
        val offset: Long, val length: Long)
    private data class Event(val startUs: Long, val endUs: Long, val text: String)
    private data class Occurrence(val event: Event, val duplicate: Int)

    companion object {
        const val UNVERIFIED = "Index de flux non certifiable : piste, format ou minutage de sous-titres non pris en charge. La lecture des événements reçus reste disponible."
        const val LIVE = "Flux en direct : aucun inventaire complet ne peut être établi. La lecture couvre les événements reçus."
        const val LIMIT = "Limite d’indexation du flux atteinte. Aucun index complet n’a été déclaré."
        private const val MANIFEST_BYTES = 2 * 1024 * 1024
        private const val SEGMENT_BYTES = 8 * 1024 * 1024
        private const val TOTAL_BYTES = 128L * 1024 * 1024
        private const val MAX_REQUESTS = 50_000
        private const val MAX_DURATION_US = 7L * 24 * 60 * 60 * 1_000_000
    }

    private var requests = 0
    private var readBytes = 0L
    private var origin: Uri? = null

    fun scan(uri: Uri, dash: Boolean, tracks: Set<String>, progress: (Int) -> Unit,
        onPrefix: ((newItems: List<CueAheadIndex.Item>, coveredThroughUs: Long) -> Unit)? = null): CueAheadIndex {
        verified(onPrefix == null || tracks.size == 1)
        val document = read(uri)
        origin = document.uri
        val result = if (dash) scanDash(document, tracks, progress, onPrefix) else scanHls(document, tracks, progress, onPrefix)
        checkActive()
        if (result.truncated) throw IOException(LIMIT)
        progress(100)
        return result
    }

    private fun scanHls(document: Document, requested: Set<String>, progress: (Int) -> Unit,
        onPrefix: ((List<CueAheadIndex.Item>, Long) -> Unit)?): CueAheadIndex {
        val master = HlsPlaylistParser().parse(document.uri, document.bytes.inputStream())
        if (master is HlsMediaPlaylist && !master.hasEndTag) throw IOException(LIVE)
        verified(master is HlsMultivariantPlaylist)
        val multivariant = master as HlsMultivariantPlaylist
        verified(multivariant.sessionKeyDrmInitData.isEmpty())
        val renditions = multivariant.subtitles.filter { requested.isEmpty() || it.format.id?.let(requested::contains) == true }
        val ids = renditions.map { it.format.id }
        verified(ids.isNotEmpty() && ids.size <= 32 && ids.all { !it.isNullOrBlank() } &&
            ids.distinct().size == ids.size && ids.containsAll(requested))
        val result = CueAheadIndex()
        val variantTimelines = HashMap<Uri, Pair<Long, Long?>>()
        for ((ordinal, rendition) in renditions.withIndex()) {
            val subtitle = playlist(multivariant, requireUri(rendition.url))
            val matchingVariants = multivariant.variants.filter { it.subtitleGroupId == rendition.groupId }
            verified(matchingVariants.isNotEmpty() && matchingVariants.size <= 64)
            for (variant in matchingVariants) {
                val (duration, originUs) = variantTimelines.getOrPut(variant.url) {
                    val video = playlist(multivariant, variant.url)
                    video.durationUs to video.startTimeUs.takeIf { video.hasProgramDateTime }
                }
                verified(abs(duration - subtitle.durationUs) <= 1_000L &&
                    originUs == subtitle.startTimeUs.takeIf { subtitle.hasProgramDateTime })
            }
            val segments = subtitle.segments.map { segment ->
                verified(segment.initializationSegment == null)
                Segment(child(Uri.parse(subtitle.baseUri), segment.url), segment.relativeStartTimeUs,
                    segment.durationUs, segment.byteRangeOffset, segment.byteRangeLength)
            }
            appendTrack(result, requireNotNull(rendition.format.id), segments, subtitle.durationUs, 0L, onPrefix) { count ->
                progress(((ordinal.toLong() * segments.size + count) * 100 /
                    (renditions.size.toLong() * segments.size)).toInt().coerceAtMost(99))
            }
        }
        return result
    }

    private fun playlist(master: HlsMultivariantPlaylist, uri: Uri): HlsMediaPlaylist {
        val document = read(uri)
        val parsed = HlsPlaylistParser(master, null).parse(document.uri, document.bytes.inputStream())
        verified(parsed is HlsMediaPlaylist)
        val media = parsed as HlsMediaPlaylist
        if (!media.hasEndTag) throw IOException(LIVE)
        verified(media.durationUs in 1..MAX_DURATION_US && media.segments.size in 1..MAX_REQUESTS &&
            (media.playlistType in setOf(HlsMediaPlaylist.PLAYLIST_TYPE_VOD, HlsMediaPlaylist.PLAYLIST_TYPE_EVENT) ||
                media.mediaSequence == 0L) && media.discontinuitySequence == 0 &&
            media.trailingParts.isEmpty() && media.tags.none { it.startsWith("#EXT-X-DATERANGE") })
        verified(media.segments.all { it.relativeDiscontinuitySequence == 0 && !it.hasGapTag &&
            it.drmInitData == null && it.fullSegmentEncryptionKeyUri == null })
        return media
    }

    private fun scanDash(document: Document, requested: Set<String>, progress: (Int) -> Unit,
        onPrefix: ((List<CueAheadIndex.Item>, Long) -> Unit)?): CueAheadIndex {
        // Keep untrusted DTD/entity expansion outside the Android XML parser.
        val xml = document.bytes.toString(Charsets.UTF_8)
        verified(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true) && '\u0000' !in xml)
        val manifest = DashManifestParser().parse(document.uri, document.bytes.inputStream())
        if (manifest.dynamic) throw IOException(LIVE)
        verified(manifest.periodCount == 1)
        val period = manifest.getPeriod(0)
        val duration = manifest.getPeriodDurationUs(0)
        verified(period.startMs == 0L && duration in 1..MAX_DURATION_US)
        verified(period.adaptationSets.all { adaptation -> adaptation.representations.all { it.format.drmInitData == null } })
        val representations = period.adaptationSets.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { it.representations }.filter { requested.isEmpty() || it.format.id?.let(requested::contains) == true }
        val ids = representations.map { it.format.id }
        verified(ids.isNotEmpty() && ids.size <= 32 && ids.all { !it.isNullOrBlank() } &&
            ids.distinct().size == ids.size && ids.containsAll(requested))
        val result = CueAheadIndex()
        for ((ordinal, representation) in representations.withIndex()) {
            verified(representation.format.sampleMimeType == MimeTypes.TEXT_VTT &&
                representation.format.containerMimeType == MimeTypes.TEXT_VTT &&
                representation.initializationUri == null && representation.indexUri == null)
            val index = representation.index ?: throw IOException(UNVERIFIED)
            val size = index.getSegmentCount(duration)
            if (size !in 1..MAX_REQUESTS.toLong()) throw IOException(LIMIT)
            val first = index.firstSegmentNum
            val base = representation.baseUrls.firstOrNull()?.url ?: throw IOException(UNVERIFIED)
            val segments = (0L until size).map { i ->
                checkActive()
                val number = Math.addExact(first, i)
                val ranged = index.getSegmentUrl(number)
                val uri = ranged.resolveUri(base)
                Segment(uri, index.getTimeUs(number), index.getDurationUs(number, duration), ranged.start, ranged.length)
            }
            appendTrack(result, requireNotNull(representation.format.id), segments, duration,
                Math.negateExact(representation.presentationTimeOffsetUs), onPrefix) { count ->
                progress(((ordinal * size + count) * 100 / (representations.size * size)).toInt().coerceAtMost(99))
            }
        }
        return result
    }

    private fun appendTrack(result: CueAheadIndex, track: String, segments: List<Segment>, durationUs: Long,
        cueOffsetUs: Long, onPrefix: ((List<CueAheadIndex.Item>, Long) -> Unit)?, progress: (Int) -> Unit) {
        var previousEnd = 0L
        var ordinal = 0
        var carry = emptySet<Occurrence>()
        for ((segmentIndex, segment) in segments.withIndex()) {
            checkActive()
            verified(segment.startUs in 0..MAX_DURATION_US && abs(segment.startUs - previousEnd) <= 1L && segment.durationUs in 1..MAX_DURATION_US)
            val end = Math.addExact(segment.startUs, segment.durationUs)
            verified(end in 1..MAX_DURATION_US)
            val bytes = read(segment.uri, segment.offset, segment.length, SEGMENT_BYTES).bytes
            val multiplicities = HashMap<Event, Int>()
            val current = LinkedHashSet<Occurrence>()
            val addedItems = ArrayList<CueAheadIndex.Item>()
            for (event in parseWebVtt(bytes, cueOffsetUs)) {
                verified(event.startUs >= 0 && event.startUs < end && event.endUs > segment.startUs &&
                    event.endUs <= durationUs + 1)
                val duplicate = multiplicities.getOrDefault(event, 0)
                multiplicities[event] = duplicate + 1
                val occurrence = Occurrence(event, duplicate)
                current.add(occurrence)
                if (occurrence in carry) continue
                // A cue that began in a preceding segment has to be present in
                // that segment too. A mismatch means the coverage is unknown.
                verified(event.startUs >= segment.startUs)
                addedItems += CueAheadIndex.Item(track, event.startUs, event.endUs, event.text, ordinal++)
            }
            verified(current.containsAll(carry))
            carry = current.filterTo(HashSet()) { it.event.endUs > end }
            previousEnd = end
            if (segmentIndex == segments.lastIndex) verified(abs(previousEnd - durationUs) <= 1L && carry.isEmpty())
            for (item in addedItems) {
                result.addOccurrence(item)
                if (result.truncated) throw IOException(LIMIT)
            }
            checkActive()
            onPrefix?.invoke(addedItems.toList(), end)
            progress(segmentIndex + 1)
        }
        verified(abs(previousEnd - durationUs) <= 1L && carry.isEmpty())
        result.registerTextTrack(track)
    }

    private fun parseWebVtt(bytes: ByteArray, offsetUs: Long): List<Event> {
        // Reject undecodable text rather than turning broken bytes into a valid
        // replacement character in an index declared complete.
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
        val input = ParsableByteArray(bytes)
        WebvttParserUtil.validateWebvttHeaderLine(input)
        while (true) {
            val header = input.readLine() ?: throw IOException(UNVERIFIED)
            if (header.isEmpty()) break
            verified("-->" !in header)
            if (header.startsWith("X-TIMESTAMP-MAP")) {
                // Nonzero MPEG-TS anchoring needs the actual video timestamp
                // adjuster. Refuse to infer it from the first subtitle cue.
                verified(header == "X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0" ||
                    header == "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000")
            }
        }
        val result = ArrayList<Event>()
        var characters = 0
        while (input.bytesLeft() > 0) {
            checkActive()
            val before = input.position
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            if (line == "STYLE" || line == "REGION" || line == "NOTE" || line.startsWith("NOTE ")) {
                while (!input.readLine().isNullOrEmpty()) checkActive()
                continue
            }
            input.position = before
            val info = WebvttCueParser.parseCue(input, emptyList()) ?: throw IOException(UNVERIFIED)
            val text = info.cue.text?.toString().orEmpty()
            verified(input.position > before && text.isNotBlank() && info.endTimeUs > info.startTimeUs)
            characters += text.length
            if (characters > 12_000_000 || result.size >= 100_000) throw IOException(LIMIT)
            result += Event(Math.addExact(info.startTimeUs, offsetUs), Math.addExact(info.endTimeUs, offsetUs), text)
        }
        return result
    }

    private fun read(uri: Uri, offset: Long = 0L, length: Long = C.LENGTH_UNSET.toLong(),
        maxBytes: Int = MANIFEST_BYTES): Document {
        checkActive()
        origin?.let { verifyChild(it, uri) }
        verified(offset >= 0 && (length == C.LENGTH_UNSET.toLong() || length in 1..maxBytes.toLong()))
        val available = minOf(maxBytes.toLong(), TOTAL_BYTES - readBytes).toInt()
        if (++requests > MAX_REQUESTS || available <= 0) throw IOException(LIMIT)
        val document = load(uri, offset, length, available)
        if (document.bytes.size > available) throw IOException(LIMIT)
        readBytes += document.bytes.size
        checkActive()
        origin?.let { verifyChild(it, document.uri) }
        return document
    }

    private fun child(base: Uri, path: String): Uri = Uri.parse(URI(base.toString()).resolve(path).toASCIIString()).also {
        verifyChild(base, it)
    }

    private fun verifyChild(base: Uri, candidate: Uri) {
        verified(candidate.userInfo == null && !candidate.host.isNullOrBlank() &&
            candidate.scheme in setOf("http", "https", RemoteMediaUris.SERVER_SCHEME) &&
            (base.scheme != "https" || candidate.scheme == "https") &&
            (candidate.scheme != RemoteMediaUris.SERVER_SCHEME ||
                base.scheme == candidate.scheme && base.host == candidate.host))
    }

    private fun requireUri(uri: Uri?): Uri = uri ?: throw IOException(UNVERIFIED)
    private fun verified(value: Boolean) { if (!value) throw IOException(UNVERIFIED) }
}
