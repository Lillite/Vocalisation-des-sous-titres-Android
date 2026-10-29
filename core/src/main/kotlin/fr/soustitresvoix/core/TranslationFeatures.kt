package fr.soustitresvoix.core

import java.util.Locale

/** Export is a text interchange format; rendering styles and overlapping timing remain explicit. */
enum class SubtitleExportFormat(val mimeType: String, val extension: String) {
    SRT("application/x-subrip", "srt"), VTT("text/vtt", "vtt")
}

object TranslatedSubtitleExport {
    fun encode(cues: List<TranslationCue>, format: SubtitleExportFormat): String {
        require(cues.isNotEmpty()) { "Aucune occurrence à exporter." }
        val result = StringBuilder(if (format == SubtitleExportFormat.VTT) "WEBVTT\n\n" else "")
        cues.forEachIndexed { index, cue ->
            require(cue.endUs > cue.startUs && cue.text.isNotBlank() && '\u0000' !in cue.text)
            if (format == SubtitleExportFormat.SRT) result.append(index + 1).append('\n')
            // Text formats have millisecond precision. Preserve a positive display interval after clipping negative starts.
            val startMs = cue.startUs.coerceAtLeast(0) / 1000
            val endMs = maxOf(cue.endUs.coerceAtLeast(0) / 1000, startMs + 1)
            result.append(timestamp(startMs, format)).append(" --> ").append(timestamp(endMs, format)).append('\n')
            // Escape markup so an edited '<' remains literal after import into a subtitle renderer.
            val literal = cue.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            result.append(literal.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n{2,}"), "\n")).append("\n\n")
        }
        return result.toString()
    }
    private fun timestamp(ms: Long, format: SubtitleExportFormat): String {
        val hours = ms / 3_600_000
        val minutes = (ms / 60_000) % 60
        val seconds = (ms / 1000) % 60
        val fraction = ms % 1000
        val separator = if (format == SubtitleExportFormat.SRT) ',' else '.'
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, fraction)
    }
}

/** Review hints are deliberately fallible. They neither reject nor rewrite translations. */
data class TranslationReviewHint(val cueId: String, val reasons: List<String>)
object TranslationReview {
    private val possibleName = Regex("(?<![\\p{L}])[\\p{Lu}][\\p{L}’'-]{2,}(?![\\p{L}])")
    private val word = Regex("[\\p{L}’'-]+")
    private val commonCapitals = setOf("the", "this", "that", "what", "where", "when", "with", "you", "your",
        "take", "there", "they", "have", "please", "bonjour", "mais", "pour", "avec", "nous", "vous", "une", "les", "des", "que")
    fun inspect(source: List<TranslationCue>, translated: List<TranslationCue>,
        sourceLanguage: String? = null, targetLanguage: String? = null): List<TranslationReviewHint> {
        val results = translated.associateBy { it.id }
        val sourceNames = source.associate { cue -> cue.id to possibleName.findAll(cue.text)
            .map { it.value.lowercase(Locale.ROOT) }.filter { it !in commonCapitals }.toSet() }
        val candidates = sourceNames.values.flatten()
            .groupingBy { it }.eachCount().filter { (_, count) -> count >= 2 }
            .keys
        // Only flag a variable spelling when another translated occurrence has
        // kept the source name. Consistent transliteration is not an error.
        val translatedNames = translated.associate { cue -> cue.id to word.findAll(cue.text)
            .map { it.value.lowercase(Locale.ROOT) }.filter { it in candidates }.toSet() }
        val retainedNames = translatedNames.values.flatten().toSet()
        return source.mapNotNull { original ->
            val result = results[original.id] ?: return@mapNotNull null
            val reasons = mutableListOf<String>()
            if (result.text.isBlank()) reasons += "Texte vide : vérifier cette occurrence."
            if (sourceNames[original.id].orEmpty().any { it in retainedNames && it !in translatedNames[result.id].orEmpty() })
                reasons += "Nom propre possible variable ou absent : comparer avec les autres répliques ; traduction ou adaptation légitime possible."
            when (TranslationNumbers.compare(original.text, result.text, sourceLanguage, targetLanguage)) {
                TranslationNumbers.Comparison.DIFFERENT ->
                    reasons += "Nombres ou unités différents : vérifier les valeurs, leur ordre et les nombres écrits en lettres."
                TranslationNumbers.Comparison.UNCERTAIN ->
                    reasons += "Format numérique ambigu ou non pris en charge : comparer les nombres et leur notation."
                TranslationNumbers.Comparison.SAME -> Unit
            }
            if (original.text.length >= 40 && (result.text.length * 4 < original.text.length || result.text.length > original.text.length * 4))
                reasons += "Longueur très différente : une omission ou un ajout est possible."
            if (original.text.length >= 20 && original.text.equals(result.text, ignoreCase = true))
                reasons += "Texte identique à la source : peut être correct pour des noms ou une langue déjà française."
            if (reasons.isEmpty()) null else TranslationReviewHint(original.id, reasons)
        }
    }
}

data class SubtitleLanguageHint(val code: String?, val explanation: String)
/** Small local script/word heuristic, never a confidence score or a language classifier. */
object SubtitleLanguageProposal {
    fun suggest(texts: List<String>): SubtitleLanguageHint {
        val sample = texts.take(30).joinToString(" ").take(8000).lowercase(Locale.ROOT)
        val letters = sample.count { it.isLetter() }
        if (letters < 20) return SubtitleLanguageHint(null, "Échantillon trop court ; choisis la langue des sous-titres.")
        fun proportion(range: IntRange) = sample.count { it.code in range }.toDouble() / letters
        if (proportion(0x3040..0x30ff) > .12) return SubtitleLanguageHint("ja", "Écriture japonaise détectée ; langue à confirmer.")
        if (proportion(0xac00..0xd7af) > .12) return SubtitleLanguageHint("ko", "Écriture coréenne détectée ; langue à confirmer.")
        if (proportion(0x0600..0x06ff) > .3) return SubtitleLanguageHint("ar", "Écriture arabe probable ; plusieurs langues utilisent cette écriture, confirme la langue.")
        if (proportion(0x0400..0x04ff) > .3) return SubtitleLanguageHint(null, "Écriture cyrillique : russe, serbe et autres langues possibles ; choisis explicitement.")
        if (proportion(0x4e00..0x9fff) > .3) return SubtitleLanguageHint("zh", "Caractères chinois majoritaires ; chinois ou autre langue à confirmer.")
        val words = Regex("[\\p{L}']+").findAll(sample).map { it.value }.toList()
        val vocabularies = mapOf(
            "en" to setOf("the", "you", "what", "this", "that", "don't", "your", "with"),
            "es" to setOf("que", "pero", "está", "estoy", "tienes", "quiero", "por", "qué"),
            "de" to setOf("ich", "nicht", "und", "das", "ist", "wir", "sie", "du"),
            "it" to setOf("che", "sono", "questo", "perché", "voglio", "della", "sei", "una"),
            "fr" to setOf("je", "vous", "nous", "pas", "avec", "pour", "c'est", "qu'il"),
            "pt" to setOf("você", "não", "estou", "isso", "obrigado", "também", "uma"),
            "tr" to setOf("bir", "ben", "sen", "için", "değil", "evet", "hayır"))
        val scores = vocabularies.mapValues { (_, vocabulary) -> words.count { it in vocabulary } }.entries.sortedByDescending { it.value }
        val first = scores.first()
        return if (first.value >= 4 && first.value >= scores[1].value + 2)
            SubtitleLanguageHint(first.key, "Proposition fondée sur quelques mots fréquents ; vérifie avant tout envoi, surtout pour une piste multilingue.")
        else SubtitleLanguageHint(null, "Langue incertaine ou mélangée ; choisis explicitement la langue des sous-titres.")
    }
}
