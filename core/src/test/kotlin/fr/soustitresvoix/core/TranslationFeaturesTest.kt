package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class TranslationFeaturesTest {
    private val cues = listOf(
        TranslationCue("panel", 0, 90_000_000, "SORTIE"),
        TranslationCue("dialogue", 1_234_000, 4_567_000, "Bonjour <Élodie> & amis.\nOui."),
        TranslationCue("repeat", 8_000_000, 10_000_000, "SORTIE"))
    @Test fun srtRetainsOverlapRepeatTimesLiteralMarkupAndUnicode() {
        val srt = TranslatedSubtitleExport.encode(cues, SubtitleExportFormat.SRT)
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:01:30,000\nSORTIE\n\n"))
        assertTrue(srt.contains("2\n00:00:01,234 --> 00:00:04,567"))
        assertTrue(srt.contains("Bonjour &lt;Élodie&gt; &amp; amis.\nOui."))
        assertEquals(2, Regex("SORTIE").findAll(srt).count())
    }
    @Test fun webVttHasHeaderAndDotTimestampsWithoutSequenceNumbers() {
        val vtt = TranslatedSubtitleExport.encode(cues, SubtitleExportFormat.VTT)
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.000 --> 00:01:30.000"))
        assertFalse(vtt.contains("\n1\n"))
        assertTrue(vtt.contains("00:00:01.234 --> 00:00:04.567"))
    }
    @Test fun exportRejectsEmptyInvalidTimingAndBlankTextRatherThanLosingCue() {
        for (input in listOf(emptyList(), listOf(cues[0].copy(endUs = 0)), listOf(cues[0].copy(text = " ")))) {
            try { TranslatedSubtitleExport.encode(input, SubtitleExportFormat.SRT); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun reviewReportsNumberChangeAndExtremeLengthButDoesNotAlterOutput() {
        val source = listOf(TranslationCue("x", 0, 1, "Take 123 apples from the room before everyone returns to see what happened."))
        val target = listOf(source[0].copy(text = "Prenez 12."))
        val hints = TranslationReview.inspect(source, target)
        assertEquals("x", hints.single().cueId)
        assertEquals(2, hints.single().reasons.size)
        assertEquals("Prenez 12.", target.single().text)
    }
    @Test fun decimalPunctuationDoesNotInventAnAlertAndAbsenceOfAlertIsNotAScore() {
        val source = listOf(TranslationCue("x", 0, 1, "Price 1.25"))
        assertTrue(TranslationReview.inspect(source, listOf(source[0].copy(text = "Prix 1,25"))).isEmpty())
    }
    @Test fun localLanguageProposalsAreNeverSilentlyAppliedOrOverconfidentForCyrillic() {
        assertEquals("en", SubtitleLanguageProposal.suggest(listOf("What do you want? This is the house that you love, with your friends.")).code)
        val hint = SubtitleLanguageProposal.suggest(listOf("Добар дан! Како сте данас? Желим да знам шта се догодило."))
        assertNull(hint.code)
        assertTrue(hint.explanation.contains("cyrillique"))
        assertNull(SubtitleLanguageProposal.suggest(listOf("OK")).code)
    }
    @Test fun subMillisecondAndNegativeExportIntervalsRemainPositiveAtMillisecondPrecision() {
        val input = listOf(TranslationCue("tiny", -20, 10, "Bonjour"), TranslationCue("later", 1_000, 1_001, "Oui"))
        val output = TranslatedSubtitleExport.encode(input, SubtitleExportFormat.SRT)
        assertTrue(output.contains("00:00:00,000 --> 00:00:00,001"))
        assertTrue(output.contains("00:00:00,001 --> 00:00:00,002"))
    }

    @Test fun repeatedPossibleNamesFlagInconsistentTranslationWithoutChangingAnyOccurrence() {
        val source = listOf(TranslationCue("a", 0, 1, "Alice is here."), TranslationCue("b", 2, 3, "Wait for Alice."))
        val translated = listOf(source[0].copy(text = "Alice est ici."), source[1].copy(text = "Attends Alixe."))
        val hints = TranslationReview.inspect(source, translated)
        assertEquals("b", hints.single().cueId)
        assertTrue(hints.single().reasons.any { "Nom propre" in it })
        assertEquals("Attends Alixe.", translated[1].text)
        assertTrue(TranslationReview.inspect(source, translated.map { it.copy(text = "Attends Alixe.") }).isEmpty())
    }

    @Test fun emptyTranslationIsAnExplicitReviewHintWhileTheRunnerStillRejectsIt() {
        val source = listOf(TranslationCue("empty", 0, 1, "Hello."))
        assertTrue(TranslationReview.inspect(source, listOf(source[0].copy(text = "")))
            .single().reasons.any { "Texte vide" in it })
    }

}
