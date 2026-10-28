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
            assertTrue(info.processId > 0)
            assertNotEquals(Process.myPid(), info.processId)
            for (prompt in listOf("The train leaves at seven.", "An independent synthetic request with é and 🚆.")) {
                val result = client.generate(prompt, maxOutputTokens = 1, addSpecialTokens = true)
                assertEquals(" probe", result.output)
                assertEquals(1, result.generatedTokens)
                assertEquals("end_of_generation", result.stopReason)
                assertTrue(result.promptTokens > 0 && result.peakRssBytes > 0)
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
            assertFalse(info.gpuObserved)
            val cold = cpu.generate("Prefix cache synthetic request.", 1, true)
            val warm = cpu.generate("Prefix cache synthetic request.", 1, true)
            assertEquals(cold.output, warm.output)
            assertEquals(0, cold.cachedPromptTokens)
            assertEquals(warm.promptTokens - 1, warm.cachedPromptTokens)
            assertEquals(1, warm.promptEvaluatedTokens)
            assertFalse(warm.gpuObserved)
            assertEquals(0L, warm.gpuGraphNodes)
            // This barrier is valid even when an asynchronous UI close happened first.
            cpu.close()
            cpu.closeAndAwait()
            val next = LocalGgufRuntime.Client(context)
            try {
                val auto = next.open(file, 1, 512, executionMode = "AUTO")
                assertEquals("AUTO", auto.requestedMode)
                if (auto.backend == "CPU") assertTrue(auto.fallbackReason.isNotEmpty())
                val output = next.generate("Independent backend comparison.", 1, true)
                assertEquals(" probe", output.output)
                assertEquals(0, output.cachedPromptTokens)
                if (auto.backend == "CPU") { assertFalse(output.gpuObserved); assertEquals(0L, output.gpuGraphNodes) }
            } finally { next.closeAndAwait() }
            if (android.os.Build.SUPPORTED_ABIS.first() == "x86_64") {
                val strict = LocalGgufRuntime.Client(context)
                try {
                    try { strict.open(file, 1, 512, executionMode = "OPENCL"); fail("CPU-only ABI claimed GPU execution") }
                    catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.BACKEND, failure.code) }
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
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.MODEL, failure.code) }
        } finally { invalid.close(); malformed.delete() }
    }
}
