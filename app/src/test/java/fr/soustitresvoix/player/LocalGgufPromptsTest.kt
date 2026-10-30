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
        // All 33 added tokens in the pinned Index tokenizer are included, even special=false.
        val tokens = listOf(
            "<bos>", "<eos>", "<pad>", "<unk>",
            "<mask>", "<start_of_turn>", "<end_of_turn>", "<start_of_image>",
            "<end_of_image>", "<image_soft_token>", "<unused0>", "<unused98>",
            "<|endoftext|>", "<|startoftext|>", "<|bos|>", "<|eos|>",
            "<|pad|>", "<|extra_0|>", "<|extra_203|>", "<|im_start|>",
            "<|im_end|>", "<|object_ref_start|>", "<|object_ref_end|>", "<|box_start|>",
            "<|box_end|>", "<|quad_start|>", "<|quad_end|>", "<|vision_start|>",
            "<|vision_end|>", "<|vision_pad|>", "<|image_pad|>", "<|video_pad|>",
            "<tool_call>", "</tool_call>", "<|fim_prefix|>", "<|fim_middle|>",
            "<|fim_suffix|>", "<|fim_pad|>", "<|repo_name|>", "<|file_sep|>",
            "<tool_response>", "</tool_response>", "<think>", "</think>",
            "<|audio_start|>", "<|audio_end|>", "<tts_pad>", "<tts_text_bos>",
            "<tts_text_eod>", "<tts_text_bos_single>", "<|audio_pad|>")
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
    @Test fun indexEnglishFrenchPlainTaskMatchesPublisherInstructionAndNonThinkingWrapper() {
        // Independent literal fixture: Index official trans_prompt + tokenizer
        // 4e06279e62352dab96bbeb6fa0579bca5e822d75, one user and enable_thinking=false.
        val expected = "<|im_start|>user\n" +
            "请将以下英语文本翻译为法语，直接输出翻译结果，不要进行任何解释。\n\n" +
            "The blue door is closed.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val actual = LocalGgufPrompts.render(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            "The blue door is closed.", "en", "fr", emptyList(), "")
        assertEquals(expected, actual)
        // Independently executed from the publisher's Jinja using Jinja2 3.1.6.
        assertEquals(191, actual.toByteArray(Charsets.UTF_8).size)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(actual.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        assertEquals("41e72950780222dfb9e749eb363678a67b28f480d98e9b34c1640213f8c458ec", digest)
    }

    @Test fun indexConstraintBackgroundAndCompleteHistoryStaySeparateFromVerbatimCurrentText() {
        val source = " \nDo not return it to him. 😀\n "
        val pairs = listOf(LocalGgufPrompts.PairContext("Earlier source " + "a".repeat(450),
            "Traduction précédente " + "b".repeat(450)))
        val background = "Maya owns the original letter."
        val prompt = LocalGgufPrompts.render(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            source, "en", "fr", pairs, background)
        assertTrue(prompt.contains("请将以下英语字幕翻译成法语，并且严格遵循所有约束要求。"))
        assertTrue(prompt.contains("【源文】\n$source\n\n【约束要求】"))
        assertTrue(prompt.indexOf(source) < prompt.indexOf(background))
        assertTrue(prompt.contains(pairs.single().source)); assertTrue(prompt.contains(pairs.single().translated))
        assertTrue(prompt.endsWith("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        val isolated = LocalGgufPrompts.render(LocalGgufPrompts.Family.INDEX_TRANSLATE, source, "en", "fr", emptyList(), "")
        assertTrue(isolated.contains("\n\n$source<|im_end|>"))
    }

    @Test fun indexPublisherNamesHaveStandardLanguageAliasesAndUnknownCodesAreRefused() {
        val expected = mapOf("en" to "英语", "fr" to "法语", "ja" to "日语", "es" to "西班牙语",
            "uk" to "乌克兰语", "fa" to "波斯语", "cs" to "捷克语", "nb" to "书面挪威语", "fil" to "菲律宾语")
        expected.forEach { (code, name) -> assertEquals(name, LocalGgufExtendedPrompts.indexLanguage(code)) }
        assertEquals("法语", LocalGgufExtendedPrompts.indexLanguage("fr-FR"))
        assertEquals("繁体中文", LocalGgufExtendedPrompts.indexLanguage("zh-Hant"))
        try { LocalGgufExtendedPrompts.indexLanguage("zz"); fail("Invented language must fail visibly") }
        catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.LANGUAGE, failure.reason) }
    }

    @Test fun genericRequestContainsOneUnframedUserContentAndPreservesSourceWhitespace() {
        val source = " \nAmina did not give it to Chen. 😀\n "
        val prompt = LocalGgufPrompts.render(LocalGgufPrompts.Family.MODEL_TEMPLATE, source, "en", "fr",
            listOf(LocalGgufPrompts.PairContext("Earlier source.", "Traduction précédente.")), "A fictional scene.")
        assertTrue(prompt.contains("English (en) into French (fr)"))
        assertTrue(prompt.contains("[Source text]\n$source\n[End source text]"))
        assertTrue(prompt.contains("[Previous source]\nEarlier source."))
        assertTrue(prompt.contains("[Previous translation]\nTraduction précédente."))
        assertTrue(prompt.contains("[Background for interpretation only]\nA fictional scene."))
        assertFalse(LocalGgufPrompts.hasControlTokens(prompt))
        assertFalse(prompt.contains("<|im_start|>")); assertFalse(prompt.contains("<bos>"))
    }

    @Test fun qwen35PlainTaskMatchesExecutedPublisherTemplateWithThinkingDisabled() {
        // Qwen/Qwen3.5-9B c202236235762e1c871ad0ccb60c8ee5ba337b9a,
        // chat_template.jinja, one user, no tools, enable_thinking=false.
        // Independently rendered with Jinja2 3.1.6 outside the application.
        val expected = "<|im_start|>user\n" +
            "Translate the source text from English (en) into French (fr).\n" +
            "Output only the complete translation, without explanation. Preserve meaning, names, numbers, negations and line breaks. " +
            "Use background and previous translations only for interpretation; do not translate or repeat them.\n" +
            "\n[Source text]\nThe blue door is closed.\n[End source text]" +
            "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val actual = LocalGgufPrompts.render(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            "The blue door is closed.", "en", "fr", emptyList(), "")
        assertEquals(expected, actual)
        assertEquals(407, actual.toByteArray(Charsets.UTF_8).size)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(actual.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        assertEquals("0729811e10f8cf330d83f0aac77fd5e78586eec678e2c949cf019e62080bc4fa", digest)
        assertFalse(actual.contains("/nothink")); assertFalse(actual.contains("<bos>"))
    }

    @Test fun qwen35CompleteHistoryAndBackgroundStayInsideOneTextOnlyUserTurn() {
        val source = " \nGive it to her. 😀\n "
        val history = listOf(LocalGgufPrompts.PairContext("The letter belongs to Maya.", "La lettre appartient à Maya."))
        val prompt = LocalGgufPrompts.render(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            source, "en", "fr", history, "Maya is speaking to Chen.")
        assertTrue(prompt.contains("[Source text]\n$source\n[End source text]"))
        assertTrue(prompt.contains("[Background for interpretation only]\nMaya is speaking to Chen."))
        assertTrue(prompt.contains("[Previous source]\nThe letter belongs to Maya."))
        assertTrue(prompt.contains("[Previous translation]\nLa lettre appartient à Maya."))
        assertEquals(1, Regex(Regex.escape("<|im_start|>user\n")).findAll(prompt).count())
        assertEquals(1, Regex(Regex.escape("<|im_start|>assistant\n")).findAll(prompt).count())
        assertTrue(prompt.endsWith("<think>\n\n</think>\n\n"))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        assertEquals("1bfcfb999d6544aa2f1f7e7e806fafbb7a4f88fbc9e2ba7bb0bb8ea77ea82d85", digest)
    }

    @Test fun qwen35LanguageCodesAreValidatedAndRegionalCodesStayExplicit() {
        val prompt = LocalGgufPrompts.render(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            "Não a entregue.", "pt-BR", "fr-FR", emptyList(), "")
        assertTrue(prompt.contains("Portuguese (pt-BR) into French (fr-FR)"))
        for ((source, target) in listOf("zz" to "fr", "en" to "zz", "en\nfr" to "fr")) {
            try {
                LocalGgufPrompts.render(LocalGgufPrompts.Family.QWEN35_INSTRUCT, "Source.", source, target, emptyList(), "")
                fail("Invalid language must fail before model inference")
            } catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.LANGUAGE, failure.reason) }
        }
    }

}
