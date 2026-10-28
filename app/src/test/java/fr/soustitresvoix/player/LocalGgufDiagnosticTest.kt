package fr.soustitresvoix.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufDiagnosticTest {
    private fun sample() = JSONObject().put("stage", "OPENCL_ICD").put("reason", "ICD_NO_PLATFORM")
        .put("elapsedMs", 50).put("openClStatus", -1001).put("directPlatforms", 1)
        .put("icdPlatforms", 0).put("gpuDevices", -1).put("probeCached", true)

    @Test fun onlyKnownCodesAndMeasurementsRoundTripWithoutExtraText() {
        val source = sample().put("privatePath", "/private/synthetic-model.gguf")
            .put("missingSymbol", "/private/synthetic-symbol-not-an-ICD-measurement")
            .put("output", "Synthetic subtitle that must not be copied into the diagnostic.")
        val value = requireNotNull(LocalGgufDiagnostic.parse(source))
        assertEquals(LocalGgufDiagnostic.Stage.OPENCL_ICD, value.stage)
        assertEquals(LocalGgufDiagnostic.Reason.ICD_NO_PLATFORM, value.reason)
        assertEquals(50L, value.elapsedMs)
        assertEquals(-1001, value.openClStatus)
        assertEquals(1, value.directPlatforms)
        assertEquals(0, value.icdPlatforms)
        assertEquals(-1, value.gpuDevices)
        assertTrue(value.probeCached)
        assertNull(value.missingSymbol)
        assertFalse(value.toJson().has("missingSymbol"))
        val restored = requireNotNull(LocalGgufDiagnostic.parse(value.toJson()))
        assertEquals(value.render(), restored.render())
        assertTrue(restored.render().contains("déjà mémorisé"))
        assertTrue(restored.render().contains("ICD_NO_PLATFORM"))
        for (text in listOf(value.render(), value.toJson().toString(), value.toString())) {
            assertFalse(text.contains("synthetic-model"))
            assertFalse(text.contains("Synthetic subtitle"))
            assertFalse(text.contains("privatePath"))
            assertFalse(text.contains("synthetic-symbol"))
        }
    }

    @Test fun malformedOptionalDetailsAreDiscardedWhileTheFailureCodeRemainsVisible() {
        val invalid = listOf(
            sample().put("stage", "/private/synthetic-secret"),
            sample().put("reason", "Synthetic subtitle"),
            sample().put("elapsedMs", -1),
            sample().put("elapsedMs", 86_400_001),
            sample().put("elapsedMs", "50"),
            sample().put("openClStatus", "-1001"),
            sample().put("openClStatus", Long.MAX_VALUE),
            sample().put("gpuDevices", -2),
            sample().put("directPlatforms", 65_536),
            sample().put("icdPlatforms", 1.5),
            sample().put("probeCached", "true"),
            JSONObject().put("stage", "OPENCL_ICD"))
        for (diagnostic in invalid) {
            try {
                LocalGgufRuntime.response(JSONObject().put("code", "BACKEND").put("diagnostic", diagnostic).toString())
                fail("A failed response became successful")
            } catch (failure: LocalGgufRuntime.Failure) {
                assertEquals(LocalGgufRuntime.Code.BACKEND, failure.code)
                assertNull(failure.diagnostic)
                assertFalse(failure.message.orEmpty().contains("Synthetic"))
                assertFalse(failure.message.orEmpty().contains("private"))
            }
        }
    }

    @Test fun aPreservedFallbackDiagnosticDoesNotClaimActualGpuExecution() {
        val diagnostic = sample().put("openClStatus", JSONObject.NULL).put("probeCached", false)
        val json = JSONObject().put("code", "OK").put("output", "Réponse synthétique.")
            .put("promptTokens", 4).put("generatedTokens", 2).put("stopReason", "end_of_generation")
            .put("promptMs", 5).put("generationMs", 6).put("peakRssBytes", 1000)
            .put("backend", "CPU").put("requestedMode", "AUTO").put("diagnostic", diagnostic)
        val generation = LocalGgufRuntime.generation(json.toString())
        assertEquals("CPU", generation.backend)
        assertEquals("AUTO", generation.requestedMode)
        assertFalse(generation.gpuObserved)
        assertEquals("", generation.openClTransport)
        assertEquals(0L, generation.gpuGraphNodes)
        assertNull(requireNotNull(generation.diagnostic).openClStatus)
        assertFalse(requireNotNull(generation.diagnostic).render().contains("déjà mémorisé"))
    }

    @Test fun missingDirectFunctionsHaveAFixedLocalizedReasonWithoutDriverText() {
        val source = sample().put("stage", "OPENCL_DIRECT").put("reason", "DIRECT_SYMBOLS_MISSING")
            .put("icdPlatforms", -1).put("openClStatus", JSONObject.NULL).put("probeCached", false)
            .put("missingSymbol", "clCreateBuffer")
            .put("driverError", "/private/synthetic-driver.so: confidential synthetic error")
        val diagnostic = requireNotNull(LocalGgufDiagnostic.parse(source))
        assertEquals(LocalGgufDiagnostic.Stage.OPENCL_DIRECT, diagnostic.stage)
        assertEquals(LocalGgufDiagnostic.Reason.DIRECT_SYMBOLS_MISSING, diagnostic.reason)
        assertEquals(-1, diagnostic.icdPlatforms)
        assertEquals("clCreateBuffer", diagnostic.missingSymbol)
        assertTrue(diagnostic.render().contains("fonctions OpenCL nécessaires au calcul direct"))
        assertTrue(diagnostic.render().contains("DIRECT_SYMBOLS_MISSING"))
        assertTrue(diagnostic.render().contains("Fonction OpenCL requise absente : clCreateBuffer."))
        assertEquals("clCreateBuffer", diagnostic.toJson().getString("missingSymbol"))
        assertEquals(diagnostic.render(), requireNotNull(LocalGgufDiagnostic.parse(diagnostic.toJson())).render())
        assertFalse(diagnostic.render().contains("synthetic-driver"))
        assertFalse(diagnostic.toJson().toString().contains("confidential"))
        for (value in listOf("/private/synthetic-driver.so", "clSyntheticPrivateValue", "clGetKernelSubGroupInfo", 7, true)) {
            assertNull(LocalGgufDiagnostic.parse(JSONObject(source.toString()).put("missingSymbol", value)))
        }
        for (legacy in listOf(JSONObject(source.toString()).apply { remove("missingSymbol") },
            JSONObject(source.toString()).put("missingSymbol", JSONObject.NULL))) {
            val unknown = requireNotNull(LocalGgufDiagnostic.parse(legacy))
            assertNull(unknown.missingSymbol)
            assertFalse(unknown.render().contains("clCreateBuffer"))
            assertFalse(unknown.toJson().has("missingSymbol"))
        }
    }
}
