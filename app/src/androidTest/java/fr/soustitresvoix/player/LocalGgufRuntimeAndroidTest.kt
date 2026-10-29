package fr.soustitresvoix.player

import android.content.ComponentName
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI and IPC, using only deterministic, untrained 51-KiB synthetic weights. */
@RunWith(AndroidJUnit4::class)
class LocalGgufRuntimeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(terminating: Boolean): File {
        val file = File(context.cacheDir, if (terminating) "translation-é-🚆-eos.gguf" else "translation-limit.gguf")
        if (terminating) instrumentation.context.assets.open("translation_test_model.gguf").use { input -> file.outputStream().use(input::copyTo) }
        else {
            val resource = context.resources.getIdentifier("diagnostic_model", "raw", context.packageName)
            assertTrue(resource != 0)
            context.resources.openRawResource(resource).use { input -> file.outputStream().use(input::copyTo) }
        }
        return file
    }
    private fun open(file: File): Pair<LocalGgufRuntime.Client, LocalGgufRuntime.Info> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val client = LocalGgufRuntime.Client(context)
            try { return client to client.open(file, threads = 1, contextTokens = 512) }
            catch (failure: LocalGgufRuntime.Failure) {
                client.close()
                if (failure.code != LocalGgufRuntime.Code.BUSY || System.nanoTime() >= deadline) throw failure
                Thread.sleep(50)
            }
        }
    }
    @Test fun oneLoadedSessionGeneratesTwiceWithTrueEosAndExcludesDiagnosticModel() {
        val file = fixture(true)
        val opened = open(file)
        val client = opened.first
        try {
            val service = context.packageManager.getServiceInfo(ComponentName(context, LocalGgufTranslationService::class.java), 0)
            assertFalse(service.exported)
            assertEquals("${context.packageName}:model_diagnostic", service.processName)
            val info = opened.second
            assertEquals("llama", info.architecture)
            assertTrue(info.modelBytes > 0 && info.modelParameters > 0)
            assertEquals(512, info.contextTokens)
            assertEquals("", info.openClTransport)
            // These measurements must survive the real native/service IPC boundary.
            val load = requireNotNull(info.loadProfile)
            assertEquals(1, load.loadAttempts)
            assertEquals(0L, load.openClPrepareMs)
            assertTrue(load.modelLoadMs + load.contextInitMs <= info.loadMs)
            assertTrue(load.openTotalMs >= info.loadMs)
            assertTrue(info.processId > 0)
            assertNotEquals(Process.myPid(), info.processId)
            for (prompt in listOf("The train leaves at seven.", "An independent synthetic request with é and 🚆.")) {
                val result = client.generate(prompt, maxOutputTokens = 1, addSpecialTokens = true)
                assertEquals(" probe", result.output)
                assertEquals(1, result.generatedTokens)
                assertEquals("end_of_generation", result.stopReason)
                assertTrue(result.promptTokens > 0 && result.peakRssBytes > 0)
                assertEquals("", result.openClTransport)
            }
            val done = CountDownLatch(1)
            val result = AtomicReference<LocalModelProbe.Result>()
            lateinit var probe: LocalModelProbeClient
            instrumentation.runOnMainSync {
                probe = LocalModelProbeClient(context, {}, { result.set(it); done.countDown() })
                probe.start(file, threads = 1, maxOutputTokens = 4)
            }
            try {
                assertTrue(done.await(20, TimeUnit.SECONDS))
                assertEquals(LocalModelProbe.Status.BUSY, result.get().status)
            } finally { instrumentation.runOnMainSync { probe.close() } }
            assertEquals(" probe", client.generate("Still usable after rejected diagnostic.", 1, true).output)
        } finally { client.close(); file.delete() }
    }
    @Test fun tokenLimitCannotPublishPartialOutputAndCloseReleasesModelForNextTask() {
        val repeating = fixture(false)
        val client = open(repeating).first
        try {
            try { client.generate("Synthetic completion", maxOutputTokens = 4, addSpecialTokens = true); fail("Truncated output was accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.OUTPUT_LIMIT, failure.code) }
            try { client.generate("a".repeat(8192), 32, true); fail("Oversized input was accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.INPUT_TOO_LONG, failure.code) }
        } finally { client.close(); repeating.delete() }
        val terminating = fixture(true)
        val next = open(terminating).first
        try { assertEquals(" probe", next.generate("Next task after rejected output", 1, true).output) }
        finally { next.close(); terminating.delete() }
    }
    @Test fun prefixReuseAndAcknowledgedCloseAllowAnImmediateBackendChange() {
        val file = fixture(true)
        val cpu = LocalGgufRuntime.Client(context)
        try {
            val info = cpu.open(file, 1, 512, executionMode = "CPU")
            assertEquals("CPU", info.backend)
            assertEquals("", info.openClTransport)
            assertFalse(info.gpuObserved)
            val cold = cpu.generate("Prefix cache synthetic request.", 1, true)
            val warm = cpu.generate("Prefix cache synthetic request.", 1, true)
            assertEquals(cold.output, warm.output)
            assertEquals(0, cold.cachedPromptTokens)
            assertEquals(warm.promptTokens - 1, warm.cachedPromptTokens)
            assertEquals(1, warm.promptEvaluatedTokens)
            assertFalse(warm.gpuObserved)
            assertEquals("", cold.openClTransport)
            assertEquals("", warm.openClTransport)
            assertEquals(0L, warm.gpuGraphNodes)
            val continuation = "Prefix cache synthetic request.${warm.output}</s> Another request."
            val continued = cpu.generate(continuation, 1, true)
            assertEquals(" probe", continued.output)
            assertEquals("end_of_generation", continued.stopReason)
            // Real JNI/IPC must report reuse of an evaluated output token,
            // beyond the previous prompt, rather than just its input prefix.
            // The sampled EOS was never decoded and must not count as reused.
            assertTrue(continued.cachedPromptTokens > warm.promptTokens)
            assertEquals(warm.promptTokens + warm.generatedTokens, continued.cachedPromptTokens)
            assertEquals(continued.promptTokens, continued.cachedPromptTokens + continued.promptEvaluatedTokens)
            assertTrue(continued.promptEvaluatedTokens >= 1)
            try { cpu.generate("x".repeat(8192), 32, true); fail("Oversized input was accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.INPUT_TOO_LONG, failure.code) }
            val resumed = cpu.generate(continuation, 1, true)
            assertEquals(continued.output, resumed.output)
            assertEquals(0, resumed.cachedPromptTokens)
            // This barrier is valid even when an asynchronous UI close happened first.
            cpu.close()
            cpu.closeAndAwait()
            val next = LocalGgufRuntime.Client(context)
            try {
                val auto = next.open(file, 1, 512, executionMode = "AUTO")
                assertEquals("AUTO", auto.requestedMode)
                if (auto.backend == "CPU") {
                    assertTrue(auto.fallbackReason.isNotEmpty())
                    assertEquals("", auto.openClTransport)
                } else {
                    assertEquals("OPENCL", auto.backend)
                    assertEquals("DIRECT", auto.openClTransport)
                }
                val output = next.generate("Independent backend comparison.", 1, true)
                assertEquals(" probe", output.output)
                assertEquals(0, output.cachedPromptTokens)
                if (output.backend == "CPU") {
                    assertFalse(output.gpuObserved)
                    assertEquals(0L, output.gpuGraphNodes)
                    assertEquals("", output.openClTransport)
                } else assertEquals("DIRECT", output.openClTransport)
                if (android.os.Build.SUPPORTED_ABIS.first() == "x86_64") {
                    assertEquals("CPU", auto.backend)
                    assertEquals(auto.fallbackReason, output.fallbackReason)
                    when (auto.fallbackReason) {
                        "OPENCL_RAM_BUDGET", "OPENCL_RAM_UNKNOWN" -> {
                            // Admission can avoid probing OpenCL on a memory-constrained emulator.
                            assertNull(auto.diagnostic)
                            assertNull(output.diagnostic)
                        }
                        "OPENCL_UNAVAILABLE" -> {
                            assertEquals(LocalGgufDiagnostic.Reason.BACKEND_NOT_COMPILED, auto.diagnostic?.reason)
                            assertEquals(LocalGgufDiagnostic.Stage.BACKEND, auto.diagnostic?.stage)
                            assertEquals(auto.diagnostic?.reason, output.diagnostic?.reason)
                            assertEquals(auto.diagnostic?.stage, output.diagnostic?.stage)
                        }
                        else -> fail("Unexpected CPU-only AUTO fallback: ${auto.fallbackReason}")
                    }
                }
            } finally { next.closeAndAwait() }
            if (android.os.Build.SUPPORTED_ABIS.first() == "x86_64") {
                val strict = LocalGgufRuntime.Client(context)
                try {
                    try { strict.open(file, 1, 512, executionMode = "OPENCL"); fail("CPU-only ABI claimed GPU execution") }
                    catch (failure: LocalGgufRuntime.Failure) {
                        assertEquals(LocalGgufRuntime.Code.BACKEND, failure.code)
                        assertEquals(LocalGgufDiagnostic.Stage.BACKEND, failure.diagnostic?.stage)
                        assertEquals(LocalGgufDiagnostic.Reason.BACKEND_NOT_COMPILED, failure.diagnostic?.reason)
                        assertTrue(requireNotNull(failure.diagnostic).elapsedMs >= 0)
                        assertFalse(requireNotNull(failure.diagnostic).probeCached)
                        assertFalse(requireNotNull(failure.diagnostic).render().contains(file.name))
                    }
                } finally { strict.closeAndAwait() }
            }
        } finally { cpu.closeAndAwait(); file.delete() }
    }
    @Test fun cancelledClientAndMalformedModelNeverProduceTranslation() {
        val file = fixture(true)
        val client = LocalGgufRuntime.Client(context)
        client.cancel()
        try {
            try { client.open(file, 1, 512); fail("Cancelled client opened a model") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.CANCELLED, failure.code) }
        } finally { client.close(); file.delete() }
        val malformed = File(context.cacheDir, "translation-corrupt.gguf").apply {
            writeBytes(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0) + ByteArray(16))
        }
        val invalid = LocalGgufRuntime.Client(context)
        try {
            try { invalid.open(malformed, 1, 512); fail("Malformed model was loaded") }
            catch (failure: LocalGgufRuntime.Failure) {
                assertEquals(LocalGgufRuntime.Code.MODEL, failure.code)
                assertEquals(LocalGgufDiagnostic.Stage.MODEL_LOAD, failure.diagnostic?.stage)
                assertEquals(LocalGgufDiagnostic.Reason.MODEL_LOAD_FAILED, failure.diagnostic?.reason)
                assertFalse(requireNotNull(failure.diagnostic).render().contains(malformed.name))
            }
        } finally { invalid.close(); malformed.delete() }
    }
}
