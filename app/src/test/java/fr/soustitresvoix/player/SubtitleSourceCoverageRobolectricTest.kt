package fr.soustitresvoix.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitleSourceCoverageRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun combinedTrackRetainsIdenticalTextFromBothOriginsInDeterministicOrder() {
        val panel = CueAheadIndex.Item("signs", 0, 4_000_000, "Bienvenue", sourceToken = "sign-1")
        val first = CueAheadIndex.Item("dialogue", 0, 2_000_000, "Bienvenue", sourceToken = "line-1")
        val second = CueAheadIndex.Item("dialogue", 0, 2_000_000, "Bienvenue", sourceToken = "line-2")
        val original = listOf(panel, first, second)
        val index = CueAheadIndex.restored(original).combinedTrack("dialogue", "signs")
        val combined = CueAheadIndex.combinedTrackId("dialogue", "signs")
        assertEquals(listOf(first.identity, second.identity, panel.identity), index.selected(combined).map { it.identity })
        assertEquals(listOf(first, second, panel), index.activeItems(combined, 1_000_000))
        assertEquals(listOf(panel), index.activeItems(combined, 3_000_000))
        assertEquals(original, index.allIndexedItems())
        assertEquals(3, index.count)
        assertThrows(IllegalArgumentException::class.java) { index.combinedTrack("dialogue", "not-indexed") }
    }

    @Test fun closedHlsIndexesCrossSegmentRepetitionsOnceAndRealDuplicatesSeparately() {
        val index = scan(hls(), "movie.m3u8", "subs:French")
        val cues = index.selected("subs:French")
        assertEquals(listOf("Oui.", "Oui.", "Bref."), cues.map { it.raw })
        assertEquals(listOf(500_000L, 500_000L, 1_650_000L), cues.map { it.startUs })
        assertEquals(3, cues.map { it.identity }.distinct().size)
        assertEquals(3, index.count)
    }

    @Test fun hlsMissingCarryOrLivePlaylistCannotCertifyCoverage() {
        val missing = hls().toMutableMap().apply { put("/two.vtt", vtt("00:00:01.650 --> 00:00:01.700\nBref.")) }
        val absent = assertThrows(IOException::class.java) { scan(missing, "movie.m3u8", "subs:French") }
        assertEquals(FiniteWebSubtitleIndex.UNVERIFIED, FullSubtitleIndexer.safeFailureMessage(absent))
        val live = hls().mapValues { (_, value) -> value.replace("#EXT-X-ENDLIST", "") }
        val failure = assertThrows(IOException::class.java) { scan(live, "movie.m3u8", "subs:French") }
        assertEquals(FiniteWebSubtitleIndex.LIVE, FullSubtitleIndexer.safeFailureMessage(failure))
    }

    @Test fun hlsAmbiguousMpegTimestampMappingIsNotInvented() {
        val documents = hls().toMutableMap().apply {
            put("/one.vtt", getValue("/one.vtt").replace("WEBVTT\n\n", "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n"))
        }
        val error = assertThrows(IOException::class.java) { scan(documents, "movie.m3u8", "subs:French") }
        assertEquals(FiniteWebSubtitleIndex.UNVERIFIED, FullSubtitleIndexer.safeFailureMessage(error))
    }

    @Test fun hlsEndedSlidingWindowAndConflictingDeclaredOriginsStayUnverified() {
        val sliding = hls().mapValues { (_, contents) ->
            contents.replace("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MEDIA-SEQUENCE:4")
        }
        val mismatched = hls().toMutableMap().apply {
            val tag = "#EXT-X-PLAYLIST-TYPE:VOD\n"
            put("/subs.m3u8", getValue("/subs.m3u8").replace(tag, tag + "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T01:00:00Z\n"))
            put("/video.m3u8", getValue("/video.m3u8").replace(tag, tag + "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T02:00:00Z\n"))
        }
        for (documents in listOf(sliding, mismatched)) {
            val error = assertThrows(IOException::class.java) { scan(documents, "movie.m3u8", "subs:French") }
            assertEquals(FiniteWebSubtitleIndex.UNVERIFIED, FullSubtitleIndexer.safeFailureMessage(error))
        }
    }

    @Test fun dashStaticPlainVttSegmentsKeepAbsolutePresentationTimes() {
        val documents = mapOf("/movie.mpd" to """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S" minBufferTime="PT1S">
              <Period start="PT0S">
                <AdaptationSet id="1" mimeType="text/vtt" lang="fr">
                  <Representation id="fra" bandwidth="1000">
                    <SegmentTemplate timescale="1000" duration="2000" startNumber="1" media="subtitle-${'$'}Number${'$'}.vtt" />
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent(), "/subtitle-1.vtt" to vtt("00:00:00.400 --> 00:00:00.900\nUn."),
            "/subtitle-2.vtt" to vtt("00:00:02.100 --> 00:00:03.700\nDeux."))
        val cues = scan(documents, "movie.mpd", "fra").selected("fra")
        assertEquals(listOf("Un.", "Deux."), cues.map { it.raw })
        assertEquals(listOf(400_000L, 2_100_000L), cues.map { it.startUs })
        assertEquals(listOf(900_000L, 3_700_000L), cues.map { it.endUs })
    }

    @Test fun imageDraftRequiresEachOccurrenceToBeReviewedAndPreservesIdenticalRows() {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val cue = Cue.Builder().setBitmap(image).build()
        val draft = ImageSubtitleDraft { "Proposition" }
        draft.accept(CuesWithTiming(listOf(cue, cue), 1_000_000, C.TIME_UNSET))
        draft.accept(CuesWithTiming(emptyList(), 2_000_000, C.TIME_UNSET))
        draft.accept(CuesWithTiming(listOf(cue), 3_000_000, 500_000))
        val rows = draft.finish(4_000_000)
        assertEquals(listOf(2_000_000L, 2_000_000L, 3_500_000L), rows.map { it.endUs })
        assertEquals(listOf(1_000_000L, 1_000_000L, 3_000_000L), rows.map { it.startUs })
        val directory = File(context.cacheDir, "test-reviewed-subtitles")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ReviewedImageSubtitles.write(directory, rows, listOf("Validé", null, "Validé"))
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val sidecar = ReviewedImageSubtitles.write(directory, rows, listOf("Oui.", "Oui.", "Fini."))
            val index = FullSubtitleIndexer(context).scanTracks(SavedMedia("content://fixture/movie", "Synthétique",
                subtitlePath = sidecar.absolutePath, subtitleMime = MimeTypes.APPLICATION_SUBRIP), setOf("external-subtitle")) {}
            val cues = index.selected("external-subtitle")
            assertEquals(listOf("Oui.", "Oui.", "Fini."), cues.map { it.raw })
            assertEquals(3, cues.map { it.identity }.distinct().size)
        } finally { directory.deleteRecursively(); image.recycle() }
    }

    @Test fun imageDraftCannotInventEndForUndatedFinalBitmap() {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val draft = ImageSubtitleDraft { "Texte à vérifier" }
            draft.accept(CuesWithTiming(listOf(Cue.Builder().setBitmap(image).build()), 1_000_000, C.TIME_UNSET))
            assertThrows(IllegalArgumentException::class.java) { draft.finish(C.TIME_UNSET) }
        } finally { image.recycle() }
    }

    @Test fun realMatroskaPgsExtractionEmitsBitmapAndClearBoundary() {
        val palette = pgsSection(0x14, bytes(0, 0, 1, 235, 128, 128, 255))
        val pixels = pgsSection(0x15, bytes(0, 0, 0, 0xC0, 0, 0, 8, 0, 2, 0, 2, 1, 1, 1, 1))
        val placement = pgsSection(0x16, bytes(0x07, 0x80, 0x04, 0x38) + ByteArray(15))
        val endPacket = pgsSection(0x80, byteArrayOf())
        val track = ebml("1654ae6b", ebml("ae", ebml("d7", bytes(1)) + ebml("83", bytes(17)) +
            ebml("86", "S_HDMV/PGS".toByteArray())))
        val info = ebml("1549a966", ebml("2ad7b1", bytes(0x0F, 0x42, 0x40)))
        val cluster = ebml("1f43b675", ebml("e7", bytes(0)) +
            ebml("a3", bytes(0x81, 0x03, 0xE8, 0x80) + palette + pixels + placement + endPacket) +
            ebml("a3", bytes(0x81, 0x07, 0xD0, 0x80) + endPacket))
        val movie = File.createTempFile("real-pgs-", ".mkv", context.cacheDir)
        try {
            movie.writeBytes(ebml("1a45dfa3", ebml("4282", "matroska".toByteArray())) +
                ebml("18538067", info + track + cluster))
            val draft = ImageSubtitleDraft { bitmap ->
                assertEquals(2, bitmap.width)
                assertEquals(2, bitmap.height)
                "Sous-titre vérifié"
            }
            FullSubtitleIndexer(context).scanImageCues(SavedMedia(Uri.fromFile(movie).toString(), "PGS"),
                "1", draft::accept) {}
            assertEquals(listOf(ImageSubtitleProposal(1_000_000, 2_000_000, "Sous-titre vérifié")), draft.finish(C.TIME_UNSET))
        } finally { movie.delete() }
    }

    @Test fun sampledOcrKeepsSeparateRepeatsAfterAFrameWithoutText() {
        val draft = SampledImageSubtitleDraft()
        draft.sample(1_000_000, "Entrée\nprincipale")
        draft.sample(1_500_000, "Entrée principale")
        draft.sample(2_000_000, "")
        draft.sample(2_500_000, "Entrée principale")
        assertEquals(listOf(ImageSubtitleProposal(1_000_000, 2_000_000, "Entrée\nprincipale"),
            ImageSubtitleProposal(2_500_000, 3_000_000, "Entrée principale")), draft.finish(3_000_000))
    }

    @Test fun progressiveReadAllowanceUsesFiniteLengthWithoutOverflowAndBoundsUnknownSources() {
        val gib = 1024L * 1024 * 1024
        assertEquals(42 * gib, progressiveIndexReadLimit(40 * gib))
        assertEquals(32 * gib, progressiveIndexReadLimit(C.LENGTH_UNSET.toLong()))
        assertEquals(Long.MAX_VALUE, progressiveIndexReadLimit(Long.MAX_VALUE))
        assertEquals(1_000_000 + gib / 4, progressiveIndexReadLimit(1_000_000))
    }

    private fun scan(documents: Map<String, String>, path: String, track: String): CueAheadIndex {
        val sources = DataSource.Factory {
            object : DataSource {
                private var reader: ByteArrayDataSource? = null
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long {
                    assertEquals("fixture.invalid", dataSpec.uri.host)
                    val contents = documents[dataSpec.uri.path] ?: throw IOException("Fichier de test absent")
                    val source = ByteArrayDataSource(contents.toByteArray(Charsets.UTF_8))
                    reader = source
                    return source.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(reader).read(buffer, offset, length)
                override fun getUri(): Uri? = reader?.uri
                override fun close() { reader?.close(); reader = null }
            }
        }
        return FullSubtitleIndexer(context, sources).scanTracks(SavedMedia("https://fixture.invalid/$path", "Synthétique"), setOf(track)) {}
    }

    private fun hls(): Map<String, String> {
        val cue = "00:00:00.500 --> 00:00:01.500\nOui."
        val prefix = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-PLAYLIST-TYPE:VOD\n"
        return mapOf("/movie.m3u8" to """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="French",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="fr",URI="subs.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=100000,SUBTITLES="subs"
            video.m3u8
        """.trimIndent(),
            "/subs.m3u8" to prefix + "#EXTINF:1.0,\none.vtt\n#EXTINF:1.0,\ntwo.vtt\n#EXT-X-ENDLIST\n",
            "/video.m3u8" to prefix + "#EXTINF:1.0,\none.ts\n#EXTINF:1.0,\ntwo.ts\n#EXT-X-ENDLIST\n",
            "/one.vtt" to vtt(cue, cue),
            "/two.vtt" to vtt(cue, cue, "00:00:01.650 --> 00:00:01.700\nBref."))
    }

    private fun vtt(vararg cues: String) = "WEBVTT\n\n" + cues.joinToString("\n\n") + "\n"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun pgsSection(type: Int, contents: ByteArray) = bytes(type, contents.size ushr 8, contents.size) + contents

    private fun ebml(hexId: String, contents: ByteArray): ByteArray {
        require(contents.size < 127)
        val id = hexId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return id + bytes(0x80 or contents.size) + contents
    }
}
