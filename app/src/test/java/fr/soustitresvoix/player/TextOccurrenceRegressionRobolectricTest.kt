package fr.soustitresvoix.player

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.RealCueTimeline
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechTools
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Media3 source parsing and rendering plus occurrence-aware narration. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TextOccurrenceRegressionRobolectricTest {
    private data class Parsed(val index: CueAheadIndex, val groups: List<CuesWithTiming>)
    private fun parse(bytes: ByteArray, mime: String,
        options: SubtitleParser.OutputOptions = SubtitleParser.OutputOptions.allCues()): Parsed {
        val index = CueAheadIndex()
        val groups = mutableListOf<CuesWithTiming>()
        val factory = CueAheadFactory { track, group -> index.add(track, group) }
            .setOccurrenceListener { track, group, origins -> index.add(track, group, origins) }
        factory.sidecarParsers().create(Format.Builder().setSampleMimeType(mime).build())
            .parse(bytes, 0, bytes.size, options, Consumer { groups += it })
        assertFalse("Occurrence annotation must remain complete", factory.truncated)
        assertFalse(index.truncated)
        return Parsed(index, groups)
    }
    private fun native(bytes: ByteArray, mime: String, options: SubtitleParser.OutputOptions): List<CuesWithTiming> {
        val groups = mutableListOf<CuesWithTiming>()
        DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
            .parse(bytes, 0, bytes.size, options, Consumer { groups += it })
        return groups
    }
    private fun sameDisplay(expected: List<CuesWithTiming>, actual: List<CuesWithTiming>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (before, after) ->
            assertEquals(before.startTimeUs, after.startTimeUs)
            assertEquals(before.durationUs, after.durationUs)
            assertEquals(before.cues, after.cues)
        }
    }
    private fun spoken(parsed: Parsed): List<String> {
        val machine = NarrationMachine().apply {
            setSpeechReady(true); setMediaReady(true); play()
        }
        val said = mutableListOf<String>()
        for (frame: CueGroup in RealCueTimeline.frames(parsed.groups)) {
            val text = frame.cues.mapNotNull { it.text?.toString() }
                .map { SpeechTools.filter(it, SpeechTools.Filters()) }.filter { it.isNotBlank() }
            val events = parsed.index.resolved("external-subtitle", text, frame.presentationTimeUs, SpeechTools.Filters())
            assertNotNull("Every displayed source event, including duplicates, must be recoverable", events)
            var effects = machine.cues(frame.presentationTimeUs, text,
                identities = events!!.map { it.identity }, deadlinesMs = events.map { it.endUs / 1000 + 600 })
            var guard = 0
            while (true) {
                said += effects.filterIsInstance<NarrationMachine.Effect.Speak>().map { it.text }
                val id = machine.snapshot().speakingId ?: break
                assertTrue(++guard < 100)
                effects = machine.speechFinished(id)
            }
        }
        assertEquals(parsed.index.count, machine.journalSnapshot().completedCount)
        return said
    }

    @Test fun subripSimultaneousSameTextAndTimesRemainTwoRealOccurrences() {
        val bytes = subrip().toByteArray()
        val parsed = parse(bytes, MimeTypes.APPLICATION_SUBRIP)
        val items = parsed.index.selected("external-subtitle")
        assertEquals(5, items.size)
        assertEquals(5, items.map { it.identity }.distinct().size)
        assertTrue(items.all { it.sourceToken?.startsWith("subrip-event-v1:") == true })
        assertEquals(2, items.count { it.raw == "Oui." && it.startUs == 2_000_000L })
        assertEquals(listOf("PANNEAU", "Oui.", "Oui.", "Bref.", "Oui."), spoken(parsed))
        sameDisplay(native(bytes, MimeTypes.APPLICATION_SUBRIP, SubtitleParser.OutputOptions.allCues()), parsed.groups)
    }

    @Test fun subripSourceIdentityIsStableAcrossSeekAndACompleteRescan() {
        val bytes = subrip().toByteArray()
        val whole = parse(bytes, MimeTypes.APPLICATION_SUBRIP).index.selected("external-subtitle")
        for (options in listOf(SubtitleParser.OutputOptions.onlyCuesAfter(3_500_000),
            SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(3_500_000))) {
            val selected = parse(bytes, MimeTypes.APPLICATION_SUBRIP, options)
            assertTrue(selected.index.selected("external-subtitle").all { item -> whole.any { it == item } })
            sameDisplay(native(bytes, MimeTypes.APPLICATION_SUBRIP, options), selected.groups)
        }
        assertEquals(whole, parse(bytes, MimeTypes.APPLICATION_SUBRIP).index.selected("external-subtitle"))
    }

    @Test fun subripUtf16BomAndLiteralFormattingRetainNativeDisplayAndOccurrenceCount() {
        val text = subrip().replace("PANNEAU", "<i>ÉCRITEAU</i>")
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(-1, -2) else byteArrayOf(-2, -1)
            val bytes = bom + text.toByteArray(charset)
            val parsed = parse(bytes, MimeTypes.APPLICATION_SUBRIP)
            assertEquals(5, parsed.index.count)
            sameDisplay(native(bytes, MimeTypes.APPLICATION_SUBRIP, SubtitleParser.OutputOptions.allCues()), parsed.groups)
        }
    }

    @Test fun webvttPersistentPanelAndSimultaneousIdenticalSpeakersKeepFiveSourceEvents() {
        val bytes = webvtt().toByteArray()
        val parsed = parse(bytes, MimeTypes.TEXT_VTT)
        val items = parsed.index.selected("external-subtitle")
        assertEquals(5, items.size)
        assertEquals(5, items.map { it.identity }.distinct().size)
        val panel = items.single { it.raw == "PANNEAU" }
        assertEquals(1_000_000L, panel.startUs); assertEquals(9_000_000L, panel.endUs)
        assertTrue(panel.sourceToken!!.startsWith("webvtt-event-v1:"))
        assertEquals(listOf("PANNEAU", "Oui.", "Oui.", "Bref.", "Oui."), spoken(parsed))
        sameDisplay(native(bytes, MimeTypes.TEXT_VTT, SubtitleParser.OutputOptions.allCues()), parsed.groups)
    }

    @Test fun webvttExplicitAndAutomaticLineOrderingCannotSwapPersistentIdenticalSourceIds() {
        val bytes = """
            WEBVTT

            00:00:01.000 --> 00:00:09.000
            MÊME TEXTE

            00:00:02.000 --> 00:00:03.000 line:20%
            MÊME TEXTE
        """.trimIndent().toByteArray()
        val parsed = parse(bytes, MimeTypes.TEXT_VTT)
        assertEquals(2, parsed.index.count)
        assertEquals(listOf("MÊME TEXTE", "MÊME TEXTE"), spoken(parsed))
        sameDisplay(native(bytes, MimeTypes.TEXT_VTT, SubtitleParser.OutputOptions.allCues()), parsed.groups)
    }

    @Test fun webvttCssCommentAndCueIdentifiersDoNotCreateFalseNarrationOrChangeRendering() {
        val source = webvtt().replace("WEBVTT\n", "WEBVTT\n\nSTYLE\n::cue { color: lime; font-style: italic; }\n\nNOTE commentaire\n00:00:00.000 --> 00:00:10.000\nFAUX SOUS-TITRE\n")
            .replace("00:00:02.000 --> 00:00:03.000\nOui.", "identifiant\n00:00:02.000 --> 00:00:03.000\n<v ALICE>Oui.</v>")
        val bytes = source.toByteArray()
        val parsed = parse(bytes, MimeTypes.TEXT_VTT)
        assertEquals(5, parsed.index.count)
        assertFalse(parsed.index.selected("external-subtitle").any { "FAUX" in it.raw })
        assertEquals(listOf("PANNEAU", "Oui.", "Oui.", "Bref.", "Oui."), spoken(parsed))
        sameDisplay(native(bytes, MimeTypes.TEXT_VTT, SubtitleParser.OutputOptions.allCues()), parsed.groups)
    }

    @Test fun webvttSourceIdentitySurvivesSeekAndNativeSelectionOrdering() {
        val bytes = webvtt().toByteArray()
        val full = parse(bytes, MimeTypes.TEXT_VTT).index.selected("external-subtitle")
        // Media3 clips the interval straddling a seek, including a ten-ms cue;
        // outputAllCues emits the earlier half later in the output sequence.
        for (timeUs in listOf(2_500_000L, 3_500_000L, 6_005_000L)) {
            for (options in listOf(SubtitleParser.OutputOptions.onlyCuesAfter(timeUs),
                SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(timeUs))) {
                val selected = parse(bytes, MimeTypes.TEXT_VTT, options)
                val items = selected.index.selected("external-subtitle")
                assertTrue(items.all { it in full })
                assertEquals(full.single { it.raw == "PANNEAU" }, items.single { it.raw == "PANNEAU" })
                if (options.outputAllCues) assertEquals(full, items)
                sameDisplay(native(bytes, MimeTypes.TEXT_VTT, options), selected.groups)
            }
        }
    }

    @Test fun completeRealIndexerRetainsDuplicateSourceEventsForSubripAndWebvtt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((mime, text) in listOf(MimeTypes.APPLICATION_SUBRIP to subrip(), MimeTypes.TEXT_VTT to webvtt())) {
            val file = File.createTempFile("source-occurrences-", ".txt", context.cacheDir).apply { writeText(text) }
            try {
                val index = FullSubtitleIndexer(context).scan(SavedMedia("content://synthetic/video", "fixture",
                    subtitlePath = file.path, subtitleMime = mime)) { }
                assertFalse(index.truncated)
                assertEquals(5, index.count)
                assertEquals(5, index.selected("external-subtitle").map { it.identity }.distinct().size)
            } finally { file.delete() }
        }
    }

    private fun subrip() = """
        1
        00:00:01,000 --> 00:00:09,000
        PANNEAU

        2
        00:00:02,000 --> 00:00:03,000
        Oui.

        3
        00:00:02,000 --> 00:00:03,000
        Oui.

        4
        00:00:06,000 --> 00:00:06,010
        Bref.

        5
        00:00:07,000 --> 00:00:08,000
        Oui.
    """.trimIndent() + "\n"
    private fun webvtt() = "WEBVTT\n\n" + subrip().lines().filterNot { it.toIntOrNull() != null }
        .joinToString("\n").replace(',', '.')
}
