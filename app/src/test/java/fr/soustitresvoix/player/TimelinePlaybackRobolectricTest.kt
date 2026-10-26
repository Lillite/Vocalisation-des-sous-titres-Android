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

    private inner class Fixture(text: String = ordinarySubtitles(), private val sharedFile: File? = null,
        restore: Boolean = false) : AutoCloseable {
        val components = Components()
        val messages = mutableListOf<Pair<String, Boolean>>()
        val captions = mutableListOf<String>()
        val session = PlaybackSession(context, PlayerPreferences(context), {}, { text, error -> messages += text to error },
            { captions += it }, components)
        val file = sharedFile ?: File.createTempFile("timing-synthetic-", ".srt", context.cacheDir).apply { writeText(text) }
        val media = SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}", "Timing fixture",
            subtitlePath = file.path, subtitleMime = MimeTypes.APPLICATION_SUBRIP)
        init {
            components.voice.ready()
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            var restorationNotified = false
            session.onSessionRestored = { restorationNotified = true }
            session.open(media, restoreSession = restore)
            await("Synthetic subtitle track did not become ready") {
                (session.player.playbackState == Player.STATE_READY || (restore && session.player.playbackState == Player.STATE_ENDED)) && session.selectedTextId() == "external-subtitle"
            }
            if (restore) await("Session restoration did not finish: $messages") { restorationNotified }
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
        override fun close() { components.releaseScan.countDown(); session.close(); if (sharedFile == null) file.delete() }
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

    private fun pendingSubtitles() = "1\n00:00:01,000 --> 00:00:02,000\nFirst reply.\n\n2\n00:00:02,000 --> 00:00:03,000\nSecond reply.\n"
    private fun flushAndForgetProcessMemory(f: Fixture) {
        f.session.savePosition()
        val uri = f.media.uri
        await("Live pending occurrence did not reach the durable record") {
            MediaSessionStore(context).get(uri)?.narrationCheckpoint != null
        }
        f.session.close()
        val writer = PlaybackFeatures::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(f.features()) as java.util.concurrent.ExecutorService
        writer.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        // A recreated process has no Activity handoff cache: force the next owner
        // to read the real JSON and hashed journal from disk.
        PlaybackFeatures::class.java.getDeclaredField("COORDINATORS").apply { isAccessible = true }
            .get(null).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<String, Any>).clear() }
    }
    private fun pressResume(f: Fixture, button: Int = android.content.DialogInterface.BUTTON_POSITIVE) {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            f.session.showResumeSession(controller.get())
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            dialog.getButton(button).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        } finally { controller.pause().stop().destroy() }
    }
    private fun translate(f: Fixture) {
        var preview: SubtitleTranslationSession.Preview? = null
        f.session.translation.prepare("en", "checkpoint-fixture") { preview = it }
        await("Translation source index unavailable") { preview != null }
        f.session.translation.start(requireNotNull(preview)) { object : TranslationEngine {
            override val id = "checkpoint-fixture"
            override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                cancelled: () -> Boolean) = texts.map { "FR : $it" }
            override fun close() = Unit
        } }
        await("Controlled complete translation did not activate") { f.session.translation.complete && f.session.translation.ready }
        assertTrue(f.session.translation.saveCurrentTranslation())
    }

    @Test fun simpleResumeButtonAfterProcessRecreationRecoversExpiredSpeechAndQueuedReply() {
        val file = File.createTempFile("resume-source-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            old.timeline(SubtitleTimeline(globalOffsetMs = 1)); old.session.play(); old.at(1001); old.at(2001)
            assertEquals(listOf("First reply."), old.components.voice.requests.map { it.second })
            assertTrue(old.session.machine.snapshot().waitingForSpeech)
            val obsolete = old.session.machine.snapshot().speakingId!!
            flushAndForgetProcessMemory(old)
            Fixture(sharedFile = file, restore = true).use { restored ->
                assertEquals(2001L, restored.session.player.currentPosition)
                assertFalse(restored.session.machine.snapshot().wantsPlayback)
                assertFalse(restored.session.player.playWhenReady)
                assertTrue(restored.components.voice.requests.isEmpty())
                assertEquals("external-subtitle", restored.session.savedSessionRecord()!!.narrationCheckpointTrackId)
                pressResume(restored)
                await("Simple Resume did not recover the expired first source occurrence") { restored.components.voice.requests.isNotEmpty() }
                assertEquals("First reply.", restored.components.voice.requests.first().second)
                assertEquals(1001L, restored.session.player.currentPosition)
                old.components.voice.complete(obsolete)
                assertEquals(0, restored.session.machine.journalSnapshot().completedCount)
                restored.at(2001); restored.drain()
                assertEquals(listOf("First reply.", "Second reply."), restored.components.voice.requests.map { it.second })
                assertEquals(2, restored.session.machine.journalSnapshot().completedCount)
            }
        } finally { file.delete() }
    }

    @Test fun translatedCheckpointRestoresPausedAndResumesAtTheMappedNarrationTime() {
        val file = File.createTempFile("translated-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            translate(old)
            old.timeline(SubtitleTimeline(globalOffsetMs = 500, narrationOffsetMs = 200))
            old.session.play(); old.at(1700); old.at(2700)
            assertEquals(listOf("FR : First reply."), old.components.voice.requests.map { it.second })
            flushAndForgetProcessMemory(old)
            Fixture(sharedFile = file, restore = true).use { restored ->
                assertTrue(restored.session.translation.ready)
                assertFalse(restored.session.machine.snapshot().wantsPlayback)
                assertTrue(restored.components.voice.requests.isEmpty())
                pressResume(restored)
                await("Translated first occurrence did not resume") { restored.components.voice.requests.isNotEmpty() }
                assertEquals(1700L, restored.session.player.currentPosition)
                restored.at(2700); restored.drain()
                assertEquals(listOf("FR : First reply.", "FR : Second reply."), restored.components.voice.requests.map { it.second })
                assertEquals(2, restored.session.machine.journalSnapshot().completedCount)
            }
        } finally { file.delete() }
    }

    @Test fun explicitSeekAfterRestorationDoesNotResurrectTheAbandonedOldQueue() {
        val file = File.createTempFile("seek-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            old.timeline(SubtitleTimeline(globalOffsetMs = 1)); old.session.play(); old.at(1001); old.at(2001)
            flushAndForgetProcessMemory(old)
            Fixture(sharedFile = file, restore = true).use { restored ->
                restored.session.seek(2501); restored.at(2501)
                restored.session.savePosition()
                assertNull(restored.session.savedSessionRecord()!!.narrationCheckpoint?.takeIf { it.startMs == 1000L })
                pressResume(restored); restored.drain()
                assertEquals(listOf("Second reply."), restored.components.voice.requests.map { it.second })
                assertEquals(2501L, restored.session.player.currentPosition)
                assertEquals(1, restored.session.machine.journalSnapshot().completedCount)
            }
        } finally { file.delete() }
    }

    @Test fun replayBeforeResumeConsumesTheExplicitReplyOnceAndPreservesLaterExpiredWork() {
        val file = File.createTempFile("manual-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            old.timeline(SubtitleTimeline(globalOffsetMs = 1)); old.session.play(); old.at(1001); old.at(2001); old.at(3001)
            flushAndForgetProcessMemory(old)
            Fixture(sharedFile = file, restore = true).use { restored ->
                pressResume(restored, android.content.DialogInterface.BUTTON_NEUTRAL)
                await("Explicit interrupted replay did not start") { restored.components.voice.requests.isNotEmpty() }
                restored.drain()
                assertEquals(listOf("First reply."), restored.components.voice.requests.map { it.second })
                assertFalse(restored.session.machine.snapshot().wantsPlayback)
                pressResume(restored); restored.at(2001); restored.drain()
                assertEquals(listOf("First reply.", "Second reply."), restored.components.voice.requests.map { it.second })
                assertEquals(2, restored.session.machine.journalSnapshot().completedCount)
            }
        } finally { file.delete() }
    }

    @Test fun pauseOrSeekDuringCheckpointIndexingCannotStartAnObsoleteResumeRequest() {
        for (action in listOf("pause", "seek")) {
            val file = File.createTempFile("async-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
            try {
                val old = Fixture(sharedFile = file)
                old.timeline(SubtitleTimeline(globalOffsetMs = 1)); old.session.play(); old.at(1001); old.at(2001)
                flushAndForgetProcessMemory(old)
                Fixture(sharedFile = file, restore = true).use { restored ->
                    PlaybackSession::class.java.getDeclaredField("indexedSource").apply { isAccessible = true }.set(restored.session, null)
                    restored.components.blockNextScan()
                    pressResume(restored)
                    await("Recovery did not enter the controlled asynchronous indexer") { restored.components.scanEntered.count == 0L }
                    restored.session.savePosition()
                    assertNotNull(restored.session.savedSessionRecord()!!.narrationCheckpoint)
                    if (action == "pause") restored.session.pause() else restored.session.seek(2501)
                    restored.components.releaseScan.countDown()
                    await("Cancelled recovery scanner did not return") { restored.components.scanFinished.count == 0L }
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                    assertFalse(action, restored.session.machine.snapshot().wantsPlayback)
                    assertTrue(action, restored.components.voice.requests.isEmpty())
                    assertEquals(if (action == "pause") 2001L else 2501L, restored.session.player.currentPosition)
                    restored.session.savePosition()
                    if (action == "pause") assertNotNull(restored.session.savedSessionRecord()!!.narrationCheckpoint)
                    else assertNull(restored.session.savedSessionRecord()!!.narrationCheckpoint)
                }
            } finally { file.delete() }
        }
    }

    @Test fun restoredCheckpointBeyondVideoEndFinishesEveryPendingTranslatedTimeBoundary() {
        val file = File.createTempFile("eof-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            old.timeline(SubtitleTimeline(globalOffsetMs = 30_000)); old.session.play(); old.at(31_000); old.at(32_000)
            flushAndForgetProcessMemory(old)
            Fixture(sharedFile = file, restore = true).use { restored ->
                assertFalse(restored.session.machine.snapshot().wantsPlayback)
                assertTrue(restored.components.voice.requests.isEmpty())
                pressResume(restored)
                await("EOF checkpoint did not restore its pending source speech") { restored.components.voice.requests.isNotEmpty() }
                restored.drain()
                assertEquals(listOf("First reply.", "Second reply."), restored.components.voice.requests.map { it.second })
                assertEquals(2, restored.session.machine.journalSnapshot().completedCount)
                await("Finished EOF narration did not leave playback paused") { !restored.session.machine.snapshot().wantsPlayback }
            }
        } finally { file.delete() }
    }

    @Test fun checkpointChoosesTheEarliestMappedPendingOccurrenceInsteadOfTheEarliestSourceTime() {
        Fixture(pendingSubtitles()).use { f ->
            f.timeline(SubtitleTimeline(portions = listOf(SubtitleTimeline.Portion(1000, 2000, 5000))))
            val entries = f.session.ahead.selected("external-subtitle")
            f.session.applyNarration(f.session.machine.cues(0, entries.map { it.raw }, identities = entries.map { it.identity }))
            f.session.savePosition()
            val checkpoint = requireNotNull(f.session.savedSessionRecord()!!.narrationCheckpoint)
            assertEquals(2000L, checkpoint.startMs)
            assertEquals(fr.soustitresvoix.core.NarrationLedger.hashIdentity(entries[1].identity), checkpoint.id)
        }
    }

    @Test fun unresolvedRestoredCheckpointStaysPausedAndIsNotSilentlyDiscarded() {
        val file = File.createTempFile("missing-resume-", ".srt", context.cacheDir).apply { writeText(pendingSubtitles()) }
        try {
            val old = Fixture(sharedFile = file)
            old.timeline(SubtitleTimeline(globalOffsetMs = 1)); old.session.play(); old.at(1001); old.at(2001)
            flushAndForgetProcessMemory(old)
            val store = MediaSessionStore(context)
            val uri = old.media.uri
            val checkpoint = OccurrenceResume("0".repeat(64), 1000, 2000)
            store.save(requireNotNull(store.get(uri)).copy(narrationCheckpoint = checkpoint))
            Fixture(sharedFile = file, restore = true).use { restored ->
                pressResume(restored)
                assertTrue(restored.components.voice.requests.isEmpty())
                assertFalse(restored.session.machine.snapshot().wantsPlayback)
                assertEquals(checkpoint, restored.session.savedSessionRecord()!!.narrationCheckpoint)
                assertTrue(restored.messages.any { it.second && it.first.contains("encore en attente") })
            }
        } finally { file.delete() }
    }

    private class Backend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        var positionMs = 0L
        private val listeners = mutableListOf<Player.Listener>()
        override val player: Player = object : ForwardingPlayer(delegate.player) {
            override fun getCurrentPosition(): Long = positionMs
            override fun seekTo(position: Long) { positionMs = position; super.seekTo(position) }
            override fun seekTo(mediaItemIndex: Int, position: Long) { positionMs = position; super.seekTo(mediaItemIndex, position) }
            override fun addListener(listener: Player.Listener) { listeners += listener; super.addListener(listener) }
            override fun removeListener(listener: Player.Listener) { listeners -= listener; super.removeListener(listener) }
        }
        override fun setMediaSource(source: androidx.media3.exoplayer.source.MediaSource, startPositionMs: Long) {
            positionMs = startPositionMs; delegate.setMediaSource(source, startPositionMs)
        }
        fun deliver(text: String, timeUs: Long) {
            val group = CueGroup(listOf(Cue.Builder().setText(text).build()), timeUs)
            listeners.toList().forEach { it.onCues(group) }
        }
    }
    private class Components : PlaybackComponents by DefaultPlaybackComponents {
        private val shouldBlockScan = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var scanEntered = java.util.concurrent.CountDownLatch(0)
        @Volatile var releaseScan = java.util.concurrent.CountDownLatch(0)
        @Volatile var scanFinished = java.util.concurrent.CountDownLatch(0)
        fun blockNextScan() {
            scanEntered = java.util.concurrent.CountDownLatch(1)
            releaseScan = java.util.concurrent.CountDownLatch(1)
            scanFinished = java.util.concurrent.CountDownLatch(1)
            shouldBlockScan.set(true)
        }
        override fun createSubtitles(context: Context): SubtitleSource {
            val delegate = Media3SubtitleSource(context)
            return object : SubtitleSource by delegate {
                override fun createIndexer(): SubtitleIndexer {
                    val scanner = delegate.createIndexer()
                    return object : SubtitleIndexer by scanner {
                        override fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
                            if (shouldBlockScan.getAndSet(false)) {
                                scanEntered.countDown()
                                check(releaseScan.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Controlled checkpoint scan was not released" }
                            }
                            return try { scanner.scan(media, progress) } finally { scanFinished.countDown() }
                        }
                    }
                }
            }
        }
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
