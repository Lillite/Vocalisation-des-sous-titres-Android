package fr.soustitresvoix.player

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Coordinator checks use a fake measured backend, never trained weights or an actual GPU. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufBenchmarkRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: LocalGgufModel
    private lateinit var store: LocalGgufModelStore
    @Before fun prepareSyntheticHeader() {
        File(context.filesDir, "local-diagnostic").deleteRecursively()
        store = LocalGgufModelStore(context)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
        model = store.importModel(ByteArrayInputStream(header), header.size.toLong())
    }
    private class FakeEngine(val config: TranslationProviderConfig, private val events: MutableList<String>,
        private val inputs: MutableList<String>, private val gpuObserved: Boolean = true,
        private val output: String = "Le train part à 07 h 30. Garde la porte bleue fermée.",
        private val beforeGeneration: ((() -> Boolean) -> Unit)? = null,
        private val gpuTransport: String? = null) : TranslationEngine, LocalTranslationPerformanceSource {
        private var listener: ((LocalTranslationPerformance) -> Unit)? = null
        private var calls = 0
        private var closed = false
        override val id = TranslationEngines.translationIdentity(config)
        override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) { this.listener = listener }
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> {
            assertEquals("en", sourceLanguage); assertEquals("fr", targetLanguage)
            assertEquals("", config.localContext)
            assertEquals(listOf(LocalGgufBenchmark.SOURCE), texts)
            inputs += texts.single()
            beforeGeneration?.invoke(cancelled)
            if (cancelled()) throw TranslationCancelledException()
            calls++
            if (calls == 1) listener?.invoke(LocalTranslationPerformance(phase = "load", requestedMode = config.localExecution,
                backend = config.localExecution, loadMs = 10))
            val actualGpu = config.localExecution in setOf("OPENCL", "VULKAN") && gpuObserved
            listener?.invoke(LocalTranslationPerformance(requestedMode = config.localExecution,
                backend = if (actualGpu) config.localExecution else "CPU", gpuObserved = actualGpu,
                openClTransport = gpuTransport ?: if (actualGpu && config.localExecution == "OPENCL") "DIRECT" else "",
                gpuGraphNodes = if (actualGpu) 8 else 0, device = "synthetic test device",
                promptMs = if (calls == 1) 12 else 1, generationMs = 20, endToEndMs = 40,
                promptTokens = 15, generatedTokens = 5, cachedPromptTokens = if (calls == 1) 0 else 14,
                promptEvaluatedTokens = if (calls == 1) 15 else 1, currentRssBytes = 2048, peakRssBytes = 4096))
            events += "${config.localExecution}:generate:$calls"
            return listOf(output)
        }
        override fun close() {
            if (!closed) { closed = true; events += "${config.localExecution}:closed" }
        }
    }
    private fun await(benchmark: LocalGgufBenchmark) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (benchmark.state.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Small fake benchmark must finish", benchmark.state.busy)
    }
    private val probe = { LocalGgufBenchmark.Environment(availableRamBytes = 8L * 1024 * 1024 * 1024,
        thermalStatus = 0, batteryTenthsCelsius = 310) }

    @Test fun benchmarkIsPassiveUntilRequestedThenClosesCpuBeforeOpeningGpuWithIdenticalInputs() {
        val events = mutableListOf<String>(); val inputs = mutableListOf<String>()
        val configs = mutableListOf<TranslationProviderConfig>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            if (config.localExecution == "OPENCL") assertEquals("CPU:closed", events.last())
            assertThrows(LocalGgufModelBusyException::class.java) { store.remove() }
            configs += config; events += "${config.localExecution}:open"
            FakeEngine(config, events, inputs)
        }, snapshot = probe)
        try {
            assertTrue(events.isEmpty())
            assertFalse(benchmark.state.busy)
            benchmark.start(model, 4, LocalGgufProfile.HY_MT); await(benchmark)
            assertEquals(listOf("CPU", "OPENCL"), configs.map { it.localExecution })
            assertTrue(configs.all { it.localThreads == 4 && it.localProfile == LocalGgufProfile.HY_MT && it.localContext.isEmpty() })
            assertEquals(List(4) { LocalGgufBenchmark.SOURCE }, inputs)
            assertEquals(listOf("CPU:open", "CPU:generate:1", "CPU:generate:2", "CPU:closed",
                "OPENCL:open", "OPENCL:generate:1", "OPENCL:generate:2", "OPENCL:closed"), events)
            assertEquals(2, benchmark.state.runs.size)
            assertTrue(benchmark.state.runs.all { it.failure == null && it.cleanupConfirmed })
            val report = benchmark.state.report()
            assertTrue(report.contains("GPU réellement observé : oui"))
            assertTrue(report.contains("14 réutilisés sur 15"))
            assertTrue(report.contains("07:30"))
            assertTrue(report.contains("température de batterie : 31,0 °C"))
            assertTrue(report.contains("sorties CPU et GPU sont identiques"))
            assertFalse(report.contains(model.file.absolutePath))
            assertTrue(store.remove())
        } finally { benchmark.close() }
    }

    @Test fun unobservedGpuCannotBecomeASuccessAndItsActualCpuMeasurementsRemainVisible() {
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = {
            FakeEngine(it, mutableListOf(), mutableListOf(), gpuObserved = false)
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            val gpu = benchmark.state.runs.last()
            assertEquals("OPENCL", gpu.requestedMode)
            assertNotNull(gpu.failure)
            assertTrue(gpu.samples.isEmpty())
            assertEquals("CPU", gpu.lastPerformance?.backend)
            assertTrue(benchmark.state.report().contains("Dernières mesures reçues avant l’arrêt"))
            assertFalse(benchmark.state.report().contains("GPU réellement observé : oui"))
        } finally { benchmark.close() }
    }


    @Test fun selectedVulkanRunsStrictlyAfterCpuAndReportsItsOwnBackend() {
        val loadSettings = LocalGgufLoadSettings(gpuLayers = 8, batchTokens = 128, microBatchTokens = 32, compactSwa = true)
        val events = mutableListOf<String>(); val inputs = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            if (config.localExecution == "VULKAN") assertEquals("CPU:closed", events.last())
            assertEquals(loadSettings, config.localLoadSettings)
            events += "${config.localExecution}:open"
            FakeEngine(config, events, inputs)
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO,
                LocalGgufBenchmark.Options(gpuMode = "VULKAN", loadSettings = loadSettings)); await(benchmark)
            assertEquals(listOf("CPU", "VULKAN"), benchmark.state.runs.map { it.requestedMode })
            assertTrue(benchmark.state.runs.all { it.failure == null && it.cleanupConfirmed })
            val gpu = benchmark.state.runs.last()
            assertEquals(2, gpu.samples.size)
            assertTrue(gpu.samples.all { sample -> sample.performance?.let {
                it.backend == "VULKAN" && it.gpuObserved && it.gpuGraphNodes > 0 && it.openClTransport.isEmpty()
            } == true })
            assertEquals(List(4) { LocalGgufBenchmark.SOURCE }, inputs)
            val report = benchmark.state.report()
            assertTrue(report.contains("Moteur GPU demandé pour ce comparatif : Vulkan strict"))
            assertTrue(report.contains("Ordre fixe : CPU puis GPU Vulkan strict."))
            assertTrue(report.contains("Mode demandé : VULKAN"))
            assertTrue(report.contains(loadSettings.render()))
            assertTrue(report.contains("le passage CPU utilise zéro couche GPU"))
            assertFalse(report.contains("GPU OpenCL strict"))
        } finally { benchmark.close() }
    }

    @Test fun vulkanCpuFallbackIsRejectedWhileActualCpuMeasurementsRemainInReport() {
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = {
            FakeEngine(it, mutableListOf(), mutableListOf(), gpuObserved = false)
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO,
                LocalGgufBenchmark.Options(gpuMode = "VULKAN")); await(benchmark)
            val gpu = benchmark.state.runs.last()
            assertEquals("VULKAN", gpu.requestedMode)
            assertNotNull(gpu.failure)
            assertTrue(gpu.samples.isEmpty())
            assertEquals("CPU", gpu.lastPerformance?.backend)
            assertTrue(benchmark.state.report().contains("Dernières mesures reçues avant l’arrêt"))
            assertFalse(benchmark.state.report().contains("GPU réellement observé : oui"))
        } finally { benchmark.close() }
    }

    @Test fun vulkanCannotValidateAnOpenClTransportOrAnUnknownBackendChoice() {
        var created = 0
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            created++
            FakeEngine(config, mutableListOf(), mutableListOf(),
                gpuTransport = if (config.localExecution == "VULKAN") "DIRECT" else "")
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO,
                LocalGgufBenchmark.Options(gpuMode = "AUTO"))
            assertFalse(benchmark.state.busy)
            assertEquals(0, created)
            assertTrue(benchmark.state.status.contains("Moteur GPU invalide"))
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(
                gpuMode = "VULKAN", loadSettings = LocalGgufLoadSettings(batchTokens = 32, microBatchTokens = 64)))
            assertFalse(benchmark.state.busy)
            assertEquals(0, created)
            assertTrue(benchmark.state.status.contains("Réglages de chargement invalides"))
            benchmark.start(model, 4, LocalGgufProfile.AUTO,
                LocalGgufBenchmark.Options(gpuMode = "VULKAN")); await(benchmark)
            assertEquals(2, created)
            val gpu = benchmark.state.runs.last()
            assertEquals("VULKAN", gpu.requestedMode)
            assertTrue(gpu.samples.isEmpty())
            assertNotNull(gpu.failure)
            assertEquals("DIRECT", gpu.lastPerformance?.openClTransport)
            assertFalse(benchmark.state.report().contains("sorties CPU et GPU sont identiques"))
        } finally { benchmark.close() }
    }

    @Test fun failedCleanupStopsBeforeAnyGpuLoad() {
        val events = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = {
            events += "${it.localExecution}:open"; FakeEngine(it, events, mutableListOf())
        }, snapshot = probe, closeEngine = {
            it.close(); throw IOException("synthetic missing acknowledgement")
        })
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            assertFalse(events.contains("OPENCL:open"))
            assertEquals(1, benchmark.state.runs.size)
            assertFalse(benchmark.state.runs.single().cleanupConfirmed)
            assertTrue(benchmark.state.report().contains("non confirmée ; aucun autre mode lancé"))
        } finally { benchmark.close() }
    }

    @Test fun firstCallAlreadyIncludesLazyLoadAndModeCountsItOnceWithCleanup() {
        val now = AtomicLong(1_000)
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            var calls = 0
            FakeEngine(config, mutableListOf(), mutableListOf(), beforeGeneration = {
                // First call: 10 ms load + 12 ms prompt + 20 ms generation.
                // Second call: 1 ms prompt + 20 ms generation, model still loaded.
                now.addAndGet(if (++calls == 1) 42 else 21)
            })
        }, snapshot = probe, clock = now::get, closeEngine = { engine ->
            now.addAndGet(3); engine.close()
        })
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            assertEquals(2, benchmark.state.runs.size)
            benchmark.state.runs.forEach { run ->
                assertNull(run.failure)
                assertEquals(10L, run.loadMs)
                assertEquals(listOf(42L, 21L), run.samples.map { it.wallMs })
                assertEquals(66L, run.wallMs)
                assertEquals(run.samples.sumOf { it.wallMs } + 3, run.wallMs)
            }
            assertTrue(benchmark.state.report().contains("comprennent déjà le chargement éventuel"))
        } finally { benchmark.close() }
    }

    @Test fun reportCapturesTheActualPlatformAndAppIdentityOnceAtStart() {
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = {
            FakeEngine(it, mutableListOf(), mutableListOf())
        }, snapshot = probe)
        try {
            val before = Instant.now()
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            val platform = requireNotNull(benchmark.state.platform)
            assertEquals(Build.VERSION.RELEASE, platform.androidRelease)
            assertEquals(Build.VERSION.SDK_INT, platform.api)
            assertEquals(Build.MODEL, platform.model)
            assertEquals(Build.MANUFACTURER, platform.manufacturer)
            assertEquals(Build.SUPPORTED_ABIS.toList(), platform.abis)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            assertEquals(info.versionName, platform.appVersion)
            assertEquals(info.longVersionCode, platform.appVersionCode)
            val started = Instant.parse(platform.startedAtUtc)
            assertFalse(started.isBefore(before))
            assertFalse(started.isAfter(Instant.now()))
            val report = benchmark.state.report()
            assertTrue(report.contains("Plateforme rapportée par Android : ${Build.VERSION.RELEASE} ; API ${Build.VERSION.SDK_INT}"))
            assertEquals(1, Regex("Plateforme rapportée par Android").findAll(report).count())
            assertFalse(report.contains("OxygenOS"))
            assertFalse(report.contains("fingerprint", ignoreCase = true))
        } finally { benchmark.close() }
    }

    @Test fun gpuFailureRetainsBoundedDiagnosticAndReleasesBothModesWithoutFalseSuccess() {
        val privateMarker = "synthetic private marker must not leave the parser"
        val attemptReport = "Fin de l’essai local : processus arrêté (DIED)."
        val diagnostic = requireNotNull(LocalGgufDiagnostic.parse(JSONObject().apply {
            put("stage", "OPENCL_ICD"); put("reason", "ICD_NO_PLATFORM"); put("elapsedMs", 50)
            put("openClStatus", -1001); put("directPlatforms", 1); put("icdPlatforms", 0)
            put("gpuDevices", -1); put("probeCached", true)
            put("privatePath", model.file.absolutePath); put("output", privateMarker)
        }))
        val events = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            events += "${config.localExecution}:open"
            if (config.localExecution == "CPU") FakeEngine(config, events, mutableListOf())
            else object : TranslationEngine {
                override val id = TranslationEngines.translationIdentity(config)
                override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                    cancelled: () -> Boolean): List<String> =
                    throw TranslationFailure(TranslationFailure.Reason.LOCAL_BACKEND, diagnostic, localAttemptReport = attemptReport)
                override fun close() { if (events.lastOrNull() != "OPENCL:closed") events += "OPENCL:closed" }
            }
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            val cpu = benchmark.state.runs.first()
            val gpu = benchmark.state.runs.last()
            assertNull(cpu.failure)
            assertEquals(2, cpu.samples.size)
            assertTrue(cpu.cleanupConfirmed)
            assertTrue(gpu.cleanupConfirmed)
            assertTrue(gpu.samples.isEmpty())
            assertTrue(requireNotNull(gpu.failure).contains(diagnostic.render()))
            assertNull(gpu.lastPerformance)
            assertEquals(0L, gpu.loadMs)
            assertEquals(listOf("CPU:open", "CPU:generate:1", "CPU:generate:2", "CPU:closed", "OPENCL:open", "OPENCL:closed"), events)
            val report = benchmark.state.report()
            assertTrue(report.contains("OPENCL_ICD"))
            assertEquals(1, Regex(Regex.escape(attemptReport)).findAll(report).count())
            assertTrue(report.contains("ICD_NO_PLATFORM"))
            assertTrue(report.contains("-1001"))
            assertTrue(report.contains("un chargement a pu être tenté"))
            assertFalse(report.contains("GPU réellement observé : oui"))
            assertFalse(report.contains("sorties CPU et GPU sont identiques"))
            assertFalse(report.contains(model.file.absolutePath))
            assertFalse(report.contains(privateMarker))
            assertTrue(store.remove())
        } finally { benchmark.close() }
    }

    @Test fun differingOutputsAreExplicitAndNeverBecomeAnAutomaticQualityRanking() {
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = {
            FakeEngine(it, mutableListOf(), mutableListOf(), output = if (it.localExecution == "CPU")
                "Le train part à 07 h 30." else "Le train s’en va à sept heures trente.")
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO); await(benchmark)
            val report = benchmark.state.report()
            assertTrue(report.contains("sorties CPU et GPU diffèrent"))
            assertTrue(report.contains("Aucun classement de qualité automatique"))
            assertTrue(benchmark.state.runs.all { it.failure == null })
        } finally { benchmark.close() }
    }

    @Test fun cancellationKeepsCompletedResultsAndNeverStartsTheNextMode() {
        val started = CountDownLatch(1)
        val events = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            events += "${config.localExecution}:open"
            FakeEngine(config, events, mutableListOf(), beforeGeneration = { cancelled ->
                started.countDown()
                while (!cancelled()) Thread.sleep(2)
            })
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            benchmark.cancel(); await(benchmark)
            assertEquals(listOf("CPU:open", "CPU:closed"), events)
            assertEquals(1, benchmark.state.runs.size)
            assertTrue(benchmark.state.runs.single().samples.isEmpty())
            assertTrue(benchmark.state.status.contains("annulé"))
            assertFalse(benchmark.state.report().contains("GPU réellement observé : oui"))
            assertFalse(benchmark.state.report().contains("deux réponses complètes validées"))
        } finally { benchmark.close() }
    }
}
