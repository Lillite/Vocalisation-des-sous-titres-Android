package fr.soustitresvoix.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalModelProbeRobolectricTest {
    @Test fun resultRoundTripKeepsMeasuredCpuFieldsAndSyntheticOutput() {
        val original = LocalModelProbe.Result(LocalModelProbe.Status.COMPLETED,
            "Texte synthétique, qualité non évaluée.", "Le train part à sept heures.", "CPU",
            loadMs = 120, promptMs = 8, generationMs = 42, generatedTokens = 7,
            promptTokens = 55, contextTokens = 512, threads = 2, processId = 3344,
            peakRssBytes = 100_000, modelBytes = 50_000, modelParameters = 10_000,
            architecture = "llama", stopReason = "end_of_generation", promptKind = "synthetic_plain_text")
        assertEquals(original, LocalModelProbe.fromJson(original.toJson()))
        assertEquals("non exécuté", LocalModelProbe.Result(LocalModelProbe.Status.FAILED, "Absent").backend)
    }

    @Test fun invalidNativeReplyCannotBecomeSuccessfulOrGpuMeasured() {
        for (value in listOf("", "{}", "{\"status\":\"INVENTED\"}", "not json")) {
            val result = LocalModelProbe.fromJson(value)
            assertEquals(LocalModelProbe.Status.FAILED, result.status)
            assertEquals("non exécuté", result.backend)
            assertEquals(0, result.generatedTokens)
        }
    }

    @Test fun cancellationBeforeRunDoesNotAttemptToLoadNativeLibraryOrModel() {
        val cancellation = LocalModelProbe.Cancellation()
        cancellation.cancel(); cancellation.cancel()
        var stages = 0
        val result = LocalModelProbe.run(File("/not/a/model.gguf"), cancellation) { stages++ }
        assertEquals(LocalModelProbe.Status.CANCELLED, result.status)
        assertEquals(0, stages)
        assertEquals("non exécuté", result.backend)
    }

    @Test fun externalPathIsRejectedBeforeBindingAndCloseIsIdempotent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("outside-model-", ".gguf").apply { writeBytes(ByteArray(24)) }
        val results = mutableListOf<LocalModelProbe.Result>()
        val client = LocalModelProbeClient(context, { fail("Unexpected service stage") }, results::add)
        try {
            client.start(file)
            assertEquals(1, results.size)
            assertEquals(LocalModelProbe.Status.FAILED, results.single().status)
            assertFalse(results.single().detail.contains(file.path))
            client.close(); client.close()
            client.start(file)
            assertEquals(1, results.size)
        } finally { client.close(); file.delete() }
    }
}
