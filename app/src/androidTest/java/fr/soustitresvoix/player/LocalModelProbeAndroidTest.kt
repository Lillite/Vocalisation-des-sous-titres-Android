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

/** Real JNI inference through Android IPC, using deterministic synthetic weights. */
@RunWith(AndroidJUnit4::class)
class LocalModelProbeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun probe(file: File): LocalModelProbe.Result {
        val done = CountDownLatch(1)
        val result = AtomicReference<LocalModelProbe.Result>()
        lateinit var client: LocalModelProbeClient
        instrumentation.runOnMainSync {
            client = LocalModelProbeClient(context, {}, { result.set(it); done.countDown() })
            client.start(file, threads = 1, maxOutputTokens = 4)
        }
        try {
            assertTrue("Isolated native diagnostic did not finish", done.await(45, TimeUnit.SECONDS))
            return result.get()
        } finally { instrumentation.runOnMainSync { client.close() } }
    }

    @Test fun realSyntheticGgufLoadsAndGeneratesOnCpuInSeparateProcess() {
        val resource = context.resources.getIdentifier("diagnostic_model", "raw", context.packageName)
        assertTrue("The generated 51-KiB fixture is missing from the tested APK", resource != 0)
        val file = File(context.cacheDir, "synthetic-é-🚆-probe.gguf")
        try {
            context.resources.openRawResource(resource).use { input -> file.outputStream().use(input::copyTo) }
            val info = context.packageManager.getServiceInfo(ComponentName(context, LocalModelProbeService::class.java), 0)
            assertFalse(info.exported)
            assertEquals("${context.packageName}:model_diagnostic", info.processName)
            val result = probe(file)
            assertEquals(result.detail, LocalModelProbe.Status.COMPLETED, result.status)
            assertEquals("CPU", result.backend)
            assertEquals(" probe probe probe probe", result.output)
            assertEquals(4, result.generatedTokens)
            assertTrue(result.promptTokens > 0)
            assertTrue(result.modelBytes > 0 && result.modelParameters > 0)
            assertEquals(512, result.contextTokens)
            assertTrue(result.processId > 0)
            assertNotEquals(Process.myPid(), result.processId)
            assertTrue(result.peakRssBytes > 0)
            assertEquals("token_limit", result.stopReason)
            assertEquals(LocalModelProbe.REVISION, result.runtimeRevision)
        } finally { file.delete() }
    }

    @Test fun malformedPrivateModelProducesFailureAndDoesNotPoisonNextTrial() {
        val file = File(context.cacheDir, "invalid-synthetic-probe.gguf")
        try {
            file.writeBytes(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0) + ByteArray(16))
            val result = probe(file)
            assertEquals(LocalModelProbe.Status.FAILED, result.status)
            assertEquals("non exécuté", result.backend)
            assertEquals(0, result.generatedTokens)
            assertTrue(result.output.isEmpty())
            assertFalse(result.detail.contains(file.path))
            val resource = context.resources.getIdentifier("diagnostic_model", "raw", context.packageName)
            assertTrue(resource != 0)
            context.resources.openRawResource(resource).use { input -> file.outputStream().use(input::copyTo) }
            val retried = probe(file)
            assertEquals(retried.detail, LocalModelProbe.Status.COMPLETED, retried.status)
            assertEquals(4, retried.generatedTokens)
        } finally { file.delete() }
    }
}
