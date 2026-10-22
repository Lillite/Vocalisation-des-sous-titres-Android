package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.DiagnosticValue
import fr.soustitresvoix.core.NarrationMachine
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual activity, Media3, diagnostic dialogs, clipboard and TTS IPC. The
 * service observes ALL synthesis requests, not only a subtitle prefix. Explicit
 * voice previews before and after the read-only actions are positive controls
 * for that observation channel; neither is part of the diagnostic itself. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackDiagnosticsAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    private data class PausedState(
        val narration: NarrationMachine.Snapshot,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val playbackState: Int,
        val volume: Float,
        val media: MediaItem?,
        val tracks: TrackSelectionParameters,
        val generation: Long,
        val preferences: Map<String, *>
    )

    private fun state(activity: MainActivity): PausedState {
        val session = activity.session
        return PausedState(session.machine.snapshot(), session.player.currentPosition,
            session.player.playWhenReady, session.player.playbackState, session.player.volume,
            session.player.currentMediaItem, session.player.trackSelectionParameters,
            session.diagnosticSnapshot().generation,
            activity.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap())
    }

    private fun resetClipboard(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // An artificial copied sentinel creates Android's clipboard preview,
            // which can intercept the later tap on the left-hand Refresh button.
            // Clearing creates no copied content and retains a fresh-copy oracle.
            clipboard.clearPrimaryClip()
        } else {
            // minSdk is 26; clearPrimaryClip is available from 28. These older
            // versions have no system clipboard preview to intercept the tap.
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic sentinel", "No diagnostic copied yet"))
        }
        assertClipboardReset(clipboard)
    }

    private fun assertClipboardReset(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertFalse("The clipboard must be empty until an explicit copy", clipboard.hasPrimaryClip())
        } else {
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals("Diagnostic sentinel", clip.description.label.toString())
            assertEquals("No diagnostic copied yet", clip.getItemAt(0).text.toString())
        }
    }

    @Test fun consultingRefreshingAndCopyingDiagnosticsDoesNotSynthesizeOrChangePausedPlayback() {
        val preview = "Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film."
        val requests = Collections.synchronizedList(mutableListOf<String>())
        fun observed() = synchronized(requests) { requests.toList() }
        val context = instrument.targetContext
        val preferences = PlayerPreferences(context).apply {
            enginePackage = instrument.context.packageName
            voiceName = "fixture-fr"
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.ALL_REQUESTS_ACTION) return
                intent.getStringExtra("text")?.let { requests += it }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = AtomicReference<MainActivity>()
                scenario.onActivity { current ->
                    activity.set(current)
                    current.session.open(SavedMedia(
                        "android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Synthetic diagnostics-only fixture"))
                }
                waitFor("The actual media, video decoder and fixture voice did not become ready") {
                    val session = activity.get().session
                    session.player.playbackState == Player.STATE_READY && session.speech.isReady &&
                        session.diagnosticSnapshot().capabilities.single { it.id == "video.decoder" }.observed != null
                }
                scenario.onActivity { current ->
                    assertFalse(current.session.prefs.cacheEnabled)
                    assertTrue("Paused media preparation must not synthesize", observed().isEmpty())
                    current.session.previewVoice()
                }
                waitFor("The first explicit voice preview did not reach the real engine and finish") {
                    observed().isNotEmpty() && activity.get().session.machine.snapshot().speakingId == null
                }
                assertEquals("The all-request observer must see even the non-subtitle preview", listOf(preview), observed())

                // The general settings panel already pauses on entry. Capture
                // the baseline after that existing action, never weaken it.
                onView(withText("Réglages de la voix et du confort")).perform(scrollTo(), click())
                Closeable {
                    onView(withText("Appliquer et fermer")).inRoot(isDialog()).perform(click())
                }.use {
                    var barrier: PlayerMessage? = null
                    scenario.onActivity { current ->
                        barrier = (current.session.playback as Media3PlaybackBackend).exoPlayer
                            .createMessage { _, _ -> }.send()
                    }
                    assertTrue("The playback thread did not acknowledge the paused baseline",
                        requireNotNull(barrier).blockUntilDelivered(20_000))
                    instrument.waitForIdleSync()
                    // TTS completion clears the speaking ID before the film's
                    // delayed hold/release fade has finished. The playback-thread
                    // barrier does not drain future posts on the session Handler.
                    // Keep the default fade and wait for its actual target before
                    // comparing the volume across read-only diagnostic actions.
                    waitFor("The film volume did not recover after the explicit voice preview") {
                        val session = activity.get().session
                        session.player.volume == session.prefs.movieVolume
                    }
                    var baseline: PausedState? = null
                    var decoder = ""
                    scenario.onActivity { current ->
                        baseline = state(current)
                        assertFalse(requireNotNull(baseline).playWhenReady)
                        assertFalse(requireNotNull(baseline).narration.wantsPlayback)
                        assertNull(requireNotNull(baseline).narration.speakingId)
                        decoder = (current.session.diagnosticSnapshot().capabilities.single { it.id == "video.decoder" }
                            .observed?.value as DiagnosticValue.Text).value
                        resetClipboard(current.getSystemService(ClipboardManager::class.java))
                    }
                    onView(withText("Mesures réelles de cette session")).inRoot(isDialog()).perform(scrollTo(), click())
                    Closeable {
                        onView(withText("Fermer")).inRoot(isDialog()).perform(click())
                    }.use {
                        onView(withText("Mesures locales — pas de télémétrie")).inRoot(isDialog())
                            .check(matches(isDisplayed()))
                        onView(withText("Actualiser")).inRoot(isDialog()).perform(click())
                        onView(withText("Mesures locales — pas de télémétrie")).inRoot(isDialog())
                            .check(matches(isDisplayed()))
                        scenario.onActivity { current ->
                            assertEquals(baseline, state(current))
                            assertClipboardReset(current.getSystemService(ClipboardManager::class.java))
                        }
                    }
                    onView(withText("Copier le diagnostic complet")).inRoot(isDialog()).perform(scrollTo(), click())
                    Closeable {
                        onView(withText("Fermer")).inRoot(isDialog()).perform(click())
                    }.use {
                        onView(withText("Diagnostic copié à ta demande ; aucun dialogue ou nom de fichier inclus."))
                            .inRoot(isDialog()).check(matches(isDisplayed()))
                        scenario.onActivity { current ->
                            val clipboard = current.getSystemService(ClipboardManager::class.java)
                            val clip = requireNotNull(clipboard.primaryClip)
                            val copied = clip.getItemAt(0).text.toString()
                            assertEquals("Diagnostic local", clip.description.label.toString())
                            assertTrue("The actual copy lost the current decoder observation", copied.contains(decoder))
                            assertFalse(copied.contains("Synthetic diagnostics-only fixture"))
                            assertEquals("Consultation or copying changed playback/narration/preferences", baseline, state(current))
                            assertEquals("The diagnostic triggered synthesis", listOf(preview), observed())
                            assertNull(current.session.player.playerError)
                            // Clean up only after validating the real clipboard
                            // contents. Clearing is not a dismissal of any system
                            // preview already displayed for that actual copy.
                            resetClipboard(clipboard)
                        }
                    }
                    // A second explicit request and its actual completion bound
                    // the IPC observation. A disconnected receiver or an unseen
                    // diagnostic-generated request cannot satisfy the final trace.
                    scenario.onActivity { current -> current.session.previewVoice() }
                    waitFor("The second explicit voice preview did not reach the real engine and finish") {
                        observed().size >= 2 && activity.get().session.machine.snapshot().speakingId == null
                    }
                    instrument.waitForIdleSync()
                    assertEquals("Only the two explicit controls may reach the actual speech service",
                        listOf(preview, preview), observed())
                }

                scenario.onActivity { current ->
                    val previous = current.session.diagnosticSnapshot()
                    assertNotNull(previous.capabilities.single { it.id == "video.decoder" }.observed)
                    current.session.open(SavedMedia(
                        "android.resource://${current.packageName}/${R.raw.demonstration}",
                        "Replacement synthetic diagnostics fixture"))
                    val replacement = current.session.diagnosticSnapshot()
                    assertTrue(replacement.generation > previous.generation)
                    assertNull("The old decoder must not be reported for a newly opened media",
                        replacement.capabilities.single { it.id == "video.decoder" }.observed)
                    assertFalse(current.session.player.playWhenReady)
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
            preferences.enginePackage = ""
        }
    }
}
