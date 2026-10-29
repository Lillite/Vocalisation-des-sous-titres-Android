package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class TranslationNumberReviewTest {
    private fun numberHints(source: String, translated: String, from: String? = "en", to: String? = "fr"): List<String> {
        val original = TranslationCue("number", 20, 40, source)
        return TranslationReview.inspect(listOf(original), listOf(original.copy(text = translated)), from, to)
            .flatMap { it.reasons }.filter { "numérique" in it || "Nombres" in it }
    }

    @Test fun writtenCardinalsAndDigitsDoNotInventDifferences() {
        assertTrue(numberHints("twelve minutes", "12 minutes").isEmpty())
        assertTrue(numberHints("seven records", "sept documents").isEmpty())
        assertTrue(numberHints("twenty-one boats", "vingt et un bateaux").isEmpty())
        assertTrue(numberHints("eighty-one pages", "quatre-vingt-une pages").isEmpty())
        assertTrue(numberHints("zero apples", "zéro pomme").isEmpty())
    }

    @Test fun wrongWrittenNumberStillProducesAnAlert() {
        assertTrue(numberHints("twelve minutes", "onze minutes").isNotEmpty())
        assertTrue(numberHints("seventy-two boats", "soixante-douze bateaux").isEmpty())
        assertTrue(numberHints("ninety-three boats", "quatre-vingt-treize bateaux").isEmpty())
        assertTrue(numberHints("ninety-three boats", "quatre-vingt-trois bateaux").isNotEmpty())
    }

    @Test fun thousandsAndDecimalsUseTheDeclaredLanguages() {
        assertTrue(numberHints("1,024 records", "1 024 documents").isEmpty())
        assertTrue(numberHints("1,024 records", "1\u202f024 documents").isEmpty())
        assertTrue(numberHints("1,024 records", "1\u00a0024 documents").isEmpty())
        assertTrue(numberHints("2,500.75 kg", "2 500,75 kg").isEmpty())
        assertTrue(numberHints("1.024 kg", "1 024 kg").isNotEmpty())
        assertTrue(numberHints("1,024 kg", "1,024 kg").isNotEmpty()) // English thousands versus French decimal.
    }

    @Test fun unknownLocaleNeverAssumesThatThreeDigitsAreThousands() {
        assertTrue(numberHints("1,024 records", "1 024 documents", null, null).any { "ambigu" in it })
        assertTrue(numberHints("1.024 records", "1 024 documents", "de", "fr").any { "ambigu" in it })
        assertTrue(numberHints("1.25", "1,25", null, null).isEmpty())
    }

    @Test fun invalidGroupingAndLocaleNotationRemainVisible() {
        for ((source, target, language) in listOf(Triple("12,34", "1 234", "en"),
            Triple("1,02,400", "102 400", "fr"), Triple("1.024", "1 024", "fr"))) {
            assertTrue("$language: $source", numberHints(source, target, language, "fr").any { "ambigu" in it })
        }
        // A French decimal is valid: do not mislabel it as ambiguous to make
        // an invalid test fixture pass. Its value is 12.34, never 1234.
        assertTrue(numberHints("12,34", "12.34", "fr", "en").isEmpty())
        assertTrue(numberHints("12,34", "1 234", "fr", "fr").any { "Nombres" in it })
    }

    @Test fun clocksNormalizeLeadingZerosAndHourNotation() {
        assertTrue(numberHints("leaves at 07:30", "départ à 7h30").isEmpty())
        assertTrue(numberHints("at 14:15, not 14:50", "à 14 h 15, et non 14 h 50").isEmpty())
        assertTrue(numberHints("at 09:00", "à 9 h").isEmpty())
        assertTrue(numberHints("at 07:30:05", "à 7:30:05").isEmpty())
        assertTrue(numberHints("at 07:30", "à 7h50").isNotEmpty())
    }

    @Test fun invalidTimeIsNotCertifiedBySplittingItsDigits() {
        assertTrue(numberHints("at 07:99", "à 7h99").any { "ambigu" in it })
    }

    @Test fun datesKeepTheirComponentsAndOrder() {
        assertTrue(numberHints("2026-10-29", "2026/10/29").isEmpty())
        assertTrue(numberHints("10/11/2026", "11/10/2026").isNotEmpty())
        assertTrue(numberHints("2026-10-29", "2026-10-28").isNotEmpty())
    }

    @Test fun negativePositiveAndUnicodeMinusSignsArePreserved() {
        assertTrue(numberHints("−2.5 °C", "-2,5 °C").isEmpty())
        assertTrue(numberHints("minus twelve", "moins douze").isEmpty())
        assertTrue(numberHints("-12", "12").isNotEmpty())
        assertTrue(numberHints("+12", "12").isNotEmpty())
        assertTrue(numberHints("-0", "0").isNotEmpty())
    }

    @Test fun quantitiesRemainOrderedRatherThanComparedAsSets() {
        assertTrue(numberHints("12 apples and 7 pears", "7 pommes et 12 poires").isNotEmpty())
        assertTrue(numberHints("12 apples and 12 pears", "12 pommes et 7 poires").isNotEmpty())
    }

    @Test fun unitsAndCurrenciesRemainPartOfTheReview() {
        assertTrue(numberHints("2.5 kg", "2,5 kilogrammes").isEmpty())
        assertTrue(numberHints("two kilometers", "deux kilomètres").isEmpty())
        assertTrue(numberHints("one meter", "un mètre").isEmpty())
        assertTrue(numberHints("one liter", "un litre").isEmpty())
        assertTrue(numberHints("£18.75", "18,75 £").isEmpty())
        assertTrue(numberHints("12 hours", "12 heures").isEmpty())
        assertTrue(numberHints("7 years", "sept ans").isEmpty())
        assertTrue(numberHints("twelve percent", "douze pour cent").isEmpty())
        assertTrue(numberHints("100%", "cent pour cent").isEmpty())
        assertTrue(numberHints("12 per cent", "12 %").isEmpty())
        assertTrue(numberHints("8 °C", "8 °F").isNotEmpty())
        assertTrue(numberHints("2.5 kg", "2,5 g").isNotEmpty())
        assertTrue(numberHints("12 minutes", "12 heures").isNotEmpty())
        assertTrue(numberHints("$18.75", "18,75 €").isNotEmpty())
    }

    @Test fun contradictoryCurrencyNotationIsAnExplicitUncertainty() {
        assertTrue(numberHints("$12 EUR", "12 EUR").any { "ambigu" in it })
    }

    @Test fun supportedHundredsAndThousandsKeepTheirValues() {
        assertTrue(numberHints("one hundred and six", "cent six").isEmpty())
        assertTrue(numberHints("two thousand three hundred forty-five", "deux mille trois cent quarante-cinq").isEmpty())
        assertTrue(numberHints("one thousand and five", "mille cinq").isEmpty())
        assertTrue(numberHints("one thousand and one", "mille et une").isEmpty())
        assertTrue(numberHints("nine hundred thousand", "900 000").isEmpty())
    }

    @Test fun unsupportedWrittenNumbersDoNotBecomeAClaimOfCompleteness() {
        assertTrue(numberHints("one million", "1 000 000").isNotEmpty())
        assertTrue(numberHints("one million", "un").any { "ambigu" in it })
        assertTrue(numberHints("one half", "one", "en", "en").any { "ambigu" in it })
        assertTrue(numberHints("one point five", "1,5").isNotEmpty())
        assertTrue(numberHints("3e2", "300").isNotEmpty())
    }

    @Test fun standaloneFrenchArticlesAndComparativeWordsDoNotInventNumbers() {
        assertTrue(numberHints("a parcel", "un colis").isEmpty())
        assertTrue(numberHints("a car", "une voiture").isEmpty())
        assertTrue(numberHints("much stronger", "beaucoup plus fort").isEmpty())
        assertTrue(numberHints("less often", "moins souvent").isEmpty())
    }

    @Test fun regionalCodesAndUnicodeDecimalDigitsAreHandledWithoutChangingText() {
        assertTrue(numberHints("7 records", "sept documents", "EN_us", "fr-FR").isEmpty())
        assertTrue(numberHints("12", "١٢").isEmpty())
        assertTrue(numberHints("12", "١٣").isNotEmpty())
    }

    @Test fun possibleLeadingZeroCodesRemainUncertain() {
        assertTrue(numberHints("code 007", "code 7").any { "ambigu" in it })
    }

    @Test fun reviewNeverRewritesOrDropsOccurrences() {
        val source = listOf(TranslationCue("a", 0, 10, "twelve minutes"), TranslationCue("b", 11, 20, "twelve minutes"))
        val translated = listOf(source[0].copy(text = "onze minutes"), source[1].copy(text = "12 minutes"))
        val originalCopy = translated.toList()
        val hints = TranslationReview.inspect(source, translated, "en", "fr")
        assertEquals(listOf("a"), hints.map { it.cueId })
        assertEquals(originalCopy, translated)
        assertEquals(listOf("a", "b"), translated.map { it.id })
    }
}
