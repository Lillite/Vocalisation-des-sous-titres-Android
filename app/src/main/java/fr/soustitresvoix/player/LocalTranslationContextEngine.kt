package fr.soustitresvoix.player

/** Context is supplied by the validated journal, including after a restart. */
interface ContextualTranslationEngine : TranslationEngine {
    val requiresDurableResult: Boolean get() = false
    /** Exact language/source/history/configuration keys only; no semantic similarity. */
    val supportsExactRequestCache: Boolean get() = false
    /** Source windows never depend on model-produced translations of neighbouring cues. */
    val includesPreviousTranslations: Boolean get() = true
    val followingContextItems: Int get() = 0
    fun onExactRequestCacheHit() {}
    /** Validate a reused request without opening model weights or claiming an inference. */
    fun validateExactRequestCacheHit(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean) {
        if (cancelled()) throw TranslationCancelledException()
    }
    fun validateSurroundingRequestCacheHit(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, followingSources: List<String>, cancelled: () -> Boolean) =
        validateExactRequestCacheHit(texts, sourceLanguage, targetLanguage, previousSources, previousTranslations, cancelled)
    /** First previous occurrence required to reconstruct this request after restart. */
    fun contextStartIndex(occurrenceIndex: Int): Int = (occurrenceIndex - 2).coerceAtLeast(0)
    fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String>
    /** Following text is interpretation-only input; the result still covers exactly [texts]. */
    fun translateWithSurroundingContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, followingSources: List<String>, cancelled: () -> Boolean): List<String> =
        translateWithContext(texts, sourceLanguage, targetLanguage, previousSources, previousTranslations, cancelled)
}
