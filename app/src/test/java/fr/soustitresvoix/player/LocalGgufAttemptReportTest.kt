package fr.soustitresvoix.player

import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufAttemptReportTest {
    private fun progress() = Bundle().apply {
        putString(GgufMessages.STAGE, "MODEL_LOAD"); putInt(GgufMessages.PERCENT, 17)
        putInt(GgufMessages.PROCESS_ID, 123); putLong(GgufMessages.STARTED_AT, 10_000)
        putLong(GgufMessages.ELAPSED, 350)
    }

    @Test fun progressAcceptsOnlyFixedStagesStrictTypesAndThisRequestTimeWindow() {
        val value = requireNotNull(LocalGgufProgress.parse(progress(), 10_000, 10_500))
        assertEquals("MODEL_LOAD", value.stage)
        assertEquals(17, value.percent)
        assertTrue(value.render().contains("ne prouve ni un calcul GPU"))
        val invalid = listOf(
            progress().apply { putString(GgufMessages.STAGE, "/private/a-subtitle.gguf") },
            progress().apply { putInt(GgufMessages.PERCENT, 101) },
            progress().apply { putString(GgufMessages.PERCENT, "17") },
            progress().apply { putInt(GgufMessages.PROCESS_ID, 0) },
            progress().apply { putLong(GgufMessages.STARTED_AT, 1) },
            progress().apply { putLong(GgufMessages.STARTED_AT, 50_000) },
            progress().apply { putLong(GgufMessages.ELAPSED, -1) },
            progress().apply { putLong(GgufMessages.ELAPSED, 86_400_001) })
        invalid.forEach { assertNull(LocalGgufProgress.parse(it, 10_000, 10_500)) }
    }

    @Test fun androidExitEvidenceRequiresExactPidProcessUidAndCurrentRequestInterval() {
        val good = LocalGgufAttemptReport.ExitRecord(123, "app:model_diagnostic", 1000, 10_350, 3, 9, 20, 10)
        val unrelated = listOf(good.copy(pid = 124), good.copy(processName = "app:other"),
            good.copy(uid = 1001), good.copy(timestampMs = 9_999), good.copy(timestampMs = 10_501))
        assertNull(LocalGgufAttemptReport.matchingExit(unrelated, 123, "app:model_diagnostic", 1000, 10_000, 10_500))
        assertEquals(good, LocalGgufAttemptReport.matchingExit(unrelated + good, 123, "app:model_diagnostic", 1000, 10_000, 10_500))
        assertNull(LocalGgufAttemptReport.matchingExit(listOf(good), 0, "app:model_diagnostic", 1000, 10_000, 10_500))
        assertNull(LocalGgufAttemptReport.matchingExit(listOf(good), 123, "app:model_diagnostic", 1000, 10_500, 10_000))
    }

    @Test fun timeoutAndProcessDeathRemainDifferentAndASignalIsNotCalledOutOfMemory() {
        val p = requireNotNull(LocalGgufProgress.parse(progress(), 10_000, 10_500))
        val timeout = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.TIMEOUT, p, 300_000)
        assertTrue(timeout.contains("[TIMEOUT]")); assertFalse(timeout.contains("[DIED]"))
        assertTrue(timeout.contains("MODEL_LOAD")); assertTrue(timeout.contains("300000"))
        val death = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, p, 50)
        assertTrue(death.contains("[DIED]")); assertTrue(death.contains("cause reste indéterminée"))
        val signaled = LocalGgufAttemptReport.ExitRecord(123, "app:model_diagnostic", 1000, 10_350, 2, 9, 20, 10)
        val signalReport = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, p, 50, signaled)
        assertTrue(signalReport.contains("ne prouve pas un manque de mémoire"))
        assertTrue(signalReport.contains("ne mesurent pas exactement"))
        assertFalse(signalReport.contains("app:model_diagnostic"))
        assertTrue(LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, p, 50,
            signaled.copy(reason = 3)).contains("pression mémoire rapporté par Android"))
    }

    @Test fun initialAttemptContainsNoPersonalBackgroundCredentialsOrPaths() {
        val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF, apiKey = "secret-key",
            endpoint = "https://private.invalid/secret", localContext = "PRIVATE SUBTITLE BACKGROUND")
        val report = LocalGgufAttemptReport.initial(config, 0)
        assertTrue(report.contains("1970-01-01T00:00:00Z"))
        assertTrue(report.contains("Aucun chargement ou calcul réussi"))
        for (privateText in listOf("secret", "private.invalid", "PRIVATE SUBTITLE BACKGROUND"))
            assertFalse(report.contains(privateText))
    }

    @Test fun cpuRequestAndObservedProgressCannotKeepTheMisleadingInitialPendingClaim() {
        val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF, localExecution = "CPU")
        val initial = LocalGgufAttemptReport.initial(config, 0)
        assertTrue(initial.contains("couches GPU 0 (calcul CPU explicite)"))
        assertFalse(initial.contains("couches GPU toutes"))
        val observed = LocalGgufAttemptReport.initial(config, 0, pendingStatus = false)
        assertFalse(observed.contains("Aucun chargement ou calcul réussi"))
        assertTrue(observed.contains("1970-01-01T00:00:00Z"))
    }

    @Test fun memoryLimiterDescriptionRetainsOnlyTheFixedMarkerWithAnAppropriateReason() {
        assertTrue(LocalGgufAttemptReport.memoryLimiterMarker(13,
            "MemoryLimiter:AnonSwap PRIVATE PATH /secret personal text"))
        assertTrue(LocalGgufAttemptReport.memoryLimiterMarker(17, "MemoryLimiter:AnonSwap"))
        assertFalse(LocalGgufAttemptReport.memoryLimiterMarker(3, "MemoryLimiter:AnonSwap"))
        assertFalse(LocalGgufAttemptReport.memoryLimiterMarker(13, null))
        assertFalse(LocalGgufAttemptReport.memoryLimiterMarker(13, "unrelated /private/path"))
        val exit = LocalGgufAttemptReport.ExitRecord(123, "app:model_diagnostic", 1000, 10_350,
            13, 0, 20, 10, memoryLimiter = true)
        val report = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, null, 50, exit)
        assertTrue(report.contains("MemoryLimiter:AnonSwap"))
        assertFalse(report.contains("PRIVATE"))
        assertFalse(report.contains("/secret"))
        assertFalse(report.contains("pression mémoire rapporté"))
    }

    @Test fun generationStagesRemainObservationsAndNeverClaimAValidatedOccurrence() {
        for (stage in listOf("TOKENIZE", "CACHE_REUSE", "PREFILL", "DECODE", "COMPLETE")) {
            val p = progress().apply { putString(GgufMessages.STAGE, stage) }
            val parsed = requireNotNull(LocalGgufProgress.parse(p, 10_000, 10_500))
            assertTrue(parsed.render().contains(stage))
            assertTrue(parsed.render().contains("ne prouve pas qu’une occurrence complète a été validée"))
        }
    }
    @Test fun processStateSummaryAcceptsOnlyOurBoundedFixedProtocol() {
        val valid = "s=42;r=7;stage=PREFILL;p=0".toByteArray()
        assertEquals(LocalGgufAttemptReport.ProcessSummary(42, 7, "PREFILL", 0), LocalGgufAttemptReport.processSummary(valid))
        for (invalid in listOf("s=42;r=7;stage=/private/model;p=0", "s=42;r=7;stage=PREFILL;p=101",
            "s=0;r=7;stage=PREFILL;p=0", "s=42;r=7;stage=PREFILL;p=0;private=subtitle",
            "s=9223372036854775808;r=7;stage=PREFILL;p=0", "x".repeat(129)))
            assertNull(LocalGgufAttemptReport.processSummary(invalid.toByteArray()))
        assertNull(LocalGgufAttemptReport.processSummary(null))
        assertNull(LocalGgufAttemptReport.processSummary(byteArrayOf(-1)))
    }

    @Test fun aCorrelatedProcessSummaryIsReportedWithoutFreeFormAndroidDescriptions() {
        val exit = LocalGgufAttemptReport.ExitRecord(123, "app:model_diagnostic", 1000, 10_350,
            5, 11, 20, 10, summary = LocalGgufAttemptReport.ProcessSummary(42, 7, "PREFILL", 0))
        val report = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, null, 50, exit)
        assertTrue(report.contains("session 42"))
        assertTrue(report.contains("requête 7"))
        assertTrue(report.contains("étape [PREFILL]"))
        assertFalse(report.contains("app:model_diagnostic"))
    }

    @Test fun processProgressMemoryIsOptionalStrictlyTypedAndNeverCalledAnExactPeak() {
        val bundle = progress().apply {
            putLong(GgufMessages.RESIDENT_BYTES, 10_279_424_000L)
            putLong(GgufMessages.AVAILABLE_RAM_BYTES, 523_341_824L)
        }
        val parsed = requireNotNull(LocalGgufProgress.parse(bundle, 10_000, 10_500))
        assertEquals(10_279_424_000L, parsed.residentBytes)
        assertEquals(523_341_824L, parsed.availableRamBytes)
        assertTrue(parsed.render().contains("ne garantit pas le pic exact"))
        assertEquals(-1L, requireNotNull(LocalGgufProgress.parse(progress(), 10_000, 10_500)).residentBytes)
        assertNull(LocalGgufProgress.parse(bundle.apply { putString(GgufMessages.RESIDENT_BYTES, "123") }, 10_000, 10_500))
        assertNull(LocalGgufProgress.parse(progress().apply { putLong(GgufMessages.RESIDENT_BYTES, 1L shl 45) }, 10_000, 10_500))
    }

    @Test fun failureMemoryDiscardsInvalidOptionalFieldsWithoutLosingTheOriginalError() {
        val admission = JSONObject().put("policy", "SYSTEM_RESERVE").put("availableBytes", 50L)
            .put("requiredBytes", 128L).put("reserveBytes", 128L).put("deficitBytes", 78L)
        for (invalid in listOf(
            JSONObject(admission.toString()).put("policy", "/private/unsafe"),
            JSONObject(admission.toString()).put("availableBytes", "50"),
            JSONObject(admission.toString()).put("requiredBytes", 1L shl 45),
            JSONObject(admission.toString()).put("reserveBytes", -1),
            JSONObject(admission.toString()).put("deficitBytes", 1),
            JSONObject(admission.toString()).put("extra", "PRIVATE SUBTITLE"))) {
            val failure = try {
                LocalGgufRuntime.response(JSONObject().put("code", "MEMORY").put("residentBytes", 1000L)
                    .put("availableRamBytes", 50L).put("memoryAdmission", invalid).toString())
                throw AssertionError("Expected memory failure")
            } catch (failure: LocalGgufRuntime.Failure) { failure }
            assertEquals(LocalGgufRuntime.Code.MEMORY, failure.code)
            val memory = requireNotNull(failure.nativeMemory)
            assertNull(memory.admission)
            assertTrue(memory.render().contains("RAM disponible au relevé : 50 octets"))
            assertFalse(memory.render().contains("PRIVATE")); assertFalse(memory.toJson().toString().contains("unsafe"))
        }
        assertNull(LocalGgufAttemptReport.NativeMemory.parse(JSONObject().put("residentBytes", "1000")
            .put("peakRssBytes", -1).put("availableRamBytes", 1L shl 45)))
        val unknown = requireNotNull(LocalGgufAttemptReport.NativeMemory.parse(JSONObject()
            .put("memoryAdmission", JSONObject(admission.toString()).put("availableBytes", -1).put("deficitBytes", 0))))
        assertTrue(unknown.render().contains("RAM disponible inconnue"))
        assertFalse(unknown.render().contains("RAM disponible au relevé"))
    }

}
