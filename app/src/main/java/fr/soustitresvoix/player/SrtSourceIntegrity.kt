package fr.soustitresvoix.player

import fr.soustitresvoix.core.SubtitleImport
import fr.soustitresvoix.core.SubtitleText
import fr.soustitresvoix.core.TranslationPlan
import java.io.File
import java.io.IOException

/**
 * A non-loss guard around Media3, which remains the timing/parser authority.
 * It can skip malformed SubRip blocks without reporting a truncated index.
 * Both standalone preparation and the player's translation route share this
 * check; ordinary direct playback keeps its existing tolerant parser.
 */
internal object SrtSourceIntegrity {
    const val FAILURE_MESSAGE = "Le SRT contient un bloc incomplet ou illisible. Traduction refusée pour éviter de perdre une occurrence ; corrige le fichier puis réimporte-le."
    class InvalidSource : IOException(FAILURE_MESSAGE)

    fun check(file: File, indexedCount: Int) = checkCount(expectedCount(file), indexedCount)

    fun expectedCount(file: File): Int {
        if (!file.isFile || file.length() !in 1..SubtitleImport.MAX_BYTES.toLong()) throw InvalidSource()
        return count(file.readText(Charsets.UTF_8))
    }

    fun checkCount(expectedCount: Int, indexedCount: Int) {
        if (indexedCount != expectedCount) throw InvalidSource()
    }

    private fun count(text: String): Int {
        var count = 0
        var lines = 0
        var hasIndex = false
        var hasTiming = false
        val body = StringBuilder()
        fun finishBlock() {
            if (lines == 0) return
            if (lines < 3 || !hasIndex || !hasTiming || SubtitleText.clean(body.toString()).isBlank()) throw InvalidSource()
            if (++count > TranslationPlan.MAX_CUES) throw InvalidSource()
            lines = 0; hasIndex = false; hasTiming = false; body.setLength(0)
        }
        // Keep one block at a time rather than allocating the source blocks a
        // second time beside Media3's complete index.
        for (line in text.lineSequence()) {
            if (line.isBlank()) finishBlock()
            else {
                when (lines) {
                    0 -> hasIndex = line.trim().toIntOrNull() != null
                    1 -> hasTiming = line.contains("-->")
                    else -> { if (body.isNotEmpty()) body.append('\n'); body.append(line) }
                }
                lines++
            }
        }
        finishBlock()
        if (count == 0) throw InvalidSource()
        return count
    }
}
