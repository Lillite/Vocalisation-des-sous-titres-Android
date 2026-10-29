package fr.soustitresvoix.player

/** Additional synthetic sources: no reference answer is passed to the model. */
internal object LocalGgufQualityCorpus {
    const val VERSION = "synthetic-quality-en-fr-v1"

    private val sources = listOf(
        "Nora lent the map to Sami, but he has not returned it to her.",
        "If the amber light stays on, stop. If it blinks, wait.",
        "The appointment was moved from 06:45 to 16:05.",
        "I asked for fourteen copies, not forty.",
        "Neither bottle contains alcohol.",
        "She hardly spoke after the announcement.",
        "The child put the letter inside the empty envelope.",
        "Leave the parcel beside the door, not behind it.",
        "The fee is €1,250.50; the refundable deposit is €80.",
        "Only the technician may restart the machine.",
        "He said he might arrive on Friday; he did not promise to come.",
        "Please give my passport back to me before you leave.",
        "CLOSED UNTIL 11:20 — DELIVERIES AT THE REAR",
        "— Did Lina call you?\n— No. I called her.",
        "We waited by the river bank, across from the bank that lends money.",
        "The museum opens every day except Wednesday.",
        "You must not open the box unless I am present."
    )

    // The final sentence is a legitimate new occurrence, not a duplicate to drop.
    val cues: List<LocalGgufBenchmarkCue> = (sources + sources[3]).mapIndexed { index, source ->
        val start = index * 6_000L
        LocalGgufBenchmarkCue("quality-" + (index + 1).toString().padStart(2, '0'), start,
            start + when (index) { 5 -> 7_500; 12 -> 2_000; else -> 4_500 }, source,
            if (index == 12) "panneau" else "dialogue")
    }

    val SOURCE_SRT: String = buildString {
        fun time(value: Long): String {
            val milliseconds = value % 1000
            val seconds = value / 1000
            return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
                seconds / 3600, seconds / 60 % 60, seconds % 60, milliseconds)
        }
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n').append(time(cue.startMs)).append(" --> ")
                .append(time(cue.endMs)).append('\n').append(cue.source).append("\n\n")
        }
    }
}
