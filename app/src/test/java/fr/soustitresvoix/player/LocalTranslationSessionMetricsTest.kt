package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class LocalTranslationSessionMetricsTest {
    @Test fun fragmentsFailuresAndCacheHitsPreserveSeparateOccurrenceAndNativeCounts() {
        var now = 1_000_000_000L
        val metrics = LocalTranslationSessionMetrics { now }
        assertEquals(0L, metrics.snapshot().elapsedMs)
        metrics.record(LocalTranslationPerformance(phase = "load", loadMs = 30))
        metrics.nativeCallStarted()
        val first = LocalTranslationPerformance(loadMs = 30, promptTokens = 100,
            promptEvaluatedTokens = 80, cachedPromptTokens = 20, generatedTokens = 10,
            promptMs = 8, generationMs = 20, npuObserved = true, npuGraphNodes = 5991)
        metrics.record(first)
        // A failed native attempt has no returned timing or token measurements.
        metrics.nativeCallStarted()
        metrics.nativeCallStarted()
        val last = first.copy(promptTokens = 50, promptEvaluatedTokens = 20, cachedPromptTokens = 30,
            generatedTokens = 5, promptMs = 4, generationMs = 10, npuObserved = false,
            npuGraphNodes = 999, gpuObserved = true, gpuGraphNodes = 70)
        metrics.record(last)
        metrics.completedOccurrence()
        val completed = metrics.record(last.copy(phase = "occurrence"))
        assertEquals(1L, completed.session!!.completedOccurrences)
        metrics.cachedOccurrence()
        now += 250_000_000
        val cached = metrics.record(LocalTranslationPerformance(phase = "cache"))
        val totals = cached.session!!
        assertEquals(250L, totals.elapsedMs)
        assertEquals(1L, totals.loads)
        assertEquals(30L, totals.loadMs)
        assertEquals(2L, totals.completedOccurrences)
        assertEquals(1L, totals.exactCacheHits)
        assertEquals(3L, totals.nativeCalls)
        assertEquals(2L, totals.measuredNativeCalls)
        assertEquals(150L, totals.promptTokens)
        assertEquals(100L, totals.promptEvaluatedTokens)
        assertEquals(50L, totals.cachedPromptTokens)
        assertEquals(15L, totals.generatedTokens)
        assertEquals(12L, totals.promptMs)
        assertEquals(30L, totals.generationMs)
        assertEquals(1L, totals.npuObservedCalls)
        assertEquals(5991L, totals.npuGraphNodes)
        assertEquals(1L, totals.gpuObservedCalls)
        assertEquals(70L, totals.gpuGraphNodes)
        val report = cached.render()
        assertTrue(report.contains("Aucun nouveau calcul"))
        assertTrue(report.contains("Cumul de cette séance"))
        assertTrue(report.contains("appels natifs tentés : 3"))
        assertTrue(report.contains("essais sans réponse mesurée"))
    }

    @Test fun progressAndOccurrenceRefreshDoNotInventInferenceOrSessionRssPeak() {
        val metrics = LocalTranslationSessionMetrics { 100L }
        val observation = LocalTranslationPerformance(phase = "progress", loadMs = 50,
            promptTokens = 200, generatedTokens = 100, npuObserved = true,
            npuGraphNodes = 6000, peakRssBytes = 10_000, currentRssBytes = 200,
            prefillResidentBytes = 300, workspaceBytes = 4096)
        metrics.record(observation)
        val totals = metrics.record(observation.copy(phase = "occurrence", currentRssBytes = 250)).session!!
        assertEquals(0L, totals.loads)
        assertEquals(0L, totals.loadMs)
        assertEquals(0L, totals.nativeCalls)
        assertEquals(0L, totals.measuredNativeCalls)
        assertEquals(0L, totals.npuObservedCalls)
        assertEquals(0L, totals.npuGraphNodes)
        assertEquals(0L, totals.promptTokens)
        assertEquals(300L, totals.peakProcessRssBytes)
        assertEquals(4096L, totals.peakWorkspaceBytes)
        assertTrue(totals.render().contains("comptabilisés séparément de la RSS"))
    }

    @Test fun fallbackLoadIsCountedOnceAndExpiredWindowsCountReturnedCallsOnly() {
        val metrics = LocalTranslationSessionMetrics()
        metrics.record(LocalTranslationPerformance(phase = "load", loadMs = 20))
        metrics.record(LocalTranslationPerformance(phase = "fallback", loadMs = 20))
        metrics.record(LocalTranslationPerformance(phase = "load", loadMs = 40))
        metrics.nativeCallStarted()
        val generation = LocalTranslationPerformance(cacheResetReason = "WINDOW_EXPIRED", loadMs = 60)
        metrics.record(generation)
        metrics.completedOccurrence()
        val totals = metrics.record(generation.copy(phase = "occurrence")).session!!
        assertEquals(2L, totals.loads)
        assertEquals(60L, totals.loadMs)
        assertEquals(1L, totals.nativeCalls)
        assertEquals(1L, totals.measuredNativeCalls)
        assertEquals(1L, totals.windowExpiredCalls)
        assertEquals(1L, totals.completedOccurrences)
    }
}
