package fr.soustitresvoix.core

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Conservative review hints, never a rewrite or proof of semantic fidelity.
 * Written cardinal numbers are recognized only for English/French, below one
 * million. Unsupported/ambiguous numeric notation remains a visible warning.
 */
internal object TranslationNumbers {
    enum class Comparison { SAME, DIFFERENT, UNCERTAIN }
    private enum class Language { EN, FR, UNKNOWN }
    private data class Value(val number: String, val unit: String)
    private data class UnitValue(val name: String, val end: Int)
    private data class Extracted(val values: List<Value>, val uncertain: Boolean)
    private data class Token(val text: String, val start: Int, val end: Int)

    // Dates and clocks precede general numbers so their separators/signs are
    // not silently treated as decimal separators or unary minus signs.
    private val token = Regex(
        "(?<![\\p{L}\\p{N}_])(?:" +
            "\\p{Nd}{1,4}(?:[-/.]\\p{Nd}{1,2}){2}(?!\\p{N})|" +
            "\\p{Nd}{1,2}(?::\\p{Nd}{2}(?::\\p{Nd}{2})?|[ \\u00a0\\u202f]*[hH](?:[ \\u00a0\\u202f]*\\p{Nd}{2})?)(?![\\p{L}\\p{N}])|" +
            "[+\\-−]?\\p{Nd}+(?:(?:[.,:/]|[ \\u00a0\\u202f])\\p{Nd}+)*|" +
            "\\p{L}+(?:[’']\\p{L}+)?)")
    private val wordGap = Regex("[ \\t\\r\\n\\u00a0\\u202f‐‑-]+")
    private val clock = Regex("^(\\d{1,2})(?::(\\d{2})(?::(\\d{2}))?| *[hH](?: *(\\d{2}))?)$")
    private val date = Regex("^\\d{1,4}(?:[-/.]\\d{1,2}){2}$")
    private val integerGroups = Regex("^[0-9]{1,3}(?: [0-9]{3})+$")
    private val currencyBefore = Regex("(?i)(?<![\\p{L}])(?:USD|EUR|GBP|[$€£])[ \\t\\u00a0\\u202f]*$")
    private val unitAfter = Regex("(?i)[ \\t\\u00a0\\u202f]*(°[ \\t]*[CFK](?![\\p{L}])|%|percent\\b|per[ \\t]+cent\\b|pour[ \\t]+cent\\b|[$€£]|USD\\b|EUR\\b|GBP\\b|" +
        "kilogram(?:me)?s?\\b|kg\\b|gram(?:me)?s?\\b|g\\b|kilom[eè]t(?:er|re)s?\\b|km\\b|" +
        "centim[eè]t(?:er|re)s?\\b|cm\\b|millim[eè]t(?:er|re)s?\\b|mm\\b|m[eè]t(?:er|re)s?\\b|m\\b|" +
        "millilit(?:er|re)s?\\b|ml\\b|lit(?:er|re)s?\\b|l\\b|" +
        "hours?\\b|heures?\\b|h\\b|minutes?\\b|min\\b|seconds?\\b|secondes?\\b|sec\\b|s\\b|" +
        "days?\\b|jours?\\b|weeks?\\b|semaines?\\b|years?\\b|ans?\\b|annees?\\b|années?\\b|" +
        "dollars?\\b|euros?\\b|pounds?\\b)")
    private val enSmall = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val enTens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val frUnderHundred: Map<List<String>, Int> = buildMap {
        val small = listOf("zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize")
        small.forEachIndexed { n, word -> put(listOf(word), n) }
        put(listOf("une"), 1)
        for (n in 17..19) put(listOf("dix", small[n - 10]), n)
        val tens = mapOf("vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
            "soixante" to 60, "septante" to 70, "huitante" to 80, "octante" to 80, "nonante" to 90)
        for ((name, value) in tens) {
            put(listOf(name), value)
            for (n in 1..9) put(if (n == 1) listOf(name, "et", "un") else listOf(name, small[n]), value + n)
            put(listOf(name, "et", "une"), value + 1)
        }
        for (n in 70..79) {
            val tail = if (n - 60 <= 16) listOf(small[n - 60]) else listOf("dix", small[n - 70])
            put(if (n == 71) listOf("soixante", "et", "onze") else listOf("soixante") + tail, n)
        }
        put(listOf("quatre", "vingts"), 80); put(listOf("quatre", "vingt"), 80)
        for (n in 81..99) {
            val tail = if (n - 80 <= 16) listOf(small[n - 80]) else listOf("dix", small[n - 90])
            put(listOf("quatre", "vingt") + tail, n)
        }
        put(listOf("quatre", "vingt", "une"), 81)
    }
    private val enNumberWords = enSmall.toSet() + enTens.keys + setOf("hundred", "thousand", "and", "minus", "plus")
    private val frNumberWords = frUnderHundred.keys.flatten().toSet() + setOf("cent", "cents", "mille", "moins", "plus")
    private val unsupportedNumberWords = setOf("million", "millions", "billion", "billions", "trillion", "trillions",
        "milliard", "milliards", "half", "halves", "quarter", "quarters", "demi", "demie", "demis", "demies", "moitie", "quart", "quarts")

    fun compare(source: String, translated: String, sourceLanguage: String?, targetLanguage: String?): Comparison {
        val original = extract(source, language(sourceLanguage))
        val result = extract(translated, language(targetLanguage))
        return when {
            original.uncertain || result.uncertain -> Comparison.UNCERTAIN
            original.values != result.values -> Comparison.DIFFERENT
            else -> Comparison.SAME
        }
    }

    private fun language(code: String?): Language = when (code?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)) {
        "en" -> Language.EN
        "fr" -> Language.FR
        else -> Language.UNKNOWN
    }

    private fun extract(text: String, language: Language): Extracted {
        val tokens = token.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()
        val values = ArrayList<Value>()
        var uncertain = false
        var i = 0
        var consumedUnitUntil = 0
        while (i < tokens.size) {
            val current = tokens[i]
            if (current.start < consumedUnitUntil) { i++; continue }
            val digits = asciiDigits(current.text).replace('\u00a0', ' ').replace('\u202f', ' ')
            if (current.text.any(Char::isDigit)) {
                val number = parseDigits(digits, language)
                if (number == null) uncertain = true
                else {
                    val units = units(text, current.start, current.end)
                    if (units == null) uncertain = true else {
                        values += Value(number, units.name); consumedUnitUntil = units.end
                    }
                }
                i++; continue
            }
            val vocabulary = when (language) { Language.EN -> enNumberWords; Language.FR -> frNumberWords; Language.UNKNOWN -> emptySet() }
            val first = word(current.text)
            if (first !in vocabulary) {
                if (language != Language.UNKNOWN && first in unsupportedNumberWords) uncertain = true
                i++; continue
            }
            val run = ArrayList<String>()
            var end = i
            while (end < tokens.size && end - i < 18 && word(tokens[end].text) in vocabulary &&
                (end == i || wordGap.matches(text.substring(tokens[end - 1].end, tokens[end].start)))) {
                run += word(tokens[end].text); end++
            }
            var count = run.size
            var number: String? = null
            while (count > 0 && number == null) {
                val words = run.take(count)
                val negative = words.first() in setOf("minus", "moins")
                val positive = words.first() == "plus"
                val unsigned = if (negative || positive) words.drop(1) else words
                val parsed = parseWords(unsigned, language)
                // A standalone French un/une is usually an article. Recognize
                // it as a cardinal only in isolation or beside an explicit unit.
                val ambiguousArticle = language == Language.FR && unsigned.size == 1 && unsigned[0] in setOf("un", "une") &&
                    tokens.size != 1 && unitAfter.matchAt(text, tokens[i + count - 1].end) == null
                if (parsed != null && !ambiguousArticle) number = (if (negative) "-" else if (positive) "+" else "") + parsed
                else count--
            }
            if (number != null) {
                val units = units(text, current.start, tokens[i + count - 1].end)
                if (units == null) uncertain = true else {
                    values += Value(number, units.name); consumedUnitUntil = units.end
                }
                i += count
            } else {
                if (first in setOf("minus", "moins", "plus") && run.size > 1) uncertain = true
                i++
            }
        }
        return Extracted(values, uncertain)
    }

    private fun parseDigits(raw: String, language: Language): String? {
        if (date.matches(raw)) return "date:" + raw.split('-', '/', '.').joinToString("/")
        clock.matchEntire(raw)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = (match.groupValues[2].ifEmpty { match.groupValues[4] }).ifEmpty { "0" }.toInt()
            val second = match.groupValues[3].ifEmpty { "0" }.toInt()
            return if (minute <= 59 && second <= 59) "time:$hour:$minute:$second" else null
        }
        if (':' in raw || '/' in raw) return null
        val sign = raw.firstOrNull()?.takeIf { it in "+-−" }?.let { if (it == '−') "-" else it.toString() }.orEmpty()
        val unsigned = if (sign.isEmpty()) raw else raw.drop(1)
        val decimal = when (language) {
            Language.EN -> '.'
            Language.FR -> ','
            Language.UNKNOWN -> {
                if (unsigned.count { it == ',' || it == '.' } > 1) return null
                val separator = unsigned.firstOrNull { it == ',' || it == '.' }
                if (separator != null && unsigned.substringAfter(separator).length == 3) return null
                separator ?: '.'
            }
        }
        if (unsigned.count { it == decimal } > 1) return null
        val integer = unsigned.substringBefore(decimal)
        val fraction = unsigned.substringAfter(decimal, "")
        if (decimal in unsigned && (fraction.isEmpty() || fraction.any { it !in '0'..'9' })) return null
        val grouping = if (language == Language.EN) ',' else ' '
        val grouped = integer.replace(grouping, ' ')
        if (grouped.any { it !in '0'..'9' && it != ' ' }) return null
        if (' ' in grouped && !integerGroups.matches(grouped)) return null
        val plainInteger = grouped.replace(" ", "")
        if (plainInteger.length > 1 && plainInteger.startsWith('0')) return null // May be a code, not a quantity.
        val number = plainInteger + if (fraction.isNotEmpty()) ".$fraction" else ""
        return try { sign + BigDecimal(number).stripTrailingZeros().toPlainString() }
        catch (_: NumberFormatException) { null }
    }

    private fun parseWords(words: List<String>, language: Language): Int? {
        if (words.isEmpty()) return null
        val scale = if (language == Language.EN) "thousand" else "mille"
        val index = words.indexOf(scale)
        if (index >= 0) {
            if (words.count { it == scale } != 1) return null
            val prefix = words.take(index)
            val multiplier = if (prefix.isEmpty() && language == Language.FR) 1 else underThousand(prefix, language) ?: return null
            if (multiplier !in 1..999) return null
            var tail = words.drop(index + 1)
            if (language == Language.EN && tail.firstOrNull() == "and" && tail.size > 1) tail = tail.drop(1)
            if (language == Language.FR && tail in listOf(listOf("et", "un"), listOf("et", "une"))) tail = tail.drop(1)
            val remainder = if (tail.isEmpty()) 0 else underThousand(tail, language) ?: return null
            return multiplier * 1000 + remainder
        }
        return underThousand(words, language)
    }

    private fun underThousand(words: List<String>, language: Language): Int? {
        if (words.isEmpty()) return null
        val hundred = if (language == Language.EN) setOf("hundred") else setOf("cent", "cents")
        val index = words.indexOfFirst { it in hundred }
        if (index >= 0) {
            if (words.count { it in hundred } != 1) return null
            val multiplier = if (index == 0) 1 else underHundred(words.take(index), language) ?: return null
            if (multiplier !in 1..9) return null
            var tail = words.drop(index + 1)
            if (language == Language.EN && tail.firstOrNull() == "and" && tail.size > 1) tail = tail.drop(1)
            val remainder = if (tail.isEmpty()) 0 else underHundred(tail, language) ?: return null
            return multiplier * 100 + remainder
        }
        return underHundred(words, language)
    }

    private fun underHundred(words: List<String>, language: Language): Int? = when (language) {
        Language.FR -> frUnderHundred[words]
        Language.EN -> when {
            words.size == 1 && words[0] in enSmall -> enSmall.indexOf(words[0])
            words.size == 1 -> enTens[words[0]]
            words.size == 2 && words[0] in enTens && words[1] in enSmall.subList(1, 10) -> enTens.getValue(words[0]) + enSmall.indexOf(words[1])
            else -> null
        }
        Language.UNKNOWN -> null
    }

    private fun units(text: String, start: Int, end: Int): UnitValue? {
        val before = currencyBefore.find(text.substring((start - 12).coerceAtLeast(0), start))?.value?.trim()?.let(::unit)
        val suffix = unitAfter.matchAt(text, end)
        val after = suffix?.groupValues?.get(1)?.let(::unit)
        return if (before != null && after != null && before != after) null
            else UnitValue(before ?: after.orEmpty(), suffix?.range?.last?.plus(1) ?: end)
    }

    private fun unit(value: String): String = when (val normalized = word(value).filterNot(Char::isWhitespace)) {
        "$", "usd", "dollar", "dollars" -> "USD"
        "€", "eur", "euro", "euros" -> "EUR"
        "£", "gbp", "pound", "pounds" -> "GBP"
        "kg", "kilogram", "kilograms", "kilogramme", "kilogrammes" -> "kg"
        "g", "gram", "grams", "gramme", "grammes" -> "g"
        "km", "kilometer", "kilometers", "kilometre", "kilometres" -> "km"
        "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> "cm"
        "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> "mm"
        "m", "meter", "meters", "metre", "metres" -> "m"
        "l", "liter", "liters", "litre", "litres" -> "l"
        "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
        "h", "hour", "hours", "heure", "heures" -> "h"
        "min", "minute", "minutes" -> "min"
        "s", "sec", "second", "seconds", "seconde", "secondes" -> "s"
        "day", "days", "jour", "jours" -> "day"
        "week", "weeks", "semaine", "semaines" -> "week"
        "year", "years", "an", "ans", "annee", "annees" -> "year"
        "%", "percent", "pourcent" -> "%"
        else -> normalized
    }

    private fun word(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    private fun asciiDigits(value: String): String = buildString(value.length) {
        for (character in value) append(if (character.isDigit()) ('0'.code + Character.digit(character, 10)).toChar() else character)
    }
}
