package fr.soustitresvoix.player

import java.util.Collections

/** DeepL options only. Context guides translation; it is never treated as code or a command. */
class TranslationOptions(
    val context: String = "",
    val glossaryId: String = "",
    val modelType: String = "",
    val formality: String = "default",
    /** Bump when an existing remote glossary changes, to avoid reusing stale local results. */
    val glossaryRevision: String = ""
) {
    val isDefault: Boolean get() = context.isBlank() && glossaryId.isBlank() && modelType.isBlank() &&
        formality == "default" && glossaryRevision.isBlank()

    internal fun validate(provider: TranslationProvider) {
        fun invalid(): Nothing = throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        if (context.length > 24_000 || '\u0000' in context || !validUnicode(context)) invalid()
        if (glossaryId.length > 128 || glossaryId.any { !(it.isLetterOrDigit() || it == '-') }) invalid()
        if (glossaryRevision.length > 128 || glossaryRevision.any { it.code !in 32..126 }) invalid()
        if (glossaryRevision.isNotEmpty() && glossaryId.isBlank()) invalid()
        if (modelType !in setOf("", "quality_optimized", "prefer_quality_optimized", "latency_optimized")) invalid()
        if (formality !in setOf("default", "more", "less", "prefer_more", "prefer_less")) invalid()
        if (!isDefault && provider != TranslationProvider.DEEPL_FREE && provider != TranslationProvider.DEEPL_PRO) invalid()
    }

    override fun toString(): String = "TranslationOptions(content=hidden)"

    private fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (Character.isLowSurrogate(c)) return false
            if (Character.isHighSurrogate(c)) {
                if (i >= value.length || !Character.isLowSurrogate(value[i++])) return false
            }
        }
        return true
    }
}

/** Counts are near-real-time provider values, not a guaranteed billing amount or available balance. */
data class TranslationUsage(
    val characterCount: Long,
    val characterLimit: Long,
    val apiKeyCharacterCount: Long? = null,
    val apiKeyCharacterLimit: Long? = null
) {
    val remainingCharacters: Long get() = (characterLimit - characterCount).coerceAtLeast(0)
    val apiKeyRemainingCharacters: Long? get() = apiKeyCharacterLimit?.let { limit ->
        apiKeyCharacterCount?.let { count -> (limit - count).coerceAtLeast(0) }
    }
}

/** A comparison is explicit and bounded. Failures never substitute an original text as a translation. */
class TranslationSampleResult internal constructor(
    val providerLabel: String,
    val translationIdentity: String,
    translations: List<String>,
    val elapsedMillis: Long,
    val failure: TranslationFailure.Reason? = null
) {
    val translations: List<String> = Collections.unmodifiableList(ArrayList(translations))
    override fun toString(): String = "TranslationSampleResult(provider=$providerLabel, result=hidden)"
}

object TranslationSampleComparator {
    /** Caller must show and obtain an explicit confirmation naming ALL destinations and the sample. */
    fun compareConfirmedSample(configs: List<TranslationProviderConfig>, texts: List<String>, sourceLanguage: String,
        targetLanguage: String = "fr", cancelled: () -> Boolean = { false },
        transportFactory: (TranslationProviderConfig) -> TranslationHttpTransport = { HttpsTranslationTransport() },
        context: android.content.Context? = null): List<TranslationSampleResult> {
        if (configs.size !in 1..4 || texts.size !in 1..20 || texts.any { it.isBlank() } ||
            texts.sumOf { it.length.toLong() } > 4_000L)
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        val safeTexts = texts.toList()
        val safeConfigs = configs.toList()
        // Validate every configuration before the first provider can receive any text.
        val identities = safeConfigs.map {
            TranslationEngines.validateForRequest(it, sourceLanguage, targetLanguage)
            TranslationEngines.translationIdentity(it)
        }
        val results = ArrayList<TranslationSampleResult>()
        safeConfigs.forEachIndexed { index, config ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
            val start = System.nanoTime()
            try {
                val output = TranslationEngines.create(config, transportFactory(config), context).use {
                    it.translate(safeTexts, sourceLanguage, targetLanguage, cancelled)
                }
                results += TranslationSampleResult(config.provider.label, identities[index], output,
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0))
            } catch (failure: TranslationFailure) {
                results += TranslationSampleResult(config.provider.label, identities[index], emptyList(),
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0), failure.reason)
            }
        }
        if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
        return Collections.unmodifiableList(results)
    }
}
