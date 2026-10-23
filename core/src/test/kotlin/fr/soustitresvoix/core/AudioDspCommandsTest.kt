package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class AudioDspCommandsTest {
    private val base = AudioCommandContext(7, false, CapabilityStatus.CONDITIONAL,
        AudioSettings(.6f, .35f, 240, 700, 450, true), CapabilityStatus.CONDITIONAL)
    private fun decide(settings: AudioDspSettings, context: AudioCommandContext = base, generation: Long = 7) =
        AudioCommandPolicy.validate(AudioCommandRequest(generation, AudioCommand.SetDsp(settings)), context)

    @Test fun dspCommandsPreserveEveryOtherAudioFieldAndRefuseGroupsAtomically() {
        val wanted = AudioDspSettings(enabled = true, preampDb = -7.25, balance = .35,
            band3 = AudioEqBand(true, 1765.25, 3.5, 2.25))
        val result = decide(wanted) as AudioCommandDecision.Accepted
        assertTrue(result.changed)
        assertEquals(base.requested.copy(dsp = wanted), result.requested)
        assertEquals(AudioDspSettings(), base.requested.dsp)
        for ((invalid, reason) in listOf(
            wanted.copy(preampDb = 12.0000000001) to AudioCommandRejection.OUT_OF_RANGE,
            wanted.copy(band3 = wanted.band3.copy(q = Double.NaN)) to AudioCommandRejection.NON_FINITE_VALUE,
            wanted.copy(limiterReleaseMs = 2001) to AudioCommandRejection.OUT_OF_RANGE)) {
            assertEquals(AudioCommandDecision.Rejected(7, reason), decide(invalid))
            assertEquals(AudioDspSettings(), base.requested.dsp)
        }
        val context = base.copy(requested = result.requested)
        assertEquals(wanted, (AudioCommandPolicy.validate(AudioCommandRequest(7,
            AudioCommand.SetLevels(.37f, .12f)), context) as AudioCommandDecision.Accepted).requested.dsp)
    }

    @Test fun capabilityIsRequiredForChangedEnabledConfigurationsButDisablingAndPreparationRemainPossible() {
        for ((status, reason) in listOf(CapabilityStatus.UNAVAILABLE to AudioCommandRejection.DSP_CAPABILITY_UNAVAILABLE,
            CapabilityStatus.UNKNOWN to AudioCommandRejection.DSP_CAPABILITY_UNKNOWN)) {
            val context = base.copy(dspAvailability = status)
            assertEquals(AudioCommandDecision.Rejected(7, reason), decide(AudioDspSettings(enabled = true), context))
            val prepared = AudioDspSettings(preampDb = -9.5)
            assertTrue((decide(prepared, context) as AudioCommandDecision.Accepted).changed)
            val enabled = context.copy(requested = context.requested.copy(dsp = prepared.copy(enabled = true)))
            assertFalse((decide(enabled.requested.dsp, enabled) as AudioCommandDecision.Accepted).changed)
            assertEquals(AudioCommandDecision.Rejected(7, reason),
                decide(enabled.requested.dsp.copy(preampDb = -8.0), enabled))
            assertEquals(prepared, (decide(prepared, enabled) as AudioCommandDecision.Accepted).requested.dsp)
        }
        for (status in listOf(CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL))
            assertTrue((decide(AudioDspSettings(enabled = true), base.copy(dspAvailability = status))
                as AudioCommandDecision.Accepted).changed)
    }

    @Test fun closedAndStaleRequestsHavePriorityAndSignedZeroIsANoOp() {
        val invalid = AudioDspSettings(preampDb = Double.POSITIVE_INFINITY)
        assertEquals(AudioCommandDecision.Rejected(6, AudioCommandRejection.CLOSED),
            decide(invalid, base.copy(closed = true), 6))
        assertEquals(AudioCommandDecision.Rejected(6, AudioCommandRejection.STALE_MEDIA), decide(invalid, generation = 6))
        val zeros = AudioDspSettings(preampDb = -0.0, balance = -0.0,
            band1 = AudioEqBand(frequencyHz = 60.0, gainDb = -0.0))
        val result = decide(zeros) as AudioCommandDecision.Accepted
        assertFalse(result.changed)
        assertEquals(AudioDspSettings(), result.requested.dsp)
    }
}
