package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

/** Formatting/privacy oracles only. These are not device or decoding measures. */
class PreparationProbeReportTest {
    @Test fun declarationsDoNotBecomeInferenceEvidenceAndMissingValuesStayUnknown() {
        val report = PreparationDeviceSnapshot("Vendor", "Fixture", 26, listOf("arm64-v8a"), 4,
            null, null, null, null, null, null, 12,
            listOf(PreparationCodecDeclaration("decoder.synthetic", listOf("video/avc"), null, null))).render()
        assertTrue(report.contains("RAM totale rapportée par Android, hors extension par stockage : non exposée"))
        assertTrue(report.contains("matériel=non exposé, logiciel=non exposé"))
        assertTrue(report.contains("aucune exécution déduite de cet inventaire"))
        assertTrue(report.contains("ne prouve pas un décodage effectif"))
        assertFalse(report.contains("NPU activé"))
        assertFalse(report.contains("GPU activé"))
    }

    @Test fun measuredPresentationTimesAreRenderedWithoutInventingAnOcrRate() {
        val result = PreparationVideoResult(PreparationVideoStatus.SUCCESS, "Images mesurées.",
            elapsedMs = 117, mimeType = "video/avc", width = 640, height = 360,
            decoderName = "fixture.decoder", decodedFrames = 3, accessibleFrames = 2,
            presentationTimesUs = listOf(0L, 83_333L, 166_667L))
        val report = result.render()
        assertTrue(report.contains("117 ms"))
        assertTrue(report.contains("0, 83333, 166667"))
        assertTrue(report.contains("Images dont des pixels ont été accessibles : 2"))
        assertTrue(report.contains("ne mesure ni OCR, ni traduction, ni accélération neuronale"))
        assertFalse(report.contains("images/seconde"))
        assertFalse(report.contains("file://"))
        assertFalse(report.contains("content://"))
    }

    @Test fun partialObservationsRemainPartialAfterCancellationOrTimeout() {
        for (status in listOf(PreparationVideoStatus.CANCELLED, PreparationVideoStatus.TIMED_OUT)) {
            val result = PreparationVideoResult(status, "Observation partielle.", 10_000,
                decodedFrames = 1, accessibleFrames = 1, presentationTimesUs = listOf(91L))
            val report = result.render()
            assertFalse(report.contains("Décodage observé\n"))
            assertTrue(report.contains("Observation partielle."))
            assertTrue(report.contains("Images sorties du décodeur : 1"))
            assertTrue(report.contains("91"))
            assertTrue(report.contains("Fin du flux atteinte : non"))
        }
    }
}
