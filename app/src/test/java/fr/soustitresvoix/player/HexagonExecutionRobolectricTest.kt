package fr.soustitresvoix.player

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Boundary tests use synthetic measurements; they do not assert physical NPU availability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HexagonExecutionRobolectricTest {
    private fun generation() = JSONObject().put("code", "OK").put("output", "Texte synthétique.")
        .put("promptTokens", 20).put("generatedTokens", 3).put("stopReason", "end_of_generation")
        .put("promptMs", 10).put("generationMs", 20).put("peakRssBytes", 1024)
        .put("requestedMode", "HEXAGON").put("backend", "HEXAGON")
        .put("gpuObserved", false).put("gpuGraphNodes", 0)
        .put("npuObserved", true).put("npuGraphNodes", 7)
        .put("requestedGpuLayers", 0).put("actualGpuLayers", 0)
        .put("requestedNpuLayers", -1).put("actualNpuLayers", 12)

    @Test fun hexagonRequiresItsOwnExecutionEvidenceAndNeverClaimsGpuWork() {
        val result = LocalGgufRuntime.generation(generation().toString())
        assertEquals("HEXAGON", result.backend)
        assertTrue(result.npuObserved)
        assertEquals(7L, result.npuGraphNodes)
        assertEquals(12, result.actualNpuLayers)
        assertFalse(result.gpuObserved)
        assertEquals(0L, result.gpuGraphNodes)
        val report = LocalTranslationPerformance(requestedMode = "HEXAGON", backend = "HEXAGON",
            npuObserved = true, npuGraphNodes = 7).render()
        assertTrue(report.contains("NPU Hexagon réellement observé : oui (7 nœuds"))
        assertTrue(report.contains("GPU réellement observé : non établi"))
    }

    @Test fun cpuFallbackMissingProofMixedCountersAndCoercedMetadataAreRejected() {
        val cases = listOf(
            generation().put("backend", "CPU"),
            generation().put("requestedMode", "AUTO"),
            generation().put("npuGraphNodes", 0),
            generation().put("npuObserved", false),
            generation().put("npuObserved", "true"),
            generation().put("npuGraphNodes", "7"),
            generation().put("npuGraphNodes", 1.5),
            generation().put("gpuGraphNodes", 1).put("gpuObserved", true),
            generation().put("requestedGpuLayers", -1),
            generation().put("actualNpuLayers", "12"),
            generation().put("openClTransport", "DIRECT"),
            generation().apply { remove("npuObserved"); remove("npuGraphNodes") })
        cases.forEach {
            try { LocalGgufRuntime.generation(it.toString()); fail("Unproven or contradictory Hexagon work accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }

    @Test fun loadedHexagonIsNotExecutionAndPreparationTimingIsValidated() {
        val info = JSONObject().put("code", "OK").put("name", "Synthetic").put("architecture", "gemma3")
            .put("fileType", "Q6_K").put("contextTokens", 2048).put("modelBytes", 1024)
            .put("modelParameters", 100).put("loadMs", 5).put("peakRssBytes", 2048)
            .put("requestedMode", "HEXAGON").put("backend", "HEXAGON")
            .put("requestedNpuLayers", -1).put("actualNpuLayers", 12)
            .put("loadProfile", JSONObject().put("backendInitMs", 1).put("openClPrepareMs", 0)
                .put("modelLoadMs", 2).put("contextInitMs", 1).put("openTotalMs", 6)
                .put("loadAttempts", 1).put("vulkanPrepareMs", 0).put("hexagonPrepareMs", 2))
        val result = LocalGgufRuntime.info(info.toString())
        assertFalse(result.npuObserved)
        assertEquals(2L, result.loadProfile?.hexagonPrepareMs)
        for (invalid in listOf(JSONObject(info.toString()).put("npuObserved", true),
            JSONObject(info.toString()).put("npuGraphNodes", 1),
            JSONObject(info.toString()).apply { getJSONObject("loadProfile").put("hexagonPrepareMs", "2") })) {
            try { LocalGgufRuntime.info(invalid.toString()); fail("Load metadata falsely proved execution") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }

    @Test fun explicitHexagonChoicePersistsWithoutImportOrExecution() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE).edit().clear().commit()
        var actions = 0
        fun controls() = LocalGgufModelControls(activity, { false }, { actions++ }, { actions++ }, { actions++ })
        val first = controls()
        try {
            fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            val spinner = views(first.view()).filterIsInstance<Spinner>()
                .single { it.contentDescription == "Exécution du modèle GGUF" }
            assertEquals("NPU Hexagon — expérimental ; sans repli CPU", spinner.adapter.getItem(4))
            spinner.setSelection(4)
            assertEquals("HEXAGON", first.execution)
            first.saveChoices()
            val restored = controls()
            try {
                assertEquals("HEXAGON", restored.execution)
                assertNull(restored.model)
                assertEquals(0, actions)
            } finally { restored.close() }
        } finally { first.close(); owner.pause().stop().destroy() }
    }

    @Test fun hexagonCacheIdentityIsDistinctFromEveryExistingMode() {
        val model = LocalGgufModel(File("synthetic.gguf"), "a".repeat(64), 1024, 0)
        val npu = LocalGgufTranslationEngine.identity(model, executionMode = "HEXAGON")
        for (mode in listOf("AUTO", "CPU", "OPENCL", "VULKAN"))
            assertNotEquals(npu, LocalGgufTranslationEngine.identity(model, executionMode = mode))
    }

    @Test fun persistentAttemptKeepsNpuEvidenceSeparateAndCacheHitsDoNotInventNewWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "hexagon-journal-boundary").apply { deleteRecursively() }
        val now = 1_000_000L
        val journal = LocalSrtAttemptJournal(directory) { now }
        val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF, localExecution = "HEXAGON")
        val entry = LocalSrtAttemptJournal.begin("b".repeat(64), config, now, 123)
            .copy(done = 1, total = 2)
            .measured(LocalTranslationPerformance(requestedMode = "HEXAGON", backend = "HEXAGON",
                npuObserved = true, npuGraphNodes = 7), now)
        assertTrue(journal.save(entry, force = true))
        val restored = requireNotNull(journal.read("b".repeat(64)))
        assertTrue(restored.npuObserved)
        assertEquals(7L, restored.npuGraphNodes)
        assertFalse(restored.gpuObserved)
        assertTrue(restored.render().contains("NPU Hexagon réellement observé : oui"))
        val cached = LocalTranslationPerformance(phase = "cache", requestedMode = "HEXAGON").render()
        assertTrue(cached.contains("Aucun nouveau calcul CPU, GPU ou Hexagon"))
        assertFalse(cached.contains("réellement observé : oui"))
    }
}
