package fr.soustitresvoix.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufMemoryTelemetryTest {
    private fun row(device: String = "VULKAN") = JSONObject().put("device", device)
        .put("modelBytes", 1024).put("contextBytes", 512).put("computeBytes", 256)
    private fun sample() = JSONObject().put("stage", "BEFORE_PREFILL").put("residentBytes", 2048)
        .put("peakRssBytes", 4096).put("availableRamBytes", 8192)
    private fun measured() = JSONObject().put("memoryEstimateAvailable", true)
        .put("memoryEstimate", JSONArray().put(row())).put("memoryBreakdownAvailable", true)
        .put("memoryBreakdown", JSONArray().put(row("HOST")))
        .put("memorySamples", JSONArray().put(sample()))
        .put("openClMemory", JSONObject().apply {
            for (name in listOf("scratchCurrentBytes", "scratchPeakBytes", "uploadPeakBytes", "allocationFailures",
                "hostFallbacks", "poolEntries", "poolBytes", "skippedInitialBytes")) put(name, 1)
        }).put("vulkanMemory", JSONObject().put("observed", true).apply {
            for (name in listOf("allocatedBytes", "peakAllocatedBytes", "allocations", "allocationFailures",
                "largestAllocationBytes", "importedBytes", "hostVisibleBytes")) put(name, 1)
        })

    @Test fun boundedNativeAccountingRoundTripsWithoutBecomingPhysicalSavingsOrExecutionProof() {
        val telemetry = requireNotNull(LocalGgufMemoryTelemetry.parse(measured()))
        val restored = requireNotNull(LocalGgufMemoryTelemetry.parse(telemetry.toJson()))
        assertEquals(telemetry.estimates, restored.estimates)
        assertEquals(telemetry.breakdown, restored.breakdown)
        assertEquals(telemetry.samples, restored.samples)
        assertEquals(telemetry.openCl, restored.openCl)
        assertEquals(telemetry.vulkan, restored.vulkan)
        val report = restored.render()
        assertTrue(report.contains("ne garantit pas que le modèle tienne"))
        assertTrue(report.contains("ne mesure pas une économie de RAM physique"))
        assertTrue(report.contains("ne pas les additionner"))
        assertFalse(report.contains("réellement observé : oui"))
    }

    @Test fun oversizedUntrustedOrContradictoryAccountingIsRejected() {
        val bad = listOf(
            measured().put("memorySamples", JSONArray().apply { repeat(9) { put(sample()) } }),
            measured().put("memoryEstimate", JSONArray().apply { repeat(7) { put(row()) } }),
            measured().put("memoryEstimate", JSONArray().put(row("/private/model.gguf"))),
            measured().put("memoryEstimateAvailable", false),
            measured().put("memoryBreakdownAvailable", "true"),
            measured().put("memorySamples", JSONArray().put(sample().put("stage", "private subtitle text"))),
            measured().put("memorySamples", JSONArray().put(sample().put("residentBytes", "2048"))),
            measured().put("memorySamples", JSONArray().put(sample().put("peakRssBytes", Long.MAX_VALUE))),
            measured().apply { getJSONObject("openClMemory").put("poolBytes", -1) },
            measured().apply { getJSONObject("vulkanMemory").put("observed", false) })
        bad.forEach {
            try { LocalGgufMemoryTelemetry.parse(it); fail("Unbounded or contradictory memory accounting accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun absentAndUnknownSamplesKeepTheirHistoricalMeaning() {
        assertNull(LocalGgufMemoryTelemetry.parse(JSONObject()))
        val observation = measured().put("memorySamples", JSONArray().put(sample().put("residentBytes", -1).put("availableRamBytes", -1)))
        assertTrue(requireNotNull(LocalGgufMemoryTelemetry.parse(observation)).render().contains("RSS inconnu"))
    }
    @Test fun vulkanExtensionBudgetIsDistinctFromAllocatorCountersAndUnknownWithoutExtension() {
        val extension = JSONObject().put("observed", true).put("extension", true).put("budgetBytes", 4096)
            .put("heapUsageBytes", 1024).put("freeBytes", 3072).put("heapCount", 1)
        val observation = requireNotNull(LocalGgufMemoryTelemetry.parse(measured().put("vulkanBudget", extension)))
        assertEquals(3072L, observation.vulkanBudget?.freeBytes)
        assertTrue(observation.render().contains("Budget Vulkan EXT estimé"))
        assertEquals(observation.vulkanBudget, LocalGgufMemoryTelemetry.parse(observation.toJson())?.vulkanBudget)
        val absent = JSONObject().put("observed", true).put("extension", false).put("budgetBytes", 0)
            .put("heapUsageBytes", 0).put("freeBytes", 0).put("heapCount", 0)
        assertTrue(requireNotNull(LocalGgufMemoryTelemetry.parse(measured().put("vulkanBudget", absent))).render()
            .contains("budget et mémoire libre du pilote inconnus"))
        try { LocalGgufMemoryTelemetry.parse(measured().put("vulkanBudget", absent.put("freeBytes", 10)))
            fail("A heap size or invented free value was exposed without the extension") }
        catch (_: IllegalArgumentException) { }
    }

}
