package fr.soustitresvoix.player

import java.util.Locale

/**
 * Index's single-user non-thinking framing follows the publisher's tokenizer
 * 4e06279e62352dab96bbeb6fa0579bca5e822d75. Its Chinese instruction and language
 * names follow bilibili/Index-Translate@912e86c8325d6c7c29a6a0b09c5f53482c6602f6,
 * inference/llm/translate.py trans_prompt/LANG_NAMES. Complete source whitespace is
 * deliberately preserved rather than applying the publisher client's strip().
 * History/background and the generic instruction remain application adaptations.
 */
internal object LocalGgufExtendedPrompts {
    // Publisher names with the corresponding standard ISO 639-1 aliases for
    // ukr_cyrl/fas_arab/ces_latn/ell_grek/dan_latn/hun_latn/fin_latn/nob_latn/
    // slk_latn/bul_cyrl. Their names do not establish model language coverage.
    private val indexNames = mapOf(
        "en" to "英语", "zh" to "中文", "de" to "德语", "fr" to "法语", "es" to "西班牙语",
        "ja" to "日语", "ko" to "韩语", "pt" to "葡萄牙语", "ru" to "俄语", "ar" to "阿拉伯语",
        "it" to "意大利语", "nl" to "荷兰语", "pl" to "波兰语", "ro" to "罗马尼亚语",
        "sv" to "瑞典语", "tr" to "土耳其语", "hi" to "印地语", "vi" to "越南语",
        "th" to "泰语", "id" to "印尼语", "ms" to "马来语", "fil" to "菲律宾语",
        "uk" to "乌克兰语", "fa" to "波斯语", "cs" to "捷克语", "el" to "希腊语",
        "da" to "丹麦语", "hu" to "匈牙利语", "fi" to "芬兰语", "nb" to "书面挪威语",
        "sk" to "斯洛伐克语", "bg" to "保加利亚语"
    )

    fun indexLanguage(value: String): String {
        val language = LocalGgufPrompts.language(value)
        val locale = Locale.forLanguageTag(language.code)
        if (locale.language == "zh" && locale.script == "Hant") return "繁体中文"
        if (locale.language == "zh" && locale.script == "Hans") return "简体中文"
        return indexNames[locale.language] ?: locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
            .takeIf { it.isNotBlank() && it != locale.language }
            ?: throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
    }

    fun index(text: String, source: String, target: String,
        previous: List<LocalGgufPrompts.PairContext>, context: String): String {
        val from = indexLanguage(source)
        val to = indexLanguage(target)
        val content = if (context.isBlank() && previous.isEmpty())
            "请将以下${from}文本翻译为${to}，直接输出翻译结果，不要进行任何解释。\n\n$text"
        else buildString {
            // The publisher supports a constraint block and the 字幕 genre.
            // These complete background/history constraints are this app's extension.
            append("请将以下"); append(from); append("字幕翻译成"); append(to)
            append("，并且严格遵循所有约束要求。\n\n【源文】\n"); append(text)
            append("\n\n【约束要求】\n")
            var number = 1
            if (context.isNotBlank()) {
                append(number++); append(". 【注意】以下背景信息仅供理解，不要翻译或复述：\n")
                append(context); append('\n')
            }
            previous.forEach { pair ->
                append(number++); append(". 【注意】以下已完成的对话仅供理解，不要再次翻译：\n前文原文：")
                append(pair.source); append("\n前文译文："); append(pair.translated); append('\n')
            }
            append("\n只输出译文，不要有任何额外说明。")
        }
        return "<|im_start|>user\n$content<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    /** Plain user content only: the native recognized conversation format supplies roles/tokens. */
    fun generic(text: String, source: LocalGgufPrompts.Language, target: LocalGgufPrompts.Language,
        previous: List<LocalGgufPrompts.PairContext>, context: String): String = buildString {
        append("Translate the source text from "); append(source.name); append(" ("); append(source.code)
        append(") into "); append(target.name); append(" ("); append(target.code); append(").\n")
        append("Output only the complete translation, without explanation. Preserve meaning, names, numbers, negations and line breaks. ")
        append("Use background and previous translations only for interpretation; do not translate or repeat them.\n")
        if (context.isNotBlank()) {
            append("\n[Background for interpretation only]\n"); append(context); append('\n')
        }
        previous.forEach { pair ->
            append("\n[Previous source]\n"); append(pair.source)
            append("\n[Previous translation]\n"); append(pair.translated); append('\n')
        }
        append("\n[Source text]\n"); append(text); append("\n[End source text]")
    }
}
