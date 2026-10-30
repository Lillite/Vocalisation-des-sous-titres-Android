package fr.soustitresvoix.player

import android.os.Bundle
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
}
