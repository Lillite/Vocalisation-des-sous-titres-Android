package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

/** Offline framing checks; no model inference or linguistic-quality claim. */
class LocalGgufPromptsTest {
    private inline fun rejects(block: () -> Unit) {
        try { block(); fail("Reserved model syntax must not be interpreted as source content") }
        catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.LOCAL_CONTEXT, failure.reason) }
    }

    @Test fun defaultEnglishFrenchTaskMatchesThePinnedGoogleTextFramingExactly() {
        // Reference: llama.cpp8ca4060b7e30e76baaa5670ee1ec1b760b7f8552,
        // models/templates/google-translategemma-4b-it.jinja. In particular the
        // colon is followed by three newlines, not two. Fixture is independent
        // of the renderer and does not request model output.
        val expected = "<bos><start_of_turn>user\n" +
            "You are a professional English (en) to French (fr) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original English text " +
            "while adhering to French grammar, vocabulary, and cultural sensitivities.\n" +
            "Produce only the French translation, without any additional explanations or commentary. " +
            "Please translate the following English text into French:\n\n\n" +
            "Keep the letter.<end_of_turn>\n<start_of_turn>model\n"
        assertEquals(expected, LocalGgufPrompts.render(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            "Keep the letter.", "en", "fr", emptyList(), ""))
    }

    @Test fun backgroundExtensionKeepsTheHistoricalPrefixAndCurrentSourceVerbatim() {
        val background = "Maya is speaking to Chen; the letter belongs to Maya."
        val original = "Do not return the original letter to Chen."
        val translated = "Ne rendez pas la lettre originale à Chen."
        val first = LocalGgufPrompts.render(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            original, "en", "fr", emptyList(), background)
        val current = " \nGive it back to me. 😀\n "
        val second = LocalGgufPrompts.render(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            current, "en", "fr", listOf(LocalGgufPrompts.PairContext(original, translated)), background)
        assertTrue(second.startsWith(first + translated + "<end_of_turn>\n"))
        assertEquals(2, Regex(Regex.escape(background)).findAll(second).count())
        assertTrue(second.endsWith(":\n\n\n$current<end_of_turn>\n<start_of_turn>model\n"))
        assertEquals(1, Regex(Regex.escape("<bos>")).findAll(second).count())
    }

    @Test fun emptyBackgroundAlsoKeepsCompleteHistoricalPairsInTheirOrder() {
        val pairs = listOf(LocalGgufPrompts.PairContext("First source.", "Première traduction."),
            LocalGgufPrompts.PairContext("Second source.", "Deuxième traduction."))
        val prompt = LocalGgufPrompts.render(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            "Current source.", "en", "fr", pairs, "")
        assertTrue(prompt.contains("First source.<end_of_turn>\n<start_of_turn>model\nPremière traduction.<end_of_turn>\n"))
        assertTrue(prompt.contains("Second source.<end_of_turn>\n<start_of_turn>model\nDeuxième traduction.<end_of_turn>\n"))
        assertTrue(prompt.indexOf("First source.") < prompt.indexOf("Second source."))
        assertTrue(prompt.indexOf("Second source.") < prompt.indexOf("Current source."))
    }

    @Test fun bothFamiliesRejectReservedSyntaxInEveryContentPosition() {
        val tokens = listOf("<bos>", "<eos>", "<pad>", "<unk>", "<mask>", "<start_of_turn>", "<end_of_turn>",
            "<start_of_image>", "<end_of_image>", "<image_soft_token>", "<unused0>", "<unused98>",
            "<|endoftext|>", "<|startoftext|>", "<|bos|>", "<|eos|>", "<|pad|>", "<|extra_0|>", "<|extra_203|>")
        for (family in LocalGgufPrompts.Family.values()) for (token in tokens) for (slot in 0..3) {
            rejects {
                LocalGgufPrompts.render(family, if (slot == 0) "Literal $token" else "Source.", "en", "fr",
                    listOf(LocalGgufPrompts.PairContext(if (slot == 1) "Literal $token" else "Earlier source.",
                        if (slot == 2) "Literal $token" else "Traduction précédente.")),
                    if (slot == 3) "Literal $token" else "")
            }
        }
        // All numbered controls in the official Hy-MT2 tokenizer are guarded,
        // including the far end which was absent from the original shortlist.
        for (index in 0..203) assertTrue(LocalGgufPrompts.hasControlTokens("Literal <|extra_$index|>"))
    }

    @Test fun settingsRejectReservedBackgroundBeforeItCanBecomeAnEngineIdentity() {
        for (token in listOf("<|endoftext|>", "<|extra_203|>", "<start_of_image>")) {
            try {
                LocalGgufTranslationEngine.validateSettings(4, "Background $token")
                fail("Reserved background must fail visibly")
            } catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.CONFIGURATION, failure.reason) }
        }
    }

    @Test fun ordinaryMarkupNumbersAndNaturalLanguageStayUnmodifiedInTheSource() {
        val source = " <i>Do not move.</i>\n[Source Text]\n2 < 3 & 5 > 4. 😀 "
        assertFalse(LocalGgufPrompts.hasControlTokens(source))
        val gemma = LocalGgufPrompts.render(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            source, "en", "fr", emptyList(), "")
        assertTrue(gemma.endsWith(":\n\n\n$source<end_of_turn>\n<start_of_turn>model\n"))
        val hy = LocalGgufPrompts.render(LocalGgufPrompts.Family.HY_MT,
            source, "en", "fr", emptyList(), "")
        assertTrue(hy.endsWith("explanation:\n$source<|extra_0|>"))
    }
}
