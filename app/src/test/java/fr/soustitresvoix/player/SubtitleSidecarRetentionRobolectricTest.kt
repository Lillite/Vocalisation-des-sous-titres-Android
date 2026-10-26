package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real import worker and private files; no personal video or source modification. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleSidecarRetentionRobolectricTest {
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }
    @Test fun aSavedSessionsFirstSidecarSurvivesThreeFurtherImportsAndSourcesRemainIntact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("narration", false).commit()
        val sourceDirectory = File(context.cacheDir, "sidecar-source-${UUID.randomUUID()}").apply { mkdirs() }
        val sources = (0..3).map { index -> File(sourceDirectory, "source-$index.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\nTexte synthétique $index.\n", Charsets.UTF_8)
        } }
        val originalContents = sources.map { it.readText() }
        val copies = mutableListOf<File>()
        val store = MediaSessionStore(context)
        val rememberedUri = "content://synthetic-sidecar-session/${UUID.randomUUID()}"
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = owner.get()
            activity.session.player.trackSelectionParameters = activity.session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            activity.session.open(SavedMedia("android.resource://${activity.packageName}/${R.raw.demonstration}", "Démonstration synthétique"))
            val import = MainActivity::class.java.getDeclaredMethod("importSubtitle", Uri::class.java, String::class.java).apply { isAccessible = true }
            val worker = MainActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(activity) as ExecutorService
            sources.forEachIndexed { index, source ->
                val previous = activity.session.media?.subtitlePath
                import.invoke(activity, Uri.fromFile(source), null)
                await("Import $index did not attach its private subtitle copy") {
                    activity.session.media?.subtitlePath?.let { it != previous && File(it).isFile } == true
                }
                // Drain work queued by the real attachment callback, including
                // the old automatic-eviction regression on the fourth import.
                worker.submit { }.get(5, TimeUnit.SECONDS)
                val copy = File(requireNotNull(activity.session.media?.subtitlePath))
                copies += copy
                assertTrue(copy.setLastModified((index + 1) * 1_000L))
                if (index == 0) store.save(MediaSessionSnapshot(rememberedUri, "Première œuvre synthétique",
                    subtitlePath = copy.absolutePath, subtitleMime = "application/x-subrip"))
            }
            val rememberedPath = requireNotNull(store.get(rememberedUri)?.subtitlePath)
            assertEquals(copies.first().absolutePath, rememberedPath)
            assertTrue("Importing another work evicted a sidecar referenced by a saved session", File(rememberedPath).isFile)
            assertEquals(originalContents.first(), File(rememberedPath).readText())
            assertTrue("A previous private import was deleted", copies.all { it.isFile })
            assertEquals(originalContents, sources.map { it.readText() })
        } finally {
            owner.pause().stop().destroy()
            store.remove(rememberedUri)
            copies.forEach { it.delete() }
            sourceDirectory.deleteRecursively()
        }
    }
}
