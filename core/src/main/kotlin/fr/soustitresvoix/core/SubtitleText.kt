package fr.soustitresvoix.core

object SubtitleText {
    // Only known styling tags are removed; comparisons such as "a < b" survive.
    private val html = Regex("</?(?:b|i|u|s|font|span|c(?:\\.[^ >]+)?|v|lang)(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    private val breaks = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val assOverride = Regex("\\{\\\\[^}]*}")
    private val spaces = Regex("[\\t \\u00a0]+")
    private val descriptions = Regex("\\[[^]\\r\\n]*]")

    fun clean(raw: String, skipDescriptions: Boolean = false): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = breaks.replace(text, "\n")
        text = html.replace(text, "")
        text = assOverride.replace(text, "")
        text = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        if (skipDescriptions) text = descriptions.replace(text, "")
        // Media3 has already decoded markup in ordinary text cues. Never execute
        // HTML/JS and never send captions to a language model.
        return text.lines().map { spaces.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }.joinToString("\n").trim()
    }

    /** Chunk at whitespace where possible; never split a UTF-16 surrogate pair. */
    fun splitForSpeech(text: String, limit: Int): List<String> {
        require(limit >= 2)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            if (end < text.length) {
                val breakAt = (end - 1 downTo start + (end - start) / 2).firstOrNull { text[it].isWhitespace() }
                if (breakAt != null) end = breakAt
            }
            check(end > start)
            out += text.substring(start, end).trim()
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }
}
