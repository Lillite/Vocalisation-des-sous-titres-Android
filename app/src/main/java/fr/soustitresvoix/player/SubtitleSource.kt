package fr.soustitresvoix.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.text.CuesWithTiming

/** Original timed groups and optional SSA occurrences; no text or identity conversion. */
typealias IndexedCueListener = (String?, CuesWithTiming, List<SsaOccurrence>?) -> Unit

/** One cancellable scan. The session owns its worker, epoch and task identity. */
@UnstableApi
interface SubtitleIndexer {
    fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex
    fun scanTracks(media: SavedMedia, tracks: Set<String>, progress: (Int) -> Unit): CueAheadIndex = scan(media, progress)
    fun scanTrackProgressively(media: SavedMedia, track: String,
        onPrefix: (List<CueAheadIndex.Item>, Long) -> Unit, progress: (Int) -> Unit): CueAheadIndex =
        scanTracks(media, setOf(track), progress).also { onPrefix(it.selected(track), Long.MAX_VALUE) }
    fun cancel()
}

/**
 * Both live extraction and complete indexing are selected together. The stage-one
 * boundary deliberately retains Media3 source/timing types and the existing index.
 * Live callbacks may originate on the extractor thread: the session posts them and
 * rejects obsolete media epochs. The source must not dispatch them a second time.
 */
@UnstableApi
interface SubtitleSource {
    fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource
    fun createIndexer(): SubtitleIndexer
    fun hasIncompleteCapture(): Boolean = false
    fun safeFailureMessage(error: Exception): String
}

/** Uses the existing parsers and full scanner without changing their algorithms. */
@UnstableApi
class Media3SubtitleSource(private val context: Context) : SubtitleSource {
    private val dataSources = NetworkDataSourceFactory(context)
    private var activeTap: CueAheadFactory? = null
    override fun hasIncompleteCapture(): Boolean = activeTap?.truncated == true
    override fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource {
        val tap = CueAheadFactory { track, cues -> onCue(track, cues, null) }
            .setOccurrenceListener { track, cues, origins -> onCue(track, cues, origins) }
        activeTap = tap
        val uri = item.localConfiguration?.uri
        // RTSP owns its socket transport in Media3 and must see the real scheme.
        // This internal MediaItem is never passed to history or session storage.
        val sourceItem = if (uri?.scheme == RemoteMediaUris.DIRECT_SCHEME) {
            val direct = RemoteConnectionStore(context).directUrl(uri)
            if (android.net.Uri.parse(direct).scheme.equals("rtsp", true)) item.buildUpon().setUri(direct).setMediaId(item.mediaId).build() else item
        } else item
        val mediaDataSources = if (uri != null) dataSources.forMediaUri(uri) else dataSources
        return DefaultMediaSourceFactory(mediaDataSources, tap).setSubtitleParserFactory(tap.sidecarParsers())
            .createMediaSource(sourceItem)
    }

    override fun createIndexer(): SubtitleIndexer {
        val indexer = FullSubtitleIndexer(context, dataSources)
        return object : SubtitleIndexer {
            override fun scan(media: SavedMedia, progress: (Int) -> Unit) = indexer.scan(media, progress)
            override fun scanTracks(media: SavedMedia, tracks: Set<String>, progress: (Int) -> Unit) = indexer.scanTracks(media, tracks, progress)
            override fun scanTrackProgressively(media: SavedMedia, track: String,
                onPrefix: (List<CueAheadIndex.Item>, Long) -> Unit, progress: (Int) -> Unit) =
                indexer.scanTrackProgressively(media, track, onPrefix, progress)
            override fun cancel() = indexer.cancel()
        }
    }

    override fun safeFailureMessage(error: Exception) = FullSubtitleIndexer.safeFailureMessage(error)
}
