package fr.soustitresvoix.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.RealCueTimeline
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechDurationModel
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Real session, Media3 source selection, ASS parser, complete index and narration
 * controller. Only the translation/voice boundaries are controlled. No paid API,
 * external TTS audio, device performance or TalkBack result is claimed here.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class TranslationSessionRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val french = mapOf("EXIT" to "SORTIE", "Hello." to "Bonjour.", "Yes." to "Oui.")

    @Before fun resetPreferencesAndOwnedTranslationCache() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true)
            .putString("mode", NarrationMachine.Mode.ADAPTIVE.name)
            .putInt("duckAttack", 0).putInt("duckRelease", 0).putInt("duckHold", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
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

    private inner class Fixture : AutoCloseable {
        val components = Components()
        val messages = mutableListOf<Pair<String, Boolean>>()
        val captions = mutableListOf<String>()
        val session = PlaybackSession(context, PlayerPreferences(context), {}, { text, error -> messages += text to error },
            { captions += it }, components)
        val file = File.createTempFile("translation-synthetic-", ".ass", context.cacheDir).apply {
            writeText(assText())
        }
        val media = SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}",
            "Synthetic translation fixture", subtitlePath = file.path, subtitleMime = MimeTypes.TEXT_SSA)
        init {
            components.speech.ready(true)
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            session.open(media)
            await("Media3 did not load the selected external ASS fixture") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() == "external-subtitle"
            }
        }
        fun prepare(provider: String = "controlled"): SubtitleTranslationSession.Preview {
            var result: SubtitleTranslationSession.Preview? = null
            session.translation.prepare("en", provider) { result = it }
            await("Complete selected track was not prepared: ${session.translation.status}") { result != null }
            return result!!
        }
        fun translate(): SubtitleTranslationSession.Preview {
            val preview = prepare()
            session.translation.start(preview) { Engine() }
            await("Complete French translation was not activated: ${session.translation.status}") {
                session.translation.ready && session.player.playbackState == Player.STATE_READY
            }
            assertFalse("Translation completion cannot start playback", session.machine.snapshot().wantsPlayback)
            assertTrue("Translation completion cannot speak", components.speech.requests.isEmpty())
            return preview
        }
        fun playFrames() {
            session.play()
            for (frame in frames(file.readBytes())) {
                components.backend.deliver(frame)
                var guard = 0
                while (session.machine.snapshot().speakingId != null) {
                    assertTrue("Narration must make progress", ++guard < 50)
                    components.speech.complete(session.machine.snapshot().speakingId!!)
                }
            }
        }
        override fun close() { session.close(); file.delete() }
    }

    @Test fun completeFrenchNarrationRetainsPersistentPanelSimultaneousAndLaterRepeatedOccurrences() {
        Fixture().use { fixture ->
            val preview = fixture.translate()
            val items = fixture.session.ahead.selected("external-subtitle")
            assertEquals(5, items.size)
            assertEquals(5, items.map { it.identity }.distinct().size)
            assertEquals(preview.plan.cues.map { it.id }, items.map { it.identity })
            assertEquals(listOf("EXIT", "Hello.", "Yes.", "Yes.", "Hello."), preview.plan.texts)
            fixture.playFrames()
            assertEquals(listOf("SORTIE", "Bonjour.", "Oui.", "Oui.", "Bonjour."),
                fixture.components.speech.requests.map { it.second })
            assertTrue(fixture.components.speech.requests.none { it.second in french.keys })
            val published = fixture.components.narration.frames.filter { it.texts.isNotEmpty() }
            assertTrue(published.isNotEmpty())
            assertEquals(preview.plan.cues.map { it.id }.toSet(), published.flatMap { it.identities.orEmpty() }.toSet())
            assertTrue(published.flatMap { it.texts }.all { it in french.values })
            // The persistent panel appears in several display intervals but is spoken once.
            assertTrue(published.count { "SORTIE" in it.texts } > 1)
            assertEquals(1, fixture.components.speech.requests.count { it.second == "SORTIE" })
        }
    }

    @Test fun unmappedDisplayedOccurrenceStopsPlaybackWithoutAnyOriginalLanguageFallback() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.play()
            fixture.components.backend.deliver(CueGroup(listOf(Cue.Builder().setText("Unindexed original phrase").build()), 2_000_000))
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.player.playWhenReady)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("aucune substitution") })
            assertTrue(fixture.session.translation.required)
        }
    }

    @Test fun frenchVoiceMustBeReadyAndExplicitOriginalCommandIsRequiredToLeaveTranslationMode() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.components.speech.voiceLanguage = "en"
            fixture.session.play()
            fixture.session.resumeVoice()
            fixture.session.repeatLast()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("voix française") })
            fixture.components.speech.voiceLanguage = "fr"
            fixture.components.speech.ready(false)
            assertFalse(fixture.session.translation.allowPlayback())
            fixture.components.speech.ready(true)
            assertTrue(fixture.session.translation.allowPlayback())
            fixture.session.translation.useOriginal()
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertEquals("original", fixture.session.translation.fingerprint)
        }
    }

    @Test fun cancelledPreviewCannotConstructAnEngineOrFallBackToTheOriginalTrack() {
        Fixture().use { fixture ->
            val preview = fixture.prepare()
            fixture.session.translation.cancel()
            var engines = 0
            fixture.session.translation.start(preview) { engines++; Engine() }
            assertEquals(0, engines)
            assertTrue(fixture.session.translation.required)
            assertFalse(fixture.session.translation.ready)
            fixture.session.play()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun incompleteEngineResultCannotActivateOrSpeakAnySourceText() {
        Fixture().use { fixture ->
            val preview = fixture.prepare()
            fixture.session.translation.start(preview) { Engine { emptyList() } }
            await("Invalid translation worker did not finish") { !fixture.session.translation.busy }
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.session.translation.required)
            fixture.session.play()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("incomplète") })
        }
    }

    @Test fun lateTranslationAfterCancelTrackChangeMediaReplacementOrCloseNeverActivatesOrResumes() {
        for (action in listOf("cancel", "track", "media", "close")) {
            Fixture().use { fixture ->
                File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
                val preview = fixture.prepare("blocking-$action")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(1)
                fixture.session.translation.start(preview) {
                    Engine(id = "blocking-$action", completed = finished) { texts ->
                        entered.countDown()
                        assertTrue("Controlled translation was not released", release.await(10, TimeUnit.SECONDS))
                        texts.map { french.getValue(it) }
                    }
                }
                await("Controlled translator was not entered") { entered.count == 0L }
                when (action) {
                    "cancel" -> fixture.session.translation.cancel()
                    "track" -> fixture.session.selectTrack(C.TRACK_TYPE_TEXT, null, disabled = true)
                    "media" -> fixture.session.open(fixture.media.copy(title = "New media generation"))
                    "close" -> fixture.session.close()
                }
                release.countDown()
                await("Obsolete translator did not close") { finished.count == 0L }
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(action, fixture.session.translation.ready)
                assertFalse(action, fixture.session.translation.busy)
                assertFalse(action, fixture.session.machine.snapshot().wantsPlayback)
                assertTrue(action, fixture.components.speech.requests.isEmpty())
                if (action == "cancel" || action == "track") assertTrue(action, fixture.session.translation.required)
            }
        }
    }

    @Test fun selectedExternalOrEmbeddedTrackDeterminesWhichCompleteSourceIsScanned() {
        Fixture().use { fixture ->
            val external = fixture.prepare()
            assertEquals("external-subtitle", external.track)
            assertEquals(fixture.media, fixture.components.subtitles.scans.last())
            val selected = fixture.session.player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .first { group -> (0 until group.length).any { !fixture.session.isExternalSubtitle(group.getTrackFormat(it)) } }
            val index = (0 until selected.length).first { !fixture.session.isExternalSubtitle(selected.getTrackFormat(it)) }
            fixture.session.selectTrack(C.TRACK_TYPE_TEXT, selected, index)
            await("Embedded text track was not selected") { fixture.session.selectedTextId() != null && fixture.session.selectedTextId() != "external-subtitle" }
            val embedded = fixture.prepare()
            assertNotEquals("external-subtitle", embedded.track)
            assertNull(fixture.components.subtitles.scans.last().subtitlePath)
            assertNull(fixture.components.subtitles.scans.last().subtitleMime)
            assertEquals(fixture.media.uri, fixture.components.subtitles.scans.last().uri)
            var engines = 0
            fixture.session.translation.start(external) { engines++; Engine() }
            assertEquals("An old confirmation cannot send the former track", 0, engines)
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun completeVoicePreparationAndAnticipationUseOnlyTheFrenchTranslation() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.prepareRange(null)
            await("French speech batch was not prepared") { fixture.components.speech.batches.isNotEmpty() }
            assertEquals(listOf("SORTIE", "Bonjour.", "Oui."), fixture.components.speech.batches.last())
            fixture.session.play()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            fixture.session.prepareTick()
            assertTrue(fixture.components.speech.ahead.isNotEmpty())
            assertTrue(fixture.components.speech.ahead.flatten().all { it in french.values })
            assertTrue(fixture.components.speech.batches.flatten().none { it in french.keys })
        }
    }

    @Test fun currentlyDisplayedSubtitleIsRearmedInFrenchAndAfterExplicitReturnToOriginal() {
        Fixture().use { fixture ->
            fixture.session.seek(2500)
            await("The source cue at the current paused position was not rendered") {
                fixture.session.player.currentCues.cues.mapNotNull { it.text?.toString() }.contains("Hello.")
            }
            fixture.translate()
            await("Activation lost the currently displayed translated occurrence") {
                fixture.session.player.playbackState == Player.STATE_READY && fixture.session.machine.pendingTexts().contains("Bonjour.")
            }
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            fixture.session.play()
            var guard = 0
            while (fixture.session.machine.snapshot().speakingId != null) {
                assertTrue(++guard < 20)
                fixture.components.speech.complete(fixture.session.machine.snapshot().speakingId!!)
            }
            assertEquals(listOf("SORTIE", "Bonjour."), fixture.components.speech.requests.map { it.second })
            fixture.session.pause()
            fixture.session.translation.useOriginal()
            await("Explicit original mode lost the currently displayed source occurrence") {
                fixture.session.player.playbackState == Player.STATE_READY && fixture.session.machine.pendingTexts().contains("Hello.")
            }
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertEquals(2, fixture.components.speech.requests.size)
        }
    }

    @Test fun translatedPlaybackWithoutNarrationDoesNotRequireAReadyFrenchVoice() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.prefs.narration = false
            fixture.session.refreshSettings()
            fixture.components.speech.voiceLanguage = "en"
            fixture.components.speech.ready(false)
            assertTrue(fixture.session.translation.allowPlayback())
            fixture.playFrames()
            assertTrue(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.machine.snapshot().enabled)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.components.narration.frames.flatMap { it.texts }.all { it in french.values })
            assertFalse(fixture.messages.any { it.second && it.first.contains("voix française") })
        }
    }

    @Test fun aDivergentCompleteRescanRefusesTheWholeSpeechPreparationInsteadOfSkippingMissingText() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.components.subtitles.addUntranslatedOccurrenceToNextScan = true
            fixture.session.prepareRange(null)
            await("The divergent voice preparation was not explicitly refused") {
                fixture.messages.any { it.second && it.first.contains("aucune substitution") }
            }
            assertTrue(fixture.components.speech.batches.isEmpty())
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.isPreparing)
        }
    }

    @Test fun truncatedCompleteIndexIsRefusedBeforeConfirmationOrEngineConstruction() {
        Fixture().use { fixture ->
            fixture.components.subtitles.truncateNextScan = true
            var confirmations = 0
            fixture.session.translation.prepare("en", "controlled") { confirmations++ }
            await("Truncated index worker did not finish") { !fixture.session.translation.busy }
            assertEquals(0, confirmations)
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.session.translation.required)
            assertTrue(fixture.messages.any { it.second && it.first.contains("Index incomplet") })
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    private inner class Engine(
        override val id: String = "controlled",
        private val completed: CountDownLatch? = null,
        private val response: (List<String>) -> List<String> = { texts -> texts.map { french.getValue(it) } }
    ) : TranslationEngine {
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> {
            assertEquals("en", sourceLanguage)
            assertEquals("fr", targetLanguage)
            return response(texts)
        }
        override fun close() {
            // Cancellation also closes on the session thread. Wait for the
            // worker's final use/close to prove its late response has returned.
            if (Looper.myLooper() != Looper.getMainLooper()) completed?.countDown()
        }
    }

    private class RecordingNarration(private val delegate: NarrationController = ExistingNarrationController()) : NarrationController by delegate {
        data class Frame(val texts: List<String>, val identities: List<String>?)
        val frames = mutableListOf<Frame>()
        override fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean,
            identities: List<String>?, deadlinesMs: List<Long?>?): List<NarrationMachine.Effect> {
            frames += Frame(texts.toList(), identities?.toList())
            return delegate.cues(frameTimeUs, texts, hasImages, identities, deadlinesMs)
        }
    }

    /** Capture the real session listener at its public Player boundary. */
    private class Backend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        private val listeners = mutableListOf<Player.Listener>()
        override val player: Player = object : ForwardingPlayer(delegate.player) {
            override fun addListener(listener: Player.Listener) { listeners += listener; super.addListener(listener) }
            override fun removeListener(listener: Player.Listener) { listeners -= listener; super.removeListener(listener) }
        }
        fun deliver(group: CueGroup) { listeners.toList().forEach { it.onCues(group) } }
    }

    private class Source(context: Context) : SubtitleSource {
        private val delegate = Media3SubtitleSource(context)
        val scans = Collections.synchronizedList(mutableListOf<SavedMedia>())
        @Volatile var truncateNextScan = false
        @Volatile var addUntranslatedOccurrenceToNextScan = false
        override fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource = delegate.createMediaSource(item, onCue)
        override fun createIndexer(): SubtitleIndexer {
            val scanner = delegate.createIndexer()
            return object : SubtitleIndexer by scanner {
                override fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
                    scans += media
                    if (truncateNextScan) {
                        truncateNextScan = false
                        return CueAheadIndex().apply {
                            add("external-subtitle", CuesWithTiming(listOf(Cue.Builder().setText("incomplete").build()), 0, 0))
                        }
                    }
                    return scanner.scan(media, progress).also { result ->
                        if (addUntranslatedOccurrenceToNextScan) {
                            addUntranslatedOccurrenceToNextScan = false
                            result.add("external-subtitle", CuesWithTiming(
                                listOf(Cue.Builder().setText("Untranslated new sign").build()), 10_000_000, 1_000_000))
                        }
                    }
                }
            }
        }
        override fun safeFailureMessage(error: Exception) = delegate.safeFailureMessage(error)
    }

    private class Components : PlaybackComponents by DefaultPlaybackComponents {
        val narration = RecordingNarration()
        lateinit var backend: Backend
        lateinit var subtitles: Source
        lateinit var speech: Voice
        override fun createNarration(): NarrationController = narration
        override fun createPlayback(context: Context, preferences: PlayerPreferences,
            movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend =
            Backend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer)).also { backend = it }
        override fun createSubtitles(context: Context): SubtitleSource = Source(context).also { subtitles = it }
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService = Voice(onAvailability, onDone).also { speech = it }
    }

    private class Voice(private val availability: (Boolean, String) -> Unit,
        private val done: (String) -> Unit) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        val batches = mutableListOf<List<String>>()
        val ahead = mutableListOf<List<String>>()
        var voiceLanguage = "fr"
        override val language get() = voiceLanguage
        override var isReady = false; private set
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled translation integration voice"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "translation-test-voice"
        override val engineDiagnosticIdentity = "controlled-translation-test-voice"
        override val choices = emptyList<SpeechVoiceChoice>()
        override val hits = 0
        override val misses = 0
        override val preparedCount get() = batches.sumOf { it.size }
        override val lastSynthesisMs = 0L
        override val lastAudioMs = 0L
        override val lastFactor = 1f
        override val cacheSize = 0L
        override val preparationStatus = "No external acoustic claim"
        fun ready(value: Boolean) { isReady = value; availability(value, "Controlled voice state") }
        fun complete(id: String) = done(id)
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
            remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) { requests += id to text }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) { batches += texts.toList() }
        override fun prepareAhead(texts: List<String>) { ahead += texts.toList() }
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() = Unit
        override fun clearCache() = Unit
        override fun close() = Unit
    }

    private fun assText(): String {
        val original = requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssa/full_persistent_sign.ass"))
            .use { it.reader().readText() }
        return original.substringBefore("[Events]") + """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:09.00,Sign,,0,0,0,,{\an8\pos(320,30)}EXIT
            Dialogue: 1,0:00:02.00,0:00:03.00,Dialogue,ALICE,0,0,0,,Hello.
            Dialogue: 1,0:00:04.00,0:00:05.00,Dialogue,ALICE,0,0,0,,Yes.
            Dialogue: 1,0:00:04.00,0:00:05.00,Dialogue,BOB,0,0,0,,Yes.
            Dialogue: 1,0:00:06.00,0:00:07.00,Dialogue,ALICE,0,0,0,,Hello.
        """.trimIndent() + "\n"
    }

    private fun frames(bytes: ByteArray): List<CueGroup> {
        val groups = mutableListOf<CuesWithTiming>()
        CueAheadFactory { _, _ -> }.sidecarParsers()
            .create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
            .parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues(), Consumer { groups += it })
        return RealCueTimeline.frames(groups)
    }
}
