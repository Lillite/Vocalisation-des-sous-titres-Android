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
            default.copy(batchTokens = 128, microBatchTokens = 32, compactSwa = true),
            default.copy(kvCacheType = "Q8_0")).forEach { it.validate() }
        listOf(default.copy(gpuLayers = -2), default.copy(gpuLayers = 0), default.copy(gpuLayers = 1025),
            default.copy(batchTokens = 15, microBatchTokens = 16), default.copy(batchTokens = 513),
            default.copy(microBatchTokens = 15), default.copy(microBatchTokens = 129),
            default.copy(batchTokens = 32, microBatchTokens = 64),
            default.copy(kvCacheType = ""), default.copy(kvCacheType = "Q4_0"),
            default.copy(kvCacheType = "q8_0"), default.copy(kvCacheType = " Q8_0")).forEach { settings ->
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
            default.copy(microBatchTokens = 32), default.copy(compactSwa = true),
            default.copy(kvCacheType = "Q8_0")).map { key(it) }
        assertEquals(keys.size, keys.distinct().size)
        assertNotEquals(key(default), key(default, "VULKAN"))
        assertNotEquals(key(default, "CPU"), key(default, "VULKAN"))
        assertEquals(2048, LocalGgufTranslationEngine.CONTEXT_TOKENS)
        assertEquals(512, LocalGgufTranslationEngine.OUTPUT_TOKENS)
    }

    @Test fun f16KeepsHistoricalIdentitiesAndQuantizedCacheRequiresAnExplicitChoice() {
        val default = LocalGgufLoadSettings()
        assertEquals("F16", default.kvCacheType)
        assertEquals("-1/512/128/false", default.cacheIdentity())
        val existing = LocalGgufLoadSettings(8, 128, 32, true)
        assertEquals("8/128/32/true", existing.cacheIdentity())
        assertFalse(existing.isDefault)
        val quantized = existing.copy(kvCacheType = "Q8_0")
        assertEquals("8/128/32/true/kv=Q8_0", quantized.cacheIdentity())
        assertFalse(default.copy(kvCacheType = "Q8_0").isDefault)
        assertEquals(existing, quantized.copy(kvCacheType = "F16"))
        assertTrue(default.render().contains("F16"))
        assertTrue(quantized.render().contains("modifie la précision"))
        assertTrue(quantized.render().contains("poids"))
    }
    @Test fun explicitFlashCompatibilityAndNativeCpuWeightsKeepHistoricalDefaultsDistinct() {
        val defaults = LocalGgufLoadSettings()
        assertEquals("-1/512/128/false", defaults.cacheIdentity())
        assertTrue(defaults.isDefault)
        val choices = listOf(defaults.copy(flashAttentionPolicy = "ENABLED"),
            defaults.copy(flashAttentionPolicy = "DISABLED"), defaults.copy(qualcommCompatibility = true),
            defaults.copy(nativeCpuWeights = true))
        choices.forEach { it.validate(); assertFalse(it.isDefault); assertNotEquals(defaults.cacheIdentity(), it.cacheIdentity()) }
        assertEquals(choices.size, choices.map { it.cacheIdentity() }.distinct().size)
        defaults.copy(kvCacheType = "Q8_0", flashAttentionPolicy = "AUTO").validate()
    }

    @Test fun disabledFlashCannotSilentlyChangeAnExplicitQuantizedCache() {
        for (choice in listOf(LocalGgufLoadSettings(kvCacheType = "Q8_0", flashAttentionPolicy = "DISABLED"),
            LocalGgufLoadSettings(flashAttentionPolicy = "invented"))) {
            try { choice.validate(); fail("An incompatible explicit precision/policy was accepted") }
            catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.CONFIGURATION, failure.reason) }
        }
    }

}
