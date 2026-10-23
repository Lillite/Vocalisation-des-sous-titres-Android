package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandRejection
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioSettings
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioEqBand
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.PlaybackBenchmark
import fr.soustitresvoix.core.SpeechDurationModel
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowTextToSpeech

/** Real Media3, processor, source and narration controller. The deliberate voice
 * boundary double keeps a real controller utterance unfinished while a command
 * is exercised. These tests measure configured volume and virtual callback time,
 * never acoustic loudness. AndroidSpeech IPC is covered separately on device. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioCommandsRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetPreferences() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun create(components: RecordingComponents, owner: Context = context): PlaybackSession = PlaybackSession(
        owner, PlayerPreferences(owner), onChanged = { components.notifications++ }, onMessage = { _, _ -> },
        onCaption = { }, components = components
    ).also {
        components.voice.publishReady()
        // The initial release has delayed posts even when the initial volume
        // already equals its target. Drain that transition before each oracle.
        advance(4_100)
        components.commands.clear()
    }

    private fun submit(session: PlaybackSession, command: AudioCommand): AudioCommandDecision =
        session.audioCommands.submit(AudioCommandRequest(session.audioCommands.snapshot().context.generation, command))

    private fun accepted(session: PlaybackSession, command: AudioCommand): AudioCommandDecision.Accepted {
        val result = submit(session, command)
        assertTrue("Expected acceptance, got $result", result is AudioCommandDecision.Accepted)
        return result as AudioCommandDecision.Accepted
    }

    private data class VoiceState(
        val narration: NarrationMachine.Snapshot,
        val queue: List<String>,
        val plan: List<NarrationMachine.PlannedReplica>,
        val requests: List<Pair<String, String>>,
        val playWhenReady: Boolean,
        val position: Long,
        val voiceVolume: Float,
        val voiceRate: Float,
        val voiceEngine: String,
        val voiceName: String
    )

    private fun voiceState(session: PlaybackSession, components: RecordingComponents) = VoiceState(
        session.machine.snapshot(), session.machine.pendingTexts(), session.machine.pendingPlan(),
        components.voice.requests.toList(), session.player.playWhenReady, session.player.currentPosition,
        session.prefs.voiceVolume, session.prefs.speechRate, session.prefs.enginePackage, session.prefs.voiceName
    )

    private fun seedBenchmark(session: PlaybackSession) {
        assertTrue(session.playbackBenchmark.start(120_000,
            PlaybackBenchmark.Identity("audio-command-fixture", "default-audio", "JVM fixture",
                "controlled voice", "test", 0),
            PlaybackBenchmark.Counters(0, 0, session.player.currentPosition, 0, 0, 0)))
        session.playbackBenchmark.record(PlaybackBenchmark.Metric.FIRST_AUDIO, 123)
    }

    @Test fun changedCommandsOnlyChangeFilmAudioAndPreserveAnActiveUtteranceAndItsQueue() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            session.previewVoice()
            advance(160)
            assertEquals(.25f, session.player.volume, .00001f)
            // Arrange legitimate, distinct queued occurrences at the controller
            // boundary while a preview occupies the voice. No unapplied effect
            // is permitted; actual subtitle extraction is tested on Android.
            assertTrue(session.machine.cues(10_000_000,
                listOf("Panneau à conserver.", "Encore.", "Encore."),
                identities = listOf("panel", "repeat-1", "repeat-2")).isEmpty())
            assertEquals(listOf("Panneau à conserver.", "Encore.", "Encore."), session.machine.pendingTexts())
            val before = voiceState(session, components)
            val original = session.audioCommands.snapshot()
            val sources = listOf(session.player.currentMediaItem, session.player.trackSelectionParameters)
            components.commands.clear()

            seedBenchmark(session)
            val notifications = components.notifications
            assertTrue(accepted(session, AudioCommand.SetLevels(.6f, .25f)).changed)
            assertEquals("The retained level is a multiplier: 60% × 25% = 15%", .15f, session.player.volume, .00001f)
            assertFalse("An actual audio change must invalidate the comparison", session.playbackBenchmark.active)
            assertEquals(listOf("player.setVolume"), components.commands)
            assertEquals(before, voiceState(session, components))
            assertEquals("An audio edit must not run the general UI callback which also schedules voice preparation",
                notifications, components.notifications)

            components.commands.clear()
            assertTrue(accepted(session, AudioCommand.SetDuckingTiming(240, 700, 450)).changed)
            assertTrue("A duration preference must not rewrite the running transition", components.commands.isEmpty())
            assertEquals(before, voiceState(session, components))
            assertEquals(notifications, components.notifications)

            assertTrue(accepted(session, AudioCommand.SetSpectralDucking(true)).changed)
            assertEquals(listOf("audio.setEnabled"), components.commands)
            assertEquals(before, voiceState(session, components))
            assertEquals(notifications, components.notifications)
            assertEquals(1, components.audio.sinkCreations)
            assertTrue(session.movieAudio.enabled)
            assertTrue(session.movieAudio.speaking)
            assertEquals(AudioSettings(.6f, .25f, 240, 700, 450, true),
                session.audioCommands.snapshot().context.requested)
            assertEquals("Previously returned requests must stay immutable", AudioSettings(), original.context.requested)
            assertEquals(sources, listOf(session.player.currentMediaItem, session.player.trackSelectionParameters))
            assertFalse("An audio command must not refresh the speech engine", components.commands.any { it.startsWith("speech.") })
            assertFalse("An audio command must not run the narration controller", components.commands.any { it.startsWith("narration.") })
        } finally { session.close() }
    }

    @Test fun invalidAndIdenticalCommandsPreserveDefaultAttackHoldReleaseAndAnActiveBenchmark() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            session.previewVoice()
            advance(80)
            assertEquals(.625f, session.player.volume, .00001f)
            seedBenchmark(session)
            val before = voiceState(session, components)
            val measurement = session.playbackBenchmark.snapshot()
            val generation = session.audioCommands.snapshot().context.generation
            components.commands.clear()
            for (command in listOf(AudioCommand.SetLevels(1f, .25f),
                AudioCommand.SetDuckingTiming(160, 500, 350), AudioCommand.SetSpectralDucking(false))) {
                assertFalse(accepted(session, command).changed)
            }
            for ((command, reason) in listOf(
                AudioCommand.SetLevels(Float.NaN, .5f) to AudioCommandRejection.NON_FINITE_VALUE,
                AudioCommand.SetLevels(.5f, Float.POSITIVE_INFINITY) to AudioCommandRejection.NON_FINITE_VALUE,
                AudioCommand.SetLevels(Float.NEGATIVE_INFINITY, .5f) to AudioCommandRejection.NON_FINITE_VALUE,
                AudioCommand.SetLevels(-.01f, .5f) to AudioCommandRejection.OUT_OF_RANGE,
                AudioCommand.SetLevels(.5f, 1.01f) to AudioCommandRejection.OUT_OF_RANGE,
                AudioCommand.SetDuckingTiming(-1, 700, 450) to AudioCommandRejection.OUT_OF_RANGE,
                AudioCommand.SetDuckingTiming(240, 2001, 450) to AudioCommandRejection.OUT_OF_RANGE,
                AudioCommand.SetDuckingTiming(240, 700, Int.MAX_VALUE) to AudioCommandRejection.OUT_OF_RANGE
            )) {
                assertEquals(AudioCommandDecision.Rejected(generation, reason), submit(session, command))
                assertEquals(AudioSettings(), session.audioCommands.snapshot().context.requested)
            }
            for ((availability, reason) in listOf(
                CapabilityStatus.UNKNOWN to AudioCommandRejection.CAPABILITY_UNKNOWN,
                CapabilityStatus.UNAVAILABLE to AudioCommandRejection.CAPABILITY_UNAVAILABLE)) {
                components.audio.availability = availability
                assertEquals(AudioCommandDecision.Rejected(generation, reason),
                    submit(session, AudioCommand.SetSpectralDucking(true)))
                assertFalse(accepted(session, AudioCommand.SetSpectralDucking(false)).changed)
            }
            assertEquals(emptyList<String>(), components.commands)
            assertEquals(measurement, session.playbackBenchmark.snapshot())
            assertEquals(before, voiceState(session, components))
            assertEquals(.625f, session.player.volume, .00001f)
            advance(80)
            assertEquals("The no-op/refused requests canceled or restarted the original 160 ms attack",
                .25f, session.player.volume, .00001f)
            assertTrue(session.playbackBenchmark.active)
            components.voice.complete(components.voice.requests.single().first)
            advance(200)
            val held = session.audioCommands.snapshot()
            components.commands.clear()
            assertFalse(accepted(session, AudioCommand.SetLevels(1f, .25f)).changed)
            assertFalse(accepted(session, AudioCommand.SetDuckingTiming(160, 500, 350)).changed)
            assertFalse(accepted(session, AudioCommand.SetSpectralDucking(false)).changed)
            assertEquals(held, session.audioCommands.snapshot())
            assertTrue(components.commands.isEmpty())
            advance(150)
            assertEquals("An unchanged request must retain the original hold deadline", .25f, session.player.volume, .00001f)
            advance(100)
            assertEquals(.40f, session.player.volume, .00001f)
            val recovering = session.audioCommands.snapshot()
            components.commands.clear()
            assertFalse(accepted(session, AudioCommand.SetLevels(1f, .25f)).changed)
            assertFalse(accepted(session, AudioCommand.SetDuckingTiming(160, 500, 350)).changed)
            assertFalse(accepted(session, AudioCommand.SetSpectralDucking(false)).changed)
            assertEquals(recovering, session.audioCommands.snapshot())
            assertTrue(components.commands.isEmpty())
            advance(400)
            assertEquals("An unchanged request canceled or restarted recovery", 1f, session.player.volume, .00001f)
            assertTrue(session.playbackBenchmark.active)
        } finally { session.close() }
    }

    @Test fun explicitLevelCommandsCancelOldCallbacksDuringAttackHoldAndRelease() {
        for (phase in listOf("attack", "hold", "release")) {
            resetPreferences()
            val components = RecordingComponents()
            val session = create(components)
            try {
                session.previewVoice()
                val id = components.voice.requests.single().first
                when (phase) {
                    "attack" -> { advance(80); assertEquals(.625f, session.player.volume, .00001f) }
                    "hold" -> {
                        advance(160); components.voice.complete(id); advance(200)
                        assertEquals(.25f, session.player.volume, .00001f)
                    }
                    "release" -> {
                        advance(160); components.voice.complete(id); advance(550)
                        assertEquals(.55f, session.player.volume, .00001f)
                    }
                }
                val before = voiceState(session, components)
                components.commands.clear()
                accepted(session, AudioCommand.SetLevels(.6f, .5f))
                val target = if (phase == "attack") .3f else .6f
                assertEquals("Wrong immediate target during $phase", target, session.player.volume, .00001f)
                assertEquals(listOf("player.setVolume"), components.commands)
                assertEquals(before, voiceState(session, components))
                advance(1_200)
                assertEquals("An obsolete $phase callback overwrote the explicit setting", target,
                    session.player.volume, .00001f)
                assertEquals(before, voiceState(session, components))
            } finally { session.close() }
        }
    }

    @Test fun durationChangesApplyOnlyToTransitionsCreatedAfterTheCommand() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            session.previewVoice()
            val id = components.voice.requests.single().first
            advance(40)
            assertEquals(.8125f, session.player.volume, .00001f)
            accepted(session, AudioCommand.SetDuckingTiming(1_000, 1_200, 1_000))
            advance(120)
            assertEquals("Changing attack duration must not lengthen an already constructed attack",
                .25f, session.player.volume, .00001f)
            components.voice.complete(id)
            assertNull(session.machine.snapshot().speakingId)
            advance(999)
            assertEquals("TTS completion is not the end of the hold", .25f, session.player.volume, .00001f)
            advance(601)
            assertEquals(.625f, session.player.volume, .00001f)
            accepted(session, AudioCommand.SetDuckingTiming(0, 0, 0))
            assertEquals("A new duration must not jump the current release", .625f, session.player.volume, .00001f)
            advance(600)
            assertEquals(1f, session.player.volume, .00001f)
            session.previewVoice()
            advance(0)
            assertEquals("The next zero-duration attack is applied by its callback", .25f, session.player.volume, .00001f)
            components.voice.complete(components.voice.requests.last().first)
            advance(0)
            assertEquals(1f, session.player.volume, .00001f)
        } finally { session.close() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            advance(25)
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    private fun openTextOnly(session: PlaybackSession, subtitle: File? = null) {
        session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        session.open(SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}",
            "Synthetic audio-command fixture", subtitlePath = subtitle?.path,
            subtitleMime = subtitle?.let { MimeTypes.APPLICATION_SUBRIP }))
        await("The real Media3 source and the requested text track did not become ready") {
            val selected = session.selectedTextId()
            session.player.playbackState == Player.STATE_READY &&
                if (subtitle == null) selected != null else selected == "external-subtitle"
        }
    }

    @Test fun mediaReplacementInvalidatesOldRequestsButRetainsAudioAndClosedReadsAvoidThePlayer() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            openTextOnly(session)
            accepted(session, AudioCommand.SetLevels(.6f, .4f))
            accepted(session, AudioCommand.SetDuckingTiming(80, 800, 650))
            accepted(session, AudioCommand.SetSpectralDucking(true))
            val settings = AudioSettings(.6f, .4f, 80, 800, 650, true)
            val old = session.audioCommands.snapshot().context.generation
            openTextOnly(session)
            val replacement = session.audioCommands.snapshot()
            assertTrue(replacement.context.generation > old)
            assertEquals(settings, replacement.context.requested)
            val preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap()
            val voice = voiceState(session, components)
            components.commands.clear()
            assertEquals(AudioCommandDecision.Rejected(old, AudioCommandRejection.STALE_MEDIA),
                session.audioCommands.submit(AudioCommandRequest(old, AudioCommand.SetLevels(.2f, .1f))))
            assertEquals(preferences, context.getSharedPreferences("player", Context.MODE_PRIVATE).all)
            assertEquals(voice, voiceState(session, components))
            assertTrue(components.commands.isEmpty())

            session.previewVoice()
            val staleVoice = components.voice.requests.last().first
            session.close()
            components.commands.clear()
            components.backend.failOnAnyPlayerAccess = true
            val closed = session.audioCommands.snapshot()
            assertTrue(closed.context.closed)
            assertNull(closed.configuredVolume)
            assertNull(closed.spectralEnabled)
            assertNull(closed.speaking)
            assertNull(closed.processedSamples)
            assertEquals(AudioCommandDecision.Rejected(closed.context.generation, AudioCommandRejection.CLOSED),
                session.audioCommands.submit(AudioCommandRequest(closed.context.generation, AudioCommand.SetLevels(.1f, .1f))))
            components.voice.complete(staleVoice)
            components.voice.fail(staleVoice)
            advance(3_000)
            assertTrue("A closed command or callback reached a released component", components.commands.isEmpty())
            assertEquals(settings, session.audioCommands.snapshot().context.requested)
            assertEquals(1, components.backend.closes)
            components.backend.failOnAnyPlayerAccess = false

            val nextComponents = RecordingComponents()
            val next = create(nextComponents)
            try {
                assertEquals(settings, next.audioCommands.snapshot().context.requested)
                assertTrue(next.movieAudio.enabled)
                assertEquals(.6f, next.player.volume, .00001f)
            } finally { next.close() }
        } finally {
            components.backend.failOnAnyPlayerAccess = false
            session.close()
        }
    }

    @Test fun changingHoldKeepsTheAlreadyScheduledAnticipationOfANearbySubtitle() {
        val subtitles = File(context.cacheDir, "audio-command-future.srt").apply {
            writeText("1\n00:00:25,000 --> 00:00:27,000\nRepère synthétique éloigné.\n")
        }
        val components = RecordingComponents()
        val session = create(components)
        try {
            openTextOnly(session, subtitles)
            accepted(session, AudioCommand.SetDuckingTiming(0, 0, 350))
            session.play()
            session.ahead.clear()
            val position = session.player.currentPosition
            val track = session.selectedTextId()
            assertEquals("Anticipation must use the selected sidecar, not a provisional embedded track", "external-subtitle", track)
            val first = Cue.Builder().setText("Réplique contrôlée.").build()
            session.ahead.add(track, CuesWithTiming(listOf(first), position * 1000, 100_000))
            // Synthetic subtitle callback at the platform boundary, while the
            // real source remains ready. This isolates the existing hold rule.
            components.backend.emitCues(CueGroup(listOf(first), position * 1000))
            advance(0)
            assertEquals(.25f, session.player.volume, .00001f)
            val current = components.voice.requests.single().first
            assertEquals(current, session.machine.snapshot().speakingId)
            components.backend.emitCues(CueGroup(emptyList(), position * 1000 + 100_000))
            assertTrue("The fixture needs an actual comfort hold, not a user pause", session.machine.snapshot().waitingForSpeech)
            assertFalse(session.player.playWhenReady)
            assertTrue("The actual playback thread must acknowledge the comfort pause",
                components.backend.playbackBarrier().blockUntilDelivered(20_000))
            advance(0)
            assertEquals(Player.STATE_READY, session.player.playbackState)
            assertEquals(current, session.machine.snapshot().speakingId)
            assertTrue(session.machine.snapshot().wantsPlayback)
            val pausedPosition = session.player.currentPosition
            val nextStart = pausedPosition + 900
            session.ahead.clear()
            session.ahead.add(track, CuesWithTiming(listOf(Cue.Builder().setText("Réplique proche.").build()),
                nextStart * 1000, 100_000))
            // Completing the utterance sends Video(true) before Duck(false).
            // A read taken earlier by the test cannot stand in for the two
            // real position reads used to schedule the hold after that resume.
            // Observe those calls without replacing their values or clock.
            components.backend.capturePositionReads = true
            try { components.voice.complete(current) }
            finally { components.backend.capturePositionReads = false }
            val consumedPositions = components.backend.capturedPositions.toList()
            println("Audio hold inputs: pausedPositionMs=$pausedPosition, nextStartMs=$nextStart, indexedTrack=$track, " +
                "consumedPositionsMs=$consumedPositions, selectedTracks=${components.backend.capturedTracks}, " +
                "currentTrack=${session.selectedTextId()}, index=${session.ahead.selected(track).map { it.track to it.startUs }}")
            assertEquals("Expected the lookup and gap-subtraction position reads; actual=$consumedPositions, next=$nextStart, track=$track", 2, consumedPositions.size)
            assertTrue("The nearby source cue must still be ahead during lookup", consumedPositions[0] < nextStart)
            val gap = nextStart - consumedPositions[1]
            assertTrue("The actual callback must exercise the 0..1200 ms anticipation window", gap in 0..1200)
            val actualHold = maxOf(350L, gap + 150)
            println("Audio hold fixture: pausedPositionMs=$pausedPosition, consumedPositionsMs=$consumedPositions, scheduledHoldMs=$actualHold")
            assertTrue(session.machine.snapshot().wantsPlayback)
            assertNull(session.machine.snapshot().speakingId)
            accepted(session, AudioCommand.SetDuckingTiming(0, 0, 0))
            advance(actualHold - 1)
            assertEquals("Changing the preference shortened the scheduled $actualHold ms nearby-cue hold; consumed positions=$consumedPositions", .25f,
                session.player.volume, .00001f)
            advance(1)
            assertEquals(1f, session.player.volume, .00001f)
            assertEquals(1, components.voice.requests.size)
        } finally { session.close(); subtitles.delete() }
    }

    @Test fun audioChangesAfterPauseSeekOrUnplugCannotReviveAnObsoleteVoice() {
        for (interruption in listOf("pause", "seek", "unplug")) {
            resetPreferences()
            val components = RecordingComponents()
            val session = create(components)
            try {
                session.previewVoice()
                val obsolete = components.voice.requests.single().first
                when (interruption) {
                    "pause" -> session.pause()
                    "seek" -> session.seek(0)
                    "unplug" -> {
                        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                        advance(0)
                    }
                }
                assertNull("The $interruption fixture must actually interrupt the voice", session.machine.snapshot().speakingId)
                val before = voiceState(session, components)
                accepted(session, AudioCommand.SetLevels(.65f, .3f))
                accepted(session, AudioCommand.SetDuckingTiming(200, 600, 400))
                accepted(session, AudioCommand.SetSpectralDucking(true))
                assertEquals(before, voiceState(session, components))
                components.voice.complete(obsolete)
                components.voice.fail(obsolete)
                advance(1_500)
                assertEquals("A late callback or audio change revived narration after $interruption", before,
                    voiceState(session, components))
                assertFalse(session.player.playWhenReady)
                assertFalse(session.machine.snapshot().wantsPlayback)
                assertEquals(.65f, session.player.volume, .00001f)
            } finally { session.close() }
        }
    }

    @Test fun snapshotsAreReadOnlyAndInvalidStoredLevelsUseFiniteDefaults() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
            .putFloat("movieVolume", Float.NaN).putFloat("duckVolume", Float.POSITIVE_INFINITY).commit()
        val components = RecordingComponents()
        val session = create(components)
        try {
            assertEquals(1f, session.prefs.movieVolume, 0f)
            assertEquals(.25f, session.prefs.duckVolume, 0f)
            session.previewVoice()
            advance(80)
            val preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap()
            val before = voiceState(session, components)
            components.commands.clear()
            repeat(5) {
                val snapshot = session.audioCommands.snapshot()
                assertEquals(AudioSettings(), snapshot.context.requested)
                assertEquals(.625f, requireNotNull(snapshot.configuredVolume), .00001f)
                assertEquals(false, snapshot.spectralEnabled)
                assertEquals(true, snapshot.speaking)
                assertEquals(0L, snapshot.processedSamples)
                session.diagnosticSnapshot()
            }
            assertEquals(preferences, context.getSharedPreferences("player", Context.MODE_PRIVATE).all)
            assertEquals(before, voiceState(session, components))
            assertTrue("Read-only audio snapshots dispatched component commands", components.commands.isEmpty())
            advance(80)
            assertEquals(.25f, session.player.volume, .00001f)
        } finally { session.close() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun realQuickMixControlsResetAllSixSettingsAndRefuseUnavailableOrStaleRequests() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        val components = RecordingComponents()
        val session = create(components, activity)
        val dialog = QuickMixDialog.show(activity, session, session.prefs)
        val views = descendants(requireNotNull(dialog.window).decorView).toList()
        fun button(label: String) = views.filterIsInstance<Button>().single { it.text.toString() == label }
        fun delta(description: String) = views.filterIsInstance<Button>().single { it.contentDescription?.toString() == description }
        fun spectral() = requireNotNull(dialog.findViewById<CheckBox>(R.id.audio_spectral_toggle))
        fun refresh() = requireNotNull(dialog.findViewById<Button>(R.id.audio_settings_refresh)).performClick()
        try {
            session.previewVoice()
            advance(160)
            val before = voiceState(session, components)
            delta("Volume du film : diminuer de cinq points de pourcentage").performClick()
            delta("Son du film pendant la voix : augmenter de cinq points de pourcentage").performClick()
            delta("Durée de baisse du film : augmenter de 40 millisecondes").performClick()
            delta("Durée de remontée du film : augmenter de 100 millisecondes").performClick()
            delta("Attente avant remontée entre deux répliques : augmenter de 50 millisecondes").performClick()
            spectral().performClick()
            assertEquals(AudioSettings(.95f, .30f, 200, 600, 400, true), session.audioCommands.snapshot().context.requested)
            assertEquals(.285f, session.player.volume, .00001f)
            assertEquals(before, voiceState(session, components))
            assertTrue(spectral().isChecked)

            for (label in listOf("Rétablir le volume du film à 100 %", "Rétablir le son pendant la voix à 25 %",
                "Rétablir Durée de baisse du film : 160 ms", "Rétablir Durée de remontée du film : 500 ms",
                "Rétablir Attente avant remontée entre deux répliques : 350 ms",
                "Rétablir le filtre spectral désactivé par défaut")) button(label).performClick()
            assertEquals(AudioSettings(), session.audioCommands.snapshot().context.requested)
            assertEquals(before, voiceState(session, components))
            assertFalse(spectral().isChecked)
            assertEquals(.25f, session.player.volume, .00001f)

            for ((bound, suffix) in listOf(0 to "diminuer", 2000 to "augmenter")) {
                accepted(session, AudioCommand.SetLevels(if (bound == 0) 0f else 1f, if (bound == 0) 0f else 1f))
                accepted(session, AudioCommand.SetDuckingTiming(bound, bound, bound))
                refresh()
                for (description in listOf("Volume du film : $suffix de cinq points de pourcentage",
                    "Son du film pendant la voix : $suffix de cinq points de pourcentage",
                    "Durée de baisse du film : $suffix de 40 millisecondes",
                    "Durée de remontée du film : $suffix de 100 millisecondes",
                    "Attente avant remontée entre deux répliques : $suffix de 50 millisecondes")) {
                    assertFalse("The step beyond a valid endpoint remains enabled", delta(description).isEnabled)
                }
            }
            accepted(session, AudioCommand.SetLevels(.004f, .996f))
            refresh()
            val almostZero = delta("Volume du film : diminuer de cinq points de pourcentage")
            val almostFull = delta("Son du film pendant la voix : augmenter de cinq points de pourcentage")
            assertTrue("A rounded 0% label must not block a valid decrease from 0.4%", almostZero.isEnabled)
            assertTrue("A rounded 100% label must not block a valid increase from 99.6%", almostFull.isEnabled)
            almostZero.performClick(); almostFull.performClick()
            assertEquals(0f, session.prefs.movieVolume, 0f)
            assertEquals(1f, session.prefs.duckVolume, 0f)
            assertFalse(almostZero.isEnabled)
            assertFalse(almostFull.isEnabled)
            components.audio.availability = CapabilityStatus.UNKNOWN
            val protected = session.audioCommands.snapshot()
            components.commands.clear()
            spectral().performClick()
            assertFalse("A refused spectral checkbox must display the retained setting", spectral().isChecked)
            assertEquals(protected, session.audioCommands.snapshot())
            assertTrue(requireNotNull(dialog.findViewById<TextView>(R.id.audio_settings_result)).text.contains("refusée"))
            assertTrue(components.commands.isEmpty())
            components.audio.availability = CapabilityStatus.CONDITIONAL

            accepted(session, AudioCommand.SetLevels(.5f, .5f))
            refresh()
            val generation = session.audioCommands.snapshot().context.generation
            openTextOnly(session)
            val replacement = session.audioCommands.snapshot()
            assertTrue(replacement.context.generation > generation)
            components.commands.clear()
            delta("Volume du film : diminuer de cinq points de pourcentage").performClick()
            assertEquals(replacement, session.audioCommands.snapshot())
            assertTrue(requireNotNull(dialog.findViewById<TextView>(R.id.audio_settings_result)).text.contains("autre ouverture"))
            assertTrue(components.commands.isEmpty())
        } finally { dialog.dismiss(); session.close(); owner.pause().stop().destroy() }
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun largeTextHelpAndRefreshAreReachableReadOnlyAndDestroyedControlsCannotWrite() {
        val font = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(2f)
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        val components = RecordingComponents()
        val session = create(components, activity)
        val dialog = QuickMixDialog.show(activity, session, session.prefs)
        try {
            val decor = requireNotNull(dialog.window).decorView
            val density = activity.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (520 * density).toInt()
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            val views = descendants(decor).toList()
            val scroll = views.filterIsInstance<ScrollView>().single { candidate ->
                descendants(candidate).any { it.id == R.id.audio_settings_refresh }
            }
            assertTrue("Long detailed audio help must be scrollable", scroll.getChildAt(0).height > scroll.height)
            assertTrue("The audio section needs a navigable heading", views.any { it.isAccessibilityHeading })
            val help = views.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            for (term in listOf("100 %", "25 %", "160", "500", "350", "2 000", "PCM16", "250", "3 200", "canaux", "prochain")) {
                assertTrue("A parameter's documented unit/default/limit is absent: $term", help.contains(term, ignoreCase = true))
            }
            // AlertDialog also contains unused hidden platform button slots;
            // inspect every actual audio control inside our scrollable panel.
            val controls = descendants(scroll).filterIsInstance<Button>().toList()
            for (control in controls) {
                assertTrue("An audio action has no readable label", control.text.isNotBlank())
                assertTrue("An audio action is not focusable", control.isFocusable)
                assertTrue("An audio action is smaller than the minimum touch target", control.minHeight >= 48 * density)
                assertTrue("A large-text audio action exceeds its scroll panel", control.width <= scroll.width)
                val layout = requireNotNull(control.layout)
                for (line in 0 until layout.lineCount) assertEquals("An audio action label is ellipsized", 0, layout.getEllipsisCount(line))
            }
            val snapshot = session.audioCommands.snapshot()
            val before = voiceState(session, components)
            val preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE).all.toMap()
            components.commands.clear()
            repeat(3) { requireNotNull(dialog.findViewById<Button>(R.id.audio_settings_refresh)).performClick() }
            assertEquals(snapshot, session.audioCommands.snapshot())
            assertEquals(before, voiceState(session, components))
            assertEquals(preferences, context.getSharedPreferences("player", Context.MODE_PRIVATE).all)
            assertTrue("Help or refresh submitted commands", components.commands.isEmpty())
            val oldVolume = controls.single { it.contentDescription?.toString() == "Volume du film : diminuer de cinq points de pourcentage" }
            val oldSpectral = requireNotNull(dialog.findViewById<CheckBox>(R.id.audio_spectral_toggle))
            dialog.dismiss()
            owner.pause().stop().destroy()
            // Keep the session alive deliberately: the view's own Activity
            // lifetime guard, not just session closure, must reject this write.
            oldVolume.performClick()
            oldSpectral.performClick()
            assertEquals(snapshot, session.audioCommands.snapshot())
            assertEquals(preferences, context.getSharedPreferences("player", Context.MODE_PRIVATE).all)
            assertTrue(components.commands.isEmpty())
        } finally {
            dialog.dismiss(); session.close()
            if (!activity.isDestroyed) owner.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(font)
        }
    }

    @Test fun staleGeneralVolumeSlidersRestoreTheirValuesAndShowTheRefusalInsideTheOpenDialog() {
        // The official Android TTS shadow supplies an installed local fixture
        // voice for this JVM UI test. Actual service IPC is tested on Android.
        ShadowTextToSpeech.addLanguageAvailability(Locale.FRANCE)
        ShadowTextToSpeech.addVoice(Voice("audio-ui-fixture-fr", Locale.FRANCE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = owner.get()
        var settingsDialog: AlertDialog? = null
        try {
            val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            val voice = shadowOf(tts)
            voice.onInitListener.onInit(TextToSpeech.SUCCESS)
            advance(0)
            assertTrue("The JVM fixture voice must be ready so a stray request could be observed", activity.session.speech.isReady)
            openTextOnly(activity.session)
            activity.setFullscreen(true)
            val cinema = requireNotNull(activity.findViewById<CinemaView>(R.id.cinema_root))
            descendants(cinema).filterIsInstance<Button>().single { it.text.toString() == "Voix / écran" }.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { settingsDialog = it }
            assertTrue(dialog.isShowing)
            val views = descendants(requireNotNull(dialog.window).decorView).toList()
            val originalGeneration = activity.session.audioCommands.snapshot().context.generation
            views.filterIsInstance<Button>().single { it.text.toString() == "Retirer le fichier de sous-titres ajouté" }.performClick()
            await("The real same-URI replacement did not become ready") {
                activity.session.player.playbackState == Player.STATE_READY &&
                    activity.session.audioCommands.snapshot().context.generation > originalGeneration
            }
            val session = activity.session
            val state = session.machine.snapshot()
            val pending = session.machine.pendingTexts()
            val spoken = voice.spokenTextList.toList()
            val synthesis = voice.lastSynthesizeToFileText
            val movie = views.filterIsInstance<SeekBar>().single { it.contentDescription?.toString()?.startsWith("Volume habituel du film") == true }
            val duck = views.filterIsInstance<SeekBar>().single { it.contentDescription?.toString()?.startsWith("Son du film conservé pendant la voix") == true }
            data class SliderMove(val control: SeekBar, val code: Int, val retained: Int, val label: String)
            for ((control, code, retained, label) in listOf(
                SliderMove(movie, KeyEvent.KEYCODE_DPAD_LEFT, 100, "Volume habituel du film"),
                SliderMove(duck, KeyEvent.KEYCODE_DPAD_RIGHT, 25, "Son du film conservé pendant la voix")
            )) {
                // Dispatch a real framework keyboard event: setting progress
                // programmatically would leave fromUser=false and test nothing.
                assertTrue(control.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)))
                advance(0)
                assertEquals(retained, control.progress)
                assertEquals("$label : $retained %", control.contentDescription.toString())
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "$label : $retained %" })
                val result = requireNotNull(dialog.findViewById<TextView>(R.id.audio_settings_general_result))
                assertTrue("The refusal must belong to the visible settings dialog", result.isShown)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, result.accessibilityLiveRegion)
                assertTrue(result.text.contains("autre ouverture"))
                assertTrue("A rejected slider must not dismiss settings", dialog.isShowing)
                assertSame("A rejected slider must not leave cinema mode", cinema,
                    activity.findViewById<CinemaView>(R.id.cinema_root))
                assertEquals(1f, session.prefs.movieVolume, 0f)
                assertEquals(.25f, session.prefs.duckVolume, 0f)
                assertEquals(state, session.machine.snapshot())
                assertEquals(pending, session.machine.pendingTexts())
                assertFalse(session.player.playWhenReady)
                assertEquals(spoken, voice.spokenTextList.toList())
                assertEquals(synthesis, voice.lastSynthesizeToFileText)
                assertSame(tts, ShadowTextToSpeech.getLastTextToSpeechInstance())
            }
        } finally {
            settingsDialog?.dismiss()
            owner.pause().stop().destroy()
        }
    }

    @Test fun advancedDspChangesPreserveActiveNarrationAndOnlySubmitToTheInstalledProcessor() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            session.previewVoice()
            advance(160)
            assertTrue(session.machine.cues(10_000_000, listOf("Panneau.", "Encore.", "Encore."),
                identities = listOf("dsp-panel", "dsp-repeat-1", "dsp-repeat-2")).isEmpty())
            val voice = voiceState(session, components)
            val notifications = components.notifications
            val original = session.audioCommands.snapshot()
            val desired = AudioDspSettings(enabled = true, preampDb = -5.25, balance = .35, mono = true,
                limiterCeilingDb = -2.5, limiterReleaseMs = 275,
                band3 = AudioEqBand(true, 1765.25, 3.5, 2.25))
            components.commands.clear()
            seedBenchmark(session)
            assertTrue(accepted(session, AudioCommand.SetDsp(desired)).changed)
            assertEquals(listOf("audio.dsp.submit"), components.commands)
            assertEquals(voice, voiceState(session, components))
            assertEquals(notifications, components.notifications)
            assertEquals(1, components.audio.sinkCreations)
            assertFalse(session.playbackBenchmark.active)
            assertEquals(desired, session.audioCommands.snapshot().context.requested.dsp)
            assertEquals(original.context.requested.copy(dsp = desired), session.audioCommands.snapshot().context.requested)
            assertEquals(AudioDspSettings(), original.context.requested.dsp)
            assertEquals(desired, requireNotNull(session.audioCommands.snapshot().dsp).submittedSettings)
            components.commands.clear()
            repeat(3) { session.audioCommands.snapshot() }
            assertTrue(components.commands.isEmpty())
            assertEquals(voice, voiceState(session, components))
        } finally { session.close() }
    }

    @Test fun advancedDspNoOpsRefusalsAndClosedSnapshotsDoNotMutateTheSession() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            session.previewVoice()
            advance(80)
            val voice = voiceState(session, components)
            val settings = session.audioCommands.snapshot().context.requested
            val beforeDsp = session.audioCommands.snapshot().dsp
            components.commands.clear()
            seedBenchmark(session)
            assertFalse(accepted(session, AudioCommand.SetDsp(AudioDspSettings(preampDb = -0.0))).changed)
            assertEquals(AudioCommandDecision.Rejected(0, AudioCommandRejection.NON_FINITE_VALUE),
                submit(session, AudioCommand.SetDsp(AudioDspSettings(preampDb = Double.NaN))))
            for ((status, reason) in listOf(CapabilityStatus.UNAVAILABLE to AudioCommandRejection.DSP_CAPABILITY_UNAVAILABLE,
                CapabilityStatus.UNKNOWN to AudioCommandRejection.DSP_CAPABILITY_UNKNOWN)) {
                components.audio.dspAvailability = status
                assertEquals(AudioCommandDecision.Rejected(0, reason),
                    submit(session, AudioCommand.SetDsp(AudioDspSettings(enabled = true))))
            }
            assertTrue(session.playbackBenchmark.active)
            assertEquals(settings, session.audioCommands.snapshot().context.requested)
            assertEquals(beforeDsp, session.audioCommands.snapshot().dsp)
            assertEquals(voice, voiceState(session, components))
            assertTrue(components.commands.isEmpty())
            val oldGeneration = session.audioCommands.snapshot().context.generation
            session.close()
            components.commands.clear()
            components.backend.failOnAnyPlayerAccess = true
            assertNull(session.audioCommands.snapshot().dsp)
            assertEquals(AudioCommandDecision.Rejected(oldGeneration, AudioCommandRejection.CLOSED),
                session.audioCommands.submit(AudioCommandRequest(oldGeneration,
                    AudioCommand.SetDsp(AudioDspSettings(enabled = true)))))
            assertTrue(components.commands.isEmpty())
        } finally { session.close() }
    }

    @Test fun benchmarkIdentityDistinguishesEveryDspControlAndRestoresWithTheSamePreferences() {
        val components = RecordingComponents()
        val session = create(components)
        try {
            // Invoke the real identity assembly without starting playback or
            // publishing synthetic performance measurements. Inputs other
            // than DSP stay fixed, including tracks and the voice identity.
            val method = PlaybackSession::class.java.getDeclaredMethod("benchmarkSettingsFingerprint")
                .apply { isAccessible = true }
            fun fingerprint() = method.invoke(session) as String
            val defaults = AudioDspSettings()
            val reference = fingerprint()
            val voice = voiceState(session, components)
            val alternatives = buildList {
                add(defaults.copy(enabled = true))
                add(defaults.copy(preampDb = -3.5))
                add(defaults.copy(balance = -.25))
                add(defaults.copy(mono = true))
                add(defaults.copy(limiterEnabled = false))
                add(defaults.copy(limiterCeilingDb = -3.0))
                add(defaults.copy(limiterReleaseMs = 275))
                for (index in 0 until 5) {
                    val band = defaults.band(index)
                    add(defaults.withBand(index, band.copy(enabled = true)))
                    add(defaults.withBand(index, band.copy(frequencyHz = band.frequencyHz + .25)))
                    add(defaults.withBand(index, band.copy(gainDb = 2.5)))
                    add(defaults.withBand(index, band.copy(q = 1.25)))
                }
            }
            val observed = mutableSetOf(reference)
            for (settings in alternatives) {
                assertTrue(accepted(session, AudioCommand.SetDsp(settings)).changed)
                val changed = fingerprint()
                assertNotEquals("Distinct DSP preferences must not share a benchmark settings identity", reference, changed)
                assertTrue("Each individual DSP control must be represented in the identity", observed.add(changed))
                assertFalse(accepted(session, AudioCommand.SetDsp(settings)).changed)
                assertEquals("An identical request must keep the same benchmark settings identity", changed, fingerprint())
                assertTrue(accepted(session, AudioCommand.SetDsp(defaults)).changed)
                assertEquals("Restoring all preferences must restore the identity", reference, fingerprint())
                assertEquals(voice, voiceState(session, components))
            }
            assertFalse(accepted(session, AudioCommand.SetDsp(defaults.copy(preampDb = -0.0, balance = -0.0,
                band1 = defaults.band1.copy(gainDb = -0.0)))).changed)
            assertEquals("Signed zero is the same configuration", reference, fingerprint())
            assertEquals(voice, voiceState(session, components))
        } finally { session.close() }
    }

    @Test fun expertPreferencesRoundTripPreciselyAndCorruptGroupsFallBackWithoutChangingVoice() {
        val prefs = PlayerPreferences(context)
        prefs.voiceVolume = .45f
        prefs.speechRate = 1.15f
        val desired = AudioDspSettings(enabled = true, preampDb = -7.125, balance = -.375, mono = true,
            limiterCeilingDb = -2.125, limiterReleaseMs = 275,
            band1 = AudioEqBand(true, 37.5, -2.375, .375),
            band5 = AudioEqBand(true, 17654.375, 5.125, 4.375))
        prefs.saveMovieAudioSettings(AudioSettings(.375f, .125f, 275, 715, 325, true, desired))
        val fresh = PlayerPreferences(context)
        assertEquals(desired, fresh.movieAudioSettings().dsp)
        assertEquals(.45f, fresh.voiceVolume, 0f)
        assertEquals(1.15f, fresh.speechRate, 0f)
        val session = create(RecordingComponents())
        try { assertEquals(desired, requireNotNull(session.audioCommands.snapshot().dsp).submittedSettings) }
        finally { session.close() }
        val encoded = AudioDspPreferences.encode(desired)
        for (corrupt in listOf("{", encoded.replace("\"version\":1", "\"version\":2"),
            encoded.replace("\"preampDb\":-7.125", "\"preampDb\":999"),
            encoded.replace("\"preampDb\":-7.125", "\"preampDb\":\"-7.125\""),
            encoded.replace("\"enabled\":true", "\"enabled\":\"true\""),
            encoded.replace("\"limiterReleaseMs\":275", "\"limiterReleaseMs\":275.5"), "x".repeat(4097))) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString(AudioDspPreferences.KEY, corrupt).commit()
            assertEquals(AudioDspSettings(), fresh.movieAudioSettings().dsp)
            assertEquals(.375f, fresh.movieVolume, 0f)
            assertEquals(.45f, fresh.voiceVolume, 0f)
            assertEquals(1.15f, fresh.speechRate, 0f)
        }
    }

    private class RecordingAudio(private val commands: MutableList<String>) : MovieAudio {
        private val actual = Media3MovieAudio()
        var availability = CapabilityStatus.CONDITIONAL
        var dspAvailability = CapabilityStatus.CONDITIONAL
        override val dsp: MovieDsp = object : MovieDsp by actual.dsp {
            override val capability get() = actual.dsp.capability.copy(status = dspAvailability)
            override fun submit(settings: AudioDspSettings) {
                commands += "audio.dsp.submit"
                actual.dsp.submit(settings)
            }
        }
        var sinkCreations = 0
        override var enabled: Boolean
            get() = actual.enabled
            set(value) { commands += "audio.setEnabled"; actual.enabled = value }
        override var speaking: Boolean
            get() = actual.speaking
            set(value) { commands += "audio.setSpeaking"; actual.speaking = value }
        override val processedSamples get() = actual.processedSamples
        override fun createSink(context: Context): AudioSink {
            sinkCreations++
            return actual.createSink(context)
        }
        override fun diagnosticCapability() = actual.diagnosticCapability().copy(status = availability)
    }

    private class RecordingBackend(private val actual: PlaybackBackend, private val commands: MutableList<String>) : PlaybackBackend by actual {
        val listeners = mutableListOf<Player.Listener>()
        var capturePositionReads = false
        val capturedPositions = mutableListOf<Long>()
        val capturedTracks = mutableListOf<List<String?>>()
        var failOnAnyPlayerAccess = false
        var closes = 0
        override val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
            check(!failOnAnyPlayerAccess) { "Released player accessed by ${method.name}" }
            if (method.name == "addListener") listeners += arguments!![0] as Player.Listener
            if (!method.name.startsWith("get") && !method.name.startsWith("is") && method.name !in setOf("equals", "hashCode", "toString"))
                commands += "player.${method.name}"
            val result = invoke(actual.player, method, arguments)
            if (capturePositionReads && method.name == "getCurrentPosition") capturedPositions += result as Long
            if (capturePositionReads && method.name == "getCurrentTracks") capturedTracks += (result as Tracks).groups
                .filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
                    (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it).id }
                }
            result
        } as Player
        fun emitCues(group: CueGroup) { listeners.forEach { it.onCues(group) } }
        fun playbackBarrier() = (actual as Media3PlaybackBackend).exoPlayer.createMessage { _, _ -> }.send()
        override fun close() { closes++; actual.close() }
    }

    private class RecordingComponents : PlaybackComponents {
        val commands = mutableListOf<String>()
        var notifications = 0
        lateinit var backend: RecordingBackend
        lateinit var voice: ControlledVoice
        val audio = RecordingAudio(commands)
        override fun createNarration() = record<NarrationController>(ExistingNarrationController(), "narration", commands,
            setOf("snapshot", "pendingTexts", "pendingPlan"))
        override fun createMovieAudio(): MovieAudio = audio
        override fun createVideoRenderer(): VideoRendererFactory = CinemaVideoRendererFactory()
        override fun createPlayback(context: Context, preferences: PlayerPreferences, movieAudio: MovieAudio,
            videoRenderer: VideoRendererFactory): PlaybackBackend =
            RecordingBackend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer), commands).also { backend = it }
        override fun createSubtitles(context: Context): SubtitleSource = Media3SubtitleSource(context)
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService {
            voice = ControlledVoice(onAvailability, onDone, onFailure)
            return record<SpeechService>(voice, "speech", commands, setOf("engines"))
        }
    }

    private companion object {
        fun invoke(actual: Any, method: java.lang.reflect.Method, arguments: Array<out Any?>?): Any? = try {
            method.invoke(actual, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) { throw failure.targetException }

        inline fun <reified T : Any> record(actual: T, name: String, commands: MutableList<String>, queries: Set<String>): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
                if (!method.name.startsWith("get") && !method.name.startsWith("is") && method.name !in queries &&
                    method.name !in setOf("equals", "hashCode", "toString")) commands += "$name.${method.name}"
                invoke(actual, method, arguments)
            } as T
    }

    private class ControlledVoice(
        private val availability: (Boolean, String) -> Unit,
        private val done: (String) -> Unit,
        private val failed: (String, String) -> Unit
    ) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled JVM voice boundary"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "audio-command-voice"
        override val engineDiagnosticIdentity = "audio-command-voice"
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
        override val preparationStatus = "No actual synthesis in this JVM test"
        fun publishReady() { isReady = true; availability(true, "Controlled voice ready") }
        fun complete(id: String) = done(id)
        fun fail(id: String) = failed(id, "Controlled late failure")
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
            remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) { requests += id to text }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) = Unit
        override fun prepareAhead(texts: List<String>) = Unit
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() = Unit
        override fun clearCache() = Unit
        override fun close() = Unit
    }
}
