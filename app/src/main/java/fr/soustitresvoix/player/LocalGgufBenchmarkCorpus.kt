package fr.soustitresvoix.player

/** Identifiers and synthetic timing belong to the coordinator, never to the model. */
internal data class LocalGgufBenchmarkCue(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val kind: String
)

/** Fixed, reviewable subtitle text. Contains no media, personal text or reference translation. */
internal object LocalGgufBenchmarkCorpus {
    const val VERSION = "synthetic-en-fr-v1"

    private val distinctSources = listOf(
        "The train leaves at 07:30. Please keep the blue door closed.",
        "Maya, did you leave the keys on the kitchen table?",
        "I checked twice. The envelope was already empty.",
        "NO ENTRY — STAFF ONLY",
        "We have twelve minutes to reach platform 4.",
        "I didn't say he stole it. I said he knew where it was.",
        "Wait here. I'll come back when the rain stops.",
        "Three days earlier",
        "Dr. Chen will see you at 14:15, not at 14:50.",
        "Are you sure?",
        "The total is £18.75, including the service charge.",
        "Amina has been repairing this radio since Monday.",
        "Please mind the gap between the train and the platform.",
        "If you had called yesterday, we could have changed the reservation.",
        "KEEP CLEAR — EMERGENCY EXIT",
        "Nobody told me that the bridge would be closed tonight.",
        "Leave it there.",
        "— Is that your final answer?\n— No. Give me another moment.",
        "The package weighs 2.5 kg and must remain below 8 °C.",
        "You can keep the photograph, but I need the original letter back.",
        "Somewhere near the northern border, winter 1986",
        "She looked calm, although her hands were shaking.",
        "By the time we arrived, the last ferry had already left. We spent the night at the harbour and took the first boat the following morning.",
        "I thought you meant the other bank, on the east side of the river.",
        "Do not touch the red switch while the indicator is flashing.",
        "OPEN DAILY 09:00–18:30 — CLOSED ON TUESDAYS",
        "There are 1,024 records in the archive, but only seven have been restored.",
        "He promised to help us. Whether he can actually get here before dawn is another matter, so we should prepare a second route.",
        "Thank you for waiting. Your connection has now been restored."
    )

    // The same words occur again at different times; each occurrence remains distinct.
    private val sourceIndices = (0..9).toList() + listOf(9) + (10..18).toList() +
        listOf(0) + (19..28).toList() + listOf(16)

    val cues: List<LocalGgufBenchmarkCue> = sourceIndices.mapIndexed { index, sourceIndex ->
        val start = index * 4_000L
        LocalGgufBenchmarkCue(
            id = "synthetic-" + (index + 1).toString().padStart(2, '0'),
            startMs = start,
            // Two overlapping occurrences exercise bookkeeping without altering their timing.
            endMs = start + if (index == 5 || index == 20) 5_000 else 3_000,
            source = distinctSources[sourceIndex],
            kind = when (sourceIndex) {
                3, 7, 14, 20, 25 -> "panneau"
                22, 27 -> "dialogue long"
                else -> "dialogue"
            }
        )
    }
}
