package fr.soustitresvoix.player

/** Allocation choices never shorten the source, context capacity or output budget. */
data class LocalGgufLoadSettings(
    val gpuLayers: Int = -1,
    val batchTokens: Int = 512,
    val microBatchTokens: Int = 128,
    val compactSwa: Boolean = false,
    val kvCacheType: String = "F16",
    val flashAttentionPolicy: String = "AUTO",
    val qualcommCompatibility: Boolean = false,
    val nativeCpuWeights: Boolean = false
) {
    val isDefault: Boolean get() = gpuLayers == -1 && batchTokens == 512 && microBatchTokens == 128 &&
        !compactSwa && kvCacheType == "F16" && flashAttentionPolicy == "AUTO" && !qualcommCompatibility && !nativeCpuWeights

    fun validate() {
        if (gpuLayers != -1 && gpuLayers !in 1..1024 || batchTokens !in 16..512 ||
            microBatchTokens !in 16..128 || microBatchTokens > batchTokens ||
            kvCacheType != "F16" && kvCacheType != "Q8_0" ||
            flashAttentionPolicy !in setOf("AUTO", "ENABLED", "DISABLED") ||
            kvCacheType == "Q8_0" && flashAttentionPolicy == "DISABLED")
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
    }

    internal fun cacheIdentity(): String = buildString {
        append("$gpuLayers/$batchTokens/$microBatchTokens/$compactSwa")
        if (kvCacheType != "F16") append("/kv=$kvCacheType")
        if (flashAttentionPolicy != "AUTO") append("/fa=$flashAttentionPolicy")
        if (qualcommCompatibility) append("/qualcomm-compat")
        if (nativeCpuWeights) append("/cpu-native-weights")
    }

    fun render(): String = "Réglages demandés : couches GPU = ${if (gpuLayers == -1) "toutes" else gpuLayers}; " +
        "lot = $batchTokens tokens; micro-lot = $microBatchTokens tokens; " +
        "cache d’attention locale compact = ${if (compactSwa) "oui (expérimental)" else "non"}; " +
        "cache d’attention K/V = $kvCacheType${if (kvCacheType == "Q8_0") " (8 bits, expérimental)" else " (16 bits)"}. " +
        "Politique Flash Attention demandée = $flashAttentionPolicy ; compatibilité Vulkan Qualcomm demandée = ${if (qualcommCompatibility) "oui (expérimental)" else "non"}. " +
        "Poids CPU natifs demandés = ${if (nativeCpuWeights) "oui" else "non"}. " +
        "Le cache en 8 bits conserve les poids mais modifie la précision des états d’attention ; " +
        "sa compatibilité et sa fidélité doivent être vérifiées. " +
        "Le mode CPU conserve ses couches sur CPU. Le contexte et la limite de sortie restent inchangés."
}
