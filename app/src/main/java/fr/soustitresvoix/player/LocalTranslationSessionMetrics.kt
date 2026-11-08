package fr.soustitresvoix.player

import java.util.Locale

/** Counts only this engine's current session; no subtitle or model identity is retained. */
data class LocalTranslationSessionSummary(
    val elapsedMs: Long = 0,
    val loads: Long = 0,
    val loadMs: Long = 0,
    val completedOccurrences: Long = 0,
    val exactCacheHits: Long = 0,
    val nativeCalls: Long = 0,
    val measuredNativeCalls: Long = 0,
    val promptTokens: Long = 0,
    val promptEvaluatedTokens: Long = 0,
    val cachedPromptTokens: Long = 0,
    val generatedTokens: Long = 0,
    val promptMs: Long = 0,
    val generationMs: Long = 0,
    val npuObservedCalls: Long = 0,
    val npuGraphNodes: Long = 0,
    val gpuObservedCalls: Long = 0,
    val gpuGraphNodes: Long = 0,
    val windowExpiredCalls: Long = 0,
    val peakProcessRssBytes: Long = 0,
    val peakWorkspaceBytes: Long = 0
) {
    fun render(): String = buildString {
        append("Cumul de cette séance du moteur, depuis sa première activité : ")
        append(elapsedMs); append(" ms écoulées ; "); append(loads)
        append(" chargement(s) mesuré(s), "); append(loadMs); append(" ms de chargement.")
        append("\nOccurrences terminées : "); append(completedOccurrences)
        append(" ; réutilisations du cache exact : "); append(exactCacheHits)
        append(" ; appels natifs tentés : "); append(nativeCalls)
        append(" ; réponses natives mesurées : "); append(measuredNativeCalls); append('.')
        append("\nEntrée cumulée des réponses mesurées : "); append(promptTokens)
        append(" tokens, dont "); append(promptEvaluatedTokens); append(" calculés et ")
        append(cachedPromptTokens); append(" réutilisés dans le cache de préfixe ; ")
        append(promptMs); append(" ms de traitement d’entrée.")
        append("\nSortie cumulée des réponses mesurées : "); append(generatedTokens)
        append(" tokens ; "); append(generationMs); append(" ms de génération")
        if (generationMs > 0) {
            append(" ; "); append(String.format(Locale.FRANCE, "%.2f tokens/s", generatedTokens * 1000.0 / generationMs))
        }
        append(". Les essais sans réponse mesurée ne fournissent aucun total de tokens ; les fragments d’une même occurrence restent des appels distincts.")
        append("\nNPU Hexagon observé sur "); append(npuObservedCalls)
        append(" réponse(s) ; somme des nœuds des graphes observés : "); append(npuGraphNodes)
        append(". GPU observé sur "); append(gpuObservedCalls)
        append(" réponse(s) ; somme des nœuds des graphes observés : "); append(gpuGraphNodes)
        append(". Ces observations cumulées ne prouvent pas une exécution intégrale sur un accélérateur.")
        if (windowExpiredCalls > 0) {
            append("\nRéponses avec préfixe réinitialisé WINDOW_EXPIRED : "); append(windowExpiredCalls); append('.')
        }
        if (peakProcessRssBytes > 0) {
            append("\nPlus haute RSS de processus relevée pendant cette séance : ")
            append(String.format(Locale.FRANCE, "%.2f Gio", peakProcessRssBytes / 1_073_741_824.0))
            append(". Ce relevé n’est pas la mémoire totale des pilotes, du GPU ou du NPU.")
        }
        if (peakWorkspaceBytes > 0) {
            append("\nPlus grands buffers texte/tokens réutilisables relevés : ")
            append(peakWorkspaceBytes); append(" octets, comptabilisés séparément de la RSS.")
        }
    }
}

/** One worker normally owns this accumulator. The lock also protects diagnostic callbacks. */
class LocalTranslationSessionMetrics(private val clockNanos: () -> Long = System::nanoTime) {
    private var startedNanos: Long? = null
    private var totals = LocalTranslationSessionSummary()

    private fun begin() { if (startedNanos == null) startedNanos = clockNanos() }

    @Synchronized fun nativeCallStarted() {
        begin()
        totals = totals.copy(nativeCalls = totals.nativeCalls + 1)
    }

    @Synchronized fun completedOccurrence() {
        begin()
        totals = totals.copy(completedOccurrences = totals.completedOccurrences + 1)
    }

    @Synchronized fun cachedOccurrence() {
        begin()
        totals = totals.copy(completedOccurrences = totals.completedOccurrences + 1,
            exactCacheHits = totals.exactCacheHits + 1)
    }

    /** Generation is recorded once per returned native response, never again on phase occurrence. */
    @Synchronized fun record(value: LocalTranslationPerformance): LocalTranslationPerformance {
        begin()
        if (value.phase == "load") totals = totals.copy(loads = totals.loads + 1,
            loadMs = totals.loadMs + value.loadMs.coerceAtLeast(0))
        if (value.phase == "generation") totals = totals.copy(
            measuredNativeCalls = totals.measuredNativeCalls + 1,
            promptTokens = totals.promptTokens + value.promptTokens.coerceAtLeast(0),
            promptEvaluatedTokens = totals.promptEvaluatedTokens + value.promptEvaluatedTokens.coerceAtLeast(0),
            cachedPromptTokens = totals.cachedPromptTokens + value.cachedPromptTokens.coerceAtLeast(0),
            generatedTokens = totals.generatedTokens + value.generatedTokens.coerceAtLeast(0),
            promptMs = totals.promptMs + value.promptMs.coerceAtLeast(0),
            generationMs = totals.generationMs + value.generationMs.coerceAtLeast(0),
            npuObservedCalls = totals.npuObservedCalls + if (value.npuObserved) 1 else 0,
            npuGraphNodes = totals.npuGraphNodes + if (value.npuObserved) value.npuGraphNodes.coerceAtLeast(0) else 0,
            gpuObservedCalls = totals.gpuObservedCalls + if (value.gpuObserved) 1 else 0,
            gpuGraphNodes = totals.gpuGraphNodes + if (value.gpuObserved) value.gpuGraphNodes.coerceAtLeast(0) else 0,
            windowExpiredCalls = totals.windowExpiredCalls + if (value.cacheResetReason == "WINDOW_EXPIRED") 1 else 0)
        // Native peakRssBytes is process-lifetime high water, so it must not be
        // relabelled as a peak observed during this translation session.
        totals = totals.copy(peakProcessRssBytes = maxOf(totals.peakProcessRssBytes,
            value.currentRssBytes, value.startResidentBytes, value.prefillResidentBytes),
            peakWorkspaceBytes = maxOf(totals.peakWorkspaceBytes, value.workspaceBytes))
        return value.copy(session = snapshot())
    }

    @Synchronized fun snapshot(): LocalTranslationSessionSummary = totals.copy(
        elapsedMs = startedNanos?.let { ((clockNanos() - it) / 1_000_000).coerceAtLeast(0) } ?: 0)
}
