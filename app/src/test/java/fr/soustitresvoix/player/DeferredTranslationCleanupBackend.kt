package fr.soustitresvoix.player

import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue

/** Controlled native boundary; translation owners, journals and UI are real. */
internal class DeferredTranslationCleanupBackend(
    private val closeFailure: LocalGgufRuntime.Code? = null
) : LocalGgufBackend {
    val cleanupEntered = CountDownLatch(1)
    val acknowledgeRelease = CountDownLatch(1)
    val nonBlockingClose = CountDownLatch(1)
    val generateCalls = AtomicInteger()
    val openCalls = AtomicInteger()
    var beforeGeneration: (Int, () -> Boolean) -> Unit = { _, _ -> }

    override fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean,
        executionMode: String): LocalGgufBackend.Info {
        openCalls.incrementAndGet()
        return LocalGgufBackend.Info("Google TranslateGemma 4B IT", "gemma3")
    }

    override fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation {
        val call = generateCalls.incrementAndGet()
        beforeGeneration(call, cancelled)
        if (cancelled()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
        return LocalGgufBackend.Generation("Traduction synthétique $call.")
    }

    override fun cancel() { nonBlockingClose.countDown() }
    override fun close() { nonBlockingClose.countDown() }
    override fun closeAndAwait(cancelled: () -> Boolean, timeoutMs: Long) {
        assertNotSame("The release barrier must never block the UI", Looper.getMainLooper(), Looper.myLooper())
        cleanupEntered.countDown()
        closeFailure?.let { throw LocalGgufRuntime.Failure(it) }
        assertTrue("The controlled release acknowledgement did not arrive",
            acknowledgeRelease.await(timeoutMs, TimeUnit.MILLISECONDS))
    }
}
