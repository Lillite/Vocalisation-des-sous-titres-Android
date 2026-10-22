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
    fun safeFailureMessage(error: Exception): String
}

/** Uses the existing parsers and full scanner without changing their algorithms. */
@UnstableApi
class Media3SubtitleSource(private val context: Context) : SubtitleSource {
    override fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource {
        val tap = CueAheadFactory { track, cues -> onCue(track, cues, null) }
            .setOccurrenceListener { track, cues, origins -> onCue(track, cues, origins) }
        return DefaultMediaSourceFactory(context, tap).setSubtitleParserFactory(tap.sidecarParsers())
            .createMediaSource(item)
    }

    override fun createIndexer(): SubtitleIndexer {
        val indexer = FullSubtitleIndexer(context)
        return object : SubtitleIndexer {
            override fun scan(media: SavedMedia, progress: (Int) -> Unit) = indexer.scan(media, progress)
            override fun cancel() = indexer.cancel()
        }
    }

    override fun safeFailureMessage(error: Exception) = FullSubtitleIndexer.safeFailureMessage(error)
}
