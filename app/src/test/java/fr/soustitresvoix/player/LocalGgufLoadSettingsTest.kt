package fr.soustitresvoix.player

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocalGgufLoadSettingsTest {
    @Test fun everyAllocationBoundaryIsValidatedBeforeNativeWork() {
        val default = LocalGgufLoadSettings()
        default.validate()
        assertTrue(default.isDefault)
        listOf(default.copy(gpuLayers = 1), default.copy(gpuLayers = 1024),
            default.copy(batchTokens = 16, microBatchTokens = 16),
            default.copy(batchTokens = 128, microBatchTokens = 32, compactSwa = true)).forEach { it.validate() }
        listOf(default.copy(gpuLayers = -2), default.copy(gpuLayers = 0), default.copy(gpuLayers = 1025),
            default.copy(batchTokens = 15, microBatchTokens = 16), default.copy(batchTokens = 513),
            default.copy(microBatchTokens = 15), default.copy(microBatchTokens = 129),
            default.copy(batchTokens = 32, microBatchTokens = 64)).forEach { settings ->
            try { settings.validate(); fail("Invalid allocation accepted: $settings") }
            catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.CONFIGURATION, failure.reason) }
        }
    }

    @Test fun allocationChoicesCannotReuseTranslationsWithAnotherExecutionIdentity() {
        val model = LocalGgufModel(File("synthetic.gguf"), "0".repeat(64), 100, 0)
        fun key(settings: LocalGgufLoadSettings, mode: String = "OPENCL") =
            LocalGgufTranslationEngine.identity(model, executionMode = mode, loadSettings = settings)
        val default = LocalGgufLoadSettings()
        assertEquals(LocalGgufTranslationEngine.identity(model, executionMode = "OPENCL"), key(default))
        val keys = listOf(default, default.copy(gpuLayers = 8), default.copy(batchTokens = 256),
            default.copy(microBatchTokens = 32), default.copy(compactSwa = true)).map { key(it) }
        assertEquals(keys.size, keys.distinct().size)
        assertNotEquals(key(default), key(default, "VULKAN"))
        assertNotEquals(key(default, "CPU"), key(default, "VULKAN"))
        assertEquals(2048, LocalGgufTranslationEngine.CONTEXT_TOKENS)
        assertEquals(512, LocalGgufTranslationEngine.OUTPUT_TOKENS)
    }
}
