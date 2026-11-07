package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class LocalTranslationPerformanceTest {
    @Test fun cacheObservationNeverRendersStaleGpuLoadOrGenerationMeasurements() {
        val report = LocalTranslationPerformance(phase = "cache", backend = "OPENCL", device = "stale device",
            gpuObserved = true, gpuGraphNodes = 123, loadMs = 999, generationMs = 456, generatedTokens = 10).render()
        assertTrue(report.contains("cache exact de requêtes"))
        assertTrue(report.contains("Aucun nouveau calcul"))
        for (stale in listOf("stale device", "999", "456", "123", "tokens/s", "GPU réellement observé : oui"))
            assertFalse(report.contains(stale))
    }
    @Test fun cachePolicyAndWorkspaceAreNeverPresentedAsGpuExecutionOrTotalMemory() {
        val report = LocalTranslationPerformance(cacheTypeK = "Q8_0", cacheTypeV = "Q8_0",
            flashAttentionPolicy = "ENABLED", workspaceBytes = 4096, workspaceGrowths = 2,
            cacheResetReason = "PREFIX_CHANGED", cacheResets = 3,
            startResidentBytes = 1024, prefillResidentBytes = 2048).render()
        assertTrue(report.contains("Q8_0/Q8_0"))
        assertTrue(report.contains("ne prouve pas son exécution effective"))
        assertTrue(report.contains("ne représentent pas toute la mémoire du moteur"))
        assertTrue(report.contains("GPU réellement observé : non établi"))
        assertFalse(report.contains("GPU réellement observé : oui"))
    }

}
