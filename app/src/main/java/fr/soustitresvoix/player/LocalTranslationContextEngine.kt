package fr.soustitresvoix.player

/** Context is supplied by the validated journal, including after a restart. */
interface ContextualTranslationEngine : TranslationEngine {
    val requiresDurableResult: Boolean get() = false
    fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String>
}
