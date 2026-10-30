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
        val chatPrompts = mutableListOf<String>()
        var chatGenerate: (String, () -> Boolean) -> LocalGgufBackend.Generation = { _, _ -> LocalGgufBackend.Generation("Bonjour.") }
        var generate: (String, () -> Boolean) -> LocalGgufBackend.Generation = { _, _ -> LocalGgufBackend.Generation("Bonjour.") }
        var onOpen: () -> Unit = {}
        var onAwaitedClose: () -> Unit = {}
        override fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean, executionMode: String): LocalGgufBackend.Info {
            opens++; modes += executionMode; assertEquals(2048, contextTokens); onOpen(); return metadata
        }
        override fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation {
            prompts += prompt; assertEquals(512, maxOutputTokens); return generate(prompt, cancelled)
        }
        override fun generateChat(userContent: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation {
            chatPrompts += userContent; assertEquals(512, maxOutputTokens); return chatGenerate(userContent, cancelled)
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

    @Test fun baseGemmaWithoutARecognizedTemplateCannotBeUsedAsTranslateGemma() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("Google Gemma 3 4B IT", "gemma3"))
        val failure = fails<TranslationFailure> { engine(fixture, backend).translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_TEMPLATE, failure.reason)
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
        assertEquals(TranslationFailure.Reason.LOCAL_PROCESS_DIED, failure.reason)
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
        assertTrue(base.matches(Regex("local-gguf-v5-[0-9a-f]{64}")))
        // A completed v2 journal remains its own saved result. New work must
        // not silently reuse translations made with the previous prompt rules.
        val oldInput = "local-gguf-text-v2\n${fixture.model.sha256}\n${LocalModelProbe.REVISION}\n4\n2048\n512\nauto\nAUTO\n"
        val oldDigest = java.security.MessageDigest.getInstance("SHA-256").digest(oldInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        assertNotEquals("local-gguf-v2-$oldDigest", base)
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
        assertEquals(TranslationFailure.Reason.LOCAL_TIMEOUT, failure.reason)
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

    @Test fun completeHistoricalPairsKeepTheirFinalNegationAndQuantityForBothFamilies() {
        val original = "Earlier description 😀. ".repeat(30) + "Do not leave before twelve minutes."
        val translated = "Description précédente 😀. ".repeat(30) + "Ne partez pas avant douze minutes."
        for (metadata in listOf(LocalGgufBackend.Info("TranslateGemma 4B", "gemma3"),
            LocalGgufBackend.Info("Hy-MT2-7B", "hunyuan-dense"))) {
            val fixture = fixture()
            val backend = Backend(metadata)
            val engine = engine(fixture, backend)
            try {
                assertEquals(listOf("Bonjour."), engine.translateWithContext(listOf("What happens next?"), "en", "fr",
                    listOf(original), listOf(translated)) { false })
                assertTrue(backend.prompts.single().contains(original))
                assertTrue(backend.prompts.single().contains(translated))
                assertEquals(1, backend.opens)
            } finally { engine.close() }
        }
    }

    @Test fun inputBudgetRemovesOldestWholePairsWithoutClippingTheCurrentSource() {
        val fixture = fixture()
        val older = "Older detail. ".repeat(40) + "Do not wait for eleven minutes."
        val olderTranslation = "Ancien détail. ".repeat(40) + "N'attendez pas onze minutes."
        val newer = "Newer detail 😀. ".repeat(40) + "There are twelve minutes remaining."
        val newerTranslation = "Détail récent 😀. ".repeat(40) + "Il reste douze minutes."
        val source = " \nKeep every character: 😀 12.\n "
        val backend = Backend().apply { generate = { prompt, _ ->
            if (older in prompt || newer in prompt)
                throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.INPUT_TOO_LONG)
            LocalGgufBackend.Generation("Conservez chaque caractère : 😀 12.")
        } }
        val engine = engine(fixture, backend)
        try {
            assertEquals(listOf("Conservez chaque caractère : 😀 12."), engine.translateWithContext(listOf(source), "en", "fr",
                listOf(older, newer), listOf(olderTranslation, newerTranslation)) { false })
            assertEquals(3, backend.prompts.size)
            assertTrue(backend.prompts[0].contains(older))
            assertTrue(backend.prompts[0].contains(olderTranslation))
            assertTrue(backend.prompts[0].contains(newer))
            assertTrue(backend.prompts[0].contains(newerTranslation))
            assertFalse(backend.prompts[1].contains(older))
            assertFalse(backend.prompts[1].contains(olderTranslation))
            assertTrue(backend.prompts[1].contains(newer))
            assertTrue(backend.prompts[1].contains(newerTranslation))
            assertFalse(backend.prompts[2].contains(newer))
            assertFalse(backend.prompts[2].contains(newerTranslation))
            backend.prompts.forEach { assertTrue(it.endsWith(":\n\n\n$source<end_of_turn>\n<start_of_turn>model\n")) }
            assertEquals(1, backend.opens)
        } finally { engine.close() }
    }

    @Test fun splitCueProvidesTheCompletePreviousFragmentAndTranslationAsContext() {
        val fixture = fixture()
        val source = "Alpha 😀 beta gamma. ".repeat(60)
        val fragments = mutableListOf<String>()
        val leftTranslation = "Traduction détaillée 😀. ".repeat(30) + "Ne partez pas avant douze minutes."
        val backend = Backend().apply { generate = { prompt, _ ->
            val current = prompt.substringAfterLast("text into French:\n\n\n").substringBefore("<end_of_turn>")
            if (current.codePointCount(0, current.length) > 700)
                throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.INPUT_TOO_LONG)
            if (fragments.isNotEmpty()) {
                assertTrue(prompt.contains(fragments.first()))
                assertTrue(prompt.contains(leftTranslation))
            }
            fragments += current
            LocalGgufBackend.Generation(if (fragments.size == 1) leftTranslation else "Suite.")
        } }
        val engine = engine(fixture, backend)
        try {
            assertEquals(listOf("$leftTranslation\nSuite."), engine.translate(listOf(source), "en", "fr") { false })
            assertEquals(2, fragments.size)
            assertTrue(fragments.all { it.codePointCount(0, it.length) > 300 })
            assertEquals(source, fragments.joinToString(""))
            assertEquals(3, backend.prompts.size)
        } finally { engine.close() }
    }

    @Test fun additionalReservedTokensFailBeforeOpeningAModelInSourcesAndHistory() {
        for (token in listOf("<|endoftext|>", "<|bos|>", "<|pad|>", "<|extra_203|>", "<start_of_image>")) {
            for (slot in 0..2) {
                val fixture = fixture()
                val backend = Backend()
                val engine = engine(fixture, backend)
                try {
                    val failure = fails<TranslationFailure> {
                        engine.translateWithContext(listOf(if (slot == 0) "Literal $token" else "Current source."), "en", "fr",
                            listOf(if (slot == 1) "Literal $token" else "Earlier source."),
                            listOf(if (slot == 2) "Literal $token" else "Traduction précédente.")) { false }
                    }
                    assertEquals(TranslationFailure.Reason.LOCAL_CONTEXT, failure.reason)
                    assertEquals(0, backend.opens)
                    assertTrue(backend.prompts.isEmpty())
                } finally { engine.close() }
            }
        }
    }

    @Test fun additionalReservedTokensCannotLeaveTheEngineAsCompletedOutput() {
        for (token in listOf("<|extra_203|>", "<start_of_image>")) {
            val fixture = fixture()
            val backend = Backend().apply { generate = { _, _ -> LocalGgufBackend.Generation("Une réponse $token") } }
            val engine = engine(fixture, backend)
            try {
                val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
                assertEquals(TranslationFailure.Reason.RESPONSE, failure.reason)
                assertEquals(1, backend.prompts.size)
            } finally { engine.close() }
        }
    }
    @Test fun explicitContextPoliciesPreserveWholePairsAndCurrentSource() {
        val source = " \nThe original letter belongs to Maya. 😀\n "
        val previousSources = (1..5).map { "Earlier source $it " + "s".repeat(350) }
        val previousOutputs = (1..5).map { "Traduction précédente $it " + "t".repeat(350) }
        for (policy in LocalGgufContextPolicy.entries) {
            val fixture = fixture(); val backend = Backend()
            val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, contextPolicy = policy)
            try {
                engine.translateWithContext(listOf(source), "en", "fr", previousSources, previousOutputs) { false }
                val prompt = backend.prompts.single()
                assertTrue(prompt.endsWith(":\n\n\n$source<end_of_turn>\n<start_of_turn>model\n"))
                val expected = previousSources.zip(previousOutputs).takeLast(policy.maximumPreviousPairs)
                for ((original, translated) in expected) { assertTrue(prompt.contains(original)); assertTrue(prompt.contains(translated)) }
                for (original in previousSources.dropLast(policy.maximumPreviousPairs)) assertFalse(prompt.contains(original))
                assertEquals(policy.contextStartIndex(7), engine.contextStartIndex(7))
            } finally { engine.close() }
        }
    }

    @Test fun contextPolicyChangesTheResultIdentityAndCacheHitNeverLoadsOrClaimsGpuWork() {
        val fixture = fixture(); var factories = 0
        val identities = LocalGgufContextPolicy.entries.map { policy ->
            LocalGgufTranslationEngine.identity(fixture.model, contextPolicy = policy)
        }
        assertEquals(3, identities.distinct().size)
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { factories++; Backend() }, executionMode = "OPENCL")
        val frames = mutableListOf<LocalTranslationPerformance>()
        engine.setPerformanceListener { frames += it }
        assertTrue(engine.supportsExactRequestCache)
        engine.onExactRequestCacheHit()
        val frame = frames.single()
        assertEquals("cache", frame.phase); assertEquals("OPENCL", frame.requestedMode)
        assertEquals("", frame.backend); assertFalse(frame.gpuObserved)
        assertEquals(0, frame.generatedTokens); assertEquals(0L, frame.loadMs)
        assertEquals(0L, frame.generationMs); assertEquals(0, factories)
        engine.close()
    }

    @Test fun oversizedBlockDropsCompleteOldPairsWithoutClippingTheCurrentSource() {
        val fixture = fixture(); val backend = Backend()
        val source = "Keep every word of this new source. " + "x".repeat(500)
        val older = "Oldest source " + "a".repeat(500)
        backend.generate = { prompt, _ ->
            if (older in prompt) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.INPUT_TOO_LONG)
            LocalGgufBackend.Generation("Traduction complète synthétique.")
        }
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, contextPolicy = LocalGgufContextPolicy.BLOCKS_SIX)
        try {
            engine.translateWithContext(listOf(source), "en", "fr", listOf(older, "Most recent source."),
                listOf("Ancienne traduction entière.", "Traduction récente entière.")) { false }
            assertEquals(2, backend.prompts.size)
            assertTrue(backend.prompts.first().contains(older))
            assertFalse(backend.prompts.last().contains(older))
            assertFalse(backend.prompts.last().contains("Ancienne traduction entière."))
            assertTrue(backend.prompts.all { source in it })
            assertTrue(backend.prompts.last().contains("Most recent source."))
            assertTrue(backend.prompts.last().contains("Traduction récente entière."))
        } finally { engine.close() }
    }

    @Test fun exactCacheHitValidationRefusesClosedCancelledAndReservedInputsWithoutLoading() {
        val fixture = fixture(); var factories = 0
        val model = LocalGgufModel(fixture.model.file, fixture.model.sha256, fixture.model.bytes,
            fixture.model.modifiedMillis, LocalGgufMetadata(architecture = "gemma3", name = "TranslateGemma"))
        val engine = LocalGgufTranslationEngine(model, fixture.store, { factories++; Backend() })
        fun validate(source: String = "Keep the letter.", language: String = "en",
            previous: List<String> = emptyList(), cancelled: () -> Boolean = { false }) =
            engine.validateExactRequestCacheHit(listOf(source), language, "fr", previous, previous.map { "Traduction précédente." }, cancelled)
        validate()
        assertEquals(TranslationFailure.Reason.LOCAL_CONTEXT,
            fails<TranslationFailure> { validate(source = "Literal <start_of_turn> marker.") }.reason)
        assertEquals(TranslationFailure.Reason.LOCAL_CONTEXT,
            fails<TranslationFailure> { validate(previous = listOf("Literal <|extra_0|> marker.")) }.reason)
        assertEquals(TranslationFailure.Reason.LANGUAGE,
            fails<TranslationFailure> { validate(language = "yue") }.reason)
        fails<TranslationCancelledException> { validate(cancelled = { true }) }
        assertEquals(0, factories)
        engine.close()
        fails<TranslationCancelledException> { validate() }
        assertEquals(0, factories)
    }

    @Test fun explicitIndexUsesItsPublisherRawWrapperWithoutRequiringGenericTemplateCapability() {
        val fixture = fixture(); val backend = Backend(LocalGgufBackend.Info("9b", "qwen35"))
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = LocalGgufProfile.INDEX_TRANSLATE)
        try {
            assertEquals(listOf("Bonjour."), engine.translate(listOf("Keep the original letter."), "en", "fr") { false })
            assertEquals(1, backend.opens); assertTrue(backend.chatPrompts.isEmpty())
            assertEquals("<|im_start|>user\n请将以下英语文本翻译为法语，直接输出翻译结果，不要进行任何解释。\n\n" +
                "Keep the original letter.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", backend.prompts.single())
        } finally { engine.close() }
    }

    @Test fun explicitAndIdentifiedAutomaticQwen35UseRawNonThinkingFramingWithoutTheGenericChatBridge() {
        for (profile in listOf(LocalGgufProfile.QWEN35_INSTRUCT, LocalGgufProfile.AUTO)) {
            val fixture = fixture()
            val backend = Backend(LocalGgufBackend.Info("Qwen3.5-9B", "qwen35", chatTemplateAvailable = false))
            backend.chatGenerate = { _, _ -> throw AssertionError("Qwen3.5 requires its explicit non-thinking raw wrapper") }
            val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = profile)
            val source = " \nKeep the original letter. 😀\n "
            try {
                assertEquals(listOf("Bonjour."), engine.translate(listOf(source), "en", "fr") { false })
                assertEquals(1, backend.opens)
                assertTrue(backend.chatPrompts.isEmpty())
                val prompt = backend.prompts.single()
                assertTrue(prompt.startsWith("<|im_start|>user\nTranslate the source text from English (en) into French (fr).\n"))
                assertTrue(prompt.contains("[Source text]\n$source\n[End source text]"))
                assertTrue(prompt.endsWith("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
                assertEquals(1, Regex(Regex.escape("<think>")).findAll(prompt).count())
                assertEquals(1, Regex(Regex.escape("</think>")).findAll(prompt).count())
                assertFalse(prompt.contains("/nothink"))
            } finally { engine.close() }
        }
    }

    @Test fun qwen35ReasoningMarkersInAnOtherwiseCompletedResponseAreRejectedWithoutStrippingOrRetry() {
        for (profile in listOf(LocalGgufProfile.QWEN35_INSTRUCT, LocalGgufProfile.AUTO)) {
            for (output in listOf("<think>Reasoning about the text.</think>\nBonjour.", "Bonjour.</think>")) {
                val fixture = fixture()
                val backend = Backend(LocalGgufBackend.Info("Qwen3.5-9B", "qwen35"))
                backend.generate = { _, _ -> LocalGgufBackend.Generation(output) }
                val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = profile)
                try {
                    val failure = fails<TranslationFailure> {
                        engine.translate(listOf("Keep the original letter."), "en", "fr") { false }
                    }
                    assertEquals(TranslationFailure.Reason.RESPONSE, failure.reason)
                    assertEquals(1, backend.opens)
                    assertEquals(1, backend.prompts.size)
                    assertTrue(backend.chatPrompts.isEmpty())
                } finally { engine.close() }
            }
        }
    }

    @Test fun automaticOtherInstructModelUsesRecognizedNativeConversationFormatWithoutRawFallback() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("Another instruction model", "llama", chatTemplateAvailable = true))
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, context = "A fictional scene.")
        val source = " \nDo not give it to him. 😀\n "
        val previous = "Previous source " + "a".repeat(400)
        try {
            engine.translateWithContext(listOf(source), "en", "fr", listOf(previous), listOf("Traduction antérieure complète.")) { false }
            assertEquals(1, backend.opens); assertTrue(backend.prompts.isEmpty())
            val content = backend.chatPrompts.single()
            assertTrue(content.contains("[Source text]\n$source\n[End source text]"))
            assertTrue(content.contains(previous)); assertTrue(content.contains("A fictional scene."))
            assertFalse(LocalGgufPrompts.hasControlTokens(content))
        } finally { engine.close() }
    }

    @Test fun genericProfileRequiresRuntimeFormatCapabilityAndDoesNotAssumeChatml() {
        val fixture = fixture(); val backend = Backend(LocalGgufBackend.Info("Another model", "llama"))
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = LocalGgufProfile.MODEL_TEMPLATE)
        val failure = fails<TranslationFailure> { engine.translate(listOf("Hello."), "en", "fr") { false } }
        assertEquals(TranslationFailure.Reason.LOCAL_TEMPLATE, failure.reason)
        assertEquals(1, backend.opens); assertTrue(backend.prompts.isEmpty()); assertTrue(backend.chatPrompts.isEmpty())
        engine.close()
        // Even a capability advertisement must not silently route to raw text
        // when an injected/older bridge has no conversation implementation.
        val withoutChatBridge = object : LocalGgufBackend {
            override fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean, executionMode: String) =
                LocalGgufBackend.Info("Another model", "llama", chatTemplateAvailable = true)
            override fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation =
                throw AssertionError("A missing chat bridge must never use raw generation")
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val unsupportedBridge = LocalGgufTranslationEngine(fixture.model, fixture.store, { withoutChatBridge },
            profile = LocalGgufProfile.MODEL_TEMPLATE)
        try {
            assertEquals(TranslationFailure.Reason.LOCAL_TEMPLATE,
                fails<TranslationFailure> { unsupportedBridge.translate(listOf("Hello."), "en", "fr") { false } }.reason)
        } finally { unsupportedBridge.close() }
    }

    @Test fun nativeTemplateFailureRemainsVisibleWithoutCpuRetryOrRawPromptFallback() {
        val fixture = fixture()
        val backend = Backend(LocalGgufBackend.Info("Another model", "llama",
            LocalTranslationPerformance(phase = "load", backend = "OPENCL"), chatTemplateAvailable = true))
        backend.chatGenerate = { _, _ -> throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.TEMPLATE) }
        val engine = engine(fixture, backend)
        try {
            val failure = fails<TranslationFailure> { engine.translate(listOf("Keep every word."), "en", "fr") { false } }
            assertEquals(TranslationFailure.Reason.LOCAL_TEMPLATE, failure.reason)
            assertEquals(1, backend.opens); assertEquals(1, backend.chatPrompts.size); assertTrue(backend.prompts.isEmpty())
        } finally { engine.close() }
    }

    @Test fun bothExtendedProfilesSplitLongUnicodeSourcesWithoutLosingOrReorderingAnyCharacter() {
        for (generic in listOf(false, true)) {
            val fixture = fixture(); val fragments = mutableListOf<String>()
            val backend = Backend(LocalGgufBackend.Info(if (generic) "Another model" else "9b",
                if (generic) "llama" else "qwen35", chatTemplateAvailable = generic))
            fun translate(prompt: String): LocalGgufBackend.Generation {
                val source = if (generic) prompt.substringAfter("[Source text]\n").substringBeforeLast("\n[End source text]")
                    else if ("【源文】\n" in prompt) prompt.substringAfter("【源文】\n").substringBefore("\n\n【约束要求】")
                    else prompt.substringAfter("不要进行任何解释。\n\n").substringBefore("<|im_end|>")
                if (source.codePointCount(0, source.length) > 120)
                    throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.INPUT_TOO_LONG)
                assertTrue(LocalGgufTranslationEngine.validText(source)); fragments += source
                return LocalGgufBackend.Generation("Traduction synthétique.")
            }
            backend.generate = { prompt, _ -> translate(prompt) }
            backend.chatGenerate = { content, _ -> translate(content) }
            val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend },
                profile = if (generic) LocalGgufProfile.MODEL_TEMPLATE else LocalGgufProfile.INDEX_TRANSLATE)
            val source = ("Texte 😀漢字 et dialogue. ").repeat(30)
            try {
                val result = engine.translate(listOf(source), "en", "fr") { false }
                assertEquals(1, result.size); assertTrue(fragments.size > 1)
                assertEquals(source, fragments.joinToString(""))
            } finally { engine.close() }
        }
    }

    @Test fun genericConversationFormatCannotOverrideANativeModelLoadFailure() {
        val fixture = fixture(); val backend = Backend().apply {
            onOpen = { throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.MODEL) }
        }
        val engine = LocalGgufTranslationEngine(fixture.model, fixture.store, { backend }, profile = LocalGgufProfile.MODEL_TEMPLATE)
        try {
            val failure = fails<TranslationFailure> { engine.translate(listOf("Keep this source."), "en", "fr") { false } }
            assertEquals(TranslationFailure.Reason.LOCAL_MODEL, failure.reason)
            assertEquals(1, backend.opens); assertTrue(backend.prompts.isEmpty()); assertTrue(backend.chatPrompts.isEmpty())
        } finally { engine.close() }
    }

    @Test fun positivelyIdentifiedUnchangedLegacyProfilesKeepTheirPublishedVersionFourIdentities() {
        val fixture = fixture()
        assertEquals("14f702136f2d7773c9f2ddd6c5462e679fbec2d3640079b80ebcc89664849402", fixture.model.sha256)
        val identified = LocalGgufModel(fixture.model.file, fixture.model.sha256, fixture.model.bytes,
            fixture.model.modifiedMillis, LocalGgufMetadata(architecture = "gemma3", name = "TranslateGemma"))
        val expected = mapOf(
            LocalGgufProfile.AUTO to "local-gguf-v4-bf7d4f4362324cb0a91a6d74175ac35e2868ff6f10ab33caea53608edbcc8998",
            LocalGgufProfile.TRANSLATE_GEMMA to "local-gguf-v4-ebc414faab838c79f2c3862a11f0088efaaf126112169c9ad521e42d5ee61536",
            LocalGgufProfile.HY_MT to "local-gguf-v4-6fb08165e1a458cb7aeddff415084699d351fbee4cb7249991a3537eda96fc5c"
        )
        expected.forEach { (profile, identity) ->
            assertEquals(identity, LocalGgufTranslationEngine.identity(identified, profile = profile))
        }
        val newIdentities = listOf(LocalGgufProfile.INDEX_TRANSLATE, LocalGgufProfile.MODEL_TEMPLATE).map {
            LocalGgufTranslationEngine.identity(fixture.model, profile = it)
        }
        assertEquals(2, newIdentities.distinct().size)
        assertTrue(newIdentities.none { it in expected.values })
    }

}
