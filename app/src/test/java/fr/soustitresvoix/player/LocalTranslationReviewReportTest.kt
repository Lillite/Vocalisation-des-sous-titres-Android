package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.junit.Assert.*
import org.junit.Test

class LocalTranslationReviewReportTest {
    @Test fun reviewDisplayIsBoundedAndUnicodeSafeWithoutShorteningAnyTranslation() {
        val source = List(80) { index -> TranslationCue("long-$index", index * 10L, index * 10L + 5,
            "2 " + "🚂".repeat(600)) }
        val texts = List(80) { "3 " + "🚂".repeat(600) }
        val plan = TranslationPlan.create(source, "en", "fr", "synthetic-review")
        val translated = plan.complete(texts)
        val report = LocalTranslationReviewReport.render(plan, translated)
        assertTrue(report.length <= 96_000)
        assertTrue(report.contains("80 occurrences traduites"))
        assertTrue(report.contains("Affichage borné"))
        assertTrue(report.contains("[…]"))
        assertEquals(report, Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(report.toByteArray(Charsets.UTF_8))).toString())
        assertEquals(80, translated.cues.size)
        assertEquals(texts, translated.cues.map { it.text })
        assertEquals(source, plan.cues)
        assertTrue(report.contains("Aucune traduction n’est modifiée"))
    }
}
