package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.BlackBars
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.ImageCommand
import fr.soustitresvoix.core.ImageCommandDecision
import fr.soustitresvoix.core.ImageCommandDisposition
import fr.soustitresvoix.core.ImageCommandRejection
import fr.soustitresvoix.core.ImageCommandRequest
import fr.soustitresvoix.core.ImageSettings
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.PlaybackBenchmark
import fr.soustitresvoix.core.SpeechDurationModel
import fr.soustitresvoix.core.SubmittedImageAdjustments
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

/** Real session, Media3, video output, audio processor and subtitle source.
 * Delegates record commands without replacing those components. Only speech is
 * controlled, so an unfinished utterance can expose an unwanted stop/restart.
 * These tests prove submission and policy; rendered pixels remain covered by
 * the existing instrumented SDR test, and actual TTS IPC by ImageCommandsAndroidTest. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ImageCommandsRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetPreferences() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
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
    ).also {
        components.voice.publishReady()
        // Advance virtual time through the default initial hold/release posts.
        // A volume already equal to 1 is not evidence that those posts finished.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private fun submit(session: PlaybackSession, command: ImageCommand): ImageCommandDecision =
        session.imageCommands.submit(ImageCommandRequest(session.imageCommands.snapshot().context.generation, command))

    private fun accepted(session: PlaybackSession, command: ImageCommand): ImageCommandDecision.Accepted {
        val decision = submit(session, command)
        assertTrue("Expected acceptance, got $decision", decision is ImageCommandDecision.Accepted)
        return decision as ImageCommandDecision.Accepted
    }

    private fun flushEffects() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))

    private fun assertSubmitted(session: PlaybackSession, contrast: Float, brightness: Float): SubmittedImageAdjustments {
        val snapshot = session.imageCommands.snapshot()
        val submitted = requireNotNull(snapshot.lastSubmitted)
        assertEquals(snapshot.context.generation, submitted.generation)
        assertEquals(contrast, submitted.contrast, 0f)
        assertEquals(brightness, submitted.imageBrightness, 0f)
        assertTrue(submitted.sequence > 0)
        return submitted
    }

    private data class StableState(
        val narration: NarrationMachine.Snapshot,
        val queue: List<String>,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val volume: Float,
        val media: MediaItem?,
        val tracks: TrackSelectionParameters,
        val audioEnabled: Boolean,
        val audioSpeaking: Boolean,
        val preferences: Map<String, *>
    )

    private fun stableState(session: PlaybackSession) = StableState(
        session.machine.snapshot(), session.machine.pendingTexts(), session.player.currentPosition,
        session.player.playWhenReady, session.player.volume, session.player.currentMediaItem,
        session.player.trackSelectionParameters, session.movieAudio.enabled, session.movieAudio.speaking,
        context.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap()
    )

    private fun openTextOnly(session: PlaybackSession) {
        session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        session.open(SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}",
            "Synthetic image-command fixture"))
        await("The real Media3 source and text track did not become ready") {
            session.player.playbackState == Player.STATE_READY && session.selectedTextId() != null
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    /** Seed the real measurement object to isolate interruption policy from the
     * separate start-benchmark action, which intentionally refreshes and plays.
     * These fixture counters are not advertised as measured playback results. */
    private fun seedBenchmark(session: PlaybackSession) {
        assertTrue(session.playbackBenchmark.start(120_000,
            PlaybackBenchmark.Identity("image-command-fixture", "initial-image-settings", "JVM fixture",
                "controlled voice", "test", 0),
            PlaybackBenchmark.Counters(0, 0, session.player.currentPosition, 0, 0, 0)))
        session.playbackBenchmark.record(PlaybackBenchmark.Metric.FIRST_AUDIO, 123)
    }

    @Test fun acceptedCommandsReachOnlyVideoAndPreserveAnActiveUtterance() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            // Unknown source format keeps the existing policy; it is not proof
            // of SDR and must not fabricate an observed source characteristic.
            assertNull(session.diagnosticSnapshot().capabilities.single { it.id == "video.source_hdr" }.observed)
            session.previewVoice()
            await("The active preview did not reach its default ducked volume") {
                session.player.volume == session.prefs.movieVolume * session.prefs.duckVolume
            }
            val utterance = components.voice.requests.single()
            assertEquals(utterance.first, session.machine.snapshot().speakingId)
            val baseline = stableState(session)
            components.clearCalls()
            val publishedDuringSubmission = mutableListOf<SubmittedImageAdjustments?>()
            components.backend.afterEffects = { publishedDuringSubmission += session.imageCommands.snapshot().lastSubmitted }

            val adjustment = accepted(session, ImageCommand.SetAdjustments(.2f, .1f))
            assertTrue(adjustment.changed)
            assertEquals(ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION, adjustment.disposition)
            assertNull("Acceptance alone must not acknowledge a submission", session.imageCommands.snapshot().lastSubmitted)
            flushEffects()
            val first = assertSubmitted(session, .2f, .1f)
            assertEquals(listOf(Contrast::class.java, Brightness::class.java),
                components.backend.effects.single().map { it.javaClass })
            assertEquals(listOf<SubmittedImageAdjustments?>(null), publishedDuringSubmission)

            accepted(session, ImageCommand.SetOriginalComparison(true))
            flushEffects()
            val original = assertSubmitted(session, 0f, 0f)
            assertTrue(components.backend.effects.last().isEmpty())
            val masked = accepted(session, ImageCommand.SetAdjustments(.12f, .06f))
            assertEquals(ImageCommandDisposition.MASKED_BY_ORIGINAL_COMPARISON, masked.disposition)
            flushEffects()
            assertEquals("A retained request must not create a fictitious submission", original,
                session.imageCommands.snapshot().lastSubmitted)
            assertEquals(ImageSettings(.12f, .06f, true), session.imageCommands.snapshot().context.requested)
            accepted(session, ImageCommand.SetOriginalComparison(false))
            flushEffects()
            assertSubmitted(session, .12f, .06f)

            assertEquals(listOf("video.setEffects", "video.setEffects", "video.setEffects"), components.commands)
            assertEquals(listOf(null, first, original), publishedDuringSubmission)
            assertEquals(baseline, stableState(session))
            assertEquals(listOf(utterance), components.voice.requests)
            assertNull("Submitted image settings are not a pixel observation",
                session.diagnosticSnapshot().capabilities.single { it.id == "video.effects" }.observed)

            // The active-voice oracle must also survive a later explicit pause
            // and the old utterance's completion, without starting it again.
            session.pause()
            val paused = session.machine.snapshot()
            components.voice.complete(utterance.first)
            assertEquals(paused, session.machine.snapshot())
            assertFalse(session.player.playWhenReady)
            assertFalse(session.machine.snapshot().wantsPlayback)
            assertEquals(listOf(utterance), components.voice.requests)
        } finally { session.close() }
    }

    @Test fun invalidOrUnavailableCommandsAreAtomicAndDoNotInterruptTheBenchmark() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            accepted(session, ImageCommand.SetAdjustments(.1f, .05f))
            flushEffects()
            seedBenchmark(session)
            val baseline = stableState(session)
            val image = session.imageCommands.snapshot()
            val measurement = session.playbackBenchmark.snapshot()
            components.clearCalls()
            val invalid = listOf(
                ImageCommand.SetAdjustments(Float.NaN, .1f) to ImageCommandRejection.NON_FINITE_VALUE,
                ImageCommand.SetAdjustments(.1f, Float.POSITIVE_INFINITY) to ImageCommandRejection.NON_FINITE_VALUE,
                ImageCommand.SetAdjustments(Float.NEGATIVE_INFINITY, -.1f) to ImageCommandRejection.NON_FINITE_VALUE,
                ImageCommand.SetAdjustments(.31f, .1f) to ImageCommandRejection.OUT_OF_RANGE,
                ImageCommand.SetAdjustments(-.1f, -.21f) to ImageCommandRejection.OUT_OF_RANGE
            )
            for ((command, reason) in invalid) {
                assertEquals(ImageCommandDecision.Rejected(image.context.generation, reason), submit(session, command))
                assertEquals(image, session.imageCommands.snapshot())
                assertEquals(measurement, session.playbackBenchmark.snapshot())
            }
            for ((availability, reason) in listOf(
                CapabilityStatus.UNKNOWN to ImageCommandRejection.CAPABILITY_UNKNOWN,
                CapabilityStatus.UNAVAILABLE to ImageCommandRejection.CAPABILITY_UNAVAILABLE
            )) {
                components.backend.effectsAvailability = availability
                val before = session.imageCommands.snapshot()
                for (command in listOf(ImageCommand.SetAdjustments(.2f, .1f), ImageCommand.SetOriginalComparison(true))) {
                    assertEquals(ImageCommandDecision.Rejected(before.context.generation, reason), submit(session, command))
                    assertEquals(before, session.imageCommands.snapshot())
                    assertEquals(measurement, session.playbackBenchmark.snapshot())
                }
            }
            flushEffects()
            assertTrue("A rejected command interrupted the measurement", session.playbackBenchmark.active)
            assertEquals(baseline, stableState(session))
            assertEquals(emptyList<String>(), components.commands)
            assertTrue(components.backend.effects.isEmpty())
            assertTrue(components.voice.requests.isEmpty())
        } finally { session.close() }
    }

    @Test fun comparisonRetainsChoicesAndTheSubmissionRechecksHdrWithoutLosingFraming() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            openTextOnly(session)
            val time = event(session.player.currentTimeline)
            components.backend.reportFormat(time, sdrFormat())
            session.visual.setRenderSize(1280, 720)
            session.visual.setFramingMode(2)
            session.visual.crop(BlackBars.Margins(top = .1f, bottom = .1f))
            accepted(session, ImageCommand.SetAdjustments(.12f, .06f))
            flushEffects()
            assertSubmitted(session, .12f, .06f)
            assertTrue(components.backend.effects.last().any { it is Crop })

            accepted(session, ImageCommand.SetOriginalComparison(true))
            flushEffects()
            assertSubmitted(session, 0f, 0f)
            assertEquals("Original comparison must preserve the existing stretch geometry",
                listOf(Presentation::class.java), components.backend.effects.last().map { it.javaClass })
            assertEquals(ImageSettings(.12f, .06f, true), session.imageCommands.snapshot().context.requested)
            accepted(session, ImageCommand.SetOriginalComparison(false))
            flushEffects()
            assertSubmitted(session, .12f, .06f)
            assertTrue(components.backend.effects.last().any { it is Crop })

            val pending = accepted(session, ImageCommand.SetAdjustments(-.08f, .12f))
            assertEquals(ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION, pending.disposition)
            components.backend.reportFormat(time, hdrFormat())
            flushEffects()
            assertTrue(session.imageCommands.snapshot().context.hdrProtected)
            assertSubmitted(session, 0f, 0f)
            assertEquals("A format change before dispatch allowed an SDR correction through",
                listOf(Presentation::class.java), components.backend.effects.last().map { it.javaClass })
            val protected = session.imageCommands.snapshot()
            assertEquals(ImageCommandDecision.Rejected(protected.context.generation, ImageCommandRejection.HDR_PROTECTED),
                submit(session, ImageCommand.SetAdjustments(.2f, .1f)))
            assertEquals(protected, session.imageCommands.snapshot())
            val comparison = accepted(session, ImageCommand.SetOriginalComparison(true))
            assertEquals(ImageCommandDisposition.MASKED_BY_HDR, comparison.disposition)
            flushEffects()
            components.backend.reportFormat(time, sdrFormat())
            flushEffects()
            assertSubmitted(session, 0f, 0f)
            assertEquals(ImageSettings(-.08f, .12f, true), session.imageCommands.snapshot().context.requested)
            accepted(session, ImageCommand.SetOriginalComparison(false))
            flushEffects()
            assertSubmitted(session, -.08f, .12f)
            assertFalse(session.player.playWhenReady)
            assertTrue(components.voice.requests.isEmpty())
        } finally { session.close() }
    }

    @Test fun reopeningTheSameMediaAndClosingDiscardPendingCommandsAndStaleFormats() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val components = RecordingComponents()
        val session = create(components, activity)
        val video = PlayerView(activity).apply { player = session.player }
        val cinema = CinemaView(activity, session, session.prefs, video,
            exit = { }, settings = { }, subtitles = { }, audioTracks = { })
        activity.setContentView(cinema)
        try {
            openTextOnly(session)
            val firstTimeline = session.player.currentTimeline
            val firstGeneration = session.imageCommands.snapshot().context.generation
            cinema.imageDialog()
            val obsoleteDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val oldViews = descendants(requireNotNull(obsoleteDialog.window).decorView).toList()
            val comparison = oldViews.filterIsInstance<CheckBox>().single {
                it.text.toString() == "Comparer à l’original : désactiver les traitements"
            }
            val contrast = oldViews.filterIsInstance<Button>().single {
                it.text.toString() == "Contraste léger + / original (SDR seulement)"
            }
            components.clearCalls()
            accepted(session, ImageCommand.SetAdjustments(.2f, .1f))
            assertNull(session.imageCommands.snapshot().lastSubmitted)
            openTextOnly(session)
            val secondTimeline = session.player.currentTimeline
            val replacing = session.imageCommands.snapshot()
            assertTrue(replacing.context.generation > firstGeneration)
            assertEquals(ImageSettings(), replacing.context.requested)
            flushEffects()
            assertTrue("An old queued image correction was submitted to the replacement media",
                components.backend.effects.flatten().none { it is Contrast || it is Brightness })
            val beforeStaleCommand = session.imageCommands.snapshot()
            assertEquals(ImageCommandDecision.Rejected(firstGeneration, ImageCommandRejection.STALE_MEDIA),
                session.imageCommands.submit(ImageCommandRequest(firstGeneration, ImageCommand.SetAdjustments(.1f, .05f))))
            assertEquals(beforeStaleCommand, session.imageCommands.snapshot())

            // The actual old dialog retains the ticket from its opening, even
            // when the replacement has the same URI. Rejection must be visible
            // above that dialog, and a refused checkbox must restore its state.
            for (control in listOf(comparison, contrast)) {
                // CompoundButton toggles and notifies its checked listener before
                // returning super.performClick(), which may be false without an
                // OnClickListener. The visible refusal and intact state below
                // are the oracle for this actual action.
                control.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val refusal = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                try {
                    assertNotSame(obsoleteDialog, refusal)
                    assertTrue(refusal.isShowing)
                    val message = refusal.findViewById<TextView>(android.R.id.message)
                    assertEquals(View.VISIBLE, message.visibility)
                    assertEquals("La vidéo a changé : rouvre les réglages d’image", message.text.toString())
                    assertFalse("The rejected comparison remained checked", comparison.isChecked)
                    assertEquals(beforeStaleCommand, session.imageCommands.snapshot())
                } finally {
                    refusal.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }

            components.backend.reportFormat(event(secondTimeline), hdrFormat())
            assertTrue(session.imageCommands.snapshot().context.hdrProtected)
            components.backend.reportFormat(event(firstTimeline, secondTimeline), sdrFormat())
            assertTrue("An old format lifted the new media's HDR protection", session.imageCommands.snapshot().context.hdrProtected)
            components.backend.reportFormat(AnalyticsListener.EventTime(0, secondTimeline, 0, null, 0,
                secondTimeline, 0, null, 0, 0), sdrFormat())
            assertTrue("A callback without an attributable period lifted HDR protection",
                session.imageCommands.snapshot().context.hdrProtected)
            components.backend.reportFormat(event(secondTimeline), sdrFormat())
            assertFalse(session.imageCommands.snapshot().context.hdrProtected)
            accepted(session, ImageCommand.SetAdjustments(.12f, .06f))
            session.close()
            val closed = session.imageCommands.snapshot()
            assertTrue(closed.context.closed)
            assertTrue(closed.context.generation > replacing.context.generation)
            assertNull(closed.lastSubmitted)
            val submissionsAtClose = components.backend.effects.size
            flushEffects()
            assertEquals(submissionsAtClose, components.backend.effects.size)
            assertEquals(ImageCommandDecision.Rejected(closed.context.generation, ImageCommandRejection.CLOSED),
                submit(session, ImageCommand.SetOriginalComparison(true)))
            components.backend.reportFormat(event(secondTimeline), hdrFormat())
            assertEquals(closed, session.imageCommands.snapshot())
            assertTrue(components.voice.requests.isEmpty())
        } finally {
            cinema.close()
            video.player = null
            session.close()
            controller.pause().stop().destroy()
        }
    }

    @Test fun aBurstSubmitsOnlyItsFinalPlanAndIdenticalCommandsPreserveBenchmarkResults() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            accepted(session, ImageCommand.SetOriginalComparison(true))
            flushEffects()
            seedBenchmark(session)
            val baseline = stableState(session)
            components.clearCalls()
            assertFalse(accepted(session, ImageCommand.SetOriginalComparison(true)).changed)
            assertFalse(accepted(session, ImageCommand.SetAdjustments(0f, -0f)).changed)
            flushEffects()
            assertTrue(session.playbackBenchmark.active)
            assertTrue(components.commands.isEmpty())
            assertNull(session.imageCommands.snapshot().lastSubmitted)

            accepted(session, ImageCommand.SetOriginalComparison(false))
            val interrupted = requireNotNull(session.playbackBenchmark.snapshot())
            assertFalse(interrupted.active)
            assertFalse(interrupted.completed)
            assertEquals("Comparaison d’image modifiée : essai interrompu", interrupted.reason)
            assertEquals(123L, interrupted.latencies.getValue(PlaybackBenchmark.Metric.FIRST_AUDIO).p50)
            accepted(session, ImageCommand.SetAdjustments(.1f, .05f))
            accepted(session, ImageCommand.SetAdjustments(-.2f, .15f))
            accepted(session, ImageCommand.SetOriginalComparison(true))
            accepted(session, ImageCommand.SetAdjustments(.3f, -.2f))
            accepted(session, ImageCommand.SetOriginalComparison(false))
            val queued = session.imageCommands.snapshot()
            assertEquals(ImageSettings(.3f, -.2f, false), queued.context.requested)
            assertNull(queued.lastSubmitted)
            assertTrue(components.backend.effects.isEmpty())
            flushEffects()
            val submitted = assertSubmitted(session, .3f, -.2f)
            assertEquals(listOf("video.setEffects"), components.commands)
            assertEquals(1, components.backend.effects.size)
            assertEquals(listOf(Contrast::class.java, Brightness::class.java),
                components.backend.effects.single().map { it.javaClass })
            assertNull("Previously returned reports must stay immutable", queued.lastSubmitted)

            assertFalse(accepted(session, ImageCommand.SetAdjustments(.3f, -.2f)).changed)
            assertFalse(accepted(session, ImageCommand.SetOriginalComparison(false)).changed)
            flushEffects()
            assertEquals(submitted, session.imageCommands.snapshot().lastSubmitted)
            assertEquals(listOf("video.setEffects"), components.commands)
            assertEquals(interrupted, session.playbackBenchmark.snapshot())
            assertTrue(context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE)
                .getString("latest", "").orEmpty().contains(interrupted.reason))
            assertEquals(baseline, stableState(session))
            assertTrue(components.voice.requests.isEmpty())
        } finally { session.close() }
    }

    private fun sdrFormat() = Format.Builder().setSampleMimeType("video/avc").setWidth(640).setHeight(360)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR).build()).build()

    private fun hdrFormat() = Format.Builder().setSampleMimeType("video/hevc").setWidth(640).setHeight(360)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()

    /** Original and current timelines deliberately differ for delayed events. */
    private fun event(origin: Timeline, current: Timeline = origin) = AnalyticsListener.EventTime(
        0, origin, 0, MediaSource.MediaPeriodId(origin.getUidOfPeriod(0)), 0,
        current, 0, MediaSource.MediaPeriodId(current.getUidOfPeriod(0)), 0, 0
    )

    private class RecordingBackend(private val actual: PlaybackBackend, private val commands: MutableList<String>) : PlaybackBackend by actual {
        val effects = mutableListOf<List<Effect>>()
        val listeners = mutableListOf<AnalyticsListener>()
        var effectsAvailability: CapabilityStatus? = null
        var afterEffects: (() -> Unit)? = null
        override val player = record<Player>(actual.player, "player", commands)
        override val video = object : VideoOutput by record<VideoOutput>(actual.video, "video", commands) {
            override fun setEffects(effects: List<Effect>) {
                commands += "video.setEffects"
                actual.video.setEffects(effects)
                this@RecordingBackend.effects += effects.toList()
                afterEffects?.invoke()
            }
        }
        override fun diagnosticCapabilities() = actual.diagnosticCapabilities().map {
            if (it.id == "video.effects" && effectsAvailability != null) it.copy(status = effectsAvailability!!) else it
        }
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
        fun reportFormat(time: AnalyticsListener.EventTime, format: Format) {
            listeners.forEach { it.onVideoInputFormatChanged(time, format, null) }
        }
    }

    private class RecordingComponents : PlaybackComponents {
        val commands = mutableListOf<String>()
        lateinit var backend: RecordingBackend
        lateinit var voice: ControlledVoice
        fun clearCalls() { commands.clear(); backend.effects.clear() }
        override fun createNarration() = record<NarrationController>(ExistingNarrationController(), "narration", commands,
            setOf("snapshot", "pendingTexts", "pendingPlan"))
        override fun createMovieAudio() = record<MovieAudio>(Media3MovieAudio(), "audio", commands, setOf("diagnosticCapability"))
        override fun createVideoRenderer(): VideoRendererFactory = CinemaVideoRendererFactory()
        override fun createPlayback(context: Context, preferences: PlayerPreferences,
            movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend =
            RecordingBackend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer), commands).also { backend = it }
        override fun createSubtitles(context: Context) = record<SubtitleSource>(Media3SubtitleSource(context), "subtitles", commands,
            setOf("safeFailureMessage"))
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService {
            voice = ControlledVoice(onAvailability, onDone)
            return record<SpeechService>(voice, "speech", commands, setOf("engines"))
        }
    }

    private companion object {
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

    private class ControlledVoice(
        private val availability: (Boolean, String) -> Unit,
        private val done: (String) -> Unit
    ) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled JVM voice boundary"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "image-command-voice"
        override val engineDiagnosticIdentity = "image-command-voice"
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
        fun complete(id: String) = done(id)
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
