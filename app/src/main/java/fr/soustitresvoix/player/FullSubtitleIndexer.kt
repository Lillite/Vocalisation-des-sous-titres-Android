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
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Sequential demux only: discarded audio/video samples are never decoded or copied to RAM. */
@UnstableApi
class FullSubtitleIndexer(private val context: Context) {
    companion object {
        private const val CANCELLED = "Indexation annulée"
        private const val FILE_LIMIT = "Fichier de sous-titres hors limite (8 Mio)."
        private const val TEXT_LIMIT = "Indexation textuelle au-delà des limites de sécurité"
        private const val LOCAL_ONLY = "Indexation locale uniquement"
        private const val READ_LIMIT = "Indexation limitée à 32 Gio lus. Lecture directe conservée."
        private const val CONTAINER_UNAVAILABLE = "Indexation complète indisponible pour ce conteneur. La lecture directe reste disponible."
        private const val INCOMPLETE = "Indexation incomplète : limites de sécurité ou sous-titre textuel illisible. Lecture directe conservée."
        private const val NO_PROGRESS = "Ce conteneur ne permet plus de faire progresser l’indexation."
        private const val SEEK_LIMIT = "Trop de déplacements dans ce conteneur"
        private const val INDEX_LIMIT = "Index au-delà de 100 000 répliques / 12 millions de caractères. Prélecture directe conservée."
        private val controlledFailureMessages = setOf(
            CANCELLED, FILE_LIMIT, TEXT_LIMIT, LOCAL_ONLY, READ_LIMIT, CONTAINER_UNAVAILABLE, INCOMPLETE, NO_PROGRESS, SEEK_LIMIT, INDEX_LIMIT
        )
        /** Never expose a provider/parser exception message in a copied diagnostic.
         * Retain only exact, application-owned explanations; scan() keeps its
         * original exception types and causes for internal cancellation/error flow.
         */
        internal fun safeFailureMessage(error: Exception): String =
            error.message?.takeIf { it in controlledFailureMessages } ?: when (error) {
                is SecurityException -> "Indexation impossible : accès au fichier refusé. Rouvre la vidéo et autorise son accès."
                is IOException -> "Indexation impossible : fichier indisponible ou lecture du fichier échouée. Rouvre-le puis réessaie ; la lecture directe reste disponible."
                else -> "Indexation impossible : ce fichier n’a pas pu être analysé. La lecture directe reste disponible."
            }
    }
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    private fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException(CANCELLED)
    }
    fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
        ensureActive()
        val result = CueAheadIndex()
        val factory = CueAheadFactory { track, cues -> ensureActive(); result.add(track, cues) }
            .setOccurrenceListener { track, cues, origins -> ensureActive(); result.add(track, cues, origins) }
        if (media.subtitlePath != null && media.subtitleMime != null) {
            val file = File(media.subtitlePath)
            require(file.length() in 1..8_388_608) { FILE_LIMIT }
            val format = Format.Builder().setSampleMimeType(media.subtitleMime).build()
            val bytes = file.readBytes()
            factory.sidecarParsers().create(format).parse(bytes, 0, bytes.size,
                SubtitleParser.OutputOptions.allCues(), Consumer { ensureActive() })
            ensureActive()
            if (result.truncated || factory.truncated) throw IOException(TEXT_LIMIT)
            progress(100); ensureActive(); return result
        }
        val uri = Uri.parse(media.uri)
        require(uri.scheme in setOf("content", "android.resource", "file")) { LOCAL_ONLY }
        val source = DefaultDataSource(context, false)
        var extractor: Extractor? = null
        var reads = 0L; var seeks = 0; var lastPercent = -1; var idleReads = 0
        val guardedReader = DataReader { buffer, offset, length ->
            ensureActive()
            val count = source.read(buffer, offset, length)
            reads += count.coerceAtLeast(0)
            if (reads > 32L * 1024 * 1024 * 1024) throw IOException(READ_LIMIT)
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
                val percent = if (length > 0) (input.position * 100 / length).toInt().coerceIn(0, 99) else -1
                if (percent != lastPercent) { progress(percent); lastPercent = percent }
                if (status == Extractor.RESULT_END_OF_INPUT) break
                if (status == Extractor.RESULT_SEEK) {
                    if (++seeks > 100_000 || seek.position < 0) throw IOException(SEEK_LIMIT)
                    source.close(); val position = seek.position
                    val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining
                    input = DefaultExtractorInput(guardedReader, position, length)
                }
            }
            progress(100)
            ensureActive()
            if (result.truncated || factory.truncated) throw IOException(INDEX_LIMIT)
            return result
        } finally { runCatching { extractor?.release() }; runCatching { source.close() } }
    }
}
