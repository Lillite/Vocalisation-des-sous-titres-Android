package fr.soustitresvoix.player

import fr.soustitresvoix.core.SubtitleImport
import org.junit.Assert.*
import org.junit.Test

class LocalGgufQualityCorpusTest {
    @Test fun newSourcesDoNotReplaceTheOriginalBenchmark() {
        assertEquals(32, LocalGgufBenchmarkCorpus.cues.size)
        assertEquals(18, LocalGgufQualityCorpus.cues.size)
        assertEquals(17, LocalGgufQualityCorpus.cues.map { it.source }.distinct().size)
        assertTrue(LocalGgufQualityCorpus.cues.none { added ->
            LocalGgufBenchmarkCorpus.cues.any { old -> old.source == added.source }
        })
        assertEquals(18, LocalGgufQualityCorpus.cues.map { it.id }.distinct().size)
    }

    @Test fun repeatedAndOverlappingOccurrencesKeepTheirOwnTimes() {
        val cues = LocalGgufQualityCorpus.cues
        assertEquals(cues[3].source, cues[17].source)
        assertNotEquals(cues[3].id, cues[17].id)
        assertNotEquals(cues[3].startMs, cues[17].startMs)
        assertTrue(cues[5].endMs > cues[6].startMs)
        assertEquals(2_000L, cues[12].endMs - cues[12].startMs)
    }

    @Test fun standaloneSrtContainsEverySourceWithoutReferenceAnswers() {
        val text = LocalGgufQualityCorpus.SOURCE_SRT
        val decoded = SubtitleImport.decode(text.toByteArray(Charsets.UTF_8), "qualite-traduction-en.srt", "UTF-8")
        assertEquals(SubtitleImport.Kind.SRT, decoded.kind)
        assertEquals(text, decoded.text)
        assertEquals(18, Regex("(?m)^\\d{2}:\\d{2}:\\d{2},\\d{3} --> ").findAll(text).count())
        LocalGgufQualityCorpus.cues.forEach { assertTrue(text.contains(it.source)) }
        assertTrue(text.contains("€1,250.50"))
        assertTrue(text.contains("— Did Lina call you?\n— No. I called her."))
    }
}
