package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

/** Fixture and arithmetic only; these checks do not execute a model or assess translation quality. */
class LocalGgufBenchmarkStatisticsTest {
    @Test fun corpusPreservesEveryRepeatedOccurrenceAndBothOverlaps() {
        val cues = LocalGgufBenchmarkCorpus.cues
        assertEquals("synthetic-en-fr-v1", LocalGgufBenchmarkCorpus.VERSION)
        assertEquals(32, cues.size)
        assertEquals(29, cues.map { it.source }.distinct().size)
        assertEquals(cues.size, cues.map { it.id }.distinct().size)
        assertTrue(cues.all { it.id.isNotBlank() && it.startMs >= 0 && it.endMs > it.startMs })
        assertTrue(cues.zipWithNext().all { (left, right) -> left.startMs < right.startMs })
        assertEquals(2, cues.zipWithNext().count { (left, right) -> left.endMs > right.startMs })
        val repeats = cues.groupBy { it.source }.values.filter { it.size > 1 }
        assertEquals(3, repeats.size)
        assertTrue(repeats.all { group ->
            group.size == 2 && group.map { it.id }.distinct().size == 2 &&
                group.map { it.startMs }.distinct().size == 2
        })
        assertTrue(cues.any { it.kind == "panneau" })
        assertTrue(cues.any { it.kind == "dialogue long" })
        assertTrue(cues.all { it.source.isNotBlank() && it.source.length <= 400 })
    }

    @Test fun nearestRankUsesEverySampleWithoutChangingTheInputOrder() {
        val values = (1L..20L).reversed().toList()
        val before = values.toList()
        val result = LocalGgufBenchmarkStatistics.distribution(values)
        assertEquals(20, result.count)
        assertEquals(10L, result.p50Ms)
        assertEquals(19L, result.p95Ms)
        assertEquals(20L, result.maxMs)
        assertEquals(before, values)
        val odd = LocalGgufBenchmarkStatistics.distribution(listOf(70, 10, 40, 20, 30))
        assertEquals(30L, odd.p50Ms)
        assertEquals(70L, odd.p95Ms)
    }

    @Test fun missingMeasurementsRemainAbsentAndZeroIsAValidObservedLatency() {
        val empty = LocalGgufBenchmarkStatistics.distribution(emptyList())
        assertEquals(0, empty.count)
        assertNull(empty.p50Ms)
        assertNull(empty.p95Ms)
        assertNull(empty.maxMs)
        val zero = LocalGgufBenchmarkStatistics.distribution(listOf(0))
        assertEquals(LocalGgufBenchmarkStatistics.Distribution(1, 0, 0, 0), zero)
        val single = LocalGgufBenchmarkStatistics.distribution(listOf(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, single.p50Ms)
        assertEquals(Long.MAX_VALUE, single.p95Ms)
        assertEquals(Long.MAX_VALUE, single.maxMs)
    }

    @Test fun invalidNegativeMeasurementCannotBeTurnedIntoAFasterResult() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalGgufBenchmarkStatistics.distribution(listOf(4, -1, 9))
        }
    }
}
