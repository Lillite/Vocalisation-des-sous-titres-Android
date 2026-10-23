package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.DiagnosticFact
import fr.soustitresvoix.core.DiagnosticSource
import fr.soustitresvoix.core.DiagnosticValue
import fr.soustitresvoix.core.PlaybackDiagnosticSnapshot
import fr.soustitresvoix.core.PlaybackDiagnosticStore

/** Session-thread observation only. No commands, timers, codec scans or speech calls.
 * A media token is opaque application metadata, unique even when reopening the
 * same URI. Neither tokens, media names nor subtitle contents enter the report. */
@UnstableApi
internal class PlaybackDiagnosticsObserver(private val session: PlaybackSession) : AnalyticsListener {
    private data class MediaToken(val generation: Long)
    private data class Evidence<T>(val generation: Long, val value: T)
    private data class SelectedText(val selected: Boolean, val external: Boolean)
    private val store = PlaybackDiagnosticStore()
    private var token: MediaToken? = null
    private var closed = false
    private var decoder: Evidence<String>? = null
    private var inputFormat: Evidence<Format>? = null
    private var trackFormat: Evidence<Format>? = null
    private var selectedText: Evidence<SelectedText>? = null

    fun beginMedia(): Any {
        check(!closed)
        val next = MediaToken(store.invalidate())
        token = next
        clearMediaEvidence()
        return next
    }

    private fun clearMediaEvidence() {
        decoder = null; inputFormat = null; trackFormat = null; selectedText = null
    }

    /** A null mediaPeriodId can be an analytics fallback to the current timeline.
     * Its current tag alone is NOT evidence of the event's original media. */
    private fun generationOf(event: AnalyticsListener.EventTime): Long? {
        val current = token ?: return null
        if (closed) return null
        val mediaPeriod = event.mediaPeriodId ?: return null
        val timeline = event.timeline
        if (event.windowIndex !in 0 until timeline.windowCount) return null
        val index = timeline.getIndexOfPeriod(mediaPeriod.periodUid)
        if (index == C.INDEX_UNSET) return null
        if (timeline.getPeriod(index, Timeline.Period()).windowIndex != event.windowIndex) return null
        val tag = timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.localConfiguration?.tag
        return current.generation.takeIf { tag === current }
    }

    /** Reuse the same attribution for the image guard and read-only diagnostics. */
    internal fun isCurrentMediaEvent(event: AnalyticsListener.EventTime): Boolean = generationOf(event) != null

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        val generation = generationOf(eventTime) ?: return
        decoder = Evidence(generation, decoderName)
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime,
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        val generation = generationOf(eventTime) ?: return
        inputFormat = Evidence(generation, format)
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val generation = generationOf(eventTime) ?: return
        var text = SelectedText(false, false)
        var video: Format? = null
        tracks.groups.forEach { group ->
            for (i in 0 until group.length) if (group.isTrackSelected(i)) {
                val format = group.getTrackFormat(i)
                if (group.type == C.TRACK_TYPE_TEXT)
                    text = SelectedText(true, session.isExternalSubtitle(format))
                if (group.type == C.TRACK_TYPE_VIDEO) video = format
            }
        }
        selectedText = Evidence(generation, text)
        trackFormat = video?.let { Evidence(generation, it) }
    }

    private fun currentWindow(): Timeline.Window? {
        val current = token ?: return null
        val player = session.player
        val timeline = player.currentTimeline
        if (player.currentMediaItemIndex !in 0 until timeline.windowCount) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return window.takeIf { !it.isPlaceholder && it.mediaItem.localConfiguration?.tag === current }
    }

    private fun <T> Evidence<T>?.current(): T? = this?.takeIf { it.generation == store.generation }?.value
    private fun flag(value: Boolean, source: DiagnosticSource, detail: String? = null) =
        DiagnosticFact(DiagnosticValue.Flag(value), source, detail)
    private fun count(value: Long, source: DiagnosticSource, detail: String? = null) =
        DiagnosticFact(DiagnosticValue.Count(value), source, detail)
    private fun text(value: String, source: DiagnosticSource, detail: String? = null) =
        DiagnosticFact(DiagnosticValue.Text(value), source, detail)
    private fun decimal(value: Float, source: DiagnosticSource, detail: String? = null): DiagnosticFact? =
        value.takeIf { it.isFinite() }?.let { DiagnosticFact(DiagnosticValue.Decimal(it.toDouble()), source, detail) }

    fun snapshot(): PlaybackDiagnosticSnapshot {
        if (closed) return store.snapshot()
        val window = currentWindow()
        val player = session.player
        val backend = session.playback.diagnosticCapabilities().associateBy { it.id }
        fun declared(id: String) = backend[id] ?: CapabilityDiagnostic(id, CapabilityStatus.UNKNOWN,
            DiagnosticSource.NOT_EXPOSED, "Le moteur ne déclare pas la disponibilité de cette fonction.")
        val name = decoder.current()
        val classification = name?.let { session.playback.decoderClassification(it) }
        val format = inputFormat.current() ?: trackFormat.current()
        val hdr = when {
            format == null -> null
            format.sampleMimeType == "video/dolby-vision" -> true
            format.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) -> true
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR -> false
            else -> null
        }
        val image = session.visual.diagnosticState()
        val narration = session.machine.snapshot()
        val selected = selectedText.current()
        val hasMedia = token != null
        val capabilities = mutableListOf<CapabilityDiagnostic>()
        capabilities += declared("video.decoder").copy(
            requested = flag(session.prefs.bool("preferSoftware"), DiagnosticSource.USER_PREFERENCE,
                "Préférer un décodeur logiciel lors de la sélection."),
            observed = name?.let { text(it, DiagnosticSource.DECODER_EVENT,
                "Dernière initialisation attribuable à ce média ; ne garantit pas que le codec soit encore actif à cet instant.") },
            reason = declared("video.decoder").reason + if (name == null)
                " Aucune initialisation attribuable observée ; une réutilisation de codec peut ne pas émettre de nouvel événement." else "")
        capabilities += CapabilityDiagnostic("video.decoder_kind",
            if (classification == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED,
            if (classification == null) DiagnosticSource.NOT_EXPOSED else DiagnosticSource.PLATFORM_DECLARATION,
            "Classification déclarée pour le nom observé, disponible ici à partir d’Android 10 ; aucune mesure de charge CPU/GPU.",
            observed = classification?.let { text(
                "matériel déclaré=${it.hardwareAccelerated}, logiciel déclaré=${it.softwareOnly}",
                DiagnosticSource.PLATFORM_DECLARATION) })
        capabilities += CapabilityDiagnostic("playback.seek", when (window?.isSeekable) {
            true -> CapabilityStatus.SUPPORTED; false -> CapabilityStatus.UNAVAILABLE; null -> CapabilityStatus.UNKNOWN
        }, DiagnosticSource.PLAYER_STATE,
            if (window == null) "La fenêtre du média courant n’est pas encore établie."
            else "Possibilité de déplacement déclarée par la fenêtre courante ; aucune commande de déplacement exécutée.",
            observed = window?.let { flag(it.isSeekable, DiagnosticSource.PLAYER_STATE) })
        capabilities += CapabilityDiagnostic("playback.position_ms",
            if (window == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.PLAYER_STATE,
            "Position rapportée par le lecteur pour le média courant, en millisecondes.",
            observed = window?.let { count(player.currentPosition.coerceAtLeast(0), DiagnosticSource.PLAYER_STATE) })
        val duration = if (window != null) player.duration.takeIf { it != C.TIME_UNSET && it >= 0 } else null
        capabilities += CapabilityDiagnostic("playback.duration_ms",
            if (duration == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.PLAYER_STATE,
            "Durée rapportée du média courant, en millisecondes ; une durée inconnue n’est pas remplacée par zéro.",
            observed = duration?.let { count(it, DiagnosticSource.PLAYER_STATE) })
        capabilities += CapabilityDiagnostic("video.format",
            if (format == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.MEDIA_FORMAT,
            "Dernier format d’entrée ou de piste vidéo attribuable à ce média ; métadonnées, pas mesure de l’image affichée.",
            observed = format?.let { text(describeFormat(it), DiagnosticSource.MEDIA_FORMAT) })
        capabilities += CapabilityDiagnostic("video.source_hdr",
            if (hdr == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.MEDIA_FORMAT,
            "Caractéristique du format source. La sortie HDR de l’écran et le tone mapping effectif ne sont pas vérifiés ici.",
            observed = hdr?.let { flag(it, DiagnosticSource.MEDIA_FORMAT) })
        val effects = declared("video.effects")
        capabilities += effects.copy(
            status = if (hdr == true && effects.status != CapabilityStatus.UNKNOWN) CapabilityStatus.UNAVAILABLE else effects.status,
            reason = effects.reason + when {
                hdr == true -> " Corrections désactivées par la protection HDR actuelle."
                image.bypass -> " Comparaison avec l’original demandée."
                else -> ""
            },
            requested = text("contraste=${image.contrast}, luminosité=${image.brightness}, marges=${image.margins}",
                DiagnosticSource.USER_PREFERENCE),
            configured = text("contraste=${image.configuredContrast}, luminosité=${image.configuredBrightness}, marges=${image.configuredMargins}",
                DiagnosticSource.PLAYER_CONFIGURATION, "Dernier plan transmis, portée session. Les changements peuvent attendre le prochain envoi ; les pixels ne sont pas mesurés."))
        val scaling = declared("video.scaling")
        capabilities += scaling.copy(
            status = if (hdr == true && scaling.status != CapabilityStatus.UNKNOWN) CapabilityStatus.UNAVAILABLE else scaling.status,
            reason = scaling.reason + if (!image.surfaceKnown) " Surface non encore mesurée." else "",
            requested = flag(image.scalingRequested, DiagnosticSource.USER_PREFERENCE),
            configured = flag(image.configuredScalingWidth > 0 && image.configuredScalingHeight > 0,
                DiagnosticSource.PLAYER_CONFIGURATION,
                "Dernier plan transmis : ${image.configuredScalingWidth} × ${image.configuredScalingHeight} ; portée session, rendu non mesuré."))
        capabilities += declared("video.frame_rate").copy(
            requested = flag(session.prefs.bool("matchFrameRate", true), DiagnosticSource.USER_PREFERENCE),
            configured = text(when (session.video.frameRateStrategy) {
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF -> "adaptation désactivée"
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS -> "adaptation seulement si transparente"
                else -> "autre stratégie rapportée par le moteur"
            }, DiagnosticSource.PLAYER_CONFIGURATION), observed = null)
        capabilities += session.movieAudio.diagnosticCapability().copy(
            requested = flag(session.prefs.bool("spectralDucking"), DiagnosticSource.USER_PREFERENCE),
            configured = flag(session.movieAudio.enabled, DiagnosticSource.PLAYER_CONFIGURATION,
                "Signal de parole transmis au processeur : ${session.movieAudio.speaking}."),
            observed = count(session.movieAudio.processedSamples, DiagnosticSource.AUDIO_PROCESSOR,
                "Échantillons individuels de tous les canaux cumulés depuis le début de session, y compris sans atténuation ; aucune preuve d’atténuation instantanée."))
        capabilities += CapabilityDiagnostic("audio.movie_volume", CapabilityStatus.SUPPORTED,
            DiagnosticSource.PLAYER_CONFIGURATION, "Consigne de volume du film entre 0 et 1. La valeur configurée peut être intermédiaire pendant un fondu ; pas une mesure acoustique de la sortie.",
            requested = decimal(session.prefs.movieVolume, DiagnosticSource.USER_PREFERENCE),
            configured = decimal(player.volume, DiagnosticSource.PLAYER_CONFIGURATION), observed = null)
        capabilities += CapabilityDiagnostic("audio.duck_ratio", CapabilityStatus.SUPPORTED,
            DiagnosticSource.APPLICATION_POLICY,
            "Multiplicateur du volume normal pendant la voix, entre 0 et 1. Exemple : 0,60 × 0,25 = 0,15. La cible peut ne pas être atteinte pendant le fondu.",
            requested = decimal(session.prefs.duckVolume, DiagnosticSource.USER_PREFERENCE))
        val audio = session.prefs.movieAudioSettings()
        capabilities += CapabilityDiagnostic("audio.duck_timing", CapabilityStatus.SUPPORTED,
            DiagnosticSource.APPLICATION_POLICY,
            "Durées demandées pour les prochaines transitions, en millisecondes. Une transition déjà planifiée conserve ses durées. L’attente peut être prolongée pour une réplique proche ; la latence réelle n’est pas mesurée ici.",
            requested = text("baisse=${audio.attackMs} ms, remontée=${audio.releaseMs} ms, attente minimale=${audio.holdMs} ms",
                DiagnosticSource.USER_PREFERENCE))
        capabilities += CapabilityDiagnostic("subtitle.selection",
            if (selected == null) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.PLAYER_STATE,
            "Sélection attribuable au média courant, sans nom de fichier, libellé de piste ni texte de sous-titre.",
            observed = selected?.let { flag(it.selected, DiagnosticSource.PLAYER_STATE,
                if (!it.selected) "Aucune piste sélectionnée." else if (it.external) "Piste externe." else "Piste intégrée.") })
        capabilities += CapabilityDiagnostic("subtitle.index",
            if (!hasMedia) CapabilityStatus.UNKNOWN else CapabilityStatus.SUPPORTED, DiagnosticSource.PLAYER_STATE,
            if (!hasMedia) "Aucun média ouvert." else session.diagnosticIndexingStatus(),
            observed = if (hasMedia) count(session.ahead.count.toLong(), DiagnosticSource.PLAYER_STATE,
                "Entrées de l’index du média courant ; ne mesure pas une exhaustivité non encore vérifiée.") else null)
        capabilities += CapabilityDiagnostic("speech.readiness",
            if (session.speech.isReady) CapabilityStatus.SUPPORTED else CapabilityStatus.CONDITIONAL,
            DiagnosticSource.PLAYER_STATE, "Disponibilité du service vocal ; aucune synthèse ni écoute déclenchée par ce diagnostic.",
            requested = flag(session.prefs.narration, DiagnosticSource.USER_PREFERENCE),
            configured = flag(narration.enabled, DiagnosticSource.PLAYER_CONFIGURATION),
            observed = flag(session.speech.isReady, DiagnosticSource.PLAYER_STATE))
        capabilities += CapabilityDiagnostic("speech.queue", CapabilityStatus.SUPPORTED, DiagnosticSource.PLAYER_STATE,
            "État du contrôleur de narration, sans le contenu ni l’identifiant des demandes.",
            observed = count(narration.pendingCount.toLong(), DiagnosticSource.PLAYER_STATE,
                "Demande en cours : ${narration.speakingId != null} ; intention de lecture : ${narration.wantsPlayback} ; attente de voix : ${narration.waitingForSpeech}."))
        store.publish(store.generation, capabilities)
        return store.snapshot()
    }

    private fun describeFormat(format: Format): String {
        // Do not copy arbitrary track IDs, labels, filenames or codec-private strings.
        val codec = when (format.sampleMimeType) {
            "video/avc" -> "AVC/H.264"; "video/hevc" -> "HEVC/H.265"
            "video/av01" -> "AV1"; "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"
            "video/dolby-vision" -> "Dolby Vision"; "video/mp4v-es" -> "MPEG-4 Visual"
            "video/mpeg2" -> "MPEG-2"; else -> "type vidéo non identifié"
        }
        val size = if (format.width > 0 && format.height > 0) "${format.width} × ${format.height}" else "dimensions inconnues"
        val rate = if (format.frameRate.isFinite() && format.frameRate > 0) "${format.frameRate} images/s déclarées" else "cadence inconnue"
        return "$codec ; $size ; $rate"
    }

    fun close() {
        if (closed) return
        closed = true; token = null; clearMediaEvidence(); store.close()
    }
}
