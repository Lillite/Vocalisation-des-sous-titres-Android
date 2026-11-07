package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class LocalPreparationGovernorTest {
    @Test fun thermalHeadroomAndMemoryAreSampledAtMostEveryTenSeconds() {
        var now = 100L
        var probes = 0
        val governor = LocalPreparationGovernor({ probes++; LocalPreparationGovernor.Sample(thermalHeadroom = 0.96f) }, { now }, {})
        repeat(50) { assertEquals(LocalPreparationGovernor.Kind.THERMAL, governor.reason()) }
        assertEquals(1, probes)
        now += 9_999
        governor.reason()
        assertEquals(1, probes)
        now++
        governor.reason()
        assertEquals(2, probes)
    }

    @Test fun unknownAndNonFiniteReadingsAreNotInventedAsMemoryOrThermalFailures() {
        for (sample in listOf(LocalPreparationGovernor.Sample(), LocalPreparationGovernor.Sample(false, 0, Float.NaN))) {
            val governor = LocalPreparationGovernor({ sample }, { 0 }, {})
            assertNull(governor.reason())
        }
        assertEquals(LocalPreparationGovernor.Kind.MEMORY,
            LocalPreparationGovernor({ LocalPreparationGovernor.Sample(true, 4, 1f) }, { 0 }, {}).reason())
        assertEquals(LocalPreparationGovernor.Kind.THERMAL,
            LocalPreparationGovernor({ LocalPreparationGovernor.Sample(false, 3) }, { 0 }, {}).reason())
    }

    @Test fun aReleasedModelWaitsForANewSafeSampleWithinABoundedInterval() {
        var now = 0L
        var probes = 0
        var notifications = 0
        val governor = LocalPreparationGovernor({
            probes++; LocalPreparationGovernor.Sample(lowMemory = probes == 1)
        }, { now }, { now += it })
        try { governor.beforeRequest { false }; fail("Low-memory signal must stop the next inference") }
        catch (pause: LocalPreparationGovernor.Pause) { assertEquals(LocalPreparationGovernor.Kind.MEMORY, pause.kind) }
        governor.awaitReady(LocalPreparationGovernor.Kind.MEMORY, { false }) { notifications++ }
        assertEquals(10_000L, now)
        assertEquals(2, probes)
        assertEquals(1, notifications)
        governor.beforeRequest { false }
    }

    @Test fun waitingIsCancellableAndCannotBecomeAnUnboundedAutomaticRestart() {
        var now = 0L
        val governor = LocalPreparationGovernor({ LocalPreparationGovernor.Sample(thermalStatus = 4) }, { now }, { now += it })
        try { governor.awaitReady(LocalPreparationGovernor.Kind.THERMAL, { false }) {}; fail("Wait must expire") }
        catch (expired: LocalPreparationGovernor.Expired) { assertEquals(LocalPreparationGovernor.Kind.THERMAL, expired.kind) }
        assertEquals(LocalPreparationGovernor.MAX_WAIT_MS, now)
        var cancelled = false
        val other = LocalPreparationGovernor({ LocalPreparationGovernor.Sample(lowMemory = true) }, { now }, { cancelled = true; now += it })
        try { other.awaitReady(LocalPreparationGovernor.Kind.MEMORY, { cancelled }) {}; fail("User cancellation must stop the wait") }
        catch (_: TranslationCancelledException) { }
    }
}
