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
class TimelinePlaybackRobolectricTest {
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

    private inner class Fixture(text: String = ordinarySubtitles()) : AutoCloseable {
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
                assertTrue("Source narration failed to drain", ++guard < 100)
                components.voice.complete(session.machine.snapshot().speakingId!!)
            }
        }
        fun display() = session.displayedCues.map { it.text.toString() }
        override fun close() { session.close(); file.delete() }
    }

    @Test fun independentSubtitleAndVoiceOffsetsKeepEverySourceOccurrenceAndRealRepeat() {
        Fixture().use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1000, narrationOffsetMs = 500))
            f.session.play(); f.at(0)
            f.at(2000)
            assertEquals(listOf("PANNEAU"), f.display())
            assertTrue(f.components.voice.requests.isEmpty())
            f.at(2500); f.drain()
            assertEquals(listOf("PANNEAU"), f.components.voice.requests.map { it.second })
            f.at(3500); f.drain()
            f.at(7500); f.drain()
            f.at(8500); f.drain()
            assertEquals(listOf("PANNEAU", "Oui.", "Oui.", "Bref.", "Oui."),
                f.components.voice.requests.map { it.second })
            assertEquals(5, f.session.machine.journalSnapshot().completedCount)
            // An offline occurrence crossing the end of an edited source portion
            // must retain its full interval, exactly like foreground narration.
            f.timeline(SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(500, 1500, 5000))))
            assertTrue(f.features().configureOffline())
            val offline = f.components.voice.offlineCues.first { it.text == "PANNEAU" }
            assertEquals(6000L, offline.startMs)
            assertEquals(14000L, offline.endMs)
        }
    }

    @Test fun twoAnchorsAndOffsetsDriveDisplayAndSpeechFromTheSameUnchangedSourceIds() {
        Fixture().use { f ->
            val sourceIds = f.session.ahead.selected("external-subtitle").map { it.identity }
            val timeline = SubtitleTimeline(1000, 250,
                listOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(10_000, 12_000)))
            f.timeline(timeline); f.session.play(); f.at(0)
            f.at(2200)
            assertEquals(listOf("PANNEAU"), f.display())
            assertTrue(f.components.voice.requests.isEmpty())
            for (sourceTime in listOf(1000L, 2000L, 6000L, 7000L)) {
                f.at(timeline.narrationTimeMs(sourceTime)); f.drain()
            }
            assertEquals(listOf("PANNEAU", "Oui.", "Oui.", "Bref.", "Oui."),
                f.components.voice.requests.map { it.second })
            assertEquals(sourceIds, f.session.ahead.selected("external-subtitle").map { it.identity })
            assertEquals(5, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun sourceCueShorterThanOneTickIsSpokenWhenBothBoundariesAreCrossed() {
        Fixture("1\n00:00:01,010 --> 00:00:01,020\nTexte bref intégral.\n").use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1))
            f.session.play(); f.at(1000)
            f.at(1100); f.drain()
            assertEquals(listOf("Texte bref intégral."), f.components.voice.requests.map { it.second })
            assertEquals(1, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun subMillisecondSourceIntervalIsNarratedAtItsRoundedMappedBoundary() {
        Fixture("1\n00:00:20,000 --> 00:00:21,000\nPlus tard.\n").use { f ->
            f.session.ahead.add("external-subtitle", CuesWithTiming(
                listOf(Cue.Builder().setText("Très bref mais entier.").build()), 1_001_001, 500))
            f.timeline(SubtitleTimeline(globalOffsetMs = 1))
            f.session.play(); f.at(1000); f.at(1100); f.drain()
            assertEquals(listOf("Très bref mais entier."), f.components.voice.requests.map { it.second })
            assertEquals(1, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun emptyBoundaryClearsTheCaptionWithoutCreatingOrDroppingAnOccurrence() {
        Fixture("1\n00:00:01,000 --> 00:00:01,010\nÉphémère.\n").use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1))
            f.session.play(); f.at(1000); f.at(1001); f.drain()
            assertEquals(listOf("Éphémère."), f.display())
            f.at(1011)
            assertTrue(f.display().isEmpty())
            assertEquals("", f.session.lastRawCaption)
            f.at(1100)
            assertEquals(listOf("Éphémère."), f.components.voice.requests.map { it.second })
            assertEquals(1, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun longUiTickGapDoesNotSilentlySkipTheBriefOccurrencesBetweenPositions() {
        Fixture("1\n00:00:01,010 --> 00:00:01,020\nPremière.\n\n2\n00:00:03,010 --> 00:00:03,020\nDeuxième.\n").use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1))
            f.session.play(); f.at(1000)
            f.at(7000); f.drain()
            assertEquals(listOf("Première.", "Deuxième."), f.components.voice.requests.map { it.second })
            assertEquals(2, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun restoredMetadataReplaysTheReResolvedSourceTextWithVideoPaused() {
        Fixture().use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1)); f.session.play(); f.at(1001)
            assertEquals("PANNEAU", f.components.voice.requests.single().second)
            f.session.pause()
            val before = f.session.machine.journalSnapshot()
            assertEquals(1, before.interruptedCount)
            val expectedMetadata = f.session.machine.exportJournalMetadata()
            val store = NarrationJournalStore(context)
            // Pause schedules the real session writer. A second manual save
            // raced that writer and could read an older snapshot instead.
            await("The interrupted source occurrence did not reach durable storage") {
                store.read(before.contextHash) == expectedMetadata
            }
            val restored = requireNotNull(store.read(before.contextHash))
            assertEquals(expectedMetadata, restored)
            f.session.applyNarration(f.session.machine.reset(false))
            assertTrue(f.session.machine.restoreJournalMetadata(restored))
            f.features().javaClass.getDeclaredMethod("replayInterrupted", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(f.features(), false)
            assertEquals(listOf("PANNEAU", "PANNEAU"), f.components.voice.requests.map { it.second })
            assertFalse(f.session.machine.snapshot().wantsPlayback)
            assertFalse(f.session.player.playWhenReady)
            f.drain()
            assertEquals(1, f.session.machine.journalSnapshot().completedCount)
            assertFalse(restored.contains("PANNEAU"))
        }
    }

    @Test fun comprehensionPauseRestoresTheVideoAfterItsControlledDelay() {
        PlayerPreferences(context).putInt("comprehensionPauseMs", 800)
        Fixture(singleSubtitle()).use { f ->
            f.session.play(); f.components.backend.positionMs = 1000
            f.components.backend.deliver("Une phrase complète.", 1_000_000)
            val id = f.components.voice.requests.single().first
            f.components.voice.complete(id)
            assertFalse(f.session.player.playWhenReady)
            assertFalse(f.session.machine.snapshot().videoRequested)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
            assertTrue(f.session.machine.snapshot().wantsPlayback)
            assertTrue(f.session.machine.snapshot().videoRequested)
            assertTrue(f.session.player.playWhenReady)
            assertEquals(1, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun manualPauseDuringComprehensionDelayCancelsTheCallbackAndRetainsThePhrase() {
        PlayerPreferences(context).putInt("comprehensionPauseMs", 800)
        Fixture(singleSubtitle()).use { f ->
            f.session.play(); f.components.backend.positionMs = 1000
            f.components.backend.deliver("Une phrase complète.", 1_000_000)
            val old = f.components.voice.requests.single().first
            f.components.voice.complete(old); f.session.pause()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
            assertFalse(f.session.machine.snapshot().wantsPlayback)
            assertFalse(f.session.player.playWhenReady)
            f.session.play()
            val resumed = f.components.voice.requests.last()
            assertEquals("Une phrase complète.", resumed.second)
            assertNotEquals(old, resumed.first)
            f.components.voice.complete(old)
            assertEquals(resumed.first, f.session.machine.snapshot().speakingId)
        }
    }

    @Test fun seekDuringComprehensionDelayDoesNotCompleteOrReplayTheOldRequest() {
        PlayerPreferences(context).putInt("comprehensionPauseMs", 800)
        Fixture(singleSubtitle()).use { f ->
            f.session.play(); f.components.backend.positionMs = 1000
            f.components.backend.deliver("Une phrase complète.", 1_000_000)
            f.components.voice.complete(f.components.voice.requests.single().first)
            f.components.backend.positionMs = 5000
            f.session.seek(5000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
            assertEquals(1, f.components.voice.requests.size)
            assertEquals(0, f.session.machine.journalSnapshot().completedCount)
        }
    }

    @Test fun integralProfileOverridesAllDestructiveFiltersAndContinuousMode() {
        PlayerPreferences(context).apply {
            setMode(NarrationMachine.Mode.CONTINUOUS)
            skipDescriptions = true
            putBool("skipSpeakers", true); putBool("skipSongs", true); putBool("skipNotes", true)
        }
        Fixture(filterSubtitles()).use { f ->
            assertEquals(NarrationMachine.Mode.COMFORT, f.session.machine.snapshot().mode)
            f.timeline(SubtitleTimeline(globalOffsetMs = 1)); f.session.play(); f.at(0)
            for (time in listOf(1001L, 2001L, 3001L, 4001L, 5001L)) { f.at(time); f.drain() }
            assertEquals(listOf("PANNEAU", "ALICE: Bonjour.", "♪ Chanson", "[Musique]", "NDT : note utile."),
                f.components.voice.requests.map { it.second })
            assertEquals(5, f.session.machine.journalSnapshot().completedCount)
            assertEquals(0, f.session.machine.journalSnapshot().filteredCount)
        }
    }

    @Test fun explicitlyLeavingIntegralProfileAllowsFiltersAndAccountsForExcludedOccurrences() {
        PlayerPreferences(context).apply {
            putBool("integralReading", false); skipDescriptions = true
            putBool("skipSpeakers", true); putBool("skipSongs", true); putBool("skipNotes", true)
        }
        Fixture(filterSubtitles()).use { f ->
            f.timeline(SubtitleTimeline(globalOffsetMs = 1)); f.session.play(); f.at(0)
            for (time in listOf(1001L, 2001L, 3001L, 4001L, 5001L)) { f.at(time); f.drain() }
            assertEquals(listOf("PANNEAU", "Bonjour."), f.components.voice.requests.map { it.second })
            assertEquals(2, f.session.machine.journalSnapshot().completedCount)
            assertEquals(3, f.session.machine.journalSnapshot().filteredCount)
        }
    }

    private class Backend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        var positionMs = 0L
        private val listeners = mutableListOf<Player.Listener>()
        override val player: Player = object : ForwardingPlayer(delegate.player) {
            override fun getCurrentPosition(): Long = positionMs
            override fun addListener(listener: Player.Listener) { listeners += listener; super.addListener(listener) }
            override fun removeListener(listener: Player.Listener) { listeners -= listener; super.removeListener(listener) }
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
        var offlineCues = emptyList<fr.soustitresvoix.core.OfflineSpeechCue>()
        override fun configureOfflineWork(mediaKey: String, cues: List<fr.soustitresvoix.core.OfflineSpeechCue>, totalDurationMs: Long,
            complete: Boolean, title: String) { offlineCues = cues }
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
    private fun ordinarySubtitles() = """
        1
        00:00:01,000 --> 00:00:09,000
        PANNEAU

        2
        00:00:02,000 --> 00:00:03,000
        Oui.

        3
        00:00:02,000 --> 00:00:03,000
        Oui.

        4
        00:00:06,000 --> 00:00:06,010
        Bref.

        5
        00:00:07,000 --> 00:00:08,000
        Oui.
    """.trimIndent() + "\n"
    private fun singleSubtitle() = "1\n00:00:01,000 --> 00:00:03,000\nUne phrase complète.\n"
    private fun filterSubtitles() = listOf("PANNEAU", "ALICE: Bonjour.", "♪ Chanson", "[Musique]", "NDT : note utile.")
        .mapIndexed { index, text -> "${index + 1}\n00:00:0${index + 1},000 --> 00:00:0${index + 1},800\n$text" }
        .joinToString("\n\n", postfix = "\n")
}
