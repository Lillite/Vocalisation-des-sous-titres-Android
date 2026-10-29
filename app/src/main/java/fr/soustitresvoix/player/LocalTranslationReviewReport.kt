package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslatedTrack
import fr.soustitresvoix.core.TranslationPlan
import fr.soustitresvoix.core.TranslationReview

/** Bounded, on-demand presentation of fallible hints. It never changes a result. */
internal object LocalTranslationReviewReport {
    const val UNAVAILABLE = "La vérification heuristique n’a pas pu être préparée. La traduction complète et ses exports restent disponibles ; cela ne confirme ni n’infirme leur fidélité."
    private const val MAX_HINTS = 50
    private const val MAX_REPORT_CHARACTERS = 96_000
    private const val MAX_EXCERPT_CODE_POINTS = 240

    /** Called on the translation worker after native model cleanup. */
    fun render(plan: TranslationPlan, translated: TranslatedTrack): String {
        require(translated.sourceFingerprint == plan.fingerprint)
        val hints = TranslationReview.inspect(plan.cues, translated.cues, plan.sourceLanguage, plan.targetLanguage)
        val output = StringBuilder("Vérification heuristique — ${plan.sourceLanguage} vers ${plan.targetLanguage}\n")
            .append("${plan.cues.size} occurrences traduites ; ${hints.size} occurrence(s) avec un indice à vérifier.\n")
            .append("Ces indices peuvent être erronés. Leur absence ne prouve pas la fidélité linguistique. Aucune traduction n’est modifiée.\n")
            .append("Les sources et traductions ci-dessous sont des extraits ; le fichier original et l’export contiennent les textes intégraux.\n")
        var shown = 0
        for (hint in hints.take(MAX_HINTS)) {
            val index = plan.cues.indexOfFirst { it.id == hint.cueId }
            if (index < 0) continue
            val record = "\nOccurrence ${index + 1}\n${hint.reasons.joinToString(" ")}\n" +
                "Source : ${excerpt(plan.cues[index].text)}\nTraduction : ${excerpt(translated.cues[index].text)}\n"
            if (output.length + record.length > MAX_REPORT_CHARACTERS - 512) break
            output.append(record)
            shown++
        }
        if (shown < hints.size) output.append("\nAffichage borné : $shown indice(s) présenté(s) sur ${hints.size}. Les autres occurrences restent dans la traduction et l’export.\n")
        if (hints.isEmpty()) output.append("\nAucun indice détecté par ces règles. Les pronoms, conditions, négations et contresens peuvent néanmoins être incorrects.\n")
        return output.toString()
    }

    private fun excerpt(value: String): String = if (value.codePointCount(0, value.length) <= MAX_EXCERPT_CODE_POINTS) value
        else value.substring(0, value.offsetByCodePoints(0, MAX_EXCERPT_CODE_POINTS)) + " […]"
}
