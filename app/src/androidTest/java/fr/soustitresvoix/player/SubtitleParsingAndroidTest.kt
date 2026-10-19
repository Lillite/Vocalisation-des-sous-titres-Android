package fr.soustitresvoix.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.soustitresvoix.core.SubtitleImport
import fr.soustitresvoix.core.SubtitleText
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Must run on Android, not only on the desktop JVM: Android's ICU regex engine
 * rejects some unescaped literal delimiters which desktop Java accepts.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleParsingAndroidTest {
    @Test fun markupAndDescriptionsAreCleanedOnAndroid() {
        assertEquals("Salut\ntoi", SubtitleText.clean("{\\an8}Salut\\Ntoi"))
        assertEquals("Bonjour\na < b", SubtitleText.clean("<i>Bonjour</i><br/>a < b"))
        assertEquals("[Musique] Bonjour", SubtitleText.clean("[Musique] Bonjour"))
        assertEquals("Bonjour", SubtitleText.clean("[Musique] Bonjour [Rires]", true))
        assertEquals("x { y }", SubtitleText.clean("x { y }"))
        assertEquals("こんにちは\nمرحبا\n😀", SubtitleText.clean("こんにちは\nمرحبا\n😀"))
    }

    @Test fun allTextSubtitleImportsWorkOnAndroid() {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\nÉté à Paris.\n"
        val decoded = SubtitleImport.decode(srt.toByteArray(Charsets.UTF_8), "test.srt")
        assertEquals(SubtitleImport.Kind.SRT, decoded.kind)
        assertEquals(srt, decoded.text)
        assertEquals(srt, SubtitleImport.decode(("\uFEFF" + srt).toByteArray(Charsets.UTF_16LE), "test.srt").text)
        assertEquals(srt, SubtitleImport.decode(srt.toByteArray(Charset.forName("windows-1252")), "test.srt", "windows-1252").text)
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\nBonjour.\n"
        assertEquals(SubtitleImport.Kind.VTT, SubtitleImport.decode(vtt.toByteArray(), "test.vtt").kind)
        val ass = "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Bonjour\n"
        // Detect from content too; do not rely only on the filename extension.
        assertEquals(SubtitleImport.Kind.ASS, SubtitleImport.decode(ass.toByteArray(), "test.txt").kind)
    }

    @Test fun speechChunksPreserveUnicodeOnAndroid() {
        val text = "😀".repeat(700)
        val chunks = SubtitleText.splitForSpeech(text, 999)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 999 && !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
    }
}
