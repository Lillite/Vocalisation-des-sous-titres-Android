package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.DiagnosticSource
import fr.soustitresvoix.core.DiagnosticValue
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.PlaybackDiagnosticSnapshot
import fr.soustitresvoix.core.SpeechDurationModel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration
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

/** Real session, Media3 and diagnostic buttons. Delegates record boundary calls;
 * they do not replace the player, source or renderer. The voice boundary is
 * controlled here; the Android test verifies actual synthesis IPC separately.
 * The existing, explicitly synthetic PSS shadow is unrelated to these oracles. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DiagnosticProcessMemoryShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackDiagnosticsRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetPreferences() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true)
            .putBoolean("spectralDucking", true).putInt("duckAttack", 0)
            .putInt("duckRelease", 0).putInt("duckHold", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun create(components: RecordingComponents, owner: Context = context) = PlaybackSession(
        owner, PlayerPreferences(owner), onChanged = { }, onMessage = { _, _ -> },
        onCaption = { }, components = components
    ).also { components.voice.publishReady() }

    private fun demonstration(title: String = "Synthetic diagnostics fixture") = SavedMedia(
        "android.resource://${context.packageName}/${R.raw.demonstration}", title
    )

    private fun openTextOnly(session: PlaybackSession) {
        session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        session.open(demonstration())
        await("The actual Media3 source and text track did not become ready") {
            session.player.playbackState == Player.STATE_READY && session.selectedTextId() != null
        }
    }

    private fun capability(snapshot: PlaybackDiagnosticSnapshot, id: String): CapabilityDiagnostic =
        snapshot.capabilities.single { it.id == id }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun click(column: LinearLayout, label: String) {
        assertTrue(descendants(column).filterIsInstance<Button>().single { it.text.toString() == label }.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun readingRefreshingAndCopyingTheRealDiagnosticDispatchNoPlaybackOrVoiceCommands() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val components = RecordingComponents()
        val session = create(components, activity)
        try {
            openTextOnly(session)
            val decoder = "fixture.observed.read-only.decoder"
            components.backend.reportDecoder(event(session.player.currentTimeline), decoder)
            session.previewVoice()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, components.voice.requests.size)
            assertNotNull("The command oracle must include an active utterance", session.machine.snapshot().speakingId)

            // Exercise the production buttons directly. Entering MainActivity's
            // general settings already pauses by design; that is not a diagnostic read.
            val column = LinearLayout(activity)
            AdvancedSettings(activity, session, session.prefs).populate(column)
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Sentinel", "Not copied yet"))
            val state = session.machine.snapshot()
            val queued = session.machine.pendingTexts()
            val preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap()
            val generation = session.diagnosticSnapshot().generation
            val playbackIntent = session.player.playWhenReady
            val volume = session.player.volume
            val media = session.player.currentMediaItem
            components.commands.clear()

            repeat(3) {
                val snapshot = session.diagnosticSnapshot()
                assertEquals(generation, snapshot.generation)
                assertEquals(DiagnosticValue.Text(decoder), capability(snapshot, "video.decoder").observed?.value)
                assertTrue(session.diagnostics().contains(decoder))
            }
            click(column, "Mesures réelles de cette session")
            val first = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            first.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val refreshed = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            try { assertNotSame("Actualiser must exercise a fresh diagnostic read", first, refreshed) }
            finally { refreshed.dismiss(); first.dismiss() }
            click(column, "Copier le diagnostic complet")
            val notice = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            try {
                val clip = requireNotNull(clipboard.primaryClip)
                assertEquals("Diagnostic local", clip.description.label.toString())
                assertTrue("The real copy button omitted the observed decoder", clip.getItemAt(0).text.toString().contains(decoder))
                assertEquals("Diagnostic consultation dispatched commands: ${components.commands}", emptyList<String>(), components.commands)
                assertEquals(state, session.machine.snapshot())
                assertEquals(queued, session.machine.pendingTexts())
                assertEquals(preferences, context.getSharedPreferences("player", Context.MODE_PRIVATE).all)
                assertEquals(playbackIntent, session.player.playWhenReady)
                assertEquals(volume, session.player.volume, 0f)
                assertEquals(media, session.player.currentMediaItem)
                assertEquals(1, components.voice.requests.size)
            } finally { notice.dismiss() }
        } finally {
            session.close()
            activityController.pause().stop().destroy()
        }
    }

    @Test fun requestedSettingsNeverBecomeObservedDecoderCadenceOrInstantaneousAudioProof() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            // Deliberately different requested/configured states, before opening
            // media. Reading the report must neither apply these preferences nor
            // pretend a decoder or measured display cadence exists.
            session.prefs.putBool("preferSoftware", true)
            session.prefs.putBool("matchFrameRate", true)
            session.video.frameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            session.movieAudio.enabled = false
            components.commands.clear()
            val snapshot = session.diagnosticSnapshot()
            val decoder = capability(snapshot, "video.decoder")
            assertEquals(DiagnosticValue.Flag(true), decoder.requested?.value)
            assertEquals(DiagnosticSource.USER_PREFERENCE, decoder.requested?.source)
            assertNull("A preference does not initialize a decoder", decoder.observed)
            assertNull("No declaration exists for an uninitialized decoder", capability(snapshot, "video.decoder_kind").observed)

            val cadence = capability(snapshot, "video.frame_rate")
            assertEquals(DiagnosticValue.Flag(true), cadence.requested?.value)
            assertEquals(DiagnosticSource.USER_PREFERENCE, cadence.requested?.source)
            assertNotNull(cadence.configured)
            assertEquals(DiagnosticSource.PLAYER_CONFIGURATION, cadence.configured?.source)
            assertNull("Configured strategy is not an observed display refresh rate", cadence.observed)
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, session.video.frameRateStrategy)

            val audio = capability(snapshot, "audio.spectral_ducking")
            assertEquals(DiagnosticValue.Flag(true), audio.requested?.value)
            assertEquals(DiagnosticValue.Flag(false), audio.configured?.value)
            assertEquals(DiagnosticValue.Count(0), audio.observed?.value)
            assertEquals(DiagnosticSource.AUDIO_PROCESSOR, audio.observed?.source)
            assertFalse("The cumulative metric needs an explicit scope", audio.observed?.detail.isNullOrBlank())
            assertTrue("The sample counter must be identified as cumulative",
                audio.observed?.detail.orEmpty().contains("cumul", ignoreCase = true))
            assertEquals(emptyList<String>(), components.commands)
            assertTrue(components.voice.requests.isEmpty())
            assertFalse(session.player.playWhenReady)
        } finally { session.close() }
    }

    @Test fun oldMediaEventsCannotPopulateANewGenerationAndClosedSnapshotsStayInvalidated() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            openTextOnly(session)
            val firstTimeline = session.player.currentTimeline
            val firstTag = firstTimeline.getWindow(0, Timeline.Window()).mediaItem.localConfiguration?.tag
            assertNotNull("Every opening needs an opaque identity", firstTag)
            components.backend.reportDecoder(event(firstTimeline), "fixture.first.decoder")
            components.backend.reportFormat(event(firstTimeline), Format.Builder().setSampleMimeType("video/avc")
                .setWidth(320).setHeight(180).setFrameRate(24f)
                .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR).build()).build())
            val first = session.diagnosticSnapshot()
            assertEquals(DiagnosticValue.Text("fixture.first.decoder"), capability(first, "video.decoder").observed?.value)
            val firstFormat = requireNotNull(capability(first, "video.format").observed)
            assertEquals(DiagnosticSource.MEDIA_FORMAT, firstFormat.source)
            assertTrue((firstFormat.value as DiagnosticValue.Text).value.contains("320 × 180"))
            assertEquals(DiagnosticValue.Flag(false), capability(first, "video.source_hdr").observed?.value)

            // Same URI is intentional: URL equality cannot protect two openings.
            session.open(demonstration("Replacement synthetic diagnostics fixture"))
            val replacing = session.diagnosticSnapshot()
            assertTrue(replacing.generation > first.generation)
            assertNull(capability(replacing, "video.decoder").observed)
            assertNull(capability(replacing, "video.format").observed)
            assertNull(capability(replacing, "video.source_hdr").observed)
            await("The replacement real source did not become ready") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() != null
            }
            val secondTimeline = session.player.currentTimeline
            val secondTag = secondTimeline.getWindow(0, Timeline.Window()).mediaItem.localConfiguration?.tag
            assertNotNull(secondTag)
            assertNotSame("A repeated URI must still receive a new opaque opening token", firstTag, secondTag)
            components.backend.reportDecoder(event(firstTimeline, secondTimeline), "fixture.stale.decoder")
            components.backend.reportFormat(event(firstTimeline, secondTimeline), Format.Builder().setSampleMimeType("video/hevc")
                .setWidth(1920).setHeight(1080)
                .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build())
            assertNull("An old event must not be relabeled with the current generation",
                capability(session.diagnosticSnapshot(), "video.decoder").observed)
            assertNull("An old format must not become the new media's metadata",
                capability(session.diagnosticSnapshot(), "video.format").observed)
            assertNull(capability(session.diagnosticSnapshot(), "video.source_hdr").observed)
            // Analytics can fall back to the current window when the original
            // period is unknown. A current tag alone does not establish origin.
            val unattributable = AnalyticsListener.EventTime(0, secondTimeline, 0, null, 0,
                secondTimeline, 0, null, 0, 0)
            components.backend.reportDecoder(unattributable, "fixture.unattributable.decoder")
            assertNull(capability(session.diagnosticSnapshot(), "video.decoder").observed)
            components.backend.reportDecoder(event(secondTimeline), "fixture.second.decoder")
            components.backend.reportFormat(event(secondTimeline), Format.Builder().setSampleMimeType("video/hevc")
                .setWidth(640).setHeight(360).setFrameRate(30f).build())
            val second = session.diagnosticSnapshot()
            assertEquals(DiagnosticValue.Text("fixture.second.decoder"), capability(second, "video.decoder").observed?.value)
            assertTrue((capability(second, "video.format").observed?.value as DiagnosticValue.Text).value.contains("640 × 360"))
            assertEquals("Previously returned snapshots must remain immutable",
                DiagnosticValue.Text("fixture.first.decoder"), capability(first, "video.decoder").observed?.value)
            assertEquals(firstFormat, capability(first, "video.format").observed)

            session.close()
            val closed = session.diagnosticSnapshot()
            assertTrue(closed.closed)
            assertTrue(closed.generation > second.generation)
            assertTrue(closed.capabilities.isEmpty())
            components.backend.reportDecoder(event(secondTimeline), "fixture.after-close.decoder")
            val afterCallback = session.diagnosticSnapshot()
            assertTrue(afterCallback.closed)
            assertEquals(closed.generation, afterCallback.generation)
            assertEquals(closed.capabilities, afterCallback.capabilities)
        } finally { session.close() }
    }

    /** Event metadata uses actual timelines from Media3, including the source tag.
     * For a late callback, currentTimeline is NEW but timeline is still OLD. */
    private fun event(origin: Timeline, current: Timeline = origin) = AnalyticsListener.EventTime(
        0, origin, 0, MediaSource.MediaPeriodId(origin.getUidOfPeriod(0)), 0,
        current, 0, MediaSource.MediaPeriodId(current.getUidOfPeriod(0)), 0, 0
    )

    private class RecordingBackend(private val actual: PlaybackBackend, private val commands: MutableList<String>) : PlaybackBackend by actual {
        val listeners = mutableListOf<AnalyticsListener>()
        override val player = record<Player>(actual.player, "player", commands)
        override val video = record<VideoOutput>(actual.video, "video", commands)
        override fun addAnalyticsListener(listener: AnalyticsListener) {
            commands += "backend.addAnalyticsListener"
            listeners += listener
            actual.addAnalyticsListener(listener)
        }
        override fun setMediaSource(source: MediaSource, startPositionMs: Long) {
            commands += "backend.setMediaSource"
            actual.setMediaSource(source, startPositionMs)
        }
        override fun close() { commands += "backend.close"; actual.close() }
        fun reportDecoder(time: AnalyticsListener.EventTime, name: String) {
            listeners.forEach { it.onVideoDecoderInitialized(time, name, 0, 0) }
        }
        fun reportFormat(time: AnalyticsListener.EventTime, format: Format) {
            listeners.forEach { it.onVideoInputFormatChanged(time, format, null) }
        }
    }

    private class RecordingComponents : PlaybackComponents {
        val commands = mutableListOf<String>()
        lateinit var backend: RecordingBackend
        lateinit var voice: ControlledVoice
        override fun createNarration() = record<NarrationController>(ExistingNarrationController(), "narration", commands,
            setOf("snapshot", "pendingTexts", "pendingPlan"))
        override fun createMovieAudio() = record<MovieAudio>(Media3MovieAudio(), "audio", commands,
            setOf("diagnosticCapability"))
        override fun createVideoRenderer(): VideoRendererFactory = CinemaVideoRendererFactory()
        override fun createPlayback(context: Context, preferences: PlayerPreferences,
            movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend =
            RecordingBackend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer), commands).also { backend = it }
        override fun createSubtitles(context: Context) = record<SubtitleSource>(Media3SubtitleSource(context), "subtitles", commands,
            setOf("safeFailureMessage"))
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService {
            voice = ControlledVoice(onAvailability)
            return record<SpeechService>(voice, "speech", commands, setOf("engines"))
        }
    }

    private companion object {
        /** Forward every call to its real implementation. Record all non-query
         * interface methods so a new command cannot silently escape the oracle. */
        inline fun <reified T : Any> record(actual: T, name: String, commands: MutableList<String>,
            extraQueries: Set<String> = emptySet()): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java)
        ) { _, method, arguments ->
            if (!method.name.startsWith("get") && !method.name.startsWith("is") &&
                method.name !in extraQueries && method.name !in setOf("equals", "hashCode", "toString")) {
                commands += "$name.${method.name}"
            }
            try { method.invoke(actual, *(arguments ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        } as T
    }

    private class ControlledVoice(private val availability: (Boolean, String) -> Unit) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled JVM voice boundary"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "diagnostic-test-voice"
        override val engineDiagnosticIdentity = "diagnostic-test-voice"
        override val choices = emptyList<SpeechVoiceChoice>()
        override val language = "fr"
        override var isReady = false; private set
        override val hits = 0
        override val misses = 0
        override val preparedCount = 0
        override val lastSynthesisMs = 0L
        override val lastAudioMs = 0L
        override val lastFactor = 1f
        override val cacheSize = 0L
        override val preparationStatus = "No real synthesis in this JVM test"
        fun publishReady() { isReady = true; availability(true, "Controlled voice ready") }
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
            remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) {
            requests += id to text
        }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) = Unit
        override fun prepareAhead(texts: List<String>) = Unit
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() = Unit
        override fun clearCache() = Unit
        override fun close() = Unit
    }
}
