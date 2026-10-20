package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Sequential demux only: discarded audio/video samples are never decoded or copied to RAM. */
@UnstableApi
class FullSubtitleIndexer(private val context: Context) {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
        val result = CueAheadIndex()
        if (media.subtitlePath != null && media.subtitleMime != null) {
            val file = File(media.subtitlePath)
            require(file.length() in 1..8_388_608) { "Fichier de sous-titres hors limite (8 Mio)." }
            val format = Format.Builder().setSampleMimeType(media.subtitleMime).build()
            val bytes = file.readBytes()
            DefaultSubtitleParserFactory().create(format).parse(bytes, 0, bytes.size,
                SubtitleParser.OutputOptions.allCues(), Consumer { if (!cancelled.get()) result.add("external-subtitle", it) })
            check(!cancelled.get()) { "Indexation annulée" }
            if (result.truncated) throw IOException("Indexation textuelle au-delà des limites de sécurité")
            progress(100); return result
        }
        val uri = Uri.parse(media.uri)
        require(uri.scheme in setOf("content", "android.resource", "file")) { "Indexation locale uniquement" }
        val factory = CueAheadFactory({ track, cues -> if (!cancelled.get()) result.add(track, cues) })
        val source = DefaultDataSource(context, false)
        var extractor: Extractor? = null
        var position = 0L; var reads = 0L; var seeks = 0; var lastPercent = -1
        val seek = PositionHolder()
        try {
            var length = source.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
            var input = DefaultExtractorInput(source, 0, length)
            for (candidate in factory.createExtractors(uri, emptyMap())) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Indexation annulée")
                val valid = try { candidate.sniff(input) } catch (_: java.io.EOFException) { false }
                input.resetPeekPosition()
                if (valid) { extractor = candidate; break } else candidate.release()
            }
            val chosen = extractor ?: throw IOException("Indexation complète indisponible pour ce conteneur. La lecture directe reste disponible.")
            chosen.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(map: SeekMap) = Unit
            })
            while (true) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Indexation annulée")
                val before = input.position
                val status = chosen.read(input, seek)
                reads += (input.position - before).coerceAtLeast(0)
                if (reads > 32L * 1024 * 1024 * 1024) throw IOException("Indexation limitée à 32 Gio lus. Lecture directe conservée.")
                val percent = if (length > 0) (input.position * 100 / length).toInt().coerceIn(0, 99) else -1
                if (percent != lastPercent) { progress(percent); lastPercent = percent }
                if (status == Extractor.RESULT_END_OF_INPUT) break
                if (status == Extractor.RESULT_SEEK) {
                    if (++seeks > 100_000 || seek.position < 0) throw IOException("Trop de déplacements dans ce conteneur")
                    source.close(); position = seek.position
                    val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining
                    input = DefaultExtractorInput(source, position, length)
                }
            }
            progress(100)
            if (result.truncated || factory.truncated) throw IOException("Index au-delà de 100 000 répliques / 12 millions de caractères. Prélecture directe conservée.")
            return result
        } finally { runCatching { extractor?.release() }; runCatching { source.close() } }
    }
}
