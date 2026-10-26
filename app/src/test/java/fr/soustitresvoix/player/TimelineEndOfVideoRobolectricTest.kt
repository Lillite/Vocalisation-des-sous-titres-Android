package fr.soustitresvoix.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechDurationModel
import fr.soustitresvoix.core.SubtitleTimeline
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

/** Real PlaybackSession, Media3 loading/indexing and source IDs. A controlled
 * public player clock and voice callbacks make exact boundary cases reproducible;
 * no acoustic, device-performance or TalkBack result is claimed. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class TimelineEndOfVideoRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearOwnedSettings() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true)
            .putBoolean("integralReading", true).putString("mode", NarrationMachine.Mode.COMFORT.name)
            .putInt("duckAttack", 0).putInt("duckRelease", 0).putInt("duckHold", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "media-sessions-v1").deleteRecursively()
        File(context.filesDir, "narration-journals-v1").deleteRecursively()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (predicate()) return
            Thread.sleep(5)
        }
        fail(message)
    }

    private inner class Fixture(text: String = "1\n00:00:27,000 --> 00:00:28,000\nLa dernière réplique entière.\n") : AutoCloseable {
        val components = Components()
        val messages = mutableListOf<Pair<String, Boolean>>()
        val captions = mutableListOf<String>()
        val session = PlaybackSession(context, PlayerPreferences(context), {}, { text, error -> messages += text to error },
            { captions += it }, components)
        val file = File.createTempFile("timing-synthetic-", ".srt", context.cacheDir).apply { writeText(text) }
        val media = SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}", "Timing fixture",
            subtitlePath = file.path, subtitleMime = MimeTypes.APPLICATION_SUBRIP)
        init {
            components.voice.ready()
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            session.open(media, restoreSession = false)
            await("Synthetic subtitle track did not become ready") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() == "external-subtitle"
            }
            var indexed = false
            session.ensureCompleteIndex { indexed = true }
            await("Complete source index unavailable: ${messages.lastOrNull()}") { indexed }
            assertTrue(session.hasCompleteSubtitleIndex)
            assertTrue(components.voice.requests.isEmpty())
        }
        fun features(): PlaybackFeatures = PlaybackSession::class.java.getDeclaredField("features")
            .apply { isAccessible = true }.get(session) as PlaybackFeatures
        fun timeline(value: SubtitleTimeline) { features().applyTimeline(value) }
        fun at(position: Long) {
            components.backend.positionMs = position
            PlaybackSession::class.java.getDeclaredMethod("driveIndexedCues").apply { isAccessible = true }.invoke(session)
        }
        fun drain() {
            var guard = 0
            while (session.machine.snapshot().speakingId != null) {
                assertTrue("Source narration failed to drain", ++guard < 1000)
                components.voice.complete(session.machine.snapshot().speakingId!!)
            }
        }
        fun display() = session.displayedCues.map { it.text.toString() }
        override fun close() { session.close(); file.delete() }
    }

    @Test fun narrationOffsetBeyondVideoEndStillFinishesTheDetectedLastOccurrence() {
        Fixture().use { fixture ->
            fixture.timeline(SubtitleTimeline(narrationOffsetMs = 5000))
            fixture.session.play(); fixture.at(0); fixture.at(26_000)
            assertTrue(fixture.components.voice.requests.isEmpty())
            fixture.components.backend.endAt(28_000)
            fixture.drain()
            assertEquals(listOf("La dernière réplique entière."), fixture.components.voice.requests.map { it.second })
            assertEquals(1, fixture.session.machine.journalSnapshot().completedCount)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.player.playWhenReady)
        }
    }

    @Test fun positiveDriftAndOffsetPastEndRetainSimultaneousRealRepeatedOccurrences() {
        val subtitles = "1\n00:00:27,000 --> 00:00:28,000\nOui.\n\n2\n00:00:27,000 --> 00:00:28,000\nOui.\n"
        Fixture(subtitles).use { fixture ->
            fixture.timeline(SubtitleTimeline(globalOffsetMs = 1000, anchors = listOf(
                SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(20_000, 25_000))))
            fixture.session.play(); fixture.at(0); fixture.at(26_000)
            fixture.components.backend.endAt(28_000)
            fixture.drain()
            assertEquals(listOf("Oui.", "Oui."), fixture.components.voice.requests.map { it.second })
            assertEquals(2, fixture.session.machine.journalSnapshot().completedCount)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
        }
    }

    @Test fun largeEndDrainYieldsBetweenBatchesAndAnExplicitPauseStopsItsContinuation() {
        Fixture(manySubtitles()).use { fixture ->
            fixture.timeline(SubtitleTimeline(globalOffsetMs = 60_000))
            fixture.session.play(); fixture.at(0)
            fixture.components.backend.endAt(28_000)
            val firstBatch = fixture.session.machine.journalSnapshot().pendingCount
            assertTrue("EOF dispatch must yield before enqueuing the entire track", firstBatch in 1 until 300)
            fixture.session.pause()
            val pausedPending = fixture.session.machine.snapshot().pendingCount
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals(pausedPending, fixture.session.machine.snapshot().pendingCount)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertEquals(1, fixture.components.voice.requests.size)

            fixture.session.play()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            fixture.drain()
            assertEquals(300, fixture.components.voice.requests.map { it.second }.distinct().size)
            assertEquals(300, fixture.session.machine.journalSnapshot().completedCount)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
        }
    }

    @Test fun seekInvalidatesAnAlreadyScheduledEndDrainWithoutReplayingItsOldOccurrences() {
        Fixture(manySubtitles()).use { fixture ->
            fixture.timeline(SubtitleTimeline(globalOffsetMs = 60_000))
            fixture.session.play(); fixture.at(0)
            fixture.components.backend.endAt(28_000)
            assertTrue(fixture.session.machine.journalSnapshot().pendingCount in 1 until 300)
            fixture.components.backend.positionMs = 0
            fixture.session.seek(0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            assertEquals(1, fixture.components.voice.requests.size)
            assertNull(fixture.session.machine.snapshot().speakingId)
            assertEquals(0, fixture.session.machine.snapshot().pendingCount)
            assertEquals(0, fixture.session.machine.snapshot().recoverableCount)
            assertEquals(0, fixture.session.machine.journalSnapshot().completedCount)
        }
    }

    private fun manySubtitles(): String {
        fun timestamp(milliseconds: Int) = String.format(java.util.Locale.ROOT, "00:00:%02d,%03d", milliseconds / 1000, milliseconds % 1000)
        return (0 until 300).joinToString("\n") { index ->
            val start = 1000 + index * 20
            "${index + 1}\n${timestamp(start)} --> ${timestamp(start + 10)}\nRéplique $index.\n"
        }
    }

    private class Backend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        var positionMs = 0L
        private var forcedState: Int? = null
        private val listeners = mutableListOf<Player.Listener>()
        override val player: Player = object : ForwardingPlayer(delegate.player) {
            override fun getCurrentPosition(): Long = positionMs
            override fun getPlaybackState(): Int = forcedState ?: super.getPlaybackState()
            override fun addListener(listener: Player.Listener) { listeners += listener; super.addListener(listener) }
            override fun removeListener(listener: Player.Listener) { listeners -= listener; super.removeListener(listener) }
        }
        fun endAt(position: Long) {
            positionMs = position; forcedState = Player.STATE_ENDED
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        }
        fun deliver(text: String, timeUs: Long) {
            val group = CueGroup(listOf(Cue.Builder().setText(text).build()), timeUs)
            listeners.toList().forEach { it.onCues(group) }
        }
    }
    private class Components : PlaybackComponents by DefaultPlaybackComponents {
        lateinit var backend: Backend
        lateinit var voice: Voice
        override fun createPlayback(context: Context, preferences: PlayerPreferences, movieAudio: MovieAudio,
            videoRenderer: VideoRendererFactory): PlaybackBackend =
            Backend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer)).also { backend = it }
        override fun createSpeech(context: Context, preferences: PlayerPreferences, onAvailability: (Boolean, String) -> Unit,
            onDone: (String) -> Unit, onFailure: (String, String) -> Unit): SpeechService =
            Voice(onAvailability, onDone).also { voice = it }
    }
    private class Voice(private val available: (Boolean, String) -> Unit, private val done: (String) -> Unit) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        override val language = "fr"
        override var isReady = false; private set
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "controlled integration voice"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "timing-test-voice"
        override val engineDiagnosticIdentity = "controlled-timing-test-voice"
        override val choices = emptyList<SpeechVoiceChoice>()
        override val hits = 0
        override val misses = 0
        override val preparedCount = 0
        override val lastSynthesisMs = 0L
        override val lastAudioMs = 0L
        override val lastFactor = 1f
        override val cacheSize = 0L
        override val preparationStatus = "controlled"
        fun ready() { isReady = true; available(true, "controlled") }
        fun complete(id: String) = done(id)
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean, remaining: String,
            forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) { requests += id to text }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) = Unit
        override fun prepareAhead(texts: List<String>) = Unit
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() = Unit
        override fun clearCache() = Unit
        override fun close() = Unit
    }
}
