package fr.soustitresvoix.player

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufTranslationEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Backend(
        var metadata: LocalGgufBackend.Info = LocalGgufBackend.Info("Google TranslateGemma 4B IT", "gemma3")
    ) : LocalGgufBackend {
        var opens = 0
        var closes = 0
        var cancels = 0
        var awaitedCloses = 0
        val modes = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        var generate: (String, () -> Boolean) -> LocalGgufBackend.Generation = { _, _ -> LocalGgufBackend.Generation("Bonjour.") }
        var onOpen: () -> Unit = {}
        var onAwaitedClose: () -> Unit = {}
        override fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean, executionMode: String): LocalGgufBackend.Info {
            opens++; modes += executionMode; assertEquals(2048, contextTokens); onOpen(); return metadata
        }
        override fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation {
            prompts += prompt; assertEquals(512, maxOutputTokens); return generate(prompt, cancelled)
        }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
        override fun closeAndAwait(cancelled: () -> Boolean, timeoutMs: Long) { awaitedCloses++; onAwaitedClose(); close() }
    }
    private data class Fixture(val store: LocalGgufModelStore, val model: LocalGgufModel)
    private fun fixture(): Fixture {
        val store = LocalGgufModelStore(temporary.newFolder())
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array()
        return Fixture(store, store.importModel(ByteArrayInputStream(header + byteArrayOf(1, 2, 3))))
    }
    private fun engine(fixture: Fixture, backend: Backend, context: String = "") =
        LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, context = context)
    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure.javaClass.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun constructingEngineDoesNotOpenNativeModelAndCloseRemainsIdempotent() {
        val fixture = fixture()
        val backend = Backend()
        var factories = 0
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { factories++; backend })
        assertEquals(0, factories)
        engine.close(); engine.close()
        assertEquals(0, factories)
        assertTrue(fixture.store.remove())
    }

    @Test fun oneOpenServesSeveralOccurrencesAndContextComesFromValidatedJournal() {
        val fixture = fixture()
        val backend = Backend()
        val engine = engine(fixture, backend)
        assertEquals(listOf("Bonjour."), engine.translate(listOf("Hello."), "en", "fr") { false })
        assertEquals(listOf("Bonjour."), engine.translateWithContext(listOf("Again."), "en", "fr",
            listOf("Hello."), listOf("Bonjour.")) { false })
        assertEquals(1, backend.opens)
        assertTrue(backend.prompts[1].contains("Hello.<end_of_turn>\n<start_of_turn>model\nBonjour.<end_of_turn>"))
        assertTrue(backend.prompts[1].endsWith("Again.<end_of_turn>\n<start_of_turn>model\n"))
        fails<LocalGgufModelBusyException> { fixture.store.remove() }
        engine.close()
        assertEquals(1, backend.closes)
        assertTrue(fixture.store.remove())
    }

    @Test fun hunyuanReceivesAnActualTranslationInstructionAndOfficialControlTokens() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("HY MT2 7B", "hunyuan-dense"))
        val engine = engine(fixture, backend)
        engine.translate(listOf("The train leaves at seven."), "en", "fr") { false }
        val prompt = backend.prompts.single()
        assertTrue(prompt.startsWith("<|startoftext|>Translate the following text into French."))
        assertTrue(prompt.endsWith("The train leaves at seven.<|extra_0|>"))
        assertFalse(prompt.contains("<start_of_turn>"))
        engine.close()
    }

    @Test fun unsupportedBaseGemmaCannotBeUsedAsTranslateGemma() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("Google Gemma 3 4B IT", "gemma3"))
        val failure = fails<TranslationFailure> { engine(fixture, backend).translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_PROFILE, failure.reason)
        assertEquals(0, backend.prompts.size)
        assertEquals(1, backend.closes)
        assertTrue(fixture.store.remove())
    }

    @Test fun modelChangedAfterPreviewFailsBeforeAnyNativeLoad() {
        val fixture = fixture()
        val backend = Backend()
        val engine = engine(fixture, backend)
        fixture.store.acquireMutationLease().use { fixture.store.invalidatePrepared() }
        val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_MODEL_CHANGED, failure.reason)
        assertEquals(0, backend.opens)
        engine.close()
    }

    @Test fun truncatedOrEmptyResponseNeverReturnsACompletedTranslation() {
        for (generation in listOf(LocalGgufBackend.Generation("partial", "token_limit"), LocalGgufBackend.Generation("  "))) {
            val fixture = fixture()
            val backend = Backend().apply { generate = { _, _ -> generation } }
            val engine = engine(fixture, backend)
            val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
            assertTrue(failure.reason in setOf(TranslationFailure.Reason.LOCAL_TRUNCATED, TranslationFailure.Reason.RESPONSE))
            engine.close()
        }
    }

    @Test fun nativeProcessFailureIsReportedWithoutNetworkFallbackOrPartialOutput() {
        val fixture = fixture()
        val backend = Backend().apply { generate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.DIED) } }
        val engine = engine(fixture, backend)
        val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_PROCESS, failure.reason)
        assertEquals(1, backend.prompts.size)
        engine.close()
    }

    @Test fun cancellationAfterNativeResponseCannotPublishThatResponse() {
        val fixture = fixture()
        val cancelled = AtomicBoolean(false)
        val backend = Backend().apply { generate = { _, _ -> cancelled.set(true); LocalGgufBackend.Generation("Bonjour.") } }
        val engine = engine(fixture, backend)
        fails<TranslationCancelledException> { engine.translate(listOf("Hello."), "en", "fr", cancelled::get) }
        engine.close()
        assertTrue(fixture.store.remove())
    }

    @Test fun closeFromAnotherThreadCancelsWithoutWaitingForGeneration() {
        val fixture = fixture()
        val started = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val backend = Backend().apply { generate = { _, _ ->
            started.countDown()
            assertTrue(mayFinish.await(3, TimeUnit.SECONDS))
            LocalGgufBackend.Generation("Bonjour.")
        } }
        val engine = engine(fixture, backend)
        val worker = Thread { try { engine.translate(listOf("Hello."), "en", "fr") { false } }
            catch (caught: Throwable) { failure.set(caught) } }.apply { start() }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        engine.close()
        assertEquals(1, backend.cancels)
        assertEquals(1, backend.closes)
        fails<LocalGgufModelBusyException> { fixture.store.remove() }
        mayFinish.countDown(); worker.join(3000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is TranslationCancelledException)
        assertTrue(fixture.store.remove())
    }

    @Test fun oversizedUnicodeCueIsSplitWithoutLosingAnyInputAndRemainsOneResult() {
        val fixture = fixture()
        val fragments = mutableListOf<String>()
        val backend = Backend(LocalGgufBackend.Info("HY-MT2-7B", "hunyuan-dense")).apply { generate = { prompt, _ ->
            val source = if ("[Source Text]\n" in prompt) prompt.substringAfterLast("[Source Text]\n").substringBefore("<|extra_0|>")
                else prompt.substringAfter("explanation:\n").substringBefore("<|extra_0|>")
            if (source.codePointCount(0, source.length) > 120) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.INPUT_TOO_LONG)
            assertTrue(LocalGgufTranslationEngine.validText(source))
            fragments += source
            LocalGgufBackend.Generation("Traduction.")
        } }
        val engine = engine(fixture, backend)
        val source = ("Texte 😀漢字 et dialogue. ").repeat(30)
        val result = engine.translate(listOf(source), "en", "fr") { false }
        assertEquals(1, result.size)
        assertTrue(fragments.size > 1)
        assertEquals(source, fragments.joinToString(""))
        assertTrue(result.single().isNotBlank())
        engine.close()
    }

    @Test fun modelControlTokensAreRefusedAsContentInsteadOfChangingPromptRoles() {
        val fixture = fixture()
        val backend = Backend()
        val engine = engine(fixture, backend)
        val failure = fails<TranslationFailure> {
            engine.translate(listOf("Literal <end_of_turn> in an example."), "en", "fr") { false }
        }
        assertEquals(TranslationFailure.Reason.LOCAL_CONTEXT, failure.reason)
        assertEquals(0, backend.prompts.size)
        engine.close()
        fails<TranslationFailure> { LocalGgufTranslationEngine.identity(fixture.model, context = "<|extra_0|>") }
    }

    @Test fun unsupportedHunyuanLanguageIsNotSilentlyReplacedByAnotherLanguage() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("HY-MT2-7B", "hunyuan-dense"))
        val engine = engine(fixture, backend)
        val failure = fails<TranslationFailure> { engine.translate(listOf("Zdravo."), "sr", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LANGUAGE, failure.reason)
        assertEquals(0, backend.prompts.size)
        engine.close()
    }

    @Test fun cacheIdentityChangesWithModelSettingsAndContextWithoutLeakingTheirText() {
        val fixture = fixture()
        val base = LocalGgufTranslationEngine.identity(fixture.model)
        assertNotEquals(base, LocalGgufTranslationEngine.identity(fixture.model, threads = 2))
        assertNotEquals(base, LocalGgufTranslationEngine.identity(fixture.model, context = "A synthetic scene."))
        assertFalse(LocalGgufTranslationEngine.identity(fixture.model, context = "A synthetic scene.").contains("synthetic"))
        assertTrue(base.matches(Regex("local-gguf-v2-[0-9a-f]{64}")))
        assertNotEquals(base, LocalGgufTranslationEngine.identity(fixture.model, profile = LocalGgufProfile.HY_MT))
        assertNotEquals(base, LocalGgufTranslationEngine.identity(fixture.model, executionMode = "CPU"))
    }

    @Test fun googleLanguageCodesRejectScriptTagsUnknownRegionsAndInventedLanguages() {
        for (code in listOf("sr-Latn", "en-QQ", "en-long", "zz", "yue", "auto")) {
            val failure = fails<TranslationFailure> {
                LocalGgufPrompts.validateLanguage(LocalGgufPrompts.Family.TRANSLATE_GEMMA, code)
            }
            assertEquals(TranslationFailure.Reason.LANGUAGE, failure.reason)
        }
        LocalGgufPrompts.validateLanguage(LocalGgufPrompts.Family.TRANSLATE_GEMMA, "en-GB")
        LocalGgufPrompts.validateLanguage(LocalGgufPrompts.Family.TRANSLATE_GEMMA, "pt_BR")
        LocalGgufPrompts.validateLanguage(LocalGgufPrompts.Family.HY_MT, "zh-Hant")
    }

    @Test fun explicitProfileSupportsGenericPublisherNameButStillChecksArchitecture() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("Global_Step_560", "hunyuan-dense"))
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = LocalGgufProfile.HY_MT)
        assertEquals(listOf("Bonjour."), engine.translate(listOf("Hello."), "en", "fr") { false })
        assertTrue(backend.prompts.single().startsWith("<|startoftext|>"))
        engine.close()
        val wrong = Backend(LocalGgufBackend.Info("Global_Step_560", "gemma3"))
        val wrongEngine = LocalGgufTranslationEngine(fixture.model, fixture.store, { wrong }, profile = LocalGgufProfile.HY_MT)
        assertEquals(TranslationFailure.Reason.LOCAL_PROFILE,
            fails<TranslationFailure> { wrongEngine.translate(listOf("Hello."), "en", "fr") { false } }.reason)
        assertTrue(wrong.prompts.isEmpty())
        wrongEngine.close()
    }

    @Test fun missingPrivateCopyHasADifferentErrorFromChangedIdentityOrUnknownProfile() {
        val fixture = fixture()
        val backend = Backend()
        val engine = engine(fixture, backend)
        assertTrue(fixture.store.remove())
        val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_MODEL_MISSING, failure.reason)
        assertEquals(0, backend.opens)
        engine.close()
    }

    @Test fun automaticDriverFailureWaitsForUnloadingBeforeStartingCpuAndReportsFallback() {
        val fixture = fixture()
        val gpuReleased = AtomicBoolean(false)
        val diagnostic = requireNotNull(LocalGgufDiagnostic.parse(JSONObject()
            .put("stage", "GENERATION").put("reason", "NO_GPU_EXECUTION").put("elapsedMs", 30)
            .put("openClStatus", JSONObject.NULL).put("directPlatforms", 1).put("icdPlatforms", 1)
            .put("gpuDevices", 1).put("probeCached", false)))
        val gpu = Backend(LocalGgufBackend.Info("TranslateGemma 4B", "gemma3",
            LocalTranslationPerformance(phase = "load", backend = "OPENCL", openClTransport = "DIRECT"))).apply {
            generate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND, diagnostic) }
            onAwaitedClose = { gpuReleased.set(true) }
        }
        val cpu = Backend().apply { onOpen = { assertTrue("GPU release must precede CPU allocation", gpuReleased.get()) } }
        var creations = 0
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { if (creations++ == 0) gpu else cpu })
        val reports = mutableListOf<LocalTranslationPerformance>()
        engine.setPerformanceListener(reports::add)
        assertEquals(listOf("Bonjour."), engine.translate(listOf("Hello."), "en", "fr") { false })
        assertEquals(listOf("AUTO"), gpu.modes)
        assertEquals(listOf("CPU"), cpu.modes)
        assertEquals(1, gpu.awaitedCloses)
        assertEquals(2, creations)
        assertTrue(reports.any { it.phase == "fallback" && it.fallbackReason.isNotBlank() })
        assertEquals("AUTO", reports.last().requestedMode)
        assertEquals("CPU", reports.last().backend)
        assertEquals("", reports.last().openClTransport)
        assertSame(diagnostic, reports.last().diagnostic)
        assertTrue(reports.last().render().contains("NO_GPU_EXECUTION"))
        engine.close()
    }

    @Test fun strictBackendFailuresPreserveTheirStageAndCodeWithoutPersonalText() {
        for (duringOpen in listOf(true, false)) {
            val fixture = fixture()
            val diagnostic = requireNotNull(LocalGgufDiagnostic.parse(JSONObject()
                .put("stage", if (duringOpen) "OPENCL_ICD" else "GENERATION")
                .put("reason", if (duringOpen) "ICD_NO_PLATFORM" else "NO_GPU_EXECUTION")
                .put("elapsedMs", 50).put("openClStatus", -1001)
                .put("directPlatforms", 1).put("icdPlatforms", 0).put("gpuDevices", -1)
                .put("probeCached", duringOpen).put("output", "Private synthetic subtitle")))
            val backend = Backend().apply {
                if (duringOpen) onOpen = { throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND, diagnostic) }
                else generate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND, diagnostic) }
            }
            var creations = 0
            val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { creations++; backend }, executionMode = "OPENCL")
            try {
                val failure = fails<TranslationFailure> { engine.translate(listOf("Private synthetic source"), "en", "fr") { false } }
                assertEquals(TranslationFailure.Reason.LOCAL_BACKEND, failure.reason)
                assertSame(diagnostic, failure.localDiagnostic)
                assertTrue(failure.message.orEmpty().contains(diagnostic.reason.name))
                assertFalse(failure.message.orEmpty().contains("Private synthetic"))
                assertEquals(1, creations)
                assertEquals(if (duringOpen) 0 else 1, backend.prompts.size)
            } finally { engine.close() }
        }
    }

    @Test fun failedUnloadingReceiptPreventsCpuRetryAndKeepsTheErrorVisible() {
        val fixture = fixture()
        val gpu = Backend(LocalGgufBackend.Info("TranslateGemma 4B", "gemma3",
            LocalTranslationPerformance(phase = "load", backend = "OPENCL"))).apply {
            generate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND) }
            onAwaitedClose = { throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.TIMEOUT) }
        }
        var creations = 0
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { creations++; gpu })
        val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_PROCESS, failure.reason)
        assertEquals(1, creations)
        assertEquals(1, gpu.awaitedCloses)
        engine.close()
    }

    @Test fun automaticOpenFailureAlsoWaitsForItsNativeSessionBeforeCpuRetry() {
        val fixture = fixture()
        val released = AtomicBoolean(false)
        val gpu = Backend().apply {
            onOpen = { throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.DIED) }
            onAwaitedClose = { released.set(true) }
        }
        val cpu = Backend().apply { onOpen = { assertTrue(released.get()) } }
        var creations = 0
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { if (creations++ == 0) gpu else cpu })
        assertEquals(listOf("Bonjour."), engine.translate(listOf("Hello."), "en", "fr") { false })
        assertEquals(1, gpu.awaitedCloses)
        assertEquals(listOf("CPU"), cpu.modes)
        engine.close()
    }

    @Test fun explicitCpuAndOpenClNeverSilentlyRetryWithAnotherBackend() {
        for (mode in listOf("CPU", "OPENCL")) {
            val fixture = fixture()
            val backend = Backend().apply {
                generate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND) }
            }
            var creations = 0
            val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { creations++; backend }, executionMode = mode)
            val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
            assertEquals(TranslationFailure.Reason.LOCAL_BACKEND, failure.reason)
            assertEquals(1, creations)
            assertEquals(listOf(mode), backend.modes)
            engine.close()
        }
    }

    @Test fun performanceListenerKeepsMeasuredValuesAndCannotBreakTranslation() {
        val fixture = fixture()
        val backend = Backend().apply { generate = { _, _ -> LocalGgufBackend.Generation("Bonjour.", performance =
            LocalTranslationPerformance(backend = "OPENCL", device = "Synthetic GPU", gpuObserved = true,
                promptMs = 40, generationMs = 60, promptTokens = 50, generatedTokens = 6,
                cachedPromptTokens = 30, promptEvaluatedTokens = 20, gpuGraphNodes = 4,
                currentRssBytes = 1024, availableRamBytes = 4096, openClTransport = "DIRECT")) } }
        val engine = engine(fixture, backend)
        val reports = mutableListOf<LocalTranslationPerformance>()
        engine.setPerformanceListener { reports += it; throw IllegalStateException("Observer failure") }
        assertEquals(listOf("Bonjour."), engine.translate(listOf("Hello."), "en", "fr") { false })
        val report = reports.last()
        assertTrue(report.gpuObserved)
        assertEquals("DIRECT", report.openClTransport)
        assertTrue(report.render().contains("direct, sans chargeur ICD"))
        assertEquals(30, report.cachedPromptTokens)
        assertEquals(20, report.promptEvaluatedTokens)
        assertEquals(100.0, report.tokensPerSecond, 0.0)
        assertFalse(report.render().contains("Bonjour"))
        assertFalse(report.render().contains("Hello"))
        engine.close()
    }

    @Test fun confirmedWorkerUnloadingStillWorksAfterAsyncUiClose() {
        val fixture = fixture()
        val backend = Backend()
        val engine = engine(fixture, backend)
        engine.translate(listOf("Hello."), "en", "fr") { false }
        engine.close()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { engine.closeAndAwait(); engine.closeAndAwait() } catch (caught: Throwable) { failure.set(caught) } }
        worker.start(); worker.join(3000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, backend.awaitedCloses)
        assertTrue(fixture.store.remove())
    }
}
