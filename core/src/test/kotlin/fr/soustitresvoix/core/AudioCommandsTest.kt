package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class AudioCommandsTest {
    private val base = AudioCommandContext(7, false, CapabilityStatus.CONDITIONAL,
        AudioSettings(.6f, .25f, 160, 500, 350, false))

    private fun accepted(command: AudioCommand, context: AudioCommandContext = base): AudioCommandDecision.Accepted {
        val decision = AudioCommandPolicy.validate(AudioCommandRequest(context.generation, command), context)
        assertTrue("Expected acceptance, got $decision", decision is AudioCommandDecision.Accepted)
        return decision as AudioCommandDecision.Accepted
    }

    private fun rejected(command: AudioCommand, reason: AudioCommandRejection,
        context: AudioCommandContext = base, generation: Long = context.generation) {
        assertEquals(AudioCommandDecision.Rejected(generation, reason),
            AudioCommandPolicy.validate(AudioCommandRequest(generation, command), context))
    }

    @Test fun levelBoundsAreInclusiveAndNonFiniteOrAdjacentOutsideValuesAreRejectedAtomically() {
        for (normal in listOf(0f, .53f, .59f, 1f)) for (ratio in listOf(0f, .25f, 1f)) {
            val result = accepted(AudioCommand.SetLevels(normal, ratio))
            assertEquals(base.requested.copy(movieVolume = normal, duckVolume = ratio), result.requested)
            assertEquals(7L, result.generation)
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejected(AudioCommand.SetLevels(invalid, .75f), AudioCommandRejection.NON_FINITE_VALUE)
            rejected(AudioCommand.SetLevels(.8f, invalid), AudioCommandRejection.NON_FINITE_VALUE)
        }
        for (outside in listOf(Math.nextDown(0f), Math.nextUp(1f), -Float.MAX_VALUE, Float.MAX_VALUE)) {
            rejected(AudioCommand.SetLevels(outside, .75f), AudioCommandRejection.OUT_OF_RANGE)
            rejected(AudioCommand.SetLevels(.8f, outside), AudioCommandRejection.OUT_OF_RANGE)
        }
        rejected(AudioCommand.SetLevels(2f, Float.NaN), AudioCommandRejection.NON_FINITE_VALUE)
        assertEquals(AudioSettings(.6f, .25f, 160, 500, 350, false), base.requested)
    }

    @Test fun timingBoundsCoverEveryFieldWithoutClampingOrPartialChanges() {
        for (attack in listOf(0, 160, 2000)) for (release in listOf(0, 500, 2000)) for (hold in listOf(0, 350, 2000)) {
            val result = accepted(AudioCommand.SetDuckingTiming(attack, release, hold))
            assertEquals(base.requested.copy(attackMs = attack, releaseMs = release, holdMs = hold), result.requested)
        }
        for (outside in listOf(-1, 2001, Int.MIN_VALUE, Int.MAX_VALUE)) {
            rejected(AudioCommand.SetDuckingTiming(outside, 100, 200), AudioCommandRejection.OUT_OF_RANGE)
            rejected(AudioCommand.SetDuckingTiming(100, outside, 200), AudioCommandRejection.OUT_OF_RANGE)
            rejected(AudioCommand.SetDuckingTiming(100, 200, outside), AudioCommandRejection.OUT_OF_RANGE)
        }
        assertEquals(AudioSettings(.6f, .25f, 160, 500, 350, false), base.requested)
    }

    @Test fun unchangedGroupsAndSignedZeroAreExplicitNoOps() {
        assertFalse(accepted(AudioCommand.SetLevels(.6f, .25f)).changed)
        assertFalse(accepted(AudioCommand.SetDuckingTiming(160, 500, 350)).changed)
        assertFalse(accepted(AudioCommand.SetSpectralDucking(false)).changed)
        for (normal in listOf(-0f, 0f)) for (ratio in listOf(-0f, 0f)) {
            val context = base.copy(requested = base.requested.copy(movieVolume = 0f, duckVolume = -0f))
            assertFalse(accepted(AudioCommand.SetLevels(normal, ratio), context).changed)
        }
        assertTrue(accepted(AudioCommand.SetLevels(.59f, .25f)).changed)
        assertTrue(accepted(AudioCommand.SetDuckingTiming(160, 500, 351)).changed)
        assertTrue(accepted(AudioCommand.SetSpectralDucking(true)).changed)
    }

    @Test fun staleOrClosedRequestsWinOverValidationAndRetainOriginalTicket() {
        val commands = listOf(AudioCommand.SetLevels(Float.NaN, 2f),
            AudioCommand.SetDuckingTiming(-1, 2001, 350), AudioCommand.SetSpectralDucking(true))
        for (command in commands) {
            val blocked = base.copy(spectralAvailability = CapabilityStatus.UNAVAILABLE)
            rejected(command, AudioCommandRejection.STALE_MEDIA, blocked, generation = 6)
            rejected(command, AudioCommandRejection.CLOSED, blocked.copy(closed = true), generation = 6)
            rejected(command, AudioCommandRejection.CLOSED, blocked.copy(closed = true))
        }
        // Media replacement invalidates an old producer, not the persistent preferences.
        val reopened = base.copy(generation = 8)
        rejected(AudioCommand.SetLevels(.5f, .2f), AudioCommandRejection.STALE_MEDIA, reopened, generation = 7)
        assertEquals(base.requested, reopened.requested)
        assertEquals(8L, accepted(AudioCommand.SetLevels(.5f, .2f), reopened).generation)
    }

    @Test fun spectralActivationRequiresADeclarationButDisablingAndUnchangedRequestsRemainSafe() {
        for ((status, reason) in listOf(CapabilityStatus.UNKNOWN to AudioCommandRejection.CAPABILITY_UNKNOWN,
            CapabilityStatus.UNAVAILABLE to AudioCommandRejection.CAPABILITY_UNAVAILABLE)) {
            val context = base.copy(spectralAvailability = status)
            rejected(AudioCommand.SetSpectralDucking(true), reason, context)
            assertFalse(accepted(AudioCommand.SetSpectralDucking(false), context).changed)
            val enabled = context.copy(requested = context.requested.copy(spectralDucking = true))
            assertFalse(accepted(AudioCommand.SetSpectralDucking(true), enabled).changed)
            val disabled = accepted(AudioCommand.SetSpectralDucking(false), enabled)
            assertTrue(disabled.changed); assertFalse(disabled.requested.spectralDucking)
            // A missing spectral capability does not forbid ordinary film levels or timing.
            assertTrue(accepted(AudioCommand.SetLevels(.5f, .2f), context).changed)
            assertTrue(accepted(AudioCommand.SetDuckingTiming(0, 0, 0), context).changed)
        }
        for (status in listOf(CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL)) {
            val result = accepted(AudioCommand.SetSpectralDucking(true), base.copy(spectralAvailability = status))
            assertTrue(result.changed); assertTrue(result.requested.spectralDucking)
        }
    }

    @Test fun eachGroupRetainsAllOtherSettingsAndValidationDoesNotMutateContext() {
        val enabled = base.copy(requested = AudioSettings(.6f, .53f, 40, 900, 450, true))
        val levels = accepted(AudioCommand.SetLevels(1f, .59f), enabled)
        assertEquals(AudioSettings(1f, .59f, 40, 900, 450, true), levels.requested)
        val timing = accepted(AudioCommand.SetDuckingTiming(2000, 0, 50), enabled)
        assertEquals(AudioSettings(.6f, .53f, 2000, 0, 50, true), timing.requested)
        val spectral = accepted(AudioCommand.SetSpectralDucking(false), enabled)
        assertEquals(AudioSettings(.6f, .53f, 40, 900, 450, false), spectral.requested)
        assertEquals(AudioSettings(.6f, .53f, 40, 900, 450, true), enabled.requested)
    }
}
