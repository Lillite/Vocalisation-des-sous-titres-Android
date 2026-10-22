package androidx.media3.exoplayer.text

import androidx.media3.common.text.CueGroup
import androidx.media3.extractor.text.CuesWithTiming

/** Test-only access to the exact resolver used by TextRenderer 1.11.1.
 * No duplicate resolver implementation and no production access to this class.
 */
object RealCueTimeline {
    fun frames(groups: List<CuesWithTiming>): List<CueGroup> {
        val resolver = MergingCuesResolver()
        groups.forEach { resolver.addCues(it, 0) }
        return groups.flatMap { listOf(it.startTimeUs, it.endTimeUs) }.distinct().sorted()
            .map { CueGroup(resolver.getCuesAtTimeUs(it), it) }
    }
}
