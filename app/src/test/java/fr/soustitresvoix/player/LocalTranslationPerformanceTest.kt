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
}
