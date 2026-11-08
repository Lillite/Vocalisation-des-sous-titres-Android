package fr.soustitresvoix.player

/** Whole source occurrences only. The native tokenizer remains the authoritative token budget. */
internal data class LocalGgufSourceWindow(val previous: List<String>, val following: List<String>) {
    val isEmpty: Boolean get() = previous.isEmpty() && following.isEmpty()

    /** Remove the most distant occurrence first, alternating sides at equal distance. */
    fun reduced(): LocalGgufSourceWindow = when {
        isEmpty -> this
        previous.isNotEmpty() && previous.size >= following.size -> copy(previous = previous.drop(1))
        else -> copy(following = following.dropLast(1))
    }

    companion object {
        // This is an additional-source character budget, not a token estimate.
        // An over-budget neighbour is removed whole; the current cue is never clipped.
        const val MAX_CHARACTERS = 2000
        fun bounded(previous: List<String>, following: List<String>): LocalGgufSourceWindow {
            var result = LocalGgufSourceWindow(previous.takeLast(2), following.take(2))
            while (result.previous.sumOf { it.length.toLong() } + result.following.sumOf { it.length.toLong() } > MAX_CHARACTERS)
                result = result.reduced()
            return result
        }
    }
}
