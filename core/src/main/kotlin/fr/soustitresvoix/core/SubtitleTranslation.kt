package fr.soustitresvoix.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** A distinct textual occurrence. The id and original timing are never translated. */
data class TranslationCue(val id: String, val startUs: Long, val endUs: Long, val text: String)

/** A batch always refers to a contiguous range of the original plan. */
class TranslationBatch internal constructor(val firstIndex: Int, texts: List<String>) {
    val texts: List<String> = immutableList(texts)
    /** UTF-16 units, a conservative limit for APIs counting Unicode code points. */
    val characterCount: Long = this.texts.sumOf { it.length.toLong() }
}

/**
 * An immutable, complete source track. Text cleaning belongs to the importer;
 * this boundary never removes descriptions, repeats, signs, or simultaneous cues.
 * There is deliberately one translation result per occurrence, including repeats.
 */
class TranslationPlan private constructor(
    cues: List<TranslationCue>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String
) {
    val cues: List<TranslationCue> = immutableList(cues)
    val texts: List<String> = immutableList(this.cues.map { it.text })
    val estimatedCharacters: Long = texts.sumOf { it.length.toLong() }
    val fingerprint: String = trackFingerprint(sourceLanguage, targetLanguage, providerId, this.cues)

    /** Never split an occurrence, including supplementary Unicode characters. */
    fun batches(maxCharacters: Int, maxItems: Int): List<TranslationBatch> {
        require(maxCharacters > 0 && maxItems > 0) { "Les limites de lot doivent être positives." }
        val output = mutableListOf<TranslationBatch>()
        var first = 0
        var characters = 0L
        val current = mutableListOf<String>()
        texts.forEachIndexed { index, text ->
            require(text.length <= maxCharacters) {
                "Une occurrence dépasse la limite du moteur ; aucun texte n’a été coupé."
            }
            if (current.isNotEmpty() && (current.size >= maxItems || characters + text.length > maxCharacters)) {
                output += TranslationBatch(first, current)
                current.clear()
                characters = 0
                first = index
            }
            current += text
            characters += text.length
        }
        if (current.isNotEmpty()) output += TranslationBatch(first, current)
        return immutableList(output)
    }

    /** Completion is atomic: missing, extra, empty, or invalid results fail the entire track. */
    fun complete(translations: List<String>): TranslatedTrack {
        val safe = translations.toList()
        require(safe.size == cues.size) { "Le moteur n’a pas retourné une traduction par occurrence." }
        require(safe.sumOf { it.length.toLong() } <= MAX_TRACK_CHARACTERS) {
            "La piste traduite dépasse la limite de caractères."
        }
        safe.forEach { validateText(it) }
        return TranslatedTrack(this, cues.mapIndexed { index, cue -> cue.copy(text = safe[index]) })
    }

    companion object {
        const val MAX_CUES = 100_000
        const val MAX_CUE_CHARACTERS = 24_000
        const val MAX_TRACK_CHARACTERS = 12_000_000L

        fun create(cues: List<TranslationCue>, sourceLanguage: String,
            targetLanguage: String = "fr", providerId: String): TranslationPlan {
            val safe = cues.toList()
            require(safe.isNotEmpty()) { "La piste ne contient aucune occurrence textuelle à traduire." }
            require(safe.size <= MAX_CUES) { "La piste dépasse 100 000 occurrences." }
            val ids = HashSet<String>(safe.size)
            safe.forEach { cue ->
                require(cue.id.isNotEmpty() && cue.id.length <= 64_000 && '\u0000' !in cue.id) {
                    "Une identité d’occurrence est invalide."
                }
                require(ids.add(cue.id)) { "Deux occurrences ont la même identité." }
                require(cue.endUs > cue.startUs) { "Le minutage d’une occurrence est invalide." }
                validateText(cue.text)
            }
            require(safe.sumOf { it.text.length.toLong() } <= MAX_TRACK_CHARACTERS) {
                "La piste dépasse la limite de caractères."
            }
            val source = language(sourceLanguage)
            val target = language(targetLanguage)
            require(source != target) { "Les langues source et cible doivent être différentes." }
            require(providerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
                "L’identifiant du moteur est invalide."
            }
            return TranslationPlan(safe, source, target, providerId)
        }

        private fun language(value: String): String {
            require(value.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}"))) {
                "La langue doit être indiquée par un code explicite."
            }
            return value.lowercase(Locale.ROOT)
        }

        private fun validateText(text: String) {
            require(text.isNotBlank() && text.length <= MAX_CUE_CHARACTERS && '\u0000' !in text) {
                "Un texte est vide, trop long ou contient un caractère nul."
            }
            var index = 0
            while (index < text.length) {
                val value = text[index]
                if (Character.isHighSurrogate(value)) {
                    require(index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        "Un texte contient une séquence Unicode invalide."
                    }
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(value)) { "Un texte contient une séquence Unicode invalide." }
                    index++
                }
            }
        }
    }
}

/** Only a fully validated result can be constructed or activated. */
class TranslatedTrack internal constructor(plan: TranslationPlan, cues: List<TranslationCue>) {
    val sourceLanguage: String = plan.sourceLanguage
    val targetLanguage: String = plan.targetLanguage
    val providerId: String = plan.providerId
    val sourceFingerprint: String = plan.fingerprint
    val cues: List<TranslationCue> = immutableList(cues)
    val fingerprint: String = trackFingerprint(sourceLanguage, targetLanguage, providerId, this.cues)
    private val originals: Map<String, String> = plan.cues.associate { it.id to it.text }
    private val translated: Map<String, String> = this.cues.associate { it.id to it.text }

    /** A stale identity or altered source must be refused, never replaced with the original. */
    fun textFor(cueId: String, sourceText: String): String? =
        if (originals[cueId] == sourceText) translated[cueId] else null
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun trackFingerprint(source: String, target: String, provider: String, cues: List<TranslationCue>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // Hash fields incrementally; length prefixes distinguish embedded delimiters/newlines.
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        digest.update(bytes)
    }
    field("subtitle-translation-v1"); field(source); field(target); field(provider)
    cues.forEach { cue ->
        field(cue.id)
        val bytes = ByteArrayOutputStream(16)
        DataOutputStream(bytes).use { it.writeLong(cue.startUs); it.writeLong(cue.endUs) }
        digest.update(bytes.toByteArray())
        field(cue.text)
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
