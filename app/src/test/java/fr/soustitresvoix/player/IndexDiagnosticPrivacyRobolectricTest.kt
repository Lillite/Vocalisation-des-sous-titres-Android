package fr.soustitresvoix.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Regression for a real FileDataSource failure and the actual diagnostic-copy UI.
 * No injected tracks, mocked exception, replacement indexer, or personal media.
 * The unrelated process-memory metric has an explicitly synthetic JVM shadow;
 * this test makes no statement about actual Android memory usage.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DiagnosticProcessMemoryShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class IndexDiagnosticPrivacyRobolectricTest {
    @Before fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    @Test fun aRealMissingFileFailureIsRaisedByTheActualIndexer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = "synthetic-private-title-${UUID.randomUUID()}.mkv"
        val missing = File(context.cacheDir, marker)
        assertFalse(missing.exists())
        val failure = runCatching {
            FullSubtitleIndexer(context).scan(SavedMedia(Uri.fromFile(missing).toString(), "Synthetic diagnostic fixture")) { }
        }.exceptionOrNull()
        assertNotNull("The actual FileDataSource unexpectedly opened a nonexistent file", failure)
        println("AUDIT real index exception: ${failure!!.javaClass.name}: ${failure.message}")
        assertTrue("The real filesystem failure did not establish the raw filename-bearing input for this regression",
            failure.message.orEmpty().contains(marker))
    }

    @Test fun copiedDiagnosticMustHonorItsNoFilenamePromiseAfterAnActualIndexFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = "synthetic-private-title-${UUID.randomUUID()}.mkv"
        val fixture = File(context.cacheDir, marker)
        context.resources.openRawResource(R.raw.demonstration).use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val session = activity.session
            // The real demuxer and text renderer remain active. Public track-selection
            // flags avoid making this filesystem/privacy check depend on hardware codecs.
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            session.open(SavedMedia(Uri.fromFile(fixture).toString(), "Synthetic diagnostic fixture"))
            await("The real player did not select the synthetic MKV text track; no privacy conclusion can be drawn") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() != null
            }
            assertTrue("The test could not remove its private synthetic copy", fixture.delete())
            assertFalse(fixture.exists())

            session.prepareRange(null)
            assertTrue("The real prepareRange did not launch its indexer", session.isPreparing)
            await("The real index worker did not publish its missing-file outcome") {
                !session.isPreparing && session.indexingStatus != "Indexation complète non demandée" &&
                    !session.indexingStatus.startsWith("Indexation complète en cours")
            }
            assertFalse("The missing file was unexpectedly indexed successfully",
                session.indexingStatus.startsWith("Indexation terminée"))
            val status = session.indexingStatus
            assertTrue("The index failure lost its visible reason", status.isNotBlank())
            assertTrue("The real activity no longer displays the index failure",
                descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == status })
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic regression sentinel", "No diagnostic copied yet"))

            descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == "Réglages de la voix et du confort"
            }.performClick()
            val settings = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            try {
                descendants(requireNotNull(settings.window).decorView).filterIsInstance<Button>().single {
                    it.text.toString() == "Copier le diagnostic complet"
                }.performClick()
                val notice = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                try {
                    assertNotSame(settings, notice)
                    assertTrue("The actual UI no longer makes the contract this regression checks",
                        descendants(requireNotNull(notice.window).decorView).filterIsInstance<TextView>().any {
                            it.text.toString().contains("aucun dialogue ou nom de fichier inclus")
                        })
                    val clip = requireNotNull(clipboard.primaryClip)
                    val copied = clip.getItemAt(0).text.toString()
                    assertEquals("Diagnostic local", clip.description.label.toString())
                    assertTrue("The copy omitted the actual index outcome", copied.contains(session.indexingStatus))
                    assertFalse("The copied diagnostic contains a synthetic private filename despite its explicit UI promise: $marker",
                        copied.contains(marker))
                } finally { notice.dismiss() }
            } finally { settings.dismiss() }
        } finally {
            controller.pause().stop().destroy()
            fixture.delete()
        }
    }
}
