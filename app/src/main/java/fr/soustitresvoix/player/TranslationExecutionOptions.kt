package fr.soustitresvoix.player

/** Transport/download choices do not change translated content or invalidate a reusable cache. */
data class TranslationExecutionOptions(
    val googleIntervalMillis: Long = 1_000L,
    val allowMobileModelDownload: Boolean = false
) {
    fun validate() {
        if (googleIntervalMillis !in 500L..10_000L)
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
    }
}
