package fr.soustitresvoix.player

/** Nearest-rank percentiles of an explicitly selected set of observed latencies. */
internal object LocalGgufBenchmarkStatistics {
    data class Distribution(
        val count: Int,
        val p50Ms: Long?,
        val p95Ms: Long?,
        val maxMs: Long?
    )

    fun distribution(latenciesMs: List<Long>): Distribution {
        require(latenciesMs.all { it >= 0 }) { "Observed latencies must be non-negative." }
        if (latenciesMs.isEmpty()) return Distribution(0, null, null, null)
        val sorted = latenciesMs.sorted()
        // Integer ceil(n * percentage / 100), widened before multiplication.
        fun percentile(percentage: Int): Long =
            sorted[(((sorted.size.toLong() * percentage + 99) / 100) - 1).toInt()]
        return Distribution(sorted.size, percentile(50), percentile(95), sorted.last())
    }
}
