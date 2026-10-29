package fr.soustitresvoix.player

/** Context is supplied by the validated journal, including after a restart. */
interface ContextualTranslationEngine : TranslationEngine {
    val requiresDurableResult: Boolean get() = false
    /** Exact language/source/history/configuration keys only; no semantic similarity. */
    val supportsExactRequestCache: Boolean get() = false
    fun onExactRequestCacheHit() {}
    /** Validate a reused request without opening model weights or claiming an inference. */
    fun validateExactRequestCacheHit(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean) {
        if (cancelled()) throw TranslationCancelledException()
    }
    /** First previous occurrence required to reconstruct this request after restart. */
    fun contextStartIndex(occurrenceIndex: Int): Int = (occurrenceIndex - 2).coerceAtLeast(0)
    fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String>
}
