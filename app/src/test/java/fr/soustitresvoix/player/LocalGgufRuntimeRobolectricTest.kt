package fr.soustitresvoix.player

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGgufRuntimeRobolectricTest {
    private fun generation(stop: String = "end_of_generation", code: String = "OK") = JSONObject()
        .put("code", code).put("output", "Traduction synthétique.").put("promptTokens", 20)
        .put("generatedTokens", 5).put("stopReason", stop).put("promptMs", 40)
        .put("generationMs", 60).put("peakRssBytes", 1000).toString()
    @Test fun onlyExplicitEosSuccessCanReturnText() {
        assertEquals("Traduction synthétique.", LocalGgufRuntime.generation(generation()).output)
        for (stop in listOf("token_limit", "byte_limit", "error", "cancelled", "")) {
            try { LocalGgufRuntime.generation(generation(stop)); fail("Non-EOS response accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
        try { LocalGgufRuntime.generation(generation(code = "OUTPUT_LIMIT")); fail("Partial response accepted") }
        catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.OUTPUT_LIMIT, failure.code) }
    }
    @Test fun gpuEvidenceAndPrefixAccountingCannotBeInventedByMetadataAlone() {
        val valid = JSONObject(generation()).put("backend", "OPENCL").put("requestedMode", "OPENCL")
            .put("gpuObserved", true).put("gpuGraphNodes", 3).put("cachedPromptTokens", 15).put("promptEvaluatedTokens", 5)
        val observed = LocalGgufRuntime.generation(valid.toString())
        assertTrue(observed.gpuObserved)
        assertEquals(3L, observed.gpuGraphNodes)
        assertEquals(15, observed.cachedPromptTokens)
        for (invalid in listOf(
            JSONObject(valid.toString()).put("gpuGraphNodes", 0),
            JSONObject(valid.toString()).put("backend", "CPU").put("gpuObserved", false),
            JSONObject(valid.toString()).put("promptEvaluatedTokens", 4),
            JSONObject(valid.toString()).put("requestedMode", "INVENTED"))) {
            try { LocalGgufRuntime.generation(invalid.toString()); fail("Unproven GPU or prefix statistics accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }
    @Test fun malformedAndInventedResponseCannotSucceed() {
        for (json in listOf("", "{}", "not json", generation(code = "INVENTED"))) {
            try { LocalGgufRuntime.generation(json); fail("Malformed response accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }
    @Test fun mainThreadCallsFailBeforeBindingAndCloseNeverWaits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LocalGgufRuntime.Client(context)
        try {
            try { client.open(File(context.filesDir, "absent.gguf")); fail("Main-thread inference accepted") }
            catch (_: IllegalStateException) { }
            client.cancel(); client.close(); client.close()
            try { client.closeAndAwait(); fail("Main-thread release wait accepted") }
            catch (_: IllegalStateException) { }
        } finally { client.close() }
    }
    @Test fun disconnectedLiveBinderCannotBeMistakenForAnAcknowledgedRelease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LocalGgufRuntime.Client(context)
        val type = client.javaClass
        type.getDeclaredField("ownershipPossible").apply { isAccessible = true }.setBoolean(client, true)
        type.getDeclaredField("remote").apply { isAccessible = true }.set(client, Messenger(Handler(Looper.getMainLooper())))
        type.getDeclaredMethod("lost").apply { isAccessible = true }.invoke(client)
        client.close()
        shadowOf(Looper.getMainLooper()).idle()
        val result = java.util.concurrent.atomic.AtomicReference<LocalGgufRuntime.Code?>()
        val done = java.util.concurrent.CountDownLatch(1)
        Thread {
            try { client.closeAndAwait(); result.set(null) }
            catch (failure: LocalGgufRuntime.Failure) { result.set(failure.code) }
            finally { done.countDown() }
        }.start()
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(LocalGgufRuntime.Code.DIED, result.get())
    }
    @Test fun staleBinderDeathCannotCancelAnotherOwnerEvenWithReusedNumericId() {
        val host = Robolectric.buildService(LocalGgufTranslationService::class.java).create()
        val service = host.get()
        val register = service.javaClass.getDeclaredMethod("registerOwner", Messenger::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val death = service.javaClass.getDeclaredField("deathRecipient").apply { isAccessible = true }
        val owner = service.javaClass.getDeclaredField("owner").apply { isAccessible = true }
        val cancelled = service.javaClass.getDeclaredField("ownerCancelled").apply { isAccessible = true }
        try {
            register.invoke(service, Messenger(Handler(Looper.getMainLooper())), 44L)
            val previousDeath = death.get(service) as IBinder.DeathRecipient
            register.invoke(service, Messenger(Handler(Looper.getMainLooper())), 44L)
            previousDeath.binderDied()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(44L, owner.get(service) as Long)
            assertFalse((cancelled.get(service) as AtomicBoolean).get())
        } finally { host.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun privatePathValidationRejectsExternalAndSymlinkEscape() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outside = File.createTempFile("outside-translation-", ".gguf").apply { writeBytes(ByteArray(24)) }
        val privateFile = File(context.filesDir, "private-synthetic.gguf").apply { writeBytes(ByteArray(24)) }
        val link = File(context.filesDir, "escaped-synthetic.gguf")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(privateGgufFile(context, link.path))
            assertNull(privateGgufFile(context, outside.path))
            assertEquals(privateFile.canonicalFile, privateGgufFile(context, privateFile.path))
            assertNull(privateGgufFile(context, File(context.filesDir, "../outside.gguf").path))
        } finally { link.delete(); outside.delete(); privateFile.delete() }
    }
}
