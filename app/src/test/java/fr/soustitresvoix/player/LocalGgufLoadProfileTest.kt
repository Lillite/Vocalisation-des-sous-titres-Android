package fr.soustitresvoix.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufLoadProfileTest {
    private fun profile() = JSONObject().put("backendInitMs", 2).put("openClPrepareMs", 3)
        .put("modelLoadMs", 30).put("contextInitMs", 20).put("openTotalMs", 60).put("loadAttempts", 2)

    @Test fun onlyMeasuredCompleteIntegralProfilesAreAccepted() {
        assertNull(LocalGgufLoadProfile.fromResponse(JSONObject()))
        val value = requireNotNull(LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", profile())))
        assertEquals(LocalGgufLoadProfile(2, 3, 30, 20, 60, 2), value)
        assertEquals(LocalGgufLoadProfile(0, 0, 0, 0, 0, 1),
            LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", JSONObject()
                .put("backendInitMs", 0).put("openClPrepareMs", 0).put("modelLoadMs", 0)
                .put("contextInitMs", 0).put("openTotalMs", 0).put("loadAttempts", 1))))

        val invalid = mutableListOf<Any>(JSONObject.NULL, true, "not a measured profile", 7)
        invalid += profile().apply { remove("contextInitMs") }
        invalid += profile().put("privatePath", "/private/must-not-be-rendered.gguf")
        for (field in listOf("backendInitMs", "openClPrepareMs", "modelLoadMs", "contextInitMs", "openTotalMs", "loadAttempts")) {
            for (bad in listOf(JSONObject.NULL, "2", 2.0, true, -1, Long.MAX_VALUE))
                invalid += profile().put(field, bad)
        }
        invalid += profile().put("loadAttempts", 0)
        invalid += profile().put("loadAttempts", 3)
        invalid += profile().put("openTotalMs", 54)
        invalid += profile().put("openTotalMs", 86_400_001L)
        for (bad in invalid) {
            try { LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", bad)); fail("Invalid profile accepted") }
            catch (_: Exception) { }
        }
    }

    @Test fun optionalVulkanPreparationRemainsDistinctAndMustFitTheMeasuredTotal() {
        val measured = profile().put("openClPrepareMs", 0).put("vulkanPrepareMs", 5)
        val parsed = requireNotNull(LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", measured)))
        assertEquals(5L, parsed.vulkanPrepareMs)
        assertTrue(parsed.render().contains("Préparation de l’accès Vulkan : 5 ms"))
        assertEquals(0L, requireNotNull(LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", profile()))).vulkanPrepareMs)
        for (bad in listOf<Any>(JSONObject.NULL, "5", 5.0, true, -1, 9, Long.MAX_VALUE)) {
            try {
                LocalGgufLoadProfile.fromResponse(JSONObject().put("loadProfile", profile().put("vulkanPrepareMs", bad)))
                fail("An invalid Vulkan preparation duration was accepted")
            } catch (_: Exception) { }
        }
    }

    @Test fun renderingKeepsLoadPhasesDistinctFromGenerationAndDoesNotSumNestedTotals() {
        val profile = LocalGgufLoadProfile(2, 3, 30, 20, 60, 2)
        val loaded = LocalTranslationPerformance(phase = "load", loadMs = 52, loadProfile = profile).render()
        assertTrue(loaded.contains("Chargement : 52 ms"))
        assertTrue(loaded.contains("Profil natif d’ouverture — total : 60 ms"))
        assertTrue(loaded.contains("Appels de chargement du modèle cumulés : 30 ms"))
        assertTrue(loaded.contains("Créations du contexte cumulées : 20 ms"))
        assertTrue(loaded.contains("Tentatives de chargement : 2"))
        assertTrue(loaded.contains("ne pas lui additionner"))
        assertTrue(loaded.contains("ne sont pas chronométrés séparément"))
        assertFalse(LocalTranslationPerformance(phase = "generation", loadProfile = profile).render()
            .contains("Profil natif d’ouverture"))
        assertFalse(LocalTranslationPerformance(phase = "load").render().contains("Profil natif d’ouverture"))
    }
}
