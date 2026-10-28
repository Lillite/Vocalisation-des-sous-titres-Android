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
    val fallbackReason: String = ""
) {
    /** Throughput of the last native call, not an aggregate of split/retried fragments. */
    val tokensPerSecond: Double get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    fun render(): String = buildString {
        append("Calcul demandé : "); append(requestedMode)
        if (backend.isNotEmpty()) { append(" ; moteur sélectionné : "); append(backend) }
        if (device.isNotEmpty()) { append(" ; appareil de calcul : "); append(safe(device)) }
        append(".\nGPU réellement observé : "); append(if (gpuObserved) "oui" else "non établi")
        if (gpuObserved && gpuGraphNodes > 0) { append(" ("); append(gpuGraphNodes); append(" nœuds du graphe)") }
        if (phase == "load") { append(".\nChargement : "); append(loadMs); append(" ms") }
        if (phase == "generation") {
            append(".\nMesures du dernier appel natif ; une occurrence peut nécessiter plusieurs appels.")
            append("\nEntrée : "); append(promptMs); append(" ms ; "); append(promptEvaluatedTokens)
            append(" tokens calculés, "); append(cachedPromptTokens); append(" réutilisés sur "); append(promptTokens)
            append(".\nSortie : "); append(generationMs); append(" ms ; "); append(generatedTokens); append(" tokens")
            if (generationMs > 0) { append(" ; "); append(String.format(Locale.FRANCE, "%.2f tokens/s", tokensPerSecond)) }
            append(".\nTemps écoulé depuis le début de l’occurrence, essais compris : "); append(endToEndMs); append(" ms")
        }
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
