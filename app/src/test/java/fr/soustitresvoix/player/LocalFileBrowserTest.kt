package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocalFileBrowserTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pagesPreserveEveryFileAndItsUriWithoutLosingDuplicateCaseNames() {
        val root = temporary.newFolder("volume")
        File(root, ".z folder").mkdir()
        val names = listOf(".a vidéo #1.mkv", ".A vidéo #1.mkv", "b film sans extension", "zz.srt")
        names.forEach { File(root, it).writeText("synthetic") }
        val browser = LocalFileBrowser()
        val first = browser.list(root, root, pageSize = 2)
        val second = browser.list(root, root, page = 1, pageSize = 2)
        val last = browser.list(root, root, page = 2, pageSize = 2)
        val entries = first.entries + second.entries + last.entries
        assertEquals(5, first.total)
        assertEquals(5, entries.map { it.file }.toSet().size)
        assertTrue(first.entries.first().isDirectory)
        assertEquals(".z folder", first.entries.first().name)
        assertTrue(first.hasNext)
        assertFalse(last.hasNext)
        assertEquals(names.toSet(), entries.filterNot { it.isDirectory }.map { it.name }.toSet())
        entries.filterNot { it.isDirectory }.forEach { entry ->
            assertEquals("file", entry.uri.scheme)
            assertEquals(entry.file.absolutePath, entry.uri.path)
            assertEquals(entry.name, entry.uri.lastPathSegment)
        }
    }

    @Test fun cancellationRejectsListingAndReusingCancelledHandle() {
        val root = temporary.newFolder()
        val cancellation = LocalBrowseCancellation()
        cancellation.close()
        repeat(2) {
            val failure = runCatching { LocalFileBrowser().list(root, root, cancel = cancellation) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("Navigation annulée.", failure?.message)
        }
    }

    @Test fun pathsCannotEscapeVolumeOrExposePrivateAndroidDirectories() {
        val root = temporary.newFolder("shared")
        val other = temporary.newFolder("outside")
        val outside = File(other, "private.mkv").apply { writeText("synthetic") }
        Files.createSymbolicLink(File(root, "outside-link").toPath(), other.toPath())
        File(root, "Android/data/private.app").mkdirs()
        File(root, "Android/obb/private.app").mkdirs()
        val browser = LocalFileBrowser()
        val rootListing = browser.list(root, root)
        assertTrue(rootListing.entries.none { it.name == "outside-link" })
        assertEquals(1, rootListing.excludedCount)
        val androidListing = browser.list(root, File(root, "Android"))
        assertTrue(androidListing.entries.isEmpty())
        assertEquals(2, androidListing.excludedCount)
        assertTrue(runCatching { browser.list(root, other) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { browser.list(root, File(root, "Android/data")) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(LocalFileBrowser.isInside(root, File(root.parentFile, root.name + "-other")))
        val shown = File(root, "synthetic.mkv").apply { writeText("synthetic") }
        assertEquals(shown.canonicalPath, browser.readableFile(root, shown, LocalBrowseCancellation()).path)
        assertTrue(shown.delete())
        Files.createSymbolicLink(shown.toPath(), outside.toPath())
        assertTrue(runCatching { browser.readableFile(root, shown, LocalBrowseCancellation()) }.exceptionOrNull() is LocalBrowseException)
    }

    @Test fun oversizedDirectoryFailsExplicitlyWithoutReturningIncompletePage() {
        val root = temporary.newFolder()
        repeat(LocalFileBrowser.MAX_ENTRIES + 1) { Files.createFile(File(root, "file-$it").toPath()) }
        val failure = runCatching { LocalFileBrowser().list(root, root) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("aucun résultat partiel"))
    }
}
