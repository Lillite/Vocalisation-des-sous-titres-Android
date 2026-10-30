package fr.soustitresvoix.player

/** Allocation choices never shorten the source, context capacity or output budget. */
data class LocalGgufLoadSettings(
    val gpuLayers: Int = -1,
    val batchTokens: Int = 512,
    val microBatchTokens: Int = 128,
    val compactSwa: Boolean = false
) {
    val isDefault: Boolean get() = this == LocalGgufLoadSettings()

    fun validate() {
        if (gpuLayers != -1 && gpuLayers !in 1..1024 || batchTokens !in 16..512 ||
            microBatchTokens !in 16..128 || microBatchTokens > batchTokens)
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
    }

    internal fun cacheIdentity(): String = "$gpuLayers/$batchTokens/$microBatchTokens/$compactSwa"

    fun render(): String = "Réglages demandés : couches GPU = ${if (gpuLayers == -1) "toutes" else gpuLayers}; " +
        "lot = $batchTokens tokens; micro-lot = $microBatchTokens tokens; " +
        "cache d’attention locale compact = ${if (compactSwa) "oui (expérimental)" else "non"}. " +
        "Le mode CPU conserve ses couches sur CPU. Le contexte et la limite de sortie restent inchangés."
}
