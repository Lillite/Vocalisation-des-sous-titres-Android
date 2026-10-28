package fr.soustitresvoix.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock

internal enum class PreparationVideoStatus { SUCCESS, CANCELLED, UNSUPPORTED, FAILED, TIMED_OUT }

/** The timestamps are source/decoder timestamps, never OCR completion times.
 * Byte/image access is measured separately from codec output. */
internal data class PreparationVideoResult(
    val status: PreparationVideoStatus,
    val reason: String,
    val elapsedMs: Long,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationUs: Long? = null,
    val decoderName: String? = null,
    val decoderHardwareDeclared: Boolean? = null,
    val decoderSoftwareDeclared: Boolean? = null,
    val decodedFrames: Int = 0,
    val accessibleFrames: Int = 0,
    val presentationTimesUs: List<Long> = emptyList(),
    val reachedEndOfStream: Boolean = false
) {
    fun render(): String = buildString {
        append("Essai local de décodage hors écran\n")
        append(when (status) {
            PreparationVideoStatus.SUCCESS -> "Décodage observé"
            PreparationVideoStatus.CANCELLED -> "Essai annulé"
            PreparationVideoStatus.UNSUPPORTED -> "Décodage non disponible dans ce parcours"
            PreparationVideoStatus.FAILED -> "Essai en échec"
            PreparationVideoStatus.TIMED_OUT -> "Essai arrêté à sa borne de diagnostic"
        })
        append("\n$reason\n")
        append("Temps écoulé : $elapsedMs ms\n")
        append("Format vidéo observé : ${mimeType ?: "non établi"}\n")
        append("Dimensions déclarées : ${width ?: "?"} × ${height ?: "?"}\n")
        append("Durée déclarée : ${durationUs?.let { "$it microsecondes" } ?: "non établie"}\n")
        append("Décodeur initialisé pour cet essai : ${decoderName ?: "aucun"}\n")
        append("Classification Android du décodeur : matériel=${declared(decoderHardwareDeclared)}, logiciel=${declared(decoderSoftwareDeclared)}\n")
        append("Images sorties du décodeur : $decodedFrames\n")
        append("Images dont des pixels ont été accessibles : $accessibleFrames\n")
        append("Horodatages de présentation observés (microsecondes) : ")
        append(presentationTimesUs.takeIf { it.isNotEmpty() }?.joinToString() ?: "aucun")
        append("\nFin du flux atteinte : ${if (reachedEndOfStream) "oui" else "non"}\n")
        append("Parcours essayé : MediaExtractor et MediaCodec Android, indépendant du lecteur Media3. Un échec ici ne suffit pas à déclarer le fichier illisible dans le lecteur.\n")
        append("La classification du décodeur est une déclaration Android. Cet essai ne mesure ni OCR, ni traduction, ni accélération neuronale, ni le débit soutenu d’un film complet.")
    }

    private fun declared(value: Boolean?): String = when (value) { true -> "oui"; false -> "non"; null -> "non exposé" }
}

/** Explicit, bounded diagnostic, with its OWN extractor/decoder and no access to
 * PlaybackSession, audio or speech. Call only on a worker thread. The deadline
 * bounds the dequeue loop; an Android/provider open or codec vendor call cannot
 * safely be interrupted in the middle and may finish after cancellation. */
internal object PreparationVideoProbe {
    const val MAX_FRAMES = 8
    const val MAX_DURATION_MS = 10_000L

    fun run(context: Context, uri: Uri, cancelled: () -> Boolean = { false }): PreparationVideoResult {
        val startedAt = SystemClock.elapsedRealtime()
        // Include stop/release in the user-visible elapsed time. The bounded
        // dequeue phase and vendor cleanup are deliberately not conflated.
        val result = decode(context, uri, cancelled)
        return result.copy(elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0))
    }

    private fun decode(context: Context, uri: Uri, cancelled: () -> Boolean): PreparationVideoResult {
        val startedAt = SystemClock.elapsedRealtime()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var decoderStarted = false
        var mime: String? = null
        var width: Int? = null
        var height: Int? = null
        var durationUs: Long? = null
        var decoderName: String? = null
        var hardware: Boolean? = null
        var software: Boolean? = null
        var decoded = 0
        var accessible = 0
        var eos = false
        val timestamps = mutableListOf<Long>()
        var stage = "ouverture du fichier"
        fun isCancelled() = cancelled() || Thread.currentThread().isInterrupted
        fun result(status: PreparationVideoStatus, reason: String) = PreparationVideoResult(status,
            reason, (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0), mime, width,
            height, durationUs, decoderName, hardware, software, decoded, accessible,
            timestamps.toList(), eos)

        if (isCancelled()) return result(PreparationVideoStatus.CANCELLED, "Aucun fichier n’a été ouvert.")
        if (uri.scheme !in setOf("content", "file", "android.resource"))
            return result(PreparationVideoStatus.UNSUPPORTED, "Cet essai accepte uniquement un fichier local sélectionné ou la démonstration intégrée.")
        try {
            val input = MediaExtractor().also { extractor = it }
            input.setDataSource(context, uri, null)
            if (isCancelled()) return result(PreparationVideoStatus.CANCELLED, "Annulation après ouverture ; aucune lecture du film lancée.")
            stage = "recherche de piste vidéo"
            var format: MediaFormat? = null
            for (track in 0 until input.trackCount) {
                val candidate = input.getTrackFormat(track)
                val type = candidate.stringOrNull(MediaFormat.KEY_MIME)
                if (type?.startsWith("video/") == true) {
                    input.selectTrack(track)
                    format = candidate
                    mime = type
                    break
                }
            }
            val selected = format ?: return result(PreparationVideoStatus.UNSUPPORTED,
                "Aucune piste vidéo exposée par MediaExtractor. Le conteneur peut nécessiter un autre extracteur ; cela ne prouve pas que le fichier est illisible par le lecteur Media3.")
            width = selected.integerOrNull(MediaFormat.KEY_WIDTH)
            height = selected.integerOrNull(MediaFormat.KEY_HEIGHT)
            durationUs = selected.longOrNull(MediaFormat.KEY_DURATION)
            stage = "sélection du décodeur"
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(selected)
                ?: return result(PreparationVideoStatus.UNSUPPORTED,
                    "Aucun décodeur Android déclaré pour ce format/profil. La reconnaissance du conteneur ne garantit pas le décodage de son codec.")
            if (isCancelled()) return result(PreparationVideoStatus.CANCELLED, "Annulation avant création du décodeur.")
            val codec = MediaCodec.createByCodecName(name).also { decoder = it }
            stage = "configuration du décodeur"
            // A null surface requests decoded output buffers, not screen output.
            codec.configure(selected, null, null, 0)
            codec.start()
            decoderStarted = true
            decoderName = codec.name
            if (Build.VERSION.SDK_INT >= 29) {
                hardware = codec.codecInfo.isHardwareAccelerated
                software = codec.codecInfo.isSoftwareOnly
            }
            stage = "décodage des images"
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            while (decoded < MAX_FRAMES && !eos) {
                if (isCancelled()) return result(PreparationVideoStatus.CANCELLED, "Annulation reçue ; les observations restent partielles.")
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_DURATION_MS)
                    return result(PreparationVideoStatus.TIMED_OUT, "La borne de dix secondes de cet essai court est atteinte. Les observations partielles sont conservées.")
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                            ?: return result(PreparationVideoStatus.FAILED, "Le décodeur n’expose pas son tampon d’entrée.")
                        buffer.clear()
                        val count = input.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val timestamp = input.sampleTime
                            if (timestamp < 0) return result(PreparationVideoStatus.FAILED,
                                "Un échantillon vidéo ne possède pas d’horodatage de présentation exploitable.")
                            codec.queueInputBuffer(index, 0, count, timestamp, 0)
                            input.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    try {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val pixels = if (!isConfig) canReadPixels(codec, outputIndex, info) else false
                        if (!isConfig && (info.size > 0 || pixels)) {
                            decoded++
                            if (pixels) accessible++
                            timestamps += info.presentationTimeUs
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(outputIndex, false) }
                }
            }
            if (isCancelled()) return result(PreparationVideoStatus.CANCELLED, "Annulation reçue ; les observations restent partielles.")
            return if (decoded > 0) result(PreparationVideoStatus.SUCCESS,
                "Des images ont réellement été décodées hors écran. Seuls ces échantillons sont validés ; le support du film complet et de l’OCR reste à tester.")
            else result(PreparationVideoStatus.FAILED, "Le flux s’est terminé sans image décodée observable.")
        } catch (_: SecurityException) {
            return result(PreparationVideoStatus.FAILED, "Le fichier n’est pas accessible avec les droits accordés à cette application.")
        } catch (failure: Exception) {
            // Never leak a content URI, file path or vendor exception payload.
            return if (isCancelled()) result(PreparationVideoStatus.CANCELLED, "Essai annulé pendant une opération Android.")
            else result(PreparationVideoStatus.FAILED, "Échec pendant : $stage (${failure.javaClass.simpleName}). Un ancien conteneur ou codec peut nécessiter un autre parcours de décodage.")
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun canReadPixels(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        val imageRead = runCatching {
            codec.getOutputImage(index)?.use { image ->
                image.planes.firstOrNull()?.buffer?.let { buffer ->
                    if (!buffer.hasRemaining()) false else { buffer.get(buffer.position()); true }
                } ?: false
            } ?: false
        }.getOrDefault(false)
        if (imageRead) return true
        return runCatching {
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.offset >= 0 && info.offset < buffer.limit()) {
                buffer.get(info.offset)
                true
            } else false
        }.getOrDefault(false)
    }

    private fun MediaFormat.stringOrNull(key: String) = runCatching { if (containsKey(key)) getString(key) else null }.getOrNull()
    private fun MediaFormat.integerOrNull(key: String) = runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()
    private fun MediaFormat.longOrNull(key: String) = runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()
}
