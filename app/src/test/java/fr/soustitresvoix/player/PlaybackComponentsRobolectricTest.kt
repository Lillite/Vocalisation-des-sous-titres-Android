package fr.soustitresvoix.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.SpeechDurationModel
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real session, Media3 factories, demuxing and indexer. Only voice output is
 * controlled at its new public boundary; the Android suite checks real TTS IPC.
 * Audio/video tracks are disabled only in text-index tests, not replaced by mocks. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackComponentsRobolectricTest {
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

    private fun create(components: ObservedComponents): PlaybackSession = PlaybackSession(
        context, PlayerPreferences(context), onChanged = { }, onMessage = { _, _ -> },
        onCaption = { }, components = components
    ).also { components.speech.publishReady() }

    private fun textOnly(session: PlaybackSession) {
        session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
    }

    private fun demonstration() = SavedMedia(
        "android.resource://${context.packageName}/${R.raw.demonstration}", "Synthetic modularity fixture"
    )

    @Test fun realCompositionRoutesNarrationDuckingAndImageChangesThroughOwnedComponents() {
        val components = ObservedComponents()
        val session = create(components)
        try {
            assertEquals(setOf("narration", "movie audio", "video renderer", "playback", "subtitles", "speech"),
                components.created.toSet())
            assertEquals(6, components.created.size)
            assertSame(components.narration, session.machine)
            assertSame(components.audio, session.movieAudio)
            assertSame(components.audio, components.audioPassedToBackend)
            assertSame(components.renderer, components.rendererPassedToBackend)
            assertSame(components.backend.player, session.player)
            assertSame(components.backend.video, session.video)
            assertSame(components.speech, session.speech)
            assertEquals(1, components.audio.sinkCreations)
            assertEquals(1, components.renderer.creations)
            assertTrue(session.movieAudio.enabled)

            session.prefs.movieVolume = .6f
            session.prefs.duckVolume = .25f
            session.previewVoice()
            shadowOf(Looper.getMainLooper()).idle()
            val request = components.speech.requests.single()
            assertEquals("Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film.", request.text)
            assertEquals(request.id, session.machine.snapshot().speakingId)
            assertTrue(components.audio.speaking)
            assertEquals(.15f, session.player.volume, .0001f)

            session.visual.adjust(.2f, .1f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
            assertTrue(components.backend.effects.last().any { it is Contrast })
            assertTrue(components.backend.effects.last().any { it is Brightness })
            session.visual.toggleComparison()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
            assertTrue(components.backend.effects.last().isEmpty())

            session.pause()
            shadowOf(Looper.getMainLooper()).idle()
            components.speech.complete(request.id)
            assertFalse(session.machine.snapshot().wantsPlayback)
            assertFalse(session.player.playWhenReady)
            assertFalse(components.audio.speaking)
            assertEquals(.6f, session.player.volume, .0001f)
            assertTrue(components.speech.stops > 0)
            session.prefs.putBool("matchFrameRate", false)
            session.refreshSettings()
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, session.video.frameRateStrategy)
        } finally {
            session.close()
            session.close()
        }
        assertEquals(1, components.backend.closes)
        assertEquals(1, components.speech.closes)
    }

    @Test fun realSelectedAssTrackAndCompleteIndexUseTheInjectedSubtitleSource() {
        val source = File(context.cacheDir, "modular-source.ass")
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssa/full_persistent_sign.ass")).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val components = ObservedComponents()
        val session = create(components)
        try {
            textOnly(session)
            val media = demonstration().copy(subtitlePath = source.path,
                subtitleMime = androidx.media3.common.MimeTypes.TEXT_SSA)
            session.open(media)
            await("The real Media3 text renderer did not select the external fixture") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() == "external-subtitle"
            }
            assertEquals(1, components.subtitles.liveCallbacks.size)
            assertEquals(1, components.backend.sourceChanges)
            assertTrue("Paused extraction must not speak", components.speech.requests.isEmpty())
            session.prepareRange(null)
            await("The real index worker did not prepare the selected track") {
                components.speech.batches.isNotEmpty()
            }
            assertEquals(1, components.subtitles.indexers)
            assertEquals(listOf(media), components.subtitles.scans.toList())
            assertEquals(listOf("ACCÈS INTERDIT", "Bonjour.", "Nous partons.", "Très bien."),
                components.speech.batches.single())
            val indexed = session.ahead.selected("external-subtitle")
            assertEquals(listOf("ACCÈS INTERDIT", "Bonjour.", "Nous partons.", "Très bien."), indexed.map { it.raw })
            assertEquals(4, indexed.map { it.identity }.distinct().size)
            assertTrue(session.indexingStatus.startsWith("Indexation terminée"))
            assertTrue("Preparing speech is not permission to speak", components.speech.requests.isEmpty())
            assertFalse(session.player.playWhenReady)
        } finally {
            session.close()
            source.delete()
        }
    }

    @Test fun obsoleteLiveExtractionAndLateVoiceCallbacksCannotReviveReplacedOrClosedMedia() {
        val components = ObservedComponents()
        val session = create(components)
        try {
            textOnly(session)
            session.open(demonstration())
            val obsolete = components.subtitles.liveCallbacks.single()
            // This represents a genuine race at the documented extractor boundary:
            // the old callback has posted to main, but the media changes first.
            obsolete("obsolete", group("Ancien panneau."), null)
            session.open(demonstration().copy(title = "Replacement synthetic fixture"))
            val current = components.subtitles.liveCallbacks.last()
            current("current", group("Nouveau panneau."), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.ahead.selected("obsolete").isEmpty())
            assertEquals(listOf("Nouveau panneau."), session.ahead.selected("current").map { it.raw })
            assertFalse(session.machine.snapshot().wantsPlayback)
            assertTrue(components.speech.requests.isEmpty())

            session.previewVoice()
            val oldVoice = components.speech.requests.single().id
            session.close()
            val closedState = session.machine.snapshot()
            current("after-close", group("Ne doit jamais entrer."), null)
            components.speech.complete(oldVoice)
            components.speech.fail(oldVoice)
            components.speech.publishReady()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(closedState, session.machine.snapshot())
            assertTrue(session.ahead.selected("after-close").isEmpty())
            assertEquals(1, components.speech.requests.size)
            assertEquals(1, components.backend.closes)
            assertEquals(1, components.speech.closes)
        } finally { session.close() }
    }

    private fun group(text: String) = CuesWithTiming(listOf(Cue.Builder().setText(text).build()), 1_000_000, 2_000_000)

    private class ObservedMovieAudio(private val delegate: MovieAudio = Media3MovieAudio()) : MovieAudio by delegate {
        var sinkCreations = 0
        override fun createSink(context: Context): AudioSink {
            sinkCreations++
            return delegate.createSink(context)
        }
    }

    private class ObservedRenderer(private val delegate: VideoRendererFactory = CinemaVideoRendererFactory()) : VideoRendererFactory {
        var creations = 0
        override fun create(builder: MediaCodecVideoRenderer.Builder): Renderer {
            creations++
            return delegate.create(builder)
        }
    }

    private class ObservedBackend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        var closes = 0
        var sourceChanges = 0
        val effects = mutableListOf<List<Effect>>()
        override val video: VideoOutput = object : VideoOutput by delegate.video {
            override fun setEffects(effects: List<Effect>) {
                this@ObservedBackend.effects += effects.toList()
                delegate.video.setEffects(effects)
            }
        }
        override fun setMediaSource(source: MediaSource, startPositionMs: Long) {
            sourceChanges++
            delegate.setMediaSource(source, startPositionMs)
        }
        override fun close() { closes++; delegate.close() }
    }

    private class ObservedSubtitles(context: Context) : SubtitleSource {
        private val delegate = Media3SubtitleSource(context)
        val liveCallbacks = mutableListOf<IndexedCueListener>()
        val scans = java.util.Collections.synchronizedList(mutableListOf<SavedMedia>())
        var indexers = 0
        override fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource {
            liveCallbacks += onCue
            return delegate.createMediaSource(item, onCue)
        }
        override fun createIndexer(): SubtitleIndexer {
            indexers++
            val actual = delegate.createIndexer()
            return object : SubtitleIndexer by actual {
                override fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
                    scans += media
                    return actual.scan(media, progress)
                }
            }
        }
        override fun safeFailureMessage(error: Exception) = delegate.safeFailureMessage(error)
    }

    private class ObservedComponents : PlaybackComponents {
        val created = mutableListOf<String>()
        val narration: NarrationController = ExistingNarrationController()
        val audio = ObservedMovieAudio()
        val renderer = ObservedRenderer()
        lateinit var backend: ObservedBackend
        lateinit var subtitles: ObservedSubtitles
        lateinit var speech: ControlledSpeech
        var audioPassedToBackend: MovieAudio? = null
        var rendererPassedToBackend: VideoRendererFactory? = null
        override fun createNarration(): NarrationController { created += "narration"; return narration }
        override fun createMovieAudio(): MovieAudio { created += "movie audio"; return audio }
        override fun createVideoRenderer(): VideoRendererFactory { created += "video renderer"; return renderer }
        override fun createPlayback(context: Context, preferences: PlayerPreferences,
            movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend {
            created += "playback"
            audioPassedToBackend = movieAudio
            rendererPassedToBackend = videoRenderer
            return ObservedBackend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer))
                .also { backend = it }
        }
        override fun createSubtitles(context: Context): SubtitleSource {
            created += "subtitles"
            return ObservedSubtitles(context).also { subtitles = it }
        }
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService {
            created += "speech"
            return ControlledSpeech(onAvailability, onDone, onFailure).also { speech = it }
        }
    }

    /** Intentional voice-boundary double: no Android TTS or acoustic claim here. */
    private class ControlledSpeech(
        private val availability: (Boolean, String) -> Unit,
        private val done: (String) -> Unit, private val failure: (String, String) -> Unit
    ) : SpeechService {
        data class Request(val id: String, val text: String)
        val requests = mutableListOf<Request>()
        val batches = mutableListOf<List<String>>()
        var stops = 0
        var closes = 0
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled test voice boundary"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "test-voice"
        override val engineDiagnosticIdentity = "controlled-test-voice"
        override val choices = emptyList<SpeechVoiceChoice>()
        override val language = "fr"
        override var isReady = false; private set
        override val hits = 0
        override val misses = 0
        override val preparedCount get() = batches.sumOf { it.size }
        override val lastSynthesisMs = 0L
        override val lastAudioMs = 0L
        override val lastFactor = 1f
        override val cacheSize = 0L
        override val preparationStatus = "No real synthesis in this JVM test"
        fun publishReady() { isReady = true; availability(true, "Controlled voice ready") }
        fun complete(id: String) = done(id)
        fun fail(id: String) = failure(id, "Controlled late failure")
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
            remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) {
            requests += Request(id, text)
        }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) { batches += texts.toList() }
        override fun prepareAhead(texts: List<String>) = Unit
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() { stops++ }
        override fun clearCache() = Unit
        override fun close() { closes++ }
    }
}
