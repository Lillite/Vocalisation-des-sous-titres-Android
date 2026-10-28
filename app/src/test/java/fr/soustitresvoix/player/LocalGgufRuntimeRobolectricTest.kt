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
        } finally { client.close() }
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
