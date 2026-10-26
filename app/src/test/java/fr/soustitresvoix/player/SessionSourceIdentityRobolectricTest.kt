package fr.soustitresvoix.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.SubtitleTimeline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionSourceIdentityRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: MetadataProvider

    class MetadataProvider : ContentProvider() {
        var size = 123_456L
        var modified = 1_234_567L
        var rejectsModified = false
        var fileOpens = 0
        var sourceFile: File? = null
        var denyAccess = false
        var lastMode: String? = null
        var openedOnMainThread = false
        var descriptor: ParcelFileDescriptor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/x-matroska"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.SIZE)
            if (rejectsModified && DocumentsContract.Document.COLUMN_LAST_MODIFIED in columns) throw IllegalArgumentException("Unsupported column")
            return MatrixCursor(columns).apply {
                addRow(columns.map { when (it) {
                    OpenableColumns.SIZE -> size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified
                    else -> null
                } }.toTypedArray())
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            fileOpens++; lastMode = mode
            openedOnMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            if (denyAccess) throw SecurityException("Synthetic revoked permission")
            val file = sourceFile ?: throw AssertionError("Fingerprint must not open the video")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptor = it }
        }
    }

    @Before fun setup() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "media-sessions-v1").deleteRecursively()
        provider = MetadataProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }
    private inline fun withFeatures(writer: ExecutorService? = null, block: (PlaybackFeatures, PlayerPreferences, MutableList<String>) -> Unit) {
        val preferences = PlayerPreferences(context)
        val messages = mutableListOf<String>()
        val session = PlaybackSession(context, preferences, {}, { value, _ -> messages += value }, {})
        val features = if (writer == null) PlaybackFeatures(context, session, {}, { value, _ -> messages += value })
            else PlaybackFeatures(context, session, {}, { value, _ -> messages += value }, writer)
        try { block(features, preferences, messages) } finally { features.close(); session.close() }
    }

    @Test fun partialFingerprintIsIndependentOfUriAndChangesWithSourceMetadataWithoutReadingBytes() = withFeatures { features, _, _ ->
        val first = features.sourceFingerprint("content://$AUTHORITY/first")
        assertTrue(first.startsWith("metadata-v1:"))
        assertEquals(first, features.sourceFingerprint("content://$AUTHORITY/renamed"))
        provider.size++
        assertNotEquals(first, features.sourceFingerprint("content://$AUTHORITY/first"))
        provider.size--; provider.modified++
        assertNotEquals(first, features.sourceFingerprint("content://$AUTHORITY/first"))
        assertEquals(0, provider.fileOpens)
        provider.sourceFile = File.createTempFile("offline-access-synthetic-", ".bin", context.cacheDir).apply { writeText("not decoded or read") }
        fun accessResult(): Boolean {
            var result: Boolean? = null
            features.checkOfflineMediaAccess("content://$AUTHORITY/first") { result = it }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (result == null && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            return requireNotNull(result)
        }
        assertTrue(accessResult())
        assertEquals("r", provider.lastMode)
        assertFalse(provider.openedOnMainThread)
        assertFalse(requireNotNull(provider.descriptor).fileDescriptor.valid())
        provider.denyAccess = true
        assertFalse(accessResult())
        provider.sourceFile?.delete()
    }

    @Test fun providersWithoutModifiedColumnStillSupplyAnExplicitPartialSizeIdentity() = withFeatures { features, _, _ ->
        provider.rejectsModified = true
        val first = features.sourceFingerprint("content://$AUTHORITY/first")
        assertTrue(first.startsWith("metadata-v1:"))
        provider.size++
        assertNotEquals(first, features.sourceFingerprint("content://$AUTHORITY/first"))
        assertEquals(0, provider.fileOpens)
    }

    @Test fun changedKnownSourceDoesNotRestoreOldPositionTranslationPistesOrTiming() = withFeatures { features, preferences, messages ->
        val uri = "content://$AUTHORITY/first"
        MediaSessionStore(context).save(MediaSessionSnapshot(uri, "Synthetic source", positionMs = 35_000,
            fingerprint = features.sourceFingerprint(uri), textTrackId = "old-track", subtitlePath = "/private/old.srt",
            subtitleMime = "application/x-subrip", translationIdentity = "old-translation", translationReady = true,
            settings = mapOf("speechRate" to 1.8f), timeline = SubtitleTimeline(4500)))
        provider.size++
        val restored = features.opening(SavedMedia(uri, "Synthetic source", 35_000, "/private/old.srt", "application/x-subrip"), restore = true)
        assertEquals(0L, restored.positionMs)
        assertNull(restored.subtitlePath)
        assertTrue(features.timeline.identity)
        assertEquals(1f, preferences.speechRate, 0f)
        assertTrue(messages.any { it.contains("métadonnées") && it.contains("pas restaurés") })
    }

    @Test fun slowWriterCoalescesSameMediaWithoutDiscardingThePreviousVideoSave() {
        val executor = HeldExecutor()
        withFeatures(executor) { features, _, messages ->
            val first = "content://$AUTHORITY/first"
            val second = "content://$AUTHORITY/second"
            features.save(SavedMedia(first, "First", 1000))
            features.save(SavedMedia(first, "First", 2000))
            features.save(SavedMedia(first, "First", 3000))
            features.save(SavedMedia(second, "Second", 9000))
            assertEquals(1, executor.tasks.size)
            executor.runAll()
            val store = MediaSessionStore(context)
            assertEquals(3000L, store.get(first)!!.positionMs)
            assertEquals(9000L, store.get(second)!!.positionMs)
            assertTrue(messages.isEmpty())
        }
    }

    @Test fun forgottenSessionCannotBeRecreatedByAnAlreadyQueuedOrLaterOldSave() {
        val executor = HeldExecutor()
        withFeatures(executor) { features, _, _ ->
            val uri = "content://$AUTHORITY/first"
            features.save(SavedMedia(uri, "First", 1000))
            features.forget(uri)
            features.save(SavedMedia(uri, "First", 2000))
            executor.runAll()
            assertNull(MediaSessionStore(context).get(uri))
            assertNull(features.sessionRecord(uri))
        }
    }

    @Test fun anOldDeletionCannotEraseANewExplicitlyOpenedSession() {
        val executor = HeldExecutor()
        withFeatures(executor) { features, _, _ ->
            val uri = "content://$AUTHORITY/first"
            features.save(SavedMedia(uri, "First", 1000))
            features.forget(uri)
            assertEquals(0L, features.opening(SavedMedia(uri, "First"), restore = true).positionMs)
            features.save(SavedMedia(uri, "First", 4000))
            executor.runAll()
            assertEquals(4000L, MediaSessionStore(context).get(uri)!!.positionMs)
        }
    }

    private class HeldExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated()
    }

    companion object { private const val AUTHORITY = "session-metadata.fixture" }
}
