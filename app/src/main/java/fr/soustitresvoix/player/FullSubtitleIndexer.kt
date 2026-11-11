package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.CuesWithTiming
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Sequential demux only: discarded audio/video samples are never decoded or copied to RAM. */
@UnstableApi
class FullSubtitleIndexer(private val context: Context, private val dataSources: DataSource.Factory = NetworkDataSourceFactory(context)) {
    companion object {
        private const val CANCELLED = "Indexation annulée"
        private const val FILE_LIMIT = "Fichier de sous-titres hors limite (8 Mio)."
        private const val TEXT_LIMIT = "Indexation textuelle au-delà des limites de sécurité"
        private const val LOCAL_ONLY = "Indexation locale uniquement"
        private const val STREAM_UNAVAILABLE = "Indexation complète indisponible pour HLS, DASH ou RTSP : le flux ne peut pas être traité comme un fichier complet. La vocalisation directe reste partielle et sa traduction intégrale n’est pas disponible."
        private const val SOURCE_UNAVAILABLE = "Cette source ne permet pas une indexation complète."
        private const val UNKNOWN_LENGTH_READ_LIMIT = "Indexation limitée à 32 Gio lus. Lecture directe conservée."
        private const val FINITE_LENGTH_READ_LIMIT = "Volume de lecture de l’indexation dépassé. Lecture directe conservée."
        private const val CONTAINER_UNAVAILABLE = "Indexation complète indisponible pour ce conteneur. La lecture directe reste disponible."
        private const val INCOMPLETE = "Indexation incomplète : limites de sécurité ou sous-titre textuel illisible. Lecture directe conservée."
        private const val NO_PROGRESS = "Ce conteneur ne permet plus de faire progresser l’indexation."
        private const val SEEK_LIMIT = "Trop de déplacements dans ce conteneur"
        private const val INDEX_LIMIT = "Index au-delà de 100 000 répliques / 12 millions de caractères. Prélecture directe conservée."
        private const val TRACK_UNAVAILABLE = "La piste choisie ne possède pas d’index textuel vérifiable. Choisis une autre piste ou prépare les sous-titres image avec l’OCR."
        private val controlledFailureMessages = setOf(
            CANCELLED, FILE_LIMIT, TEXT_LIMIT, LOCAL_ONLY, STREAM_UNAVAILABLE, SOURCE_UNAVAILABLE,
            UNKNOWN_LENGTH_READ_LIMIT, FINITE_LENGTH_READ_LIMIT,
            CONTAINER_UNAVAILABLE, INCOMPLETE, NO_PROGRESS, SEEK_LIMIT, INDEX_LIMIT, TRACK_UNAVAILABLE,
            FiniteWebSubtitleIndex.UNVERIFIED, FiniteWebSubtitleIndex.LIVE, FiniteWebSubtitleIndex.LIMIT,
            SrtSourceIntegrity.FAILURE_MESSAGE
        )
        /** Never expose a provider/parser exception message in a copied diagnostic.
         * Retain only exact, application-owned explanations; scan() keeps its
         * original exception types and causes for internal cancellation/error flow.
         */
        internal fun safeFailureMessage(error: Exception): String =
            error.message?.takeIf { it in controlledFailureMessages } ?: when (error) {
                is RemoteAccessException -> "Indexation réseau impossible : connexion interrompue, accès refusé ou fichier indisponible. Vérifie le lien et la connexion enregistrée, puis réessaie."
                is SecurityException -> "Indexation impossible : accès au fichier refusé. Rouvre la vidéo et autorise son accès."
                is IOException -> "Indexation impossible : fichier indisponible ou lecture du fichier échouée. Rouvre-le puis réessaie ; la lecture directe reste disponible."
                else -> "Indexation impossible : ce fichier n’a pas pu être analysé. La lecture directe reste disponible."
            }
    }
    private val cancelled = AtomicBoolean(false)
    private val localIndices = LocalSubtitleIndexCache(File(context.cacheDir, "complete-subtitle-indices-v1"))
    @Volatile private var activeSource: DataSource? = null
    fun cancel() { cancelled.set(true); runCatching { activeSource?.close() } }
    private fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException(CANCELLED)
    }
    fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex = scanTracks(media, emptySet(), progress)

    fun scanTracks(media: SavedMedia, tracks: Set<String>, progress: (Int) -> Unit): CueAheadIndex =
        scanSource(media, tracks, null, null, progress)

    fun scanTrackProgressively(media: SavedMedia, track: String,
        onPrefix: (List<CueAheadIndex.Item>, Long) -> Unit, progress: (Int) -> Unit): CueAheadIndex =
        scanSource(media, setOf(track), null, null, progress, onPrefix)

    /** Called on an OCR worker. A decoded bitmap is consumed before the next
     * sample is read, so the demuxer never holds a movie's worth of bitmaps. */
    internal fun scanImageCues(media: SavedMedia, track: String, onImage: (CuesWithTiming) -> Unit, progress: (Int) -> Unit) {
        scanSource(media.copy(subtitlePath = null, subtitleMime = null), emptySet(), track, onImage, progress)
    }

    private fun scanSource(media: SavedMedia, tracks: Set<String>, imageTrack: String?,
        onImage: ((CuesWithTiming) -> Unit)?, progress: (Int) -> Unit,
        onPrefix: ((List<CueAheadIndex.Item>, Long) -> Unit)? = null): CueAheadIndex {
        ensureActive()
        val mediaUri = Uri.parse(media.uri)
        val cacheKey = if (imageTrack == null && mediaUri.scheme in setOf("file", "content", "android.resource") &&
            media.subtitlePath?.let { !File(it).name.startsWith("ocr_revu_") } != false &&
            mediaUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() !in setOf("m3u8", "mpd")) localIndices.identity(media) else null
        ensureActive()
        val cached = cacheKey?.let(localIndices::read)
        ensureActive()
        if (cached != null && tracks.all(cached::hasTrack) && localIndices.identity(media) == cacheKey) {
            ensureActive(); progress(100); ensureActive()
            return cached
        }
        fun remember(index: CueAheadIndex) {
            ensureActive()
            if (cacheKey != null) {
                if (localIndices.identity(media) != cacheKey) throw IOException(INCOMPLETE)
                ensureActive()
                localIndices.write(cacheKey, index)
                ensureActive()
            }
        }
        val result = CueAheadIndex()
        var imageTrackFound = false
        val factory = CueAheadFactory { track, cues -> ensureActive(); result.add(track, cues) }
            .setOccurrenceListener { track, cues, origins -> ensureActive(); result.add(track, cues, origins) }
            .setTrackListener { track, format ->
                if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                    if (isBitmapSubtitle(format)) imageTrackFound = imageTrackFound || track == imageTrack
                    else result.registerTextTrack(track)
                }
            }
        if (imageTrack != null && onImage != null) factory.setImageListener(imageTrack) { ensureActive(); onImage(it) }
        if (media.subtitlePath != null && media.subtitleMime != null) {
            if (tracks.any { it != "external-subtitle" }) throw IOException(TRACK_UNAVAILABLE)
            val file = File(media.subtitlePath)
            require(file.length() in 1..8_388_608) { FILE_LIMIT }
            val format = Format.Builder().setSampleMimeType(media.subtitleMime).build()
            val bytes = file.readBytes()
            factory.sidecarParsers().create(format).parse(bytes, 0, bytes.size,
                SubtitleParser.OutputOptions.allCues(), Consumer { ensureActive() })
            ensureActive()
            if (result.truncated || factory.truncated) throw IOException(TEXT_LIMIT)
            if (media.subtitleMime == MimeTypes.APPLICATION_SUBRIP) SrtSourceIntegrity.check(file, result.count)
            if (!result.hasTrack("external-subtitle")) throw IOException(TRACK_UNAVAILABLE)
            remember(result)
            progress(100); ensureActive(); return result
        }
        val uri = mediaUri
        require(uri.scheme in setOf("content", "android.resource", "file", "http", "https", RemoteMediaUris.SERVER_SCHEME, RemoteMediaUris.DIRECT_SCHEME, "rtsp")) { SOURCE_UNAVAILABLE }
        val resolved = if (uri.scheme == RemoteMediaUris.DIRECT_SCHEME) Uri.parse(RemoteConnectionStore(context).directUrl(uri)) else uri
        val suffix = resolved.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (resolved.scheme.equals("rtsp", true)) throw IOException(STREAM_UNAVAILABLE)
        if (suffix in setOf("m3u8", "mpd")) {
            // A bare scan cannot establish which representation corresponds to
            // the chosen track. Adaptive playback passes actual Format IDs.
            if (imageTrack != null || tracks.isEmpty()) throw IOException(STREAM_UNAVAILABLE)
            val scoped = (dataSources as? NetworkDataSourceFactory)?.forMediaUri(uri) ?: dataSources
            return FiniteWebSubtitleIndex({ target, offset, length, maxBytes ->
                loadSubtitleDocument(scoped, target, offset, length, maxBytes)
            }, ::ensureActive).scan(uri, suffix == "mpd", tracks, progress, onPrefix).also { ensureActive() }
        }
        if (onPrefix != null) throw IOException(FiniteWebSubtitleIndex.UNVERIFIED)
        val source = dataSources.createDataSource()
        activeSource = source
        var extractor: Extractor? = null
        var seeks = 0; var lastPercent = -1; var idleReads = 0
        val seek = PositionHolder()
        try {
            var length = source.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
            var remainingReads = progressiveIndexReadLimit(length)
            val budgetFailure = if (length < 0) UNKNOWN_LENGTH_READ_LIMIT else FINITE_LENGTH_READ_LIMIT
            val guardedReader = DataReader { buffer, offset, requestSize ->
                ensureActive()
                val count = source.read(buffer, offset, requestSize)
                if (requestSize > 0 && count == 0) throw IOException(NO_PROGRESS)
                if (count > 0) {
                    if (count.toLong() > remainingReads) throw IOException(budgetFailure)
                    remainingReads -= count
                }
                count
            }
            var input = DefaultExtractorInput(guardedReader, 0, length)
            val candidates = factory.createExtractors(uri, emptyMap())
            try {
                for (candidate in candidates) {
                    ensureActive()
                    val valid = try { candidate.sniff(input) } catch (_: java.io.EOFException) { false }
                    input.resetPeekPosition()
                    if (valid) { extractor = candidate; break }
                }
            } finally {
                candidates.filter { it !== extractor }.forEach { runCatching { it.release() } }
            }
            val chosen = extractor ?: throw IOException(CONTAINER_UNAVAILABLE)
            chosen.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(map: SeekMap) = Unit
            })
            while (true) {
                ensureActive()
                val before = input.position
                val status = chosen.read(input, seek)
                ensureActive()
                if (result.truncated || factory.truncated) throw IOException(INCOMPLETE)
                idleReads = if (status == Extractor.RESULT_CONTINUE && input.position == before) idleReads + 1 else 0
                if (idleReads > 1024) throw IOException(NO_PROGRESS)
                val percent = if (length > 0) (input.position.toDouble() * 100 / length).toInt().coerceIn(0, 99) else -1
                if (percent != lastPercent) { progress(percent); lastPercent = percent }
                if (status == Extractor.RESULT_END_OF_INPUT) break
                if (status == Extractor.RESULT_SEEK) {
                    if (++seeks > 100_000 || seek.position < 0 || (length >= 0 && seek.position > length)) throw IOException(SEEK_LIMIT)
                    source.close(); val position = seek.position
                    val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    if (remaining >= 0) {
                        if (remaining > Long.MAX_VALUE - position) throw IOException(INCOMPLETE)
                        val reopenedLength = position + remaining
                        if (length >= 0 && reopenedLength != length) throw IOException(INCOMPLETE)
                        length = reopenedLength
                    }
                    input = DefaultExtractorInput(guardedReader, position, length)
                }
            }
            if (result.truncated || factory.truncated) throw IOException(INDEX_LIMIT)
            if ((imageTrack != null && !imageTrackFound) || tracks.any { !result.hasTrack(it) }) throw IOException(TRACK_UNAVAILABLE)
            remember(result)
            progress(100)
            ensureActive()
            return result
        } finally { activeSource = null; runCatching { extractor?.release() }; runCatching { source.close() } }
    }

    private fun loadSubtitleDocument(factory: DataSource.Factory, uri: Uri, offset: Long, length: Long,
        maxBytes: Int): FiniteWebSubtitleIndex.Document {
        val source = factory.createDataSource()
        activeSource = source
        try {
            ensureActive()
            val size = source.open(DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build())
            if (size > maxBytes) throw IOException(FiniteWebSubtitleIndex.LIMIT)
            val resolved = source.uri ?: uri
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive()
                val count = source.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size() + 1))
                if (count < 0) break
                if (count == 0) throw IOException(NO_PROGRESS)
                if (bytes.size() + count > maxBytes) throw IOException(FiniteWebSubtitleIndex.LIMIT)
                bytes.write(buffer, 0, count)
            }
            if ((length >= 0 && bytes.size().toLong() != length) || (size >= 0 && bytes.size().toLong() != size))
                throw IOException(INCOMPLETE)
            return FiniteWebSubtitleIndex.Document(resolved, bytes.toByteArray())
        } finally { activeSource = null; runCatching { source.close() } }
    }
}

internal fun progressiveIndexReadLimit(sourceLength: Long): Long {
    val gib = 1024L * 1024 * 1024
    if (sourceLength < 0) return 32 * gib
    val probeAndSeekAllowance = (sourceLength / 8).coerceIn(gib / 4, 2 * gib)
    return sourceLength + minOf(probeAndSeekAllowance, Long.MAX_VALUE - sourceLength)
}
