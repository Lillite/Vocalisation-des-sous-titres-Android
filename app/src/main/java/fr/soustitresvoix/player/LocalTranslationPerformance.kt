package fr.soustitresvoix.player

import java.util.Locale

/** Measurements contain no subtitle text, model path, URI or chat history. */
data class LocalTranslationPerformance(
    val phase: String = "generation",
    val requestedMode: String = "CPU",
    val backend: String = "",
    val device: String = "",
    val gpuObserved: Boolean = false,
    val loadMs: Long = 0,
    val promptMs: Long = 0,
    val generationMs: Long = 0,
    val endToEndMs: Long = 0,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    val promptEvaluatedTokens: Int = 0,
    val peakRssBytes: Long = 0,
    val currentRssBytes: Long = 0,
    val availableRamBytes: Long = 0,
    val gpuGraphNodes: Long = 0,
    val fallbackReason: String = "",
    val diagnostic: LocalGgufDiagnostic? = null,
    val openClTransport: String = "",
    val loadProfile: LocalGgufLoadProfile? = null,
    val batchTokens: Int = 0,
    val microBatchTokens: Int = 0,
    val compactSwa: Boolean? = null,
    val requestedGpuLayers: Int? = null,
    val actualGpuLayers: Int? = null,
    val attemptReport: String = "",
    val processId: Int = 0,
    val progress: LocalGgufProgress? = null,
    val cacheTypeK: String = "",
    val cacheTypeV: String = "",
    val flashAttentionPolicy: String = "",
    val startResidentBytes: Long = -1,
    val prefillResidentBytes: Long = -1,
    val cacheResetReason: String = "",
    val cacheResets: Long = 0,
    val workspaceGrowths: Long = 0,
    val workspaceBytes: Long = 0,
    val npuObserved: Boolean = false,
    val npuGraphNodes: Long = 0,
    val requestedNpuLayers: Int? = null,
    val actualNpuLayers: Int? = null,
    val qualcommCompatibility: Boolean = false,
    val nativeCpuWeights: Boolean = false,
    val memoryReserveBytes: Long = 0,
    val observedPrefillExtraBytes: Long = 0,
    val memoryTelemetry: LocalGgufMemoryTelemetry? = null
) {
    /** Throughput of the last native call, not an aggregate of split/retried fragments. */
    val tokensPerSecond: Double get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    fun render(): String = buildString {
        if (phase == "cache") {
            append("Traduction réutilisée depuis le cache exact de requêtes. Aucun nouveau calcul CPU, GPU ou Hexagon, chargement, débit ou temps de génération n’est mesuré pour cette occurrence.")
            return@buildString
        }
        append("Calcul demandé : "); append(requestedMode)
        if (backend.isNotEmpty()) { append(" ; moteur sélectionné : "); append(backend) }
        if (device.isNotEmpty()) { append(" ; appareil de calcul : "); append(safe(device)) }
        if (backend == "OPENCL" && openClTransport == "DIRECT")
            append(".\nAccès au pilote OpenCL : direct, sans chargeur ICD")
        append(".\nGPU réellement observé : "); append(if (gpuObserved) "oui" else "non établi")
        if (gpuObserved && gpuGraphNodes > 0) { append(" ("); append(gpuGraphNodes); append(" nœuds du graphe)") }
        if (requestedMode == "HEXAGON" || backend == "HEXAGON") {
            append(".\nNPU Hexagon réellement observé : "); append(if (npuObserved) "oui" else "non établi")
            if (npuObserved && npuGraphNodes > 0) { append(" ("); append(npuGraphNodes); append(" nœuds du graphe)") }
            append(". Des opérateurs peuvent aussi être exécutés sur CPU ; cette mesure ne prouve pas une exécution intégrale sur le NPU.")
        }
        if (phase == "load") {
            append(".\nChargement : "); append(loadMs); append(" ms")
            loadProfile?.let { append('\n'); append(it.render()) }
        }
        if (phase == "load") {
            if (batchTokens > 0 && microBatchTokens > 0) {
                append("\nBlocs de calcul observés : lot "); append(batchTokens)
                append(" ; micro-lot "); append(microBatchTokens); append(" tokens.")
            }
            compactSwa?.let { append("\nAllocation compacte du cache d’attention locale demandée : "); append(if (it) "oui" else "non") }
            requestedNpuLayers?.let { append("\nCouches NPU demandées : "); append(if (it == -1) "toutes" else it.toString()) }
            actualNpuLayers?.let { append(" ; couches NPU chargées observées : "); append(if (it < 0) "non établies" else it.toString()); append('.') }
            requestedGpuLayers?.let { append("\nCouches GPU demandées : "); append(if (it == -1) "toutes" else it.toString()) }
            actualGpuLayers?.let { append(" ; couches GPU chargées observées : "); append(if (it < 0) "non établies" else it.toString()); append('.') }
        }
        if (phase == "generation") {
            append(".\nMesures du dernier appel natif ; une occurrence peut nécessiter plusieurs appels.")
            append("\nEntrée : "); append(promptMs); append(" ms ; "); append(promptEvaluatedTokens)
            append(" tokens calculés, "); append(cachedPromptTokens); append(" réutilisés sur "); append(promptTokens)
            append(".\nSortie : "); append(generationMs); append(" ms ; "); append(generatedTokens); append(" tokens")
            if (generationMs > 0) { append(" ; "); append(String.format(Locale.FRANCE, "%.2f tokens/s", tokensPerSecond)) }
            append(".\nTemps écoulé depuis le début de l’occurrence, essais compris : "); append(endToEndMs); append(" ms")
        }
        if (cacheTypeK.isNotEmpty() && cacheTypeV.isNotEmpty()) {
            append("\nTypes du cache K/V : "); append(cacheTypeK); append('/'); append(cacheTypeV)
            if (flashAttentionPolicy.isNotEmpty()) {
                append(" ; politique Flash Attention : "); append(flashAttentionPolicy)
                append(" (la politique demandée ne prouve pas son exécution effective)")
            }
        }
        if (phase == "generation" && cacheResetReason.isNotEmpty()) {
            append("\nÉtat du préfixe : "); append(cacheResetReason)
            append(" ; réinitialisations cumulées : "); append(cacheResets)
            append(". Buffers texte/tokens réutilisables : "); append(workspaceBytes)
            append(" octets ; agrandissements cumulés : "); append(workspaceGrowths)
            append(". Ces buffers ne représentent pas toute la mémoire du moteur.")
        }
        if (phase == "generation" && startResidentBytes >= 0 && prefillResidentBytes >= 0) {
            append("\nRSS au début de l’appel : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", startResidentBytes / 1_073_741_824.0))
            append(" ; après traitement de l’entrée : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", prefillResidentBytes / 1_073_741_824.0))
        }
        if (requestedMode == "VULKAN" || qualcommCompatibility) {
            append("\nProfil de compatibilité Vulkan Qualcomm demandé : ")
            append(if (qualcommCompatibility) "oui (expérimental)" else "non")
            append(" ; cette politique ne prouve pas l’exécution d’un shader particulier.")
        }
        if (nativeCpuWeights) append("\nPoids CPU natifs demandés : oui ; les conversions accélérées de poids CPU sont désactivées.")
        if (memoryReserveBytes > 0) { append("\nRéserve mémoire indicative avant calcul : "); append(memoryReserveBytes); append(" octets ; ce n’est pas une limite universelle d’Android.") }
        if (observedPrefillExtraBytes > 0) { append("\nPlus grand supplément RSS de préfill observé : "); append(observedPrefillExtraBytes); append(" octets ; ce relevé ponctuel ne mesure pas le pic exact.") }
        memoryTelemetry?.let { append(it.render()) }
        progress?.let { append('\n'); append(it.render()) }
        if (currentRssBytes > 0) {
            append(".\nMémoire résidente du processus : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", currentRssBytes / 1_073_741_824.0))
        }
        if (peakRssBytes > 0) {
            append(" ; pic depuis le démarrage : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", peakRssBytes / 1_073_741_824.0))
        }
        if (availableRamBytes > 0) {
            append(". RAM disponible au relevé : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", availableRamBytes / 1_073_741_824.0))
        }
        if (fallbackReason.isNotEmpty()) { append(".\nRepli de calcul : "); append(fallbackDescription()) }
        diagnostic?.let { append('\n'); append(it.render()) }
        if (attemptReport.isNotEmpty()) { append('\n'); append(attemptReport) }
    }

    private fun fallbackDescription(): String = when (fallbackReason) {
        "OPENCL_RAM_BUDGET" -> "CPU utilisé : la RAM disponible estimée ne laisse pas la marge prévue pour charger entièrement le modèle sur GPU. Ce relevé n’est pas un échec d’allocation."
        "OPENCL_RAM_UNKNOWN" -> "CPU utilisé : la RAM disponible n’a pas pu être estimée pour préparer le chargement GPU."
        "OPENCL_UNAVAILABLE" -> "CPU utilisé : aucun pilote OpenCL ou GPU compatible n’a pu être préparé."
        "OPENCL_MODEL_LOAD_FAILED" -> "CPU utilisé après un échec de chargement du modèle sur GPU."
        "OPENCL_CONTEXT_FAILED" -> "CPU utilisé après un échec de préparation du contexte GPU."
        "OPENCL_NO_COMPUTE" -> "CPU observé : aucun calcul GPU n’a été confirmé pour cet appel."
        else -> safe(fallbackReason)
    }

    private fun safe(value: String) = value.filter { it.code >= 32 && it != '\u007f' }.take(300)
}

interface LocalTranslationPerformanceSource {
    /** Called from the translation worker; a UI consumer must post to its main thread. */
    fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?)
}
