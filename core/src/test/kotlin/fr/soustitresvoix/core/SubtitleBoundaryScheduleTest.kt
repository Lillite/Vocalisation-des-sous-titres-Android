package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleBoundaryScheduleTest {
    @Test fun startsAndEndsOfSeveralShortOccurrencesSurviveOneLongObservationGap() {
        val schedule = SubtitleBoundarySchedule(listOf(
            SubtitleTimeline.Interval(1011, 1021), SubtitleTimeline.Interval(3011, 3021),
            SubtitleTimeline.Interval(6011, 6021)))
        assertEquals(listOf(1011L, 1021L, 3011L, 3021L, 6011L, 6021L), schedule.crossed(1000, 7000))
    }

    @Test fun emptyDisplayBoundaryIsStillVisitedAndSharedStartEndIsNotDuplicated() {
        val schedule = SubtitleBoundarySchedule(listOf(
            SubtitleTimeline.Interval(1000, 2000), SubtitleTimeline.Interval(2000, 3000)))
        assertEquals(listOf(2000L), schedule.crossed(1999, 2000))
        assertEquals(listOf(3000L), schedule.crossed(2000, 4000))
        assertTrue(schedule.crossed(3000, 4000).isEmpty())
    }

    @Test fun roundedSubMillisecondSourceEventRetainsBothMappedBoundaries() {
        val sourceStartUs = 1_001_001L
        val sourceEndUs = 1_001_501L
        val sourceStartMs = sourceStartUs / 1000
        val sourceEndMs = (sourceEndUs / 1000).coerceAtLeast(sourceStartMs + 1)
        val interval = SubtitleTimeline(globalOffsetMs = 1).mapInterval(sourceStartMs, sourceEndMs, narration = true)
        val schedule = SubtitleBoundarySchedule(listOf(interval))
        assertEquals(SubtitleTimeline.Interval(1002, 1003), interval)
        assertEquals(listOf(1002L, 1003L), schedule.crossed(1000, 1100))
    }

    @Test fun distinctOverlappingOccurrencesShareAClockWithoutMergingTheirSourceIdentity() {
        val schedule = SubtitleBoundarySchedule(listOf(
            SubtitleTimeline.Interval(1000, 9000), SubtitleTimeline.Interval(2000, 3000),
            SubtitleTimeline.Interval(2000, 3000)))
        assertEquals(listOf(1000L, 2000L, 3000L, 9000L), schedule.crossed(0, 10_000))
    }

    @Test fun boundsAreExclusiveThenInclusiveAndExtremeTimestampsCannotOverflow() {
        val schedule = SubtitleBoundarySchedule(listOf(SubtitleTimeline.Interval(0, 1),
            SubtitleTimeline.Interval(Long.MAX_VALUE - 1, Long.MAX_VALUE)))
        assertEquals(listOf(0L, 1L), schedule.crossed(-1, 1))
        assertEquals(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), schedule.crossed(1, Long.MAX_VALUE))
        assertTrue(schedule.crossed(Long.MAX_VALUE, 0).isEmpty())
    }

    @Test fun invalidIntervalsAndUnboundedSchedulesAreRefused() {
        for (interval in listOf(SubtitleTimeline.Interval(-1, 1), SubtitleTimeline.Interval(1, 1),
            SubtitleTimeline.Interval(2, 1))) {
            try { SubtitleBoundarySchedule(listOf(interval)); fail("Invalid interval admitted") }
            catch (_: IllegalArgumentException) { }
        }
        try {
            SubtitleBoundarySchedule(List(100_001) { SubtitleTimeline.Interval(0, 1) })
            fail("Unbounded schedule admitted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun activeLookupMatchesLinearReferenceWithUnsortedOverlapsRepeatsPortionJumpsAndExclusiveEnds() {
        val random = kotlin.random.Random(786)
        val timeline = SubtitleTimeline(portions = listOf(
            SubtitleTimeline.Portion(1000, 2500, -800), SubtitleTimeline.Portion(3000, 4000, -1500)))
        val intervals = List(800) {
            val start = random.nextLong(0, 4000)
            timeline.mapInterval(start, start + random.nextLong(1, 1000), narration = true)
        } + List(4) { SubtitleTimeline.Interval(10, 3000) }
        val schedule = SubtitleBoundarySchedule(intervals)
        for (time in -1L..5000L) {
            val expected = intervals.indices.filter { time >= intervals[it].startMs && time < intervals[it].endMs }.toIntArray()
            assertArrayEquals("Active source ordering at $time", expected, schedule.activeIndices(time))
        }
    }

    @Test fun hundredThousandDisjointIntervalsCanBeQueriedIndividuallyWithoutScanningTheTrack() {
        val count = 100_000
        // Deliberately permute input order: returned indices must remain source
        // indices rather than the interval tree's chronological storage order.
        val intervals = List(count) { index ->
            val start = ((index.toLong() * 7919) % count) * 3
            SubtitleTimeline.Interval(start, start + 1)
        }
        val schedule = SubtitleBoundarySchedule(intervals)
        intervals.forEachIndexed { index, interval ->
            assertArrayEquals(intArrayOf(index), schedule.activeIndices(interval.startMs))
            assertTrue(schedule.activeIndices(interval.endMs).isEmpty())
        }
    }

    @Test fun hundredThousandSimultaneousIntervalsRetainEveryOriginalInputIndex() {
        val count = 100_000
        val schedule = SubtitleBoundarySchedule(List(count) { index ->
            SubtitleTimeline.Interval((index.toLong() * 7919) % count, 200_000)
        })
        assertArrayEquals(IntArray(count) { it }, schedule.activeIndices(100_000))
        assertTrue(schedule.activeIndices(200_000).isEmpty())
    }

    @Test fun boundedBoundaryPagesResumeWithoutLosingOrDuplicatingAnEvent() {
        val schedule = SubtitleBoundarySchedule(List(1000) { SubtitleTimeline.Interval(it * 3L, it * 3L + 1) })
        val expected = schedule.crossed(-1, 5000)
        val actual = mutableListOf<Long>()
        var cursor = -1L
        while (true) {
            val page = schedule.crossed(cursor, 5000, 17)
            if (page.isEmpty()) break
            assertTrue(page.size <= 17)
            actual += page
            cursor = page.last()
        }
        assertEquals(expected, actual)
        assertTrue(schedule.crossed(-1, 5000, 0).isEmpty())
        try { schedule.crossed(-1, 5000, -1); fail("Negative page limit admitted") }
        catch (_: IllegalArgumentException) { }
    }
}
