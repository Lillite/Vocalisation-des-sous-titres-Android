package fr.soustitresvoix.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslatedSubtitleExport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Media3 sidecar parsing of our export; no private media or network. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslatedExportRoundTripRobolectricTest {
    @Test fun srtAndVttExportCanBeReimportedWithLiteralTextTimesAndRepeatedOccurrences() {
        val source = listOf(
            TranslationCue("a", 1_234_000, 3_456_000, "Bonjour <Élodie> & amis."),
            TranslationCue("b", 5_000_000, 6_000_000, "Bonjour <Élodie> & amis."))
        for (format in SubtitleExportFormat.values()) {
            val mime = if (format == SubtitleExportFormat.SRT) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val bytes = TranslatedSubtitleExport.encode(source, format).toByteArray(Charsets.UTF_8)
            val result = mutableListOf<CuesWithTiming>()
            CueAheadFactory { _, _ -> }.sidecarParsers().create(Format.Builder().setSampleMimeType(mime).build())
                .parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues(), Consumer { result += it })
            assertEquals(2, result.size)
            assertEquals(source.map { it.startUs }, result.map { it.startTimeUs })
            assertEquals(source.map { it.endUs - it.startUs }, result.map { it.durationUs })
            assertEquals(source.map { it.text }, result.map { it.cues.single().text.toString() })
        }
    }
}
