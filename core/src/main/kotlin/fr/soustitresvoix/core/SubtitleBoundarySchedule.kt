package fr.soustitresvoix.core

/** A bounded immutable event clock. Both starts and ends are visited even when
 * an entire short subtitle falls between two player observations. */
class SubtitleBoundarySchedule(intervals: List<SubtitleTimeline.Interval>) {
    private val times: LongArray
    private data class Entry(val index: Int, val interval: SubtitleTimeline.Interval)
    private data class Node(val center: Long, val starts: List<Entry>, val ends: List<Entry>,
        val left: Node?, val right: Node?)
    private val root: Node?
    init {
        require(intervals.size <= 100_000)
        require(intervals.all { it.startMs >= 0 && it.endMs > it.startMs })
        times = intervals.asSequence().flatMap { sequenceOf(it.startMs, it.endMs) }.distinct().sorted().toList().toLongArray()
        root = build(intervals.mapIndexed { index, interval -> Entry(index, interval) }.sortedBy { it.interval.startMs })
    }
    fun crossed(fromExclusiveMs: Long, toInclusiveMs: Long, limit: Int = Int.MAX_VALUE): List<Long> {
        require(limit >= 0)
        if (toInclusiveMs <= fromExclusiveMs) return emptyList()
        var low = 0; var high = times.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (times[middle] <= fromExclusiveMs) low = middle + 1 else high = middle
        }
        val result = ArrayList<Long>()
        while (low < times.size && times[low] <= toInclusiveMs && result.size < limit) result += times[low++]
        return result
    }

    /** Original input indices of every active interval, including real repeats.
     * A centered interval tree visits one balanced branch plus its matches. The
     * fixed-width radix ordering restores source order without sorting by text
     * or scanning the complete track for each boundary. */
    fun activeIndices(timeMs: Long): IntArray {
        val found = ArrayList<Int>()
        var node = root
        while (node != null) {
            if (timeMs < node.center) {
                for (entry in node.starts) {
                    if (entry.interval.startMs > timeMs) break
                    found += entry.index
                }
                node = node.left
            } else {
                for (entry in node.ends) {
                    if (entry.interval.endMs <= timeMs) break
                    found += entry.index
                }
                node = node.right
            }
        }
        val result = found.toIntArray()
        if (result.size < 2) return result
        if (result.size <= 32) { result.sort(); return result }
        // Indexes are in 0..99,999, so two stable nine-bit passes suffice.
        val scratch = IntArray(result.size)
        for (shift in intArrayOf(0, 9)) {
            val counts = IntArray(512)
            for (index in result) counts[(index ushr shift) and 511]++
            var offset = 0
            for (bucket in counts.indices) { val size = counts[bucket]; counts[bucket] = offset; offset += size }
            for (index in result) scratch[counts[(index ushr shift) and 511]++] = index
            scratch.copyInto(result)
        }
        return result
    }

    private fun build(entries: List<Entry>): Node? {
        if (entries.isEmpty()) return null
        val center = entries[entries.size / 2].interval.startMs
        val left = ArrayList<Entry>()
        val right = ArrayList<Entry>()
        val crossing = ArrayList<Entry>()
        for (entry in entries) when {
            entry.interval.endMs <= center -> left += entry
            entry.interval.startMs > center -> right += entry
            else -> crossing += entry
        }
        return Node(center, crossing, crossing.sortedByDescending { it.interval.endMs }, build(left), build(right))
    }
}
