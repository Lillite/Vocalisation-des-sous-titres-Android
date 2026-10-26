package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class LanguageSegmentationTest {
    @Test fun germanHonorificsAndAdverbsStayWithTheirSentence() {
        assertEquals(listOf("Hr. Müller kommt bzw. fährt morgen.", "Er bleibt."),
            SpeechTools.segments("Hr. Müller kommt bzw. fährt morgen. Er bleibt.", "de-DE"))
    }
    @Test fun spanishAndItalianTitlesDoNotSplitNames() {
        assertEquals(listOf("La Dra. García llega.", "¿Todo bien?"),
            SpeechTools.segments("La Dra. García llega. ¿Todo bien?", "es-ES"))
        assertEquals(listOf("Il dott. Rossi arriva.", "Va bene!"),
            SpeechTools.segments("Il dott. Rossi arriva. Va bene!", "it"))
    }
    @Test fun unknownLanguageRetainsHistoricalFrenchAndEnglishSafeguards() {
        val text = "M. Dupont parle au Dr. Smith. Il arrive à 3.14."
        assertEquals(SpeechTools.segments(text), SpeechTools.segments(text, "unknown"))
        assertEquals(listOf("M. Dupont parle au Dr. Smith.", "Il arrive à 3.14."), SpeechTools.segments(text))
    }
    @Test fun cyrillicAbbreviationsAndCjkPunctuationPreserveEveryWord() {
        assertEquals(listOf("Ул. Ленина рядом.", "Мы идём."), SpeechTools.segments("Ул. Ленина рядом. Мы идём.", "ru"))
        assertEquals(listOf("こんにちは。", "元気？", "はい！"), SpeechTools.segments("こんにちは。元気？はい！", "ja"))
    }
    @Test fun speakerBoundariesAndEngineLimitRemainValidAcrossLanguages() {
        assertEquals(listOf("- Hola", "- ¿Cómo estás?"), SpeechTools.segments("- Hola\n- ¿Cómo estás?", "es"))
        val text = "長い文章".repeat(1000)
        val parts = SpeechTools.segments(text, "ja")
        assertTrue(parts.all { it.length <= 1000 })
        assertEquals(text, parts.joinToString(""))
    }
    @Test fun exactMeasuredAudioOverridesEstimationOnlyAtTheSameBaseRate() {
        val model = SpeechDurationModel(); val text = "A measured subtitle sentence."
        model.rememberMeasured(text, 4321, 1f)
        assertEquals(4321L, model.estimate(text, 1f))
        assertNull(model.measured(text, 1.2f))
        assertNotEquals(4321L, model.estimate(text, 1.2f))
        model.clear(); assertNull(model.measured(text, 1f))
    }
    @Test fun measuredDurationStoreIsBoundedAndIgnoresInvalidMeasurements() {
        val model = SpeechDurationModel()
        repeat(5000) { model.rememberMeasured("Sentence $it", 1000, 1f) }
        assertNull(model.measured("Sentence 0", 1f)); assertEquals(1000L, model.measured("Sentence 4999", 1f))
        model.rememberMeasured("Invalid", -1, 1f); assertNull(model.measured("Invalid", 1f))
    }
}
