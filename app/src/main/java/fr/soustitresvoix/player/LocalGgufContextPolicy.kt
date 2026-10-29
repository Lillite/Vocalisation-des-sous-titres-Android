package fr.soustitresvoix.player

/** Explicit context policies; none is presumed to improve linguistic fidelity. */
enum class LocalGgufContextPolicy(val id: String, val label: String, val maximumPreviousPairs: Int) {
    RECENT_TWO("recent-two", "Deux échanges précédents — comportement actuel", 2),
    ISOLATED("isolated", "Occurrence seule — référence sans historique", 0),
    BLOCKS_SIX("blocks-six", "Blocs de six occurrences — expérimental", 5);

    fun contextStartIndex(occurrenceIndex: Int): Int {
        require(occurrenceIndex >= 0)
        return when (this) {
            RECENT_TWO -> (occurrenceIndex - 2).coerceAtLeast(0)
            ISOLATED -> occurrenceIndex
            BLOCKS_SIX -> occurrenceIndex / 6 * 6
        }
    }

    companion object {
        fun fromPreference(value: String?): LocalGgufContextPolicy =
            entries.firstOrNull { it.name == value } ?: RECENT_TWO
    }
}
