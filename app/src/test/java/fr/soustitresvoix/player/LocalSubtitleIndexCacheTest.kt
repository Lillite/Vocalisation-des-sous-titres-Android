package fr.soustitresvoix.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSubtitleIndexCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reusePreservesDistinctOccurrencesAndRejectsCorruptionOrModifiedSidecar() {
        val subtitle = folder.newFile("dialogue.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\nOui.\n")
        }
        val media = SavedMedia(Uri.fromFile(folder.newFile("film.mkv")).toString(), "Film",
            subtitlePath = subtitle.path, subtitleMime = "application/x-subrip")
        val directory = folder.newFolder("indexes")
        val cache = LocalSubtitleIndexCache(directory)
        val key = requireNotNull(cache.identity(media))
        val expected = listOf(
            CueAheadIndex.Item("external-subtitle", 1_000_000, 2_000_000, "Oui.", 0, "source-1"),
            CueAheadIndex.Item("external-subtitle", 1_000_000, 2_000_000, "Oui.", 1, "source-2")
        )
        cache.write(key, CueAheadIndex.restored(expected, setOf("external-subtitle", "empty-track")))
        val restored = requireNotNull(cache.read(key))
        assertEquals(expected.map { it.identity }, restored.allIndexedItems().map { it.identity })
        assertTrue(restored.hasTrack("empty-track"))
        val entry = File(directory, "$key.index")
        RandomAccessFile(entry, "rw").use { stream ->
            val end = stream.length() - 1
            stream.seek(end)
            val last = stream.readUnsignedByte()
            stream.seek(end)
            stream.writeByte(last xor 1)
        }
        assertNull(cache.read(key))
        val oldTime = subtitle.lastModified()
        subtitle.writeText(subtitle.readText().replace("Oui.", "Non."))
        subtitle.setLastModified(oldTime)
        assertNotEquals(key, cache.identity(media))
    }
}
