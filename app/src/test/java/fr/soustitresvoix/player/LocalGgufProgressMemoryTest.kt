package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class LocalGgufProgressMemoryTest {
    @Test fun phaseObservationsAreThrottledAcrossRequestsWithoutPerTokenPolling() {
        var now = 0L
        var reads = 0
        val probe = LocalGgufProgressMemory({ now }) { reads++; LocalGgufProgressMemory.Sample(1024, 4096) }
        assertNull(probe.sample("private text is not a stage"))
        assertEquals(0, reads)
        assertEquals(1024L, probe.sample("TOKENIZE")?.residentBytes)
        assertNull(probe.sample("PREFILL"))
        now = 999_999_999
        assertNull(probe.sample("DECODE"))
        now = 1_000_000_000
        assertNotNull(probe.sample("DECODE"))
        repeat(100) { assertNull(probe.sample("DECODE")) }
        assertEquals(2, reads)
    }

    @Test fun nativeProcessResidentPagesRespectActualPageSizeAndBounds() {
        assertEquals(102400L, LocalGgufProgressMemory.parseResidentBytes("100 25 0 0 0 0 0\n", 4096))
        assertEquals(409600L, LocalGgufProgressMemory.parseResidentBytes("100 25\n", 16384))
        for (text in listOf("", "100", "100 -1", "100 1e6", "100 9223372036854775808", "1".repeat(256)))
            assertEquals(-1L, LocalGgufProgressMemory.parseResidentBytes(text, 4096))
        assertEquals(-1L, LocalGgufProgressMemory.parseResidentBytes("100 25", 0))
    }

    @Test fun unavailableOrOutOfRangeMemoryNeverInterruptsTranslationOrInventsMeasurements() {
        var now = 0L
        val unavailable = LocalGgufProgressMemory({ now }) { error("Synthetic unavailable measurement") }
        assertNull(unavailable.sample("PREFILL"))
        now = 2_000_000_000
        val invalid = LocalGgufProgressMemory({ now }) { LocalGgufProgressMemory.Sample(Long.MAX_VALUE, -2) }
        assertEquals(LocalGgufProgressMemory.Sample(-1, -1), invalid.sample("PREFILL"))
    }
}
