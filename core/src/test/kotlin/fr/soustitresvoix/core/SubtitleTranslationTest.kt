package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleTranslationTest {
    private fun cue(id: String = "one", start: Long = 10, end: Long = 20, text: String = "Hello") =
        TranslationCue(id, start, end, text)
    private fun plan(cues: List<TranslationCue> = listOf(cue()), source: String = "en", target: String = "fr",
        provider: String = "azure") = TranslationPlan.create(cues, source, target, provider)
    private fun refused(block: () -> Unit) {
        try { block(); fail("An invalid or incomplete translation must be refused") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun realRepeatedAndSimultaneousOccurrencesRetainIdentitiesTimesAndOrder() {
        val input = listOf(cue("sign", 0, 90, "EXIT"), cue("first", 10, 20),
            cue("second", 10, 20), cue("third", 20, 30), cue("again", 50, 60))
        val prepared = plan(input)
        assertEquals(listOf("EXIT", "Hello", "Hello", "Hello", "Hello"), prepared.texts)
        val french = listOf("SORTIE", "Bonjour", "Salut", "Bonjour", "Bonjour")
        val track = prepared.complete(french)
        assertEquals(input.map { it.id }, track.cues.map { it.id })
        assertEquals(input.map { it.startUs to it.endUs }, track.cues.map { it.startUs to it.endUs })
        assertEquals(french, track.cues.map { it.text })
        input.forEachIndexed { index, cue -> assertEquals(french[index], track.textFor(cue.id, cue.text)) }
    }

    @Test fun incompleteExtraBlankAndInvalidUnicodeResultsAreRefusedAtomically() {
        val prepared = plan(listOf(cue("a"), cue("b")))
        for (answers in listOf(emptyList(), listOf("Bonjour"), listOf("a", "b", "c"),
            listOf("Bonjour", " \n\t"), listOf("Bonjour", "bad\u0000text"),
            listOf("Bonjour", "\uD800"), listOf("Bonjour", "\uDC00"),
            listOf("Bonjour", "x".repeat(TranslationPlan.MAX_CUE_CHARACTERS + 1)))) {
            refused { prepared.complete(answers) }
        }
        assertEquals("Salut", prepared.complete(listOf("Bonjour", "Salut")).textFor("b", "Hello"))
    }

    @Test fun sourceDivergenceAndUnknownOccurrenceNeverFallBackToOriginalText() {
        val track = plan().complete(listOf("Bonjour"))
        assertNull(track.textFor("other", "Hello"))
        assertNull(track.textFor("one", "Changed"))
        assertNull(track.textFor("one", " Hello"))
        assertEquals("Bonjour", track.textFor("one", "Hello"))
    }

    @Test fun inputAndResultListsAreDefensivelyCopiedAndExposedListsCannotBeMutated() {
        val originals = mutableListOf(cue())
        val prepared = plan(originals)
        originals.clear()
        assertEquals(1, prepared.cues.size)
        val answers = mutableListOf("Bonjour")
        val track = prepared.complete(answers)
        answers[0] = "wrong"
        assertEquals("Bonjour", track.textFor("one", "Hello"))
        for (list in listOf(prepared.cues, prepared.texts, track.cues, prepared.batches(10, 1),
            prepared.batches(10, 1).single().texts)) {
            try { (list as MutableList<*>).clear(); fail("Exposed lists must be immutable") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun batchBoundariesPreserveEveryOccurrenceAndNeverSplitUnicodeOrText() {
        val input = listOf(cue("a", text = "a😀"), cue("b", text = "bb"), cue("c", text = "ccc"), cue("d", text = "d"))
        val prepared = plan(input)
        val batches = prepared.batches(maxCharacters = 5, maxItems = 2)
        assertEquals(listOf(0, 2), batches.map { it.firstIndex })
        assertEquals(listOf(5L, 4L), batches.map { it.characterCount })
        assertEquals(prepared.texts, batches.flatMap { it.texts })
        assertEquals(9L, prepared.estimatedCharacters)
        assertEquals(listOf(0, 1, 2, 3), prepared.batches(5, 1).map { it.firstIndex })
        refused { prepared.batches(2, 10) }
        refused { prepared.batches(0, 10) }
        refused { prepared.batches(10, 0) }
    }

    @Test fun invalidSourceIdentityTimingAndTextAreRejectedWithoutDroppingOccurrences() {
        refused { plan(emptyList()) }
        refused { plan(listOf(cue("a"), cue("a"))) }
        refused { plan(listOf(cue(""))) }
        refused { plan(listOf(cue("\u0000"))) }
        refused { plan(listOf(cue(start = 20, end = 20))) }
        refused { plan(listOf(cue(start = 21, end = 20))) }
        refused { plan(listOf(cue(text = ""))) }
        refused { plan(listOf(cue(text = "  \n"))) }
        refused { plan(listOf(cue(text = "\uD800"))) }
        refused { plan(listOf(cue(text = "\uDC00"))) }
        refused { plan(listOf(cue(text = "x\u0000y"))) }
        refused { plan(listOf(cue(text = "x".repeat(TranslationPlan.MAX_CUE_CHARACTERS + 1)))) }
        assertEquals(-10L, plan(listOf(cue(start = -10, end = 10))).cues.single().startUs)
        assertEquals(Long.MAX_VALUE, plan(listOf(cue(start = Long.MIN_VALUE, end = Long.MAX_VALUE))).cues.single().endUs)
    }

    @Test fun sourceAndTargetLanguagesAreExplicitAndCanonicalWithoutChangingContent() {
        assertEquals("sr-latn", plan(source = "SR-Latn").sourceLanguage)
        assertEquals("fr-fr", plan(target = "FR-FR").targetLanguage)
        for (source in listOf("", "auto", "en?key=secret", "en\n", "english")) refused { plan(source = source) }
        refused { plan(source = "FR", target = "fr") }
        refused { plan(provider = "") }
        refused { plan(provider = "azure\nsecret") }
    }

    @Test fun fingerprintsBindAllInputFieldsAndTranslatedTrackContent() {
        val prepared = plan()
        assertEquals(prepared.fingerprint, plan().fingerprint)
        assertEquals(64, prepared.fingerprint.length)
        val alternatives = listOf(plan(listOf(cue("other"))), plan(listOf(cue(start = 11))),
            plan(listOf(cue(end = 21))), plan(listOf(cue(text = "Other"))),
            plan(source = "de"), plan(target = "it"), plan(provider = "deepl"))
        alternatives.forEach { assertNotEquals(prepared.fingerprint, it.fingerprint) }
        val translated = prepared.complete(listOf("Bonjour"))
        assertEquals(prepared.fingerprint, translated.sourceFingerprint)
        assertEquals(translated.fingerprint, plan().complete(listOf("Bonjour")).fingerprint)
        assertNotEquals(translated.fingerprint, plan().complete(listOf("Salut")).fingerprint)
    }

    @Test fun trackLimitsRejectOverBudgetInputAndOutputBeforeActivation() {
        val text = "x".repeat(TranslationPlan.MAX_CUE_CHARACTERS)
        refused { plan((0..500).map { cue("cue-$it", text = text) }) }
        refused { plan((0..TranslationPlan.MAX_CUES).map { cue("cue-$it", text = "x") }) }
        val prepared = plan((0..500).map { cue("cue-$it", text = "x") })
        refused { prepared.complete(List(501) { text }) }
        assertEquals(501, prepared.complete(List(501) { "français" }).cues.size)
    }

    @Test fun namesDescriptionsPunctuationAndMultilingualTextRemainLiteralData() {
        val text = "[Музика]\n„Добар дан“, Élodie. <do not execute> 😀"
        val prepared = plan(listOf(cue(text = text)), source = "sr-Cyrl")
        assertEquals(text, prepared.texts.single())
        val translated = "[Musique]\n« Bonjour », Élodie. <ne pas exécuter> 😀"
        assertEquals(translated, prepared.complete(listOf(translated)).textFor("one", text))
    }
}
