package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
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
    private fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Indexation annulée")
    }
    fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
        ensureActive()
        val result = CueAheadIndex()
        if (media.subtitlePath != null && media.subtitleMime != null) {
            val file = File(media.subtitlePath)
            require(file.length() in 1..8_388_608) { "Fichier de sous-titres hors limite (8 Mio)." }
            val format = Format.Builder().setSampleMimeType(media.subtitleMime).build()
            val bytes = file.readBytes()
            DefaultSubtitleParserFactory().create(format).parse(bytes, 0, bytes.size,
                SubtitleParser.OutputOptions.allCues(), Consumer { ensureActive(); result.add("external-subtitle", it) })
            ensureActive()
            if (result.truncated) throw IOException("Indexation textuelle au-delà des limites de sécurité")
            progress(100); ensureActive(); return result
        }
        val uri = Uri.parse(media.uri)
        require(uri.scheme in setOf("content", "android.resource", "file")) { "Indexation locale uniquement" }
        val factory = CueAheadFactory({ track, cues -> ensureActive(); result.add(track, cues) })
        val source = DefaultDataSource(context, false)
        var extractor: Extractor? = null
        var reads = 0L; var seeks = 0; var lastPercent = -1; var idleReads = 0
        val guardedReader = DataReader { buffer, offset, length ->
            ensureActive()
            val count = source.read(buffer, offset, length)
            reads += count.coerceAtLeast(0)
            if (reads > 32L * 1024 * 1024 * 1024) throw IOException("Indexation limitée à 32 Gio lus. Lecture directe conservée.")
            count
        }
        val seek = PositionHolder()
        try {
            var length = source.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
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
            val chosen = extractor ?: throw IOException("Indexation complète indisponible pour ce conteneur. La lecture directe reste disponible.")
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
                if (result.truncated || factory.truncated) throw IOException("Indexation incomplète : limites de sécurité ou sous-titre textuel illisible. Lecture directe conservée.")
                idleReads = if (status == Extractor.RESULT_CONTINUE && input.position == before) idleReads + 1 else 0
                if (idleReads > 1024) throw IOException("Ce conteneur ne permet plus de faire progresser l’indexation.")
                val percent = if (length > 0) (input.position * 100 / length).toInt().coerceIn(0, 99) else -1
                if (percent != lastPercent) { progress(percent); lastPercent = percent }
                if (status == Extractor.RESULT_END_OF_INPUT) break
                if (status == Extractor.RESULT_SEEK) {
                    if (++seeks > 100_000 || seek.position < 0) throw IOException("Trop de déplacements dans ce conteneur")
                    source.close(); val position = seek.position
                    val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining
                    input = DefaultExtractorInput(guardedReader, position, length)
                }
            }
            progress(100)
            ensureActive()
            if (result.truncated || factory.truncated) throw IOException("Index au-delà de 100 000 répliques / 12 millions de caractères. Prélecture directe conservée.")
            return result
        } finally { runCatching { extractor?.release() }; runCatching { source.close() } }
    }
}
