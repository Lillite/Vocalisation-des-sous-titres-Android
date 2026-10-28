package fr.soustitresvoix.player

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MlKitTranslationEngineRobolectricTest {
    private class Backend : MlKitTranslationBackend {
        val sourceTexts = mutableListOf<String>()
        val mobilePolicies = mutableListOf<Boolean>()
        var preparedTask: Task<Void> = Tasks.forResult(null)
        var translated: (String) -> Task<String> = { Tasks.forResult("Traduit : $it") }
        var closed = 0
        val preparing = CountDownLatch(1)
        val translating = CountDownLatch(1)
        override fun prepareModels(allowMobileDownload: Boolean): Task<Void> {
            mobilePolicies += allowMobileDownload; preparing.countDown(); return preparedTask
        }
        override fun translate(text: String): Task<String> {
            sourceTexts += text; translating.countDown(); return translated(text)
        }
        override fun close() { closed++ }
    }

    private fun engine(backend: Backend, mobile: Boolean = false,
        limits: MlKitTranslationEngine.WaitLimits = MlKitTranslationEngine.WaitLimits()) =
        MlKitTranslationEngine("google-local", mobile, { _, _ -> backend }, limits)

    private fun failure(reason: TranslationFailure.Reason, action: () -> Unit) {
        try { action(); fail("Expected an application-owned failure") }
        catch (error: TranslationFailure) { assertEquals(reason, error.reason); assertNull(error.cause) }
    }

    @Test fun modelPreparationIsReusedAndEveryRepeatedOccurrenceIsReturnedInOrder() {
        val backend = Backend()
        val source = listOf("EXIT", "Hello\nworld", "EXIT", "日本語 😀")
        engine(backend).use {
            assertEquals(source.map { "Traduit : $it" }, it.translate(source, "en", "fr") { false })
            assertEquals(listOf("Traduit : Later"), it.translate(listOf("Later"), "en", "fr") { false })
        }
        assertEquals(listOf(false), backend.mobilePolicies)
        assertEquals(source + "Later", backend.sourceTexts)
        assertEquals(1, backend.closed)
    }

    @Test fun mobileModelDownloadRequiresTheExplicitSetting() {
        val backend = Backend()
        engine(backend, mobile = true).use { it.translate(listOf("Synthetic"), "en", "fr") { false } }
        assertEquals(listOf(true), backend.mobilePolicies)
    }

    @Test fun unsupportedLanguagesAndInvalidTextsAreRejectedBeforeCreatingTheSdkClient() {
        val created = AtomicInteger()
        val engine = MlKitTranslationEngine("google-local", false, { _, _ -> created.incrementAndGet(); Backend() })
        engine.use {
            failure(TranslationFailure.Reason.LANGUAGE) { it.translate(listOf("Hello"), "zz", "fr") { false } }
            failure(TranslationFailure.Reason.LANGUAGE) { it.translate(listOf("Bonjour"), "fr", "fr") { false } }
            for (bad in listOf("", "\u0000", "\uD800")) {
                failure(TranslationFailure.Reason.CONFIGURATION) { it.translate(listOf("Valid", bad), "en", "fr") { false } }
            }
        }
        assertEquals(0, created.get())
    }

    @Test fun closingDuringModelPreparationStopsWaitingAndIgnoresTheLateCompletion() {
        val model = TaskCompletionSource<Void>()
        val backend = Backend().apply { preparedTask = model.task }
        val engine = engine(backend)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                try { engine.translate(listOf("First", "Second"), "en", "fr") { false }; false }
                catch (_: TranslationCancelledException) { true }
            }
            assertTrue(backend.preparing.await(2, TimeUnit.SECONDS))
            engine.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            model.setResult(null)
            assertTrue(backend.sourceTexts.isEmpty())
            engine.close()
            assertEquals(1, backend.closed)
        } finally { engine.close(); worker.shutdownNow() }
    }

    @Test fun closingDuringInferenceCannotReturnAPartialBatchOrStartTheNextOccurrence() {
        val translation = TaskCompletionSource<String>()
        val backend = Backend().apply { translated = { translation.task } }
        val engine = engine(backend)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                try { engine.translate(listOf("First", "Second"), "en", "fr") { false }; false }
                catch (_: TranslationCancelledException) { true }
            }
            assertTrue(backend.translating.await(2, TimeUnit.SECONDS))
            engine.close()
            translation.setResult("Premier")
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("First"), backend.sourceTexts)
        } finally { engine.close(); worker.shutdownNow() }
    }

    @Test fun sdkErrorsCannotExposeSubtitleTextOrTheOriginalException() {
        val secret = "private-synthetic-subtitle-do-not-log"
        val modelFailure = Backend().apply { preparedTask = Tasks.forException(IllegalStateException(secret)) }
        engine(modelFailure).use {
            failure(TranslationFailure.Reason.MODEL_DOWNLOAD) { it.translate(listOf(secret), "en", "fr") { false } }
        }
        val inferenceFailure = Backend().apply { translated = { Tasks.forException(IllegalStateException(secret)) } }
        engine(inferenceFailure).use {
            try { it.translate(listOf(secret), "en", "fr") { false }; fail("Expected failure") }
            catch (error: TranslationFailure) {
                assertEquals(TranslationFailure.Reason.LOCAL_TRANSLATION, error.reason)
                assertFalse(error.toString().contains(secret)); assertNull(error.cause)
            }
        }
        assertTrue(modelFailure.sourceTexts.isEmpty())
    }

    @Test fun blankOrMalformedSdkResultsStopTheBatchBeforeAnotherTextIsProcessed() {
        for (invalid in listOf("", "\uD800")) {
            val backend = Backend().apply { translated = { Tasks.forResult(invalid) } }
            engine(backend).use {
                failure(TranslationFailure.Reason.RESPONSE) { it.translate(listOf("First", "Second"), "en", "fr") { false } }
            }
            assertEquals(listOf("First"), backend.sourceTexts)
        }
    }

    @Test fun modelAndInferenceWaitsHaveBoundsAndDoNotRetryUnknownFailures() {
        val limits = MlKitTranslationEngine.WaitLimits(modelMillis = 20, textMillis = 20, pollMillis = 2)
        val model = Backend().apply { preparedTask = TaskCompletionSource<Void>().task }
        engine(model, limits = limits).use {
            failure(TranslationFailure.Reason.MODEL_DOWNLOAD) { it.translate(listOf("Hello"), "en", "fr") { false } }
        }
        assertEquals(1, model.mobilePolicies.size)
        val inference = Backend().apply { translated = { TaskCompletionSource<String>().task } }
        engine(inference, limits = limits).use {
            failure(TranslationFailure.Reason.LOCAL_TRANSLATION) { it.translate(listOf("First", "Second"), "en", "fr") { false } }
        }
        assertEquals(listOf("First"), inference.sourceTexts)
    }

    @Test fun changingLanguagesClosesThePreviousTranslatorAndPreparesTheNewModels() {
        val first = Backend(); val second = Backend()
        val pairs = mutableListOf<Pair<String, String>>()
        MlKitTranslationEngine("google-local", false, { source, target ->
            pairs += source to target
            if (pairs.size == 1) first else second
        }).use {
            it.translate(listOf("Hello"), "en", "fr") { false }
            it.translate(listOf("Hola"), "es", "fr") { false }
            assertEquals(1, first.closed)
        }
        assertEquals(listOf("en" to "fr", "es" to "fr"), pairs)
        assertEquals(listOf(false), second.mobilePolicies)
        assertEquals(1, second.closed)
    }
}
