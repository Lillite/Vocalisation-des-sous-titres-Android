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
    @Test fun loadProfileIsOptionalButMalformedOrContradictoryTimingsRejectTheResponse() {
        val info = JSONObject().put("code", "OK").put("name", "Synthetic model")
            .put("architecture", "llama").put("fileType", "Q4_0").put("contextTokens", 512)
            .put("modelBytes", 1024).put("modelParameters", 100).put("loadMs", 52).put("peakRssBytes", 1000)
        assertNull(LocalGgufRuntime.info(info.toString()).loadProfile)
        val profile = JSONObject().put("backendInitMs", 2).put("openClPrepareMs", 3)
            .put("modelLoadMs", 30).put("contextInitMs", 20).put("openTotalMs", 60).put("loadAttempts", 2)
        val measured = JSONObject(info.toString()).put("loadProfile", profile)
        assertEquals(LocalGgufLoadProfile(2, 3, 30, 20, 60, 2), LocalGgufRuntime.info(measured.toString()).loadProfile)
        for (invalid in listOf(
            JSONObject(info.toString()).put("loadProfile", JSONObject.NULL),
            JSONObject(info.toString()).put("loadProfile", "unmeasured"),
            JSONObject(info.toString()).put("loadProfile", JSONObject(profile.toString()).put("modelLoadMs", "30")),
            JSONObject(info.toString()).put("loadProfile", JSONObject(profile.toString()).put("openTotalMs", 54)),
            JSONObject(measured.toString()).put("loadMs", 49),
            JSONObject(measured.toString()).put("loadMs", 61))) {
            try { LocalGgufRuntime.info(invalid.toString()); fail("Invalid native load profile accepted") }
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
    @Test fun builtinTemplateCapabilityMustHaveStrictConsistentMetadata() {
        val plain = JSONObject().put("code", "OK").put("name", "Synthetic model")
            .put("architecture", "llama").put("fileType", "Q4_0").put("contextTokens", 512)
            .put("modelBytes", 1024).put("modelParameters", 100).put("loadMs", 5).put("peakRssBytes", 1000)
        assertFalse(LocalGgufRuntime.info(plain.toString()).chatTemplateAvailable)
        assertEquals("", LocalGgufRuntime.info(plain.toString()).chatTemplateMode)
        val builtin = JSONObject(plain.toString()).put("chatTemplateAvailable", true).put("chatTemplateMode", "BUILTIN_FORMAT")
        val observed = LocalGgufRuntime.info(builtin.toString())
        assertTrue(observed.chatTemplateAvailable)
        assertEquals("BUILTIN_FORMAT", observed.chatTemplateMode)
        assertFalse(observed.gpuObserved)
        val malformed = mutableListOf(
            JSONObject(builtin.toString()).put("chatTemplateAvailable", false),
            JSONObject(plain.toString()).put("chatTemplateAvailable", true),
            JSONObject(builtin.toString()).put("chatTemplateMode", "JINJA_PROGRAM"))
        for (value in listOf<Any>("true", 1, JSONObject.NULL))
            malformed += JSONObject(builtin.toString()).put("chatTemplateAvailable", value)
        for (value in listOf<Any>(true, 1, JSONObject.NULL))
            malformed += JSONObject(builtin.toString()).put("chatTemplateMode", value)
        for (invalid in malformed) {
            try { LocalGgufRuntime.info(invalid.toString()); fail("Malformed or contradictory template capability accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }
    @Test fun templateErrorSurvivesThePrivateServiceBoundaryWithoutPayloadLeak() {
        val host = Robolectric.buildService(LocalGgufTranslationService::class.java).create()
        try {
            val error = host.get().javaClass.getDeclaredMethod("error", LocalGgufRuntime.Code::class.java,
                LocalGgufDiagnostic::class.java).apply { isAccessible = true }
            val encoded = error.invoke(host.get(), LocalGgufRuntime.Code.TEMPLATE, null) as String
            try { LocalGgufRuntime.response(encoded); fail("Template failure was erased") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.TEMPLATE, failure.code) }
            assertFalse(TranslationFailure(TranslationFailure.Reason.LOCAL_TEMPLATE).message.orEmpty().contains("private"))
        } finally { host.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun vulkanExecutionEvidenceAndOptionalLoadSettingsAreStrictlyValidated() {
        val valid = JSONObject(generation()).put("backend", "VULKAN").put("requestedMode", "VULKAN")
            .put("gpuObserved", true).put("gpuGraphNodes", 5)
            .put("batchTokens", 128).put("microBatchTokens", 32).put("compactSwa", true)
        val output = LocalGgufRuntime.generation(valid.toString())
        assertTrue(output.gpuObserved); assertEquals("VULKAN", output.backend)
        assertEquals(128, output.batchTokens); assertEquals(32, output.microBatchTokens)
        assertEquals(true, output.compactSwa)
        val invalid = listOf(
            JSONObject(valid.toString()).put("gpuGraphNodes", 0),
            JSONObject(valid.toString()).put("requestedMode", "CPU"),
            JSONObject(valid.toString()).put("requestedMode", "OPENCL"),
            JSONObject(valid.toString()).put("requestedMode", "AUTO"),
            JSONObject(valid.toString()).put("openClTransport", "DIRECT"),
            JSONObject(valid.toString()).put("batchTokens", "128"),
            JSONObject(valid.toString()).put("microBatchTokens", 129),
            JSONObject(valid.toString()).put("microBatchTokens", 256),
            JSONObject(valid.toString()).put("compactSwa", "true"),
            JSONObject(valid.toString()).apply { remove("compactSwa") })
        invalid.forEach {
            try { LocalGgufRuntime.generation(it.toString()); fail("Contradictory backend or load settings accepted") }
            catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.RESPONSE, failure.code) }
        }
    }

    @Test fun progressFromAnotherSessionRequestOrProcessCannotOverwriteTheCurrentAttempt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LocalGgufRuntime.Client(context)
        val type = client.javaClass
        val session = type.getDeclaredField("session").apply { isAccessible = true }.getLong(client)
        val pendingType = type.declaredClasses.first { it.simpleName == "Pending" }
        val constructor = pendingType.getDeclaredConstructor(java.lang.Long.TYPE, java.lang.Integer.TYPE, android.os.Bundle::class.java)
            .apply { isAccessible = true }
        val pending = constructor.newInstance(4L, GgufMessages.OPEN, android.os.Bundle())
        val started = pendingType.getDeclaredField("startedAtMs").apply { isAccessible = true }.getLong(pending)
        val progressField = pendingType.getDeclaredField("progress").apply { isAccessible = true }
        type.getDeclaredField("pending").apply { isAccessible = true }.set(client, pending)
        val replies = type.getDeclaredField("replies").apply { isAccessible = true }.get(client) as Messenger
        fun deliver(sessionId: Long = session, request: Long = 4, pid: Int = 123, stage: String = "MODEL_LOAD") {
            replies.send(android.os.Message.obtain(null, GgufMessages.PROGRESS).apply {
                data = android.os.Bundle().apply {
                    putLong(GgufMessages.SESSION, sessionId); putLong(GgufMessages.REQUEST, request)
                    putString(GgufMessages.STAGE, stage); putInt(GgufMessages.PERCENT, 10)
                    putInt(GgufMessages.PROCESS_ID, pid); putLong(GgufMessages.STARTED_AT, started)
                    putLong(GgufMessages.ELAPSED, 1)
                }
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            deliver()
            val current = progressField.get(pending) as LocalGgufProgress
            assertEquals("MODEL_LOAD", current.stage)
            deliver(sessionId = session + 1, stage = "READY")
            deliver(request = 5, stage = "READY")
            deliver(pid = 124, stage = "READY")
            deliver(stage = "/private/untrusted-stage")
            assertEquals(current, progressField.get(pending))
            val ready = pendingType.getDeclaredField("ready").apply { isAccessible = true }.get(pending)
                as java.util.concurrent.CountDownLatch
            ready.countDown()
            deliver(stage = "READY")
            assertEquals("A stage arriving after a terminal reply must be ignored", current, progressField.get(pending))
            var delivered = 0
            client.setProgressListener { delivered++ }
            client.close()
            deliver(stage = "READY")
            type.getDeclaredMethod("publishProgress", pendingType).apply { isAccessible = true }.invoke(client, pending)
            assertEquals("Already queued progress must not be delivered after close", 0, delivered)
            assertEquals(current, progressField.get(pending))
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun cancellationInsideAProgressCallbackPreventsTheRemainingQueuedCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LocalGgufRuntime.Client(context)
        val type = client.javaClass
        val pendingType = type.declaredClasses.first { it.simpleName == "Pending" }
        val pending = pendingType.getDeclaredConstructor(java.lang.Long.TYPE, java.lang.Integer.TYPE, android.os.Bundle::class.java)
            .apply { isAccessible = true }.newInstance(4L, GgufMessages.OPEN, android.os.Bundle())
        type.getDeclaredField("pending").apply { isAccessible = true }.set(client, pending)
        @Suppress("UNCHECKED_CAST")
        val queue = pendingType.getDeclaredField("progressEvents").apply { isAccessible = true }.get(pending)
            as ArrayDeque<LocalGgufProgress>
        queue.addLast(LocalGgufProgress("MODEL_LOAD", 0, 123, 10_000, 1))
        queue.addLast(LocalGgufProgress("CONTEXT_INIT", -1, 123, 10_000, 2))
        val delivered = mutableListOf<String>()
        client.setProgressListener { delivered += it.stage; client.cancel() }
        try {
            type.getDeclaredMethod("publishProgress", pendingType).apply { isAccessible = true }.invoke(client, pending)
            assertEquals(listOf("MODEL_LOAD"), delivered)
        } finally { client.close(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun mainThreadCallsFailBeforeBindingAndCloseNeverWaits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LocalGgufRuntime.Client(context)
        try {
            try { client.open(File(context.filesDir, "absent.gguf")); fail("Main-thread inference accepted") }
            catch (_: IllegalStateException) { }
            try { client.generateChat("A translation request."); fail("Main-thread chat inference accepted") }
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
