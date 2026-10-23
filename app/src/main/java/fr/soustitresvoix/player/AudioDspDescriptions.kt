package fr.soustitresvoix.player

import fr.soustitresvoix.core.AudioCommandRejection
import fr.soustitresvoix.core.AudioCommandSnapshot
import fr.soustitresvoix.core.AudioNumericInput
import fr.soustitresvoix.core.CapabilityStatus
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.log10

/** Explanations of this project's film DSP. No playback, preference or speech calls. */
internal object AudioDspDescriptions {
    const val INTRO = "Ces traitements concernent uniquement le film en PCM16 mono ou stéréo. " +
        "L’interrupteur général est désactivé par défaut. Le limiteur est présélectionné et devient actif lorsque " +
        "tu actives le traitement général. Les préférences sont conservées pour les prochaines vidéos et ouvertures. " +
        "La voix narrée et les volumes de mélange gardent leurs réglages distincts."

    const val TRANSITIONS = "Les changements sont transmis au processeur et consommés à son prochain bloc audio compatible. " +
        "Le préampli, l’égaliseur, le mono et la balance changent par transition de 20 ms ; si plusieurs demandes arrivent " +
        "pendant cette transition, la dernière attend sa fin. Les bandes sont appliquées dans l’ordre 1 à 5. " +
        "Le limiteur suit la dernière consigne consommée, même pendant une transition. Désactiver le traitement général " +
        "revient progressivement au signal original, puis libère le gain restant du limiteur. " +
        "Aucune trame audio n’est supprimée ou ajoutée. Une modification réelle interrompt un banc d’essai en cours."

    const val PREAMP = "Préampli du film : de −24 à +12 dB, défaut 0 dB. Saisis une valeur décimale ; " +
        "Rétablir remet seulement ce paramètre à 0. Il agit avant les cinq bandes d’égalisation. " +
        "+6 dB multiplie approximativement l’amplitude par deux ; −6 dB la divise approximativement par deux. " +
        "Ce ne sont pas des décibels acoustiques mesurés. Une valeur négative réserve de la marge pour les bandes amplifiées. " +
        "Les gains positifs peuvent conduire le limiteur à réduire le signal, ou provoquer un écrêtage si le limiteur est désactivé."

    const val BALANCE = "Balance stéréo : de −100 à +100 %, défaut 0. Une valeur négative favorise la gauche en " +
        "atténuant seulement la droite ; une valeur positive favorise la droite en atténuant seulement la gauche. " +
        "−100 % coupe la droite ; +100 % coupe la gauche. Le côté favorisé n’est pas amplifié. " +
        "Saisis un pourcentage décimal ; Rétablir revient au centre, 0 %. Sur une entrée à un seul canal, ce réglage est sans effet. " +
        "La balance s’applique après l’éventuel mélange mono."

    const val MONO = "Mono : désactivé par défaut. Cocher remplace les deux canaux stéréo par leur moyenne (gauche + droite) / 2, " +
        "après l’égaliseur et avant la balance. Les sons présents d’un seul côté se retrouvent des deux côtés. " +
        "Des signaux en opposition de phase peuvent s’annuler. Une entrée déjà mono ne change pas. " +
        "Décoche pour retrouver la stéréo ; aucun canal de la voix narrée n’est modifié."

    const val LIMITER = "Limiteur de crêtes numériques : présélectionné, mais sans effet lorsque le traitement général est désactivé. " +
        "Il réduit ensemble les deux canaux lorsqu’une crête calculée dépasse le plafond, afin de conserver leur rapport. " +
        "Il agit après le préampli, l’égaliseur, le mono et la balance. La baisse de gain est immédiate, sans anticipation, " +
        "et peut déformer un transitoire. Ce limiteur vérifie les crêtes des échantillons : ce n’est ni un limiteur true-peak, " +
        "ni une normalisation de volume perçu. Décocher libère le gain restant sur 20 ms ; l’écrêtage PCM16 redevient possible."

    const val CEILING = "Plafond du limiteur : de −12 à 0 dBFS, défaut −1 dBFS. Saisis une valeur décimale ; " +
        "Rétablir revient à −1. dBFS situe l’amplitude numérique par rapport à la pleine échelle, sans mesurer le volume acoustique. " +
        "Un plafond plus bas provoque plus tôt la réduction de gain. La nouvelle consigne du limiteur est utilisée dès sa consommation, " +
        "même si l’égaliseur est en transition. Le plafond est arrondi vers le bas à une amplitude PCM16 représentable, " +
        "avec au plus un pas de quantification d’écart ; à 0 dBFS, la limite symétrique est 32 767 / 32 768. " +
        "Le plafond concerne la sortie de ce processeur, avant les autres transformations éventuelles de Media3 ou du matériel. " +
        "Ce réglage n’a d’effet que si le traitement général et le limiteur sont activés."

    const val RELEASE = "Relâchement du limiteur : de 10 à 2 000 ms, défaut 100 ms. Saisis un nombre entier ; " +
        "Rétablir revient à 100 ms. Cette durée est la constante de temps d’une récupération exponentielle : après ce délai, " +
        "environ 63 % de l’écart de gain restant est récupéré en l’absence d’une nouvelle crête. " +
        "Ce n’est pas un délai au terme duquel le gain est exactement revenu à un. Une durée courte récupère plus vite " +
        "mais peut rendre les variations de gain plus perceptibles ; une durée longue prolonge l’atténuation. " +
        "Ce paramètre est distinct de la durée de remontée du film après la narration."

    const val BAND = "Cette bande est un filtre paramétrique en cloche, désactivé par défaut. " +
        "Cocher prépare son activation ; elle ne traite le film que si l’interrupteur général est aussi activé et si le format est compatible. " +
        "Avec un gain de 0 dB, elle est neutre. Les paramètres sont conservés même si la bande est désactivée. " +
        "Rétablir cette bande désactive seulement cette bande et retrouve sa fréquence initiale, son gain nul et Q = 1."

    const val FREQUENCY = "Fréquence centrale : de 20 à 20 000 Hz. Saisis une fréquence décimale. " +
        "Les cinq fréquences initiales sont 60, 250, 1 000, 4 000 et 12 000 Hz ; Rétablir retrouve celle de cette bande. " +
        "La fréquence doit rester strictement sous la moitié de la fréquence d’échantillonnage du flux pour que la bande soit appliquée. " +
        "Une bande trop haute, ou dont les coefficients seraient numériquement instables, est ignorée et signalée dans les observations ; " +
        "sa fréquence demandée n’est pas déplacée automatiquement."

    const val GAIN = "Gain de la bande : de −12 à +12 dB, défaut 0. Saisis une valeur décimale ; Rétablir remet seulement le gain à zéro. " +
        "Une valeur positive amplifie autour de la fréquence centrale ; une valeur négative atténue. " +
        "+6 dB donne environ deux fois l’amplitude à la fréquence centrale, avant les autres traitements. " +
        "Les cinq bandes peuvent cumuler leurs effets. Une forte amplification demande de la marge de préampli " +
        "ou fait agir le limiteur ; sans limiteur, elle peut écrêter le signal. Ce filtre ne sépare pas voix, musique et effets."

    const val Q = "Facteur Q : de 0,1 à 10, défaut 1, sans unité. Saisis une valeur décimale, par exemple 0,7 ou 2,5. " +
        "Q élevé concentre la correction sur une bande plus étroite ; Q faible élargit la correction. " +
        "Ce paramètre suit la définition du filtre en cloche du Audio EQ Cookbook. " +
        "Rétablir remet seulement Q à 1. Q n’a pas d’effet audible si le gain est nul ou la bande désactivée."

    const val OBSERVATION = "Les observations concernent le dernier flux PCM vu par le processeur, depuis son dernier flush ou reset. " +
        "Après une ouverture de vidéo, elles peuvent encore décrire la fin du flux précédent. Une révision soumise n’est pas une preuve " +
        "de consommation ; une révision consommée peut être en transition. La cible de chaîne décrit le préampli, l’égaliseur, le mono " +
        "et la balance ; le limiteur suit la dernière consigne consommée. La crête concerne seulement le dernier bloc PCM quantifié, " +
        "pas le son acoustique. Les compteurs ne mesurent ni la puissance perçue ni la qualité du casque. Actualiser consulte sans modifier."

    fun rejection(reason: AudioCommandRejection): String = when (reason) {
        AudioCommandRejection.NON_FINITE_VALUE -> "Modification refusée : les paramètres audio avancés doivent être des nombres finis. Aucun réglage modifié."
        AudioCommandRejection.OUT_OF_RANGE -> "Modification refusée : un paramètre audio avancé dépasse sa plage affichée. Aucun réglage modifié."
        else -> AudioCommandText.rejection(reason)
    }

    fun state(snapshot: AudioCommandSnapshot, generation: Long): String = buildString {
        val context = snapshot.context
        if (context.closed) appendLine(AudioCommandText.rejection(AudioCommandRejection.CLOSED))
        else if (context.generation != generation) appendLine(AudioCommandText.rejection(AudioCommandRejection.STALE_MEDIA))
        appendLine("Disponibilité déclarée par le composant : ${when (context.dspAvailability) {
            CapabilityStatus.UNKNOWN -> "non déclarée"
            CapabilityStatus.UNAVAILABLE -> "indisponible"
            CapabilityStatus.SUPPORTED -> "prise en charge déclarée"
            CapabilityStatus.CONDITIONAL -> "conditionnelle au parcours PCM16 mono/stéréo"
        }}.")
        appendLine("Traitement général demandé : ${yesNo(context.requested.dsp.enabled)}. Les nombres et cases de ce panneau montrent les préférences enregistrées.")
        val dsp = snapshot.dsp
        if (dsp == null) {
            append("Aucune observation du processeur audio avancé disponible.")
            return@buildString
        }
        val seen = dsp.observation
        appendLine("Révision soumise : ${dsp.submittedRevision}. Dernière révision consommée : ${seen.consumedRevision ?: "aucune"}.")
        if (seen.consumedRevision != dsp.submittedRevision)
            appendLine("La dernière configuration soumise n’a pas encore été observée comme consommée.")
        appendLine("Dernier format vu : ${seen.sampleRate?.let { "$it Hz" } ?: "non établi"}, ${seen.channelCount?.let { "$it canal/canaux" } ?: "nombre de canaux non établi"}. Compatibilité PCM16 mono/stéréo : ${yesNo(seen.compatible)}.")
        appendLine("Transition de traitement observée : ${yesNo(seen.transitioning)}.")
        val target = seen.appliedSettings
        if (target != null) appendLine("Cible de chaîne observée : activation ${yesNo(target.enabled)}, préampli ${number(target.preampDb)} dB, balance ${AudioNumericInput.format(BigDecimal.valueOf(target.balance).movePointRight(2))} %, mono ${yesNo(target.mono)}. Cette cible ne décrit pas à elle seule le signal pendant une transition.")
        val consumed = seen.consumedSettings
        if (consumed != null) appendLine("Dernière consigne de limiteur consommée : sélection ${yesNo(consumed.limiterEnabled)}, plafond ${number(consumed.limiterCeilingDb)} dBFS, relâchement ${consumed.limiterReleaseMs} ms. Elle reste soumise à l’activation générale et à sa transition de sortie.")
        val skipped = (0 until 5).filter { seen.skippedBandMask and (1 shl it) != 0 }.map { it + 1 }
        appendLine("Bandes ignorées dans la cible observée : ${if (seen.consumedRevision == null) "non établi" else if (skipped.isEmpty()) "aucune signalée" else skipped.joinToString(", ")}.")
        appendLine("Depuis le dernier flush/reset : ${seen.processedFrames} trames PCM ; ${seen.limitedFrames} trames où le limiteur a réduit une amplitude non nulle ; ${seen.clippedSamples} échantillons écrêtés après arrondi ou invalides.")
        append("Crête du dernier bloc PCM quantifié : ${peak(seen.outputPeak)}. Ces observations peuvent appartenir au flux précédent ; elles ne mesurent pas le son entendu.")
    }

    private fun yesNo(value: Boolean?) = when (value) { true -> "oui"; false -> "non"; null -> "non établi" }
    private fun number(value: Double) = AudioNumericInput.format(BigDecimal.valueOf(value))
    private fun peak(value: Double?): String = when {
        value == null || !value.isFinite() || value < 0 -> "non établie"
        value == 0.0 -> "silence numérique (−∞ dBFS)"
        else -> "${BigDecimal.valueOf(20 * log10(value)).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')} dBFS"
    }
}
