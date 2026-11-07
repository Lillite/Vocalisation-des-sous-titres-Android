package fr.soustitresvoix.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSrtAttemptJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "a".repeat(64)
    private val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
        apiKey = "PRIVATE-API-KEY", endpoint = "https://private.invalid/secret",
        localContext = "PRIVATE SUBTITLE AND HISTORY", localExecution = "VULKAN")

    private fun entry(now: Long = 10_000) = LocalSrtAttemptJournal.begin(key, config, now, 123,
        sourceLanguage = "en").copy(done = 3, total = 25)

    @Test fun pendingAttemptSurvivesRecreationWithoutStartingOrClaimingACompletedTranslation() {
        val directory = temporary.newFolder()
        val first = LocalSrtAttemptJournal(directory) { 10_100 }
        val value = entry().measured(LocalTranslationPerformance(phase = "loading", processId = 456,
            progress = LocalGgufProgress("PREFILL", -1, 456, 10_010, 90)), 10_100)
        assertTrue(first.save(value, force = true))
        val restored = requireNotNull(LocalSrtAttemptJournal(directory) { 10_200 }.read(key))
        assertEquals(value, restored)
        val report = restored.render(nowMs = 10_200)
        assertTrue(report.contains("interrompue"))
        assertTrue(report.contains("3 / 25"))
        assertTrue(report.contains("occurrence suivante à vérifier : 4"))
        assertTrue(report.contains("PREFILL"))
        assertTrue(report.contains("Relance explicitement"))
        assertEquals("RUNNING", restored.state)
    }

    @Test fun onlyWhitelistedParametersAndNumbersAreStoredAndSourceIdentityIsHashed() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        val performance = LocalTranslationPerformance(phase = "generation", requestedMode = "VULKAN",
            backend = "VULKAN", device = "/private/DEVICE", gpuObserved = true, currentRssBytes = 100,
            peakRssBytes = 200, availableRamBytes = 300, promptMs = 40, generationMs = 20,
            promptTokens = 6, generatedTokens = 4, cachedPromptTokens = 2, promptEvaluatedTokens = 4,
            gpuGraphNodes = 10, fallbackReason = "PRIVATE SUBTITLE", attemptReport = "PRIVATE PROMPT")
        assertTrue(journal.save(entry().measured(performance, 10_100), force = true))
        val bytes = File(directory, "attempt.json").readText()
        for (privateValue in listOf("PRIVATE", "private.invalid", "/private", "DEVICE")) assertFalse(bytes.contains(privateValue))
        val restored = requireNotNull(journal.read(key))
        assertEquals(100L, restored.currentRssBytes)
        assertEquals(10L, restored.graphNodes)
        assertTrue(restored.gpuObserved)
        assertEquals("en", restored.sourceLanguage)
        assertEquals(key, LocalSrtAttemptJournal.sourceKey(File("/private/source-$key.srt")))
        assertNull(LocalSrtAttemptJournal.sourceKey(File("/private/personal-title.srt")))
    }

    @Test fun progressWritesAreCoalescedButCriticalCheckpointCanBeForced() {
        val directory = temporary.newFolder()
        var now = 10_000L
        val journal = LocalSrtAttemptJournal(directory) { now }
        assertTrue(journal.save(entry(now), force = true))
        val file = File(directory, "attempt.json")
        val original = file.readBytes()
        now += 100
        val newer = entry().copy(updatedAtMs = now, done = 4)
        assertTrue(journal.save(newer))
        assertArrayEquals(original, file.readBytes())
        assertTrue(journal.save(newer, force = true))
        assertEquals(4, requireNotNull(journal.read(key)).done)
        now += 1000
        assertTrue(journal.save(newer.copy(updatedAtMs = now, done = 5)))
        assertEquals(5, requireNotNull(journal.read(key)).done)
    }

    @Test fun differentSourceOldFutureOrInvalidEntriesCannotBecomeACurrentReport() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        assertTrue(journal.save(entry(), force = true))
        assertNull(journal.read("b".repeat(64)))
        assertNull(journal.read(key, 10_000 + 8 * 24 * 60 * 60 * 1000L))
        assertNull(journal.read(key, 1))
        for (invalid in listOf(entry().copy(done = 26), entry().copy(mode = "PRIVATE-MODE"),
            entry().copy(profile = "PRIVATE-PROFILE"), entry().copy(updatedAtMs = 1),
            entry().copy(sourceKey = "/private/file"), entry().copy(failureCode = "PRIVATE-ERROR"),
            entry().copy(progress = LocalGgufProgress("/private/model", 0, 456, 10_010, 0)))) {
            assertFalse(journal.save(invalid, force = true))
        }
        assertEquals(3, requireNotNull(journal.read(key)).done)
    }

    @Test fun corruptOversizedAndIncompleteRecordsAreRejectedWithoutLosingTranslationFiles() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        val unrelated = File(directory, "translation-result.bin").apply { writeText("validated result") }
        val file = File(directory, "attempt.json")
        for (bad in listOf("{", "{}", " ".repeat(16_385))) {
            file.writeText(bad)
            assertNull(journal.read(key))
        }
        assertTrue(journal.save(entry(), force = true))
        val record = JSONObject(file.readText()).put("done", 100).put("total", 25)
        file.writeText(record.toString())
        assertNull(journal.read(key))
        assertTrue(journal.save(entry(), force = true))
        file.writeText(JSONObject(file.readText()).put("done", "3").toString())
        assertNull("Numbers encoded as strings cannot become trusted counters", journal.read(key))
        journal.clear()
        assertNull(journal.read(key))
        assertEquals("validated result", unrelated.readText())
    }

    @Test fun ioFailureIsReportedSeparatelyAndNeverPretendsToSaveADiagnostic() {
        val blocked = temporary.newFile()
        val journal = LocalSrtAttemptJournal(blocked) { 10_100 }
        assertFalse(journal.save(entry(), force = true))
        assertTrue(journal.failedWrite)
        assertNull(journal.read(key))
        assertTrue(LocalSrtAttemptJournal.WRITE_WARNING.contains("résultats de traduction sont gérés séparément"))
    }

    @Test fun completedAndFailedRecordsPreserveTheirDistinctMeaningOnRecovery() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        val complete = entry().copy(done = 25, state = "COMPLETED")
        assertTrue(journal.save(complete, force = true))
        assertTrue(requireNotNull(journal.read(key)).render(nowMs = 10_100).contains("retrouver son export"))
        val failed = entry().copy(state = "FAILED", failureCode = "LOCAL_PROCESS_DIED")
        assertTrue(journal.save(failed, force = true))
        val report = requireNotNull(journal.read(key)).render(nowMs = 10_100)
        assertTrue(report.contains("Erreur enregistrée"))
        assertFalse(report.contains("pression mémoire"))
        assertFalse(report.contains("préparation terminée"))
    }

    @Test fun requestedAndObservedKvPoliciesAndNativeMemoryMeasurementsSurviveProgressAndRecreation() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_200 }
        val value = entry().copy(requestedKvCacheType = "Q8_0", contextPolicy = "isolated",
            configurationId = "local-gguf-v5-" + "b".repeat(64))
            .measured(LocalTranslationPerformance(phase = "generation", backend = "VULKAN",
                cacheTypeK = "Q8_0", cacheTypeV = "Q8_0", flashAttentionPolicy = "ENABLED",
                startResidentBytes = 100, prefillResidentBytes = 200, currentRssBytes = 250,
                cacheResetReason = "PREFIX_CHANGED", cacheResets = 2, workspaceGrowths = 3, workspaceBytes = 400), 10_100)
            .measured(LocalTranslationPerformance(phase = "progress", processId = 456,
                progress = LocalGgufProgress("PREFILL", -1, 456, 10_100, 100)), 10_200)
        assertEquals(250L, value.currentRssBytes)
        assertEquals("Q8_0", value.cacheTypeK)
        assertTrue(journal.save(value, force = true))
        val restored = requireNotNull(journal.read(key))
        assertEquals(value, restored)
        assertEquals("isolated", restored.contextPolicy)
        val report = restored.render(nowMs = 10_200)
        assertTrue(report.contains("Cache K/V demandé : Q8_0"))
        assertTrue(report.contains("types K/V rapportés : Q8_0/Q8_0"))
        assertFalse(report.contains("Cache K/V demandé : F16"))
    }

    @Test fun anAutomaticFallbackCannotAttributeThePreviousEngineDeathToTheNewAttempt() {
        val previous = entry().measured(LocalTranslationPerformance(phase = "generation", backend = "VULKAN",
            processId = 456, gpuObserved = true, cacheTypeK = "F16", cacheTypeV = "F16",
            progress = LocalGgufProgress("DECODE", 5, 456, 10_010, 90)), 10_100)
        val fallback = previous.measured(LocalTranslationPerformance(phase = "fallback", requestedMode = "AUTO"), 10_200)
        assertEquals(0, fallback.processId)
        assertNull(fallback.progress)
        assertEquals("", fallback.backend)
        assertEquals("", fallback.cacheTypeK)
        assertFalse(fallback.gpuObserved)
        assertEquals(previous.done, fallback.done)
        assertEquals(previous.modelSha256, fallback.modelSha256)
    }

    @Test fun aNewAttemptDoesNotInheritThePreviousTasksWriteWarning() {
        val parent = temporary.newFolder()
        val directory = File(parent, "journal").apply { writeText("blocked") }
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        assertFalse(journal.save(entry(), force = true))
        assertTrue(journal.failedWrite)
        assertTrue(directory.delete())
        journal.beginAttempt()
        assertFalse(journal.failedWrite)
        assertTrue(journal.save(entry(), force = true))
        assertFalse(journal.failedWrite)
        assertNotNull(journal.read(key))
    }
    @Test fun legacyCancelledRecordsStillRequestAndroidExitEvidenceAndExplicitCancellationDoesNot() {
        val legacy = entry().copy(state = "CANCELLED")
        assertTrue(legacy.needsExitEvidence())
        assertFalse(legacy.copy(cause = "USER_CANCEL", stopCause = "USER_CANCEL").needsExitEvidence())
        assertTrue(legacy.copy(cause = "HOST_DESTROYED").needsExitEvidence())
        assertTrue(legacy.copy(state = "FAILED", cause = "PROCESS_LOSS", stopCause = "USER_CANCEL",
            failureCode = "LOCAL_PROCESS_DIED").needsExitEvidence())
    }

    @Test fun primaryFailureAndSecondaryCloseSurviveRoundTripWithoutBeingRelabelledAsUserCancellation() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_200 }
        val failed = entry().copy(state = "FAILED", cause = "PROCESS_LOSS", stopCause = "HOST_DESTROYED",
            failureCode = "LOCAL_PROCESS_DIED", failedProcessId = 456, failedRequestStartedAtMs = 10_010,
            failedAtMs = 10_100, updatedAtMs = 10_200, recoveries = 1)
        assertTrue(journal.save(failed, true))
        val restored = requireNotNull(journal.read(key))
        assertEquals(failed, restored)
        val report = restored.render(nowMs = 10_200)
        assertTrue(report.contains("[PROCESS_LOSS]"))
        assertTrue(report.contains("[HOST_DESTROYED]"))
        assertTrue(report.contains("Erreur enregistrée"))
        assertFalse(report.contains("annulée explicitement"))
    }

    @Test fun resourcePauseAndNewExplicitPoliciesRemainBoundedAndCompatibleWithOldRecords() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_100 }
        val paused = entry().copy(state = "WAITING", phase = "waiting-thermal", cause = "THERMAL_PRESSURE",
            requestedFlashAttentionPolicy = "DISABLED", qualcommCompatibility = true)
        assertTrue(journal.save(paused, true))
        val restored = requireNotNull(journal.read(key))
        assertEquals(paused, restored)
        assertTrue(restored.render().contains("reprise explicite nécessaire"))
        assertFalse(journal.save(paused.copy(cause = "/private/path"), true))
        assertFalse(journal.save(paused.copy(recoveries = 3), true))
        val file = File(directory, "attempt.json")
        val old = JSONObject(file.readText())
        for (field in listOf("cause", "stopCause", "recoveries", "failedPid", "failedStarted", "failedAt", "requestedFlash", "qualcommCompatibility")) old.remove(field)
        file.writeText(old.toString())
        val legacy = requireNotNull(journal.read(key))
        assertEquals("", legacy.cause)
        assertEquals("AUTO", legacy.requestedFlashAttentionPolicy)
        assertFalse(legacy.qualcommCompatibility)
    }

    @Test fun aFatalPrefillCanKeepItsLastWorkerMemorySampleWithoutAnyNativeCompletionJson() {
        val directory = temporary.newFolder()
        val journal = LocalSrtAttemptJournal(directory) { 10_200 }
        val withSample = entry().copy(memoryReserveBytes = 1L shl 30, observedPrefillExtraBytes = 123)
            .measured(LocalTranslationPerformance(phase = "progress",
            progress = LocalGgufProgress("PREFILL", 0, 456, 10_010, 90, 10_279_424_000L, 523_341_824L)), 10_100)
            .measured(LocalTranslationPerformance(phase = "progress",
                progress = LocalGgufProgress("PREFILL", 0, 456, 10_010, 190)), 10_200)
        assertEquals(1L shl 30, withSample.memoryReserveBytes)
        assertEquals(123L, withSample.observedPrefillExtraBytes)
        assertEquals(10_279_424_000L, withSample.currentRssBytes)
        assertEquals(523_341_824L, withSample.availableRamBytes)
        assertTrue(journal.save(withSample, true))
        val restored = requireNotNull(journal.read(key))
        assertEquals(withSample, restored)
        assertTrue(restored.render().contains("Dernier relevé mémoire conservé"))
        assertTrue(restored.render().contains("10279424000"))
    }

}
