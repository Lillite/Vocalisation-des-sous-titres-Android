package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid timing must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun identityPreservesPositiveTimesAndEveryOverlappingOccurrence() {
        val timeline = SubtitleTimeline()
        assertTrue(timeline.identity)
        val occurrences = listOf(0L to 90_000L, 2_000L to 5_000L, 2_000L to 5_000L, 9_000L to 11_000L)
        assertEquals(occurrences, occurrences.map { (start, end) -> timeline.mapInterval(start, end).let { it.startMs to it.endMs } })
        assertEquals(4, occurrences.map { timeline.mapInterval(it.first, it.second) }.size)
    }

    @Test fun subtitleOffsetAndNarrationOffsetRemainIndependentAndInvertOnce() {
        val timeline = SubtitleTimeline(globalOffsetMs = 1500, narrationOffsetMs = -200)
        assertEquals(11_500L, timeline.subtitleTimeMs(10_000))
        assertEquals(11_300L, timeline.narrationTimeMs(10_000))
        assertEquals(10_000L, timeline.sourceTimeMs(11_500))
        assertEquals(10_000L, timeline.sourceTimeMs(11_300, narration = true))
        assertEquals(SubtitleTimeline.Interval(11_300, 12_300), timeline.mapInterval(10_000, 11_000, narration = true))
    }

    @Test fun twoAnchorsCorrectDriftAcrossTheWholeTimelineIncludingExtrapolation() {
        val timeline = SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(1000, 2000), SubtitleTimeline.Anchor(11_000, 12_500)))
        assertEquals(2000L, timeline.subtitleTimeMs(1000))
        assertEquals(12_500L, timeline.subtitleTimeMs(11_000))
        assertEquals(7250L, timeline.subtitleTimeMs(6000))
        assertEquals(23_000L, timeline.subtitleTimeMs(21_000))
        assertEquals(6000L, timeline.sourceTimeMs(7250))
        assertEquals(21_000L, timeline.sourceTimeMs(23_000))
    }

    @Test fun extraAnchorsCorrectIndividualPortionsWithoutReversingTime() {
        val timeline = SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(0, 1000),
            SubtitleTimeline.Anchor(10_000, 11_000), SubtitleTimeline.Anchor(20_000, 26_000), SubtitleTimeline.Anchor(30_000, 36_000)))
        assertEquals(6000L, timeline.subtitleTimeMs(5000))
        assertEquals(18_500L, timeline.subtitleTimeMs(15_000))
        assertEquals(31_000L, timeline.subtitleTimeMs(25_000))
        assertEquals(15_000L, timeline.sourceTimeMs(18_500))
        val mapped = (0L..40_000L step 100).map(timeline::subtitleTimeMs)
        assertTrue(mapped.zipWithNext().all { (first, second) -> second > first })
    }

    @Test fun clippingAndRoundingNeverProduceAnEmptyOccurrence() {
        val timeline = SubtitleTimeline(globalOffsetMs = -10_000)
        assertEquals(SubtitleTimeline.Interval(0, 1), timeline.mapInterval(1000, 2000))
        assertEquals(SubtitleTimeline.Interval(0, 2000), timeline.mapInterval(9000, 12_000))
        val compressed = SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(10_000, 1)))
        assertEquals(SubtitleTimeline.Interval(0, 1), compressed.mapInterval(1, 2))
        reject { timeline.mapInterval(1, 1) }
        reject { timeline.mapInterval(2, 1) }
    }

    @Test fun impossibleReorderedOrAmbiguousCorrectionsAreRefused() {
        reject { SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(1, 2))) }
        reject { SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(1, 2), SubtitleTimeline.Anchor(1, 3))) }
        reject { SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(1, 2), SubtitleTimeline.Anchor(3, 2))) }
        reject { SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(1, 2), SubtitleTimeline.Anchor(3, 1))) }
        reject { SubtitleTimeline(anchors = listOf(SubtitleTimeline.Anchor(-1, 0), SubtitleTimeline.Anchor(3, 4))) }
        reject { SubtitleTimeline(narrationOffsetMs = 60_001) }
        reject { SubtitleTimeline(globalOffsetMs = Long.MAX_VALUE) }
        reject { SubtitleTimeline(anchors = List(65) { SubtitleTimeline.Anchor(it.toLong(), it.toLong()) }) }
    }

    @Test fun externalAnchorMutationCannotChangeAnActiveMapping() {
        val source = mutableListOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(1000, 2000))
        val timeline = SubtitleTimeline(anchors = source)
        source.clear()
        assertEquals(1000L, timeline.subtitleTimeMs(500))
        try { (timeline.anchors as MutableList).clear(); fail() } catch (_: UnsupportedOperationException) { }
        assertEquals(timeline, timeline.copy())
        assertNotEquals(timeline, timeline.copy(globalOffsetMs = 1))
    }

    @Test fun extremeInputTimesDoNotOverflowOrReverseIntervals() {
        val timeline = SubtitleTimeline(globalOffsetMs = SubtitleTimeline.MAX_TIME_MS,
            narrationOffsetMs = SubtitleTimeline.MAX_NARRATION_OFFSET_MS,
            anchors = listOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(1, SubtitleTimeline.MAX_TIME_MS)))
        assertEquals(SubtitleTimeline.MAX_TIME_MS, timeline.narrationTimeMs(Long.MAX_VALUE))
        assertEquals(0L, timeline.narrationTimeMs(Long.MIN_VALUE))
        assertEquals(SubtitleTimeline.Interval(SubtitleTimeline.MAX_TIME_MS - 1, SubtitleTimeline.MAX_TIME_MS),
            timeline.mapInterval(Long.MAX_VALUE - 1, Long.MAX_VALUE))
    }

    @Test fun portionsCorrectSceneEditsWithoutSpreadingTheOffsetAcrossOtherSections() {
        val timeline = SubtitleTimeline(globalOffsetMs = 100, narrationOffsetMs = -50,
            portions = listOf(SubtitleTimeline.Portion(10_000, 20_000, 5000), SubtitleTimeline.Portion(30_000, 40_000, -4000)))
        assertFalse(timeline.identity)
        assertEquals(9099L, timeline.subtitleTimeMs(8999))
        assertEquals(15_100L, timeline.subtitleTimeMs(10_000))
        assertEquals(25_099L, timeline.subtitleTimeMs(19_999))
        assertEquals(20_100L, timeline.subtitleTimeMs(20_000))
        assertEquals(26_050L, timeline.narrationTimeMs(30_000))
        assertEquals(40_100L, timeline.subtitleTimeMs(40_000))
        assertEquals(SubtitleTimeline.Interval(15_100, 16_100), timeline.mapInterval(10_000, 11_000))
    }

    @Test fun aCueCrossingAPortionBoundaryKeepsItsOwnOffsetAndEntireDuration() {
        val timeline = SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10_000, 20_000, -5000)))
        val occurrences = listOf(9000L to 11_000L, 19_000L to 22_000L, 19_000L to 22_000L)
        assertEquals(listOf(SubtitleTimeline.Interval(9000, 11_000), SubtitleTimeline.Interval(14_000, 17_000),
            SubtitleTimeline.Interval(14_000, 17_000)), occurrences.map { timeline.mapInterval(it.first, it.second) })
        assertEquals(3, occurrences.map { timeline.mapInterval(it.first, it.second) }.size)
        val drift = timeline.copy(anchors = listOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(20_000, 22_000)))
        assertEquals(SubtitleTimeline.Interval(15_900, 19_200), drift.mapInterval(19_000, 22_000))
    }

    @Test fun inverseOfSceneEditsChoosesEarliestOverlapAndNextBoundaryForAGap() {
        val timeline = SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10_000, 20_000, 5000)))
        assertEquals(10_000L, timeline.sourceTimeMs(12_000))
        assertEquals(12_000L, timeline.sourceTimeMs(17_000))
        assertEquals(16_000L, timeline.sourceTimeMs(21_000))
        assertEquals(27_000L, timeline.sourceTimeMs(27_000))
        val cut = SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10_000, 20_000, -5000)))
        assertEquals(6000L, cut.sourceTimeMs(6000))
        assertEquals(20_000L, cut.sourceTimeMs(17_000))
        assertEquals(20_000L, cut.sourceTimeMs(17_050, narration = true))
    }

    @Test fun invalidOverlappingUnorderedOrUnboundedPortionsAreRejected() {
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(-1, 10, 0))) }
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10, 10, 0))) }
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10, 9, 0))) }
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(0, 10, Long.MAX_VALUE))) }
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(0, 10, 0), SubtitleTimeline.Portion(9, 20, 0))) }
        reject { SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(10, 20, 0), SubtitleTimeline.Portion(0, 10, 0))) }
        reject { SubtitleTimeline(portions = List(65) { SubtitleTimeline.Portion(it.toLong(), it + 1L, 0) }) }
        val source = mutableListOf(SubtitleTimeline.Portion(0, 1000, 100))
        val timeline = SubtitleTimeline(portions = source)
        source.clear()
        assertEquals(600L, timeline.subtitleTimeMs(500))
        assertEquals(timeline, timeline.copy())
        assertNotEquals(timeline, timeline.copy(portions = emptyList()))
        try { (timeline.portions as MutableList).clear(); fail() } catch (_: UnsupportedOperationException) { }
    }
}
