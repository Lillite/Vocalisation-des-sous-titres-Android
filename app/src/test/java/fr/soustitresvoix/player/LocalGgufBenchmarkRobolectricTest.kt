package fr.soustitresvoix.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        private val beforeGeneration: ((() -> Boolean) -> Unit)? = null) : TranslationEngine, LocalTranslationPerformanceSource {
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
            val actualGpu = config.localExecution == "OPENCL" && gpuObserved
            listener?.invoke(LocalTranslationPerformance(requestedMode = config.localExecution,
                backend = if (actualGpu) "OpenCL" else "CPU", gpuObserved = actualGpu,
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
        } finally { benchmark.close() }
    }
}
