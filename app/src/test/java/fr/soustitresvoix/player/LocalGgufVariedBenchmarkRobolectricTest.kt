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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Synthetic coordinators only: no trained model or physical accelerator is exercised here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufVariedBenchmarkRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: LocalGgufModel
    @Before fun prepare() {
        File(context.filesDir, "local-diagnostic").deleteRecursively()
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
        model = LocalGgufModelStore(context).importModel(ByteArrayInputStream(header), header.size.toLong())
    }
    private data class Input(val source: String, val previousSources: List<String>, val previousOutputs: List<String>)
    private class MeasuredEngine(val mode: String, val events: MutableList<String>,
        val inputs: MutableList<Input>, val now: AtomicLong? = null,
        val beforeCall: ((Int, () -> Boolean) -> Unit)? = null,
        val badFirstFragmentAt: Int? = null, val oversizedAt: Int? = null) : ContextualTranslationEngine, LocalTranslationPerformanceSource {
        override val id = "synthetic-$mode"
        private var listener: ((LocalTranslationPerformance) -> Unit)? = null
        private var closed = false
        private var calls = 0
        val profile = LocalGgufLoadProfile(1, 2, 3, 4, 12, 1)
        init { events += "$mode:open" }
        override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) { this.listener = listener }
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean) = translateWithContext(texts, sourceLanguage, targetLanguage, emptyList(), emptyList(), cancelled)
        override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
            assertEquals("en", sourceLanguage); assertEquals("fr", targetLanguage)
            assertEquals(1, texts.size); assertEquals(previousSources.size, previousTranslations.size)
            calls++
            beforeCall?.invoke(calls, cancelled)
            if (cancelled()) throw TranslationCancelledException()
            inputs += Input(texts.single(), previousSources.toList(), previousTranslations.toList())
            if (calls == 1) listener?.invoke(LocalTranslationPerformance(phase = "load", requestedMode = mode,
                backend = mode, loadMs = 10, loadProfile = profile, openClTransport = if (mode == "OPENCL") "DIRECT" else ""))
            fun frame(extra: Boolean = false): LocalTranslationPerformance {
                val actual = if (extra && calls == badFirstFragmentAt) "CPU" else mode
                return LocalTranslationPerformance(requestedMode = mode, backend = actual,
                    device = "synthetic measured device", gpuObserved = actual == "OPENCL",
                    gpuGraphNodes = if (actual == "OPENCL") 7 else 0,
                    openClTransport = if (actual == "OPENCL") "DIRECT" else "",
                    promptMs = 5, generationMs = if (extra) 7 else 11, promptTokens = 9,
                    promptEvaluatedTokens = 7, cachedPromptTokens = 2, generatedTokens = if (extra) 3 else 5)
            }
            if (calls == 2) listener?.invoke(frame(extra = true))
            listener?.invoke(frame())
            now?.addAndGet(if (calls == 1) 1_000 else calls * 10L)
            events += "$mode:generate:$calls"
            return listOf(if (calls == oversizedAt) "é".repeat(1_025) else "Traduction synthétique $calls.")
        }
        override fun close() { if (!closed) { closed = true; events += "$mode:closed" } }
    }
    private val probe = { LocalGgufBenchmark.Environment(8L * 1_073_741_824L, 0, 310) }
    private fun await(benchmark: LocalGgufBenchmark) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (benchmark.state.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Synthetic benchmark must finish", benchmark.state.busy)
    }

    @Test fun variedModesKeepEveryIdentityTimingRepetitionAndTheirOwnValidatedHistory() {
        val events = mutableListOf<String>(); val engines = mutableListOf<MeasuredEngine>()
        val configs = mutableListOf<TranslationProviderConfig>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            if (engines.isNotEmpty()) assertTrue(events.last().endsWith(":closed"))
            configs += config
            MeasuredEngine(config.localExecution, events, mutableListOf()).also { engines += it }
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED)); await(benchmark)
            assertEquals(listOf("CPU", "OPENCL"), configs.map { it.localExecution })
            assertTrue(configs.all { it.localContext.isEmpty() && it.localThreads == 4 && it.localProfile == LocalGgufProfile.AUTO })
            assertEquals(2, benchmark.state.runs.size)
            benchmark.state.runs.forEachIndexed { index, run ->
                assertNull(run.failure); assertTrue(run.cleanupConfirmed); assertFalse(run.reopening)
                assertEquals(LocalGgufBenchmarkCorpus.cues, run.samples.map { it.cue })
                assertEquals(32, run.samples.size); assertEquals(32, run.samples.map { it.cue!!.id }.distinct().size)
                assertEquals(engines[index].profile, run.loadPerformance?.loadProfile)
                engines[index].inputs.forEachIndexed { i, input ->
                    assertEquals(LocalGgufBenchmarkCorpus.cues[i].source, input.source)
                    assertEquals(LocalGgufBenchmarkCorpus.cues.take(i).takeLast(2).map { it.source }, input.previousSources)
                    assertEquals(run.samples.take(i).takeLast(2).map { it.output }, input.previousOutputs)
                }
            }
            assertTrue(benchmark.state.report().contains("32 occurrences ; 29 textes distincts"))
            assertTrue(benchmark.state.report().contains("Les sorties CPU et GPU sont identiques"))
            assertFalse(benchmark.state.report().contains(model.file.absolutePath))
        } finally { benchmark.close() }
    }

    @Test fun reverseOrderAndReopeningUseFourReleasedSessionsAndOneReloadCuePerMode() {
        val events = mutableListOf<String>(); val engines = mutableListOf<MeasuredEngine>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            if (engines.isNotEmpty()) assertTrue(events.last().endsWith(":closed"))
            MeasuredEngine(config.localExecution, events, mutableListOf()).also { engines += it }
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED,
                LocalGgufBenchmark.Order.GPU_THEN_CPU, reopenForLoad = true)); await(benchmark)
            assertEquals(listOf("OPENCL", "OPENCL", "CPU", "CPU"), engines.map { it.mode })
            assertEquals(listOf(32, 1, 32, 1), engines.map { it.inputs.size })
            assertEquals(listOf(false, true, false, true), benchmark.state.runs.map { it.reopening })
            assertEquals(66, engines.sumOf { it.inputs.size })
            engines.forEach { assertTrue(it.inputs.first().previousSources.isEmpty()); assertTrue(it.inputs.first().previousOutputs.isEmpty()) }
            assertEquals(4, events.count { it.endsWith(":closed") })
            assertTrue(benchmark.state.report().contains("GPU OpenCL strict puis CPU"))
            assertTrue(benchmark.state.report().contains("ceci n’est pas une mesure garantie à cache froid"))
        } finally { benchmark.close() }
    }

    @Test fun completedFragmentsAreAggregatedAndWarmPercentilesExcludeTheFirstWholeCall() {
        val now = AtomicLong(1_000)
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            MeasuredEngine(config.localExecution, mutableListOf(), mutableListOf(), now)
        }, snapshot = probe, clock = now::get, closeEngine = { it.close(); now.addAndGet(3) })
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED)); await(benchmark)
            benchmark.state.runs.forEach { run ->
                assertNull(run.failure); assertEquals(32, run.samples.size)
                assertEquals(2, run.samples[1].nativeCalls.size)
                assertEquals(33, run.samples.sumOf { it.nativeCalls.size })
                assertEquals(163L, run.samples.flatMap { it.nativeCalls }.sumOf { it.generatedTokens.toLong() })
                assertEquals(1_000L, run.samples.first().wallMs)
                assertEquals(run.samples.sumOf { it.wallMs } + 3, run.wallMs)
            }
            val report = benchmark.state.report()
            assertTrue(report.contains("n=31 ; p50=170 ms ; p95=310 ms ; maximum=320 ms"))
            assertTrue(report.contains("tokens générés cumulés : 163"))
            assertTrue(report.contains("2 appels natifs terminés et mesurés"))
            assertTrue(report.contains("Les tentatives natives échouées ne sont pas détaillées"))
        } finally { benchmark.close() }
    }

    @Test fun anInvalidEarlierGpuFragmentCannotBeHiddenByAValidFinalFragment() {
        val events = mutableListOf<String>(); val engines = mutableListOf<MeasuredEngine>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            MeasuredEngine(config.localExecution, events, mutableListOf(), badFirstFragmentAt = 2).also { engines += it }
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED,
                LocalGgufBenchmark.Order.GPU_THEN_CPU, reopenForLoad = true)); await(benchmark)
            val run = benchmark.state.runs.single()
            assertNotNull(run.failure); assertTrue(run.cleanupConfirmed)
            assertEquals(1, run.samples.size); assertEquals("synthetic-01", run.samples.single().cue?.id)
            assertEquals("OPENCL", run.lastPerformance?.backend)
            assertEquals(listOf("OPENCL"), engines.map { it.mode })
            assertTrue(events.last().endsWith(":closed"))
        } finally { benchmark.close() }
    }

    @Test fun cancellationRetainsTwoCompletedOccurrencesAndNeverReopensOrStartsTheNextMode() {
        val started = CountDownLatch(1); val events = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            MeasuredEngine(config.localExecution, events, mutableListOf(), beforeCall = { number, cancelled ->
                if (number == 3) { started.countDown(); while (!cancelled()) Thread.sleep(2) }
            })
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED,
                reopenForLoad = true))
            assertTrue(started.await(2, TimeUnit.SECONDS)); benchmark.cancel(); await(benchmark)
            val run = benchmark.state.runs.single()
            assertEquals(listOf("synthetic-01", "synthetic-02"), run.samples.map { it.cue?.id })
            assertTrue(run.cleanupConfirmed); assertNotNull(run.failure)
            assertFalse(events.any { it.startsWith("OPENCL") })
            assertEquals(1, events.count { it.endsWith(":open") })
        } finally { benchmark.close() }
    }

    @Test fun staleAndChangedMediaObservationsNeverImplyContinuousPlaybackOrComparableCounters() {
        fun observation(at: Long, generation: Long, dropped: Long) = LocalGgufBenchmark.PlaybackObservation(
            observedAtMs = at, mediaGeneration = generation, mediaOpened = true, playing = true,
            positionMs = 12_000, droppedFrames = dropped)
        val cue = LocalGgufBenchmarkCorpus.cues.first()
        val freshBefore = observation(1_000, 1, 2); val freshAfter = observation(5_000, 1, 7)
        val sample = LocalGgufBenchmark.Sample(1, "Sortie synthétique.", 50, null, cue,
            playback = freshAfter, finishedAtMs = 8_001)
        val run = LocalGgufBenchmark.Run("CPU", listOf(sample), probe(), probe(), 100,
            plannedSamples = 32, playbackBefore = freshBefore, playbackAfter = freshAfter,
            startedAtMs = 1_100, finishedAtMs = 5_100)
        val state = LocalGgufBenchmark.State(modelSha256 = "a".repeat(64), runs = listOf(run),
            options = LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED))
        val report = state.report()
        assertTrue(report.contains("relevé périmé ou indisponible ; aucune activité de lecture déduite"))
        assertTrue(report.contains("Delta du compteur de session d’images abandonnées : 5"))
        assertTrue(report.contains("ne prouvent ni continuité, ni fluidité, ni vocalisation complète"))
        val changed = state.copy(runs = listOf(run.copy(playbackAfter = freshAfter.copy(mediaGeneration = 2))))
        assertTrue(changed.report().contains("Delta du compteur de session d’images abandonnées : non comparable"))
        val stale = state.copy(runs = listOf(run.copy(playbackAfter = freshAfter.copy(observedAtMs = 1_000))))
        assertTrue(stale.report().contains("Delta du compteur de session d’images abandonnées : non comparable"))
    }

    @Test fun completeBoundedReportsFitClipboardAndOversizedResponsesFailWithoutTruncation() {
        val fullRuns = listOf("CPU", "OPENCL").flatMap { mode ->
            val performance = LocalTranslationPerformance(requestedMode = mode, backend = mode,
                device = "d".repeat(300), gpuObserved = mode == "OPENCL", gpuGraphNodes = if (mode == "OPENCL") Long.MAX_VALUE else 0,
                openClTransport = if (mode == "OPENCL") "DIRECT" else "", promptMs = 86_400_000,
                generationMs = 86_400_000, promptTokens = 2048, promptEvaluatedTokens = 2048, generatedTokens = 1024)
            listOf(false, true).map { reopen ->
                val cues = if (reopen) LocalGgufBenchmarkCorpus.cues.take(1) else LocalGgufBenchmarkCorpus.cues
                LocalGgufBenchmark.Run(mode, cues.mapIndexed { index, cue ->
                    LocalGgufBenchmark.Sample(index + 1, "x".repeat(2048), 86_400_000, performance, cue,
                        nativeCalls = List(256) { performance })
                }, probe(), probe(), 86_400_000, reopening = reopen, plannedSamples = cues.size,
                    loadPerformance = performance.copy(phase = "load"))
            }
        }
        val largestReport = LocalGgufBenchmark.State(modelSha256 = "a".repeat(64), runs = fullRuns,
            options = LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED, reopenForLoad = true)).report()
        assertTrue("Complete fixed-corpus report must fit the UI copy gate", largestReport.length < 400_000)
        assertEquals(66, Regex("Sortie sur le texte synthétique uniquement").findAll(largestReport).count())
        assertTrue(largestReport.contains("0 ms (résolution milliseconde)"))
        val events = mutableListOf<String>()
        val benchmark = LocalGgufBenchmark(context, {}, engineFactory = { config ->
            MeasuredEngine(config.localExecution, events, mutableListOf(), oversizedAt = 1)
        }, snapshot = probe)
        try {
            benchmark.start(model, 4, LocalGgufProfile.AUTO, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED)); await(benchmark)
            val run = benchmark.state.runs.single()
            assertNotNull(run.failure); assertTrue(run.samples.isEmpty()); assertTrue(run.cleanupConfirmed)
            assertTrue(benchmark.state.report().contains("sans tronquer le texte"))
            assertFalse(events.any { it.startsWith("OPENCL") })
        } finally { benchmark.close() }
    }
}
