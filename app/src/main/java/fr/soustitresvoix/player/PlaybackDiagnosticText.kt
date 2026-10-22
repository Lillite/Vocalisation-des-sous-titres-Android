package fr.soustitresvoix.player

import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.DiagnosticFact
import fr.soustitresvoix.core.DiagnosticSource
import fr.soustitresvoix.core.DiagnosticValue
import fr.soustitresvoix.core.PlaybackDiagnosticSnapshot
import java.util.Locale

/** Formatting only: all values come from one immutable snapshot. */
internal object PlaybackDiagnosticText {
    private val labels = mapOf(
        "video.decoder" to "Préférence et initialisation du décodeur vidéo",
        "video.decoder_kind" to "Classification du décodeur déclarée par Android",
        "playback.seek" to "Déplacement dans le média",
        "playback.position_ms" to "Position de lecture (ms)",
        "playback.duration_ms" to "Durée du média (ms)",
        "video.format" to "Format vidéo observé",
        "video.source_hdr" to "HDR du format source",
        "video.effects" to "Corrections d’image",
        "video.scaling" to "Redimensionnement détaillé",
        "video.frame_rate" to "Adaptation de fréquence",
        "audio.spectral_ducking" to "Atténuation expérimentale du film",
        "audio.movie_volume" to "Volume du film",
        "subtitle.selection" to "Piste de sous-titres",
        "subtitle.index" to "Index des sous-titres",
        "speech.readiness" to "Disponibilité de la voix",
        "speech.queue" to "File de narration"
    )

    fun render(snapshot: PlaybackDiagnosticSnapshot): String = buildString {
        append("Capacités et observations du lecteur\n")
        if (snapshot.closed) { append("Session fermée ; observations du média invalidées."); return@buildString }
        append("État au moment de la consultation ; demandé, configuré et observé sont distincts.\n")
        if (snapshot.generation == 0L) append("Aucun média ouvert.\n")
        snapshot.capabilities.forEach { append('\n'); append(render(it)); append('\n') }
    }

    fun render(capability: CapabilityDiagnostic): String = buildString {
        append(labels[capability.id] ?: "Information du composant")
        append(" — ")
        append(when (capability.status) {
            CapabilityStatus.SUPPORTED -> "pris en charge"
            CapabilityStatus.CONDITIONAL -> "sous conditions"
            CapabilityStatus.UNAVAILABLE -> "indisponible dans cet état"
            CapabilityStatus.UNKNOWN -> "inconnu"
        })
        append(" ["); append(source(capability.assessmentSource)); append("]\n")
        append(capability.reason)
        append("\nDemandé : "); append(fact(capability.requested))
        append("\nConfiguré : "); append(fact(capability.configured))
        append("\nObservé : "); append(fact(capability.observed))
    }

    private fun fact(fact: DiagnosticFact?): String {
        if (fact == null) return "non établi"
        val value = when (val v = fact.value) {
            is DiagnosticValue.Flag -> if (v.value) "oui" else "non"
            is DiagnosticValue.Count -> v.value.toString()
            is DiagnosticValue.Text -> v.value
            is DiagnosticValue.Decimal -> String.format(Locale.FRANCE, "%.3f", v.value)
        }
        return "$value [${source(fact.source)}]" + (fact.detail?.let { " — $it" } ?: "")
    }

    private fun source(source: DiagnosticSource): String = when (source) {
        DiagnosticSource.USER_PREFERENCE -> "réglage demandé"
        DiagnosticSource.PLAYER_CONFIGURATION -> "configuration du composant"
        DiagnosticSource.PLAYER_STATE -> "état rapporté"
        DiagnosticSource.DECODER_EVENT -> "événement du décodeur"
        DiagnosticSource.PLATFORM_DECLARATION -> "déclaration Android"
        DiagnosticSource.MEDIA_FORMAT -> "métadonnées du format"
        DiagnosticSource.APPLICATION_POLICY -> "règle de l’application"
        DiagnosticSource.AUDIO_PROCESSOR -> "compteur du processeur audio"
        DiagnosticSource.NOT_EXPOSED -> "information non exposée"
    }
}
