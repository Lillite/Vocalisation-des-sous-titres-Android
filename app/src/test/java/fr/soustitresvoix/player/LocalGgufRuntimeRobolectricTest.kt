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
            .put("openClTransport", "DIRECT")
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
    @Test fun directTransportIsValidatedAndNeverSubstitutesForActualGpuEvidence() {
        val cpuInfo = JSONObject().put("code", "OK").put("name", "Synthetic model")
            .put("architecture", "gemma3").put("fileType", "Q4_0").put("contextTokens", 512)
            .put("modelBytes", 1024).put("modelParameters", 100).put("loadMs", 5).put("peakRssBytes", 1000)
        val directInfo = JSONObject(cpuInfo.toString()).put("backend", "OPENCL").put("requestedMode", "OPENCL")
            .put("openClTransport", "DIRECT")
        val loaded = LocalGgufRuntime.info(directInfo.toString())
        assertEquals("DIRECT", loaded.openClTransport)
        assertFalse(loaded.gpuObserved)
        val report = LocalTranslationPerformance(phase = "load", backend = loaded.backend,
            openClTransport = loaded.openClTransport, gpuObserved = loaded.gpuObserved).render()
        assertTrue(report.contains("direct, sans chargeur ICD"))
        assertTrue(report.contains("GPU réellement observé : non établi"))
        assertEquals("", LocalGgufRuntime.info(cpuInfo.toString()).openClTransport)

        val directGeneration = JSONObject(generation()).put("backend", "OPENCL").put("requestedMode", "OPENCL")
            .put("openClTransport", "DIRECT").put("gpuObserved", true).put("gpuGraphNodes", 3)
        val generated = LocalGgufRuntime.generation(directGeneration.toString())
        assertEquals("DIRECT", generated.openClTransport)
        assertTrue(generated.gpuObserved)
        assertEquals(3L, generated.gpuGraphNodes)
        assertEquals("", LocalGgufRuntime.generation(generation()).openClTransport)

        val invalidInfo = mutableListOf<JSONObject>()
        val invalidGeneration = mutableListOf<JSONObject>()
        for (value in listOf(JSONObject.NULL, 7, true, "ICD", "/private/not-a-transport.so")) {
            invalidInfo += JSONObject(cpuInfo.toString()).put("openClTransport", value)
            invalidGeneration += JSONObject(generation()).put("openClTransport", value)
        }
        invalidInfo += JSONObject(cpuInfo.toString()).put("openClTransport", "DIRECT")
        invalidGeneration += JSONObject(generation()).put("openClTransport", "DIRECT")
        for (value in listOf("", null)) {
            invalidInfo += JSONObject(directInfo.toString()).apply {
                if (value == null) remove("openClTransport") else put("openClTransport", value)
            }
            invalidGeneration += JSONObject(directGeneration.toString()).apply {
                if (value == null) remove("openClTransport") else put("openClTransport", value)
            }
        }
        for (value in invalidInfo) {
            try { LocalGgufRuntime.info(value.toString()); fail("An invalid or contradictory transport was accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
        for (value in invalidGeneration) {
            try { LocalGgufRuntime.generation(value.toString()); fail("An invalid or contradictory transport was accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
        assertFalse(LocalTranslationPerformance(backend = "CPU", openClTransport = "/private/not-a-transport.so")
            .render().contains("not-a-transport"))
    }
    @Test fun malformedAndInventedResponseCannotSucceed() {
        for (json in listOf("", "{}", "not json", generation(code = "INVENTED"))) {
            try { LocalGgufRuntime.generation(json); fail("Malformed response accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }
    @Test fun serviceErrorReconstructionRetainsOnlyTheSanitizedDiagnosticForTheClient() {
        val native = JSONObject().put("code", "BACKEND").put("diagnostic", JSONObject()
            .put("stage", "OPENCL_DRIVER").put("reason", "DRIVER_NOT_ACCESSIBLE")
            .put("elapsedMs", 17).put("openClStatus", JSONObject.NULL)
            .put("directPlatforms", -1).put("icdPlatforms", -1).put("gpuDevices", -1).put("probeCached", false)
            .put("privatePath", "/private/not-for-the-report.gguf")).toString()
        val nativeFailure = try { LocalGgufRuntime.response(native); throw AssertionError("Expected native error") }
            catch (failure: LocalGgufRuntime.Failure) { failure }
        val host = Robolectric.buildService(LocalGgufTranslationService::class.java).create()
        try {
            val error = host.get().javaClass.getDeclaredMethod("error", LocalGgufRuntime.Code::class.java,
                LocalGgufDiagnostic::class.java).apply { isAccessible = true }
            val encoded = error.invoke(host.get(), nativeFailure.code, nativeFailure.diagnostic) as String
            assertFalse(encoded.contains("privatePath"))
            assertFalse(encoded.contains("not-for-the-report"))
            try { LocalGgufRuntime.response(encoded); fail("Service error was lost") }
            catch (clientFailure: LocalGgufRuntime.Failure) {
                assertEquals(LocalGgufRuntime.Code.BACKEND, clientFailure.code)
                assertEquals(LocalGgufDiagnostic.Stage.OPENCL_DRIVER, clientFailure.diagnostic?.stage)
                assertEquals(LocalGgufDiagnostic.Reason.DRIVER_NOT_ACCESSIBLE, clientFailure.diagnostic?.reason)
                assertEquals(17L, clientFailure.diagnostic?.elapsedMs)
            }
            val direct = JSONObject(native).apply {
                getJSONObject("diagnostic").put("stage", "OPENCL_DIRECT").put("reason", "DIRECT_SYMBOLS_MISSING")
                    .put("missingSymbol", "clCreateBuffer")
            }
            val directFailure = try { LocalGgufRuntime.response(direct.toString()); throw AssertionError("Expected direct API error") }
                catch (failure: LocalGgufRuntime.Failure) { failure }
            val directEncoded = error.invoke(host.get(), directFailure.code, directFailure.diagnostic) as String
            assertFalse(directEncoded.contains("not-for-the-report"))
            try { LocalGgufRuntime.response(directEncoded); fail("Direct API error was lost") }
            catch (clientFailure: LocalGgufRuntime.Failure) {
                assertEquals(LocalGgufDiagnostic.Reason.DIRECT_SYMBOLS_MISSING, clientFailure.diagnostic?.reason)
                assertEquals("clCreateBuffer", clientFailure.diagnostic?.missingSymbol)
            }
        } finally { host.destroy(); shadowOf(Looper.getMainLooper()).idle() }
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
