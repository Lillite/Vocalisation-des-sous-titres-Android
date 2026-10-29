package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class LocalGgufContextPolicyTest {
    @Test fun defaultPolicyIsPreservedForMissingOrUnknownPreferences() {
        for (value in listOf(null, "", "future-policy"))
            assertEquals(LocalGgufContextPolicy.RECENT_TWO, LocalGgufContextPolicy.fromPreference(value))
        for (policy in LocalGgufContextPolicy.entries)
            assertEquals(policy, LocalGgufContextPolicy.fromPreference(policy.name))
    }
    @Test fun eachPolicyUsesOnlyCompletedPreviousOccurrencesAndResetsAtItsBoundary() {
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 4, 5), (0..7).map(LocalGgufContextPolicy.RECENT_TWO::contextStartIndex))
        assertEquals((0..7).toList(), (0..7).map(LocalGgufContextPolicy.ISOLATED::contextStartIndex))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 6, 6, 6, 6, 6, 6, 12),
            (0..12).map(LocalGgufContextPolicy.BLOCKS_SIX::contextStartIndex))
        for (policy in LocalGgufContextPolicy.entries) for (index in 0..200) {
            val start = policy.contextStartIndex(index)
            assertTrue(start in 0..index)
            assertTrue(index - start <= policy.maximumPreviousPairs)
        }
    }
    @Test fun negativeOccurrenceIndicesAreRefused() {
        for (policy in LocalGgufContextPolicy.entries) {
            try { policy.contextStartIndex(-1); fail("Negative index must be refused") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
