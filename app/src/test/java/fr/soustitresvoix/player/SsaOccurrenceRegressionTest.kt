package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.RealCueTimeline
import androidx.media3.extractor.*
import androidx.media3.extractor.text.*
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.SpeechTools
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real parser, muxed samples, app index and narration controller. Voice completions
 * are deliberately supplied by the test: this does not assert external engine audio.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SsaOccurrenceRegressionTest {
    private val filters = SpeechTools.Filters()
    private val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
    private val external = "external-subtitle"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private data class Parsed(val index: CueAheadIndex, val groups: List<CuesWithTiming>, val track: String?)
    private fun bytes(name: String): ByteArray = requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssa/$name")) {
        "Synthetic regression resource missing: $name"
    }.use { it.readBytes() }
    private fun fixture(name: String): File = File(context.cacheDir, name).also { it.writeBytes(bytes(name)) }
    private fun factory(index: CueAheadIndex) = CueAheadFactory { track, group -> index.add(track, group) }
        .setOccurrenceListener { track, group, origins -> index.add(track, group, origins) }
    private fun parse(name: String, options: SubtitleParser.OutputOptions = SubtitleParser.OutputOptions.allCues()): Parsed {
        val index = CueAheadIndex()
        val groups = mutableListOf<CuesWithTiming>()
        val parser = factory(index).sidecarParsers().create(format)
        val source = bytes(name)
        parser.parse(source, 0, source.size, options, Consumer { groups += it })
        assertFalse(index.truncated)
        return Parsed(index, groups, external)
    }
    private fun original(source: ByteArray, options: SubtitleParser.OutputOptions): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        DefaultSubtitleParserFactory().create(format).parse(source, 0, source.size, options, Consumer { out += it })
        return out
    }
    private fun sameDisplay(expected: List<CuesWithTiming>, actual: List<CuesWithTiming>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a,b) ->
            assertEquals(a.startTimeUs, b.startTimeUs); assertEquals(a.durationUs, b.durationUs)
            assertEquals(a.cues, b.cues)
        }
    }
    private fun ready(): NarrationController = ExistingNarrationController().apply {
        setMode(NarrationMachine.Mode.ADAPTIVE); setSpeechReady(true); setMediaReady(true); play()
    }
    private fun forward(machine: NarrationController, parsed: Parsed, group: CueGroup): List<NarrationMachine.Effect> {
        val clean = group.cues.mapNotNull { it.text?.toString() }
            .map { SpeechTools.filter(it, filters) }.filter { it.isNotBlank() }
        val items = parsed.index.resolved(parsed.track, clean, group.presentationTimeUs, filters)
        assertNotNull("Every displayed occurrence must resolve, including identical overlapping events", items)
        return machine.cues(group.presentationTimeUs, clean,
            identities = items!!.map { it.identity }, deadlinesMs = items.map { it.endUs / 1000 + 600 })
    }
    private fun drain(machine: NarrationController, first: List<NarrationMachine.Effect>): List<String> {
        val out = first.filterIsInstance<NarrationMachine.Effect.Speak>().map { it.text }.toMutableList()
        var iterations = 0
        while (machine.snapshot().speakingId != null) {
            assertTrue("Unexpected narration loop", ++iterations < 1000)
            out += machine.speechFinished(machine.snapshot().speakingId!!)
                .filterIsInstance<NarrationMachine.Effect.Speak>().map { it.text }
        }
        return out
    }
    private fun spoken(parsed: Parsed): List<String> {
        val machine = ready()
        return RealCueTimeline.frames(parsed.groups).flatMap { drain(machine, forward(machine, parsed, it)) }
    }

    @Test fun persistentExternalPanelKeepsOneOriginalIdentityAndOneNarration() {
        val parsed = parse("full_persistent_sign.ass")
        assertEquals(7, parsed.groups.size)
        val panel = parsed.index.selected(external).filter { it.raw == "ACCÈS INTERDIT" }.single()
        assertEquals(1_000_000, panel.startUs); assertEquals(9_000_000, panel.endUs)
        assertNotNull(panel.sourceToken)
        assertEquals(4, parsed.index.count)
        assertEquals(listOf("ACCÈS INTERDIT", "Bonjour.", "Nous partons.", "Très bien."), spoken(parsed))
        sameDisplay(original(bytes("full_persistent_sign.ass"), SubtitleParser.OutputOptions.allCues()), parsed.groups)
        println("FIX1 external: one source panel across seven display intervals -> one Speak; all three dialogues retained")
    }

    @Test fun persistentPanelUsesItsOriginalDeadlineInsteadOfTheNextDisplayBoundary() {
        val parsed = parse("full_persistent_sign.ass")
        val machine = ready()
        val first = forward(machine, parsed, RealCueTimeline.frames(parsed.groups).first())
            .filterIsInstance<NarrationMachine.Effect.Speak>().single()
        assertEquals(9600L, first.deadlineMs)
        machine.advance(2600)
        assertTrue(machine.snapshot().videoRequested)
        assertFalse(machine.snapshot().waitingForSpeech)
        machine.advance(9600)
        assertTrue(machine.snapshot().waitingForSpeech)
    }

    @Test fun genuineRepeatsAdjacentRepeatsAndReappearingPanelsRemainDistinct() {
        for (name in listOf("real_repeated_dialogues.ass", "genuine_contiguous.ass", "identical_reappearance.ass")) {
            val parsed = parse(name)
            assertEquals(name, 2, parsed.index.count)
            assertEquals(name, 2, parsed.index.selected(external).map { it.identity }.distinct().size)
            assertEquals(name, 2, spoken(parsed).size)
        }
    }

    @Test fun twoSimultaneousNamedSpeakersAreNotDeduplicatedEvenWhenCuesCompareEqual() {
        val parsed = parse("simultaneous_named.ass")
        val cues = parsed.groups.single().cues
        assertEquals(2, cues.size)
        assertEquals("Equal visual Cue values still represent two source events", cues[0], cues[1])
        assertNotSame(cues[0], cues[1])
        assertEquals(2, parsed.index.selected(external).map { it.identity }.distinct().size)
        assertEquals(listOf("Oui.", "Oui."), spoken(parsed))
    }

    @Test fun seekOutputOptionsPreserveActualParserDisplayOrderAndSourceIdentities() {
        val all = parse("full_persistent_sign.ass")
        val expectedIds = all.index.selected(external).associate { it.raw to it.identity }
        val source = bytes("full_persistent_sign.ass")
        for (time in listOf(0L, 2_000_000L, 4_500_000L, 9_000_000L, 10_000_000L)) {
            for (options in listOf(SubtitleParser.OutputOptions.onlyCuesAfter(time),
                SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(time))) {
                val parsed = parse("full_persistent_sign.ass", options)
                sameDisplay(original(source, options), parsed.groups)
                parsed.index.selected(external).forEach { assertEquals(expectedIds[it.raw], it.identity) }
            }
        }
    }

    @Test fun parserResetAndByteSliceDoNotInventNewIdentities() {
        val index = CueAheadIndex()
        val parser = factory(index).sidecarParsers().create(format)
        val source = bytes("full_persistent_sign.ass")
        val padded = ByteArray(source.size + 23) { 0x7f }.also { source.copyInto(it, 11) }
        parser.parse(source, 0, source.size, SubtitleParser.OutputOptions.allCues(), Consumer {})
        val first = index.selected(external).map { it.identity }
        parser.reset()
        parser.parse(padded, 11, source.size, SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(4_500_000), Consumer {})
        assertEquals(first, index.selected(external).map { it.identity })
        assertEquals(4, index.count)
    }

    @Test fun emptyAndGappedSourcesKeepTheRealParserOutputWithoutInventingAnUnboundedLastCue() {
        val full = bytes("full_persistent_sign.ass").toString(Charsets.UTF_8)
        val header = full.substringBefore("Dialogue:").toByteArray(Charsets.UTF_8)
        val actual = mutableListOf<CuesWithTiming>()
        val parser = factory(CueAheadIndex()).sidecarParsers().create(format)
        parser.parse(header, 0, header.size, SubtitleParser.OutputOptions.allCues(), Consumer { actual += it })
        assertTrue(actual.isEmpty())
        sameDisplay(original(header, SubtitleParser.OutputOptions.allCues()), actual)
        val gap = parse("real_repeated_dialogues.ass")
        sameDisplay(original(bytes("real_repeated_dialogues.ass"), SubtitleParser.OutputOptions.allCues()), gap.groups)
        assertTrue(gap.groups.all { it.durationUs > 0 && it.endTimeUs > it.startTimeUs })
        assertTrue(RealCueTimeline.frames(gap.groups).last().cues.isEmpty())
    }

    @Test fun annotationLimitPreservesAllDisplayOutputAndResetAllowsTheNextNormalParse() {
        val header = bytes("full_persistent_sign.ass").toString(Charsets.UTF_8).substringBefore("Dialogue:")
        fun clock(centiseconds: Int): String = "0:%02d:%02d.%02d".format(centiseconds / 6000, centiseconds / 100 % 60, centiseconds % 100)
        val source = buildString {
            append(header)
            append("Dialogue: 0,0:00:00.00,0:00:30.00,Sign,,0,0,0,,").append("A".repeat(12_000)).append('\n')
            repeat(1001) { i -> append("Dialogue: 1,${clock(2*i+1)},${clock(2*i+2)},Dialogue,ALICE,0,0,0,,B.\n") }
        }.toByteArray(Charsets.UTF_8)
        val expected = original(source, SubtitleParser.OutputOptions.allCues())
        assertTrue(expected.sumOf { group -> group.cues.sumOf { it.text?.length?.toLong() ?: 0L } } > 12_000_000)
        val actual = mutableListOf<CuesWithTiming>()
        var limits = 0
        var nullAnnotations = 0
        val parser = SsaOccurrenceParser(DefaultSubtitleParserFactory().create(format), format, { limits++ }) { group, origins, output ->
            if (origins == null) nullAnnotations++
            output.accept(group)
        }
        parser.parse(source, 0, source.size, SubtitleParser.OutputOptions.allCues(), Consumer { actual += it })
        assertEquals(1, limits)
        assertEquals(expected.size, nullAnnotations)
        sameDisplay(expected, actual)
        parser.reset(); actual.clear(); nullAnnotations = 0
        val normal = bytes("full_persistent_sign.ass")
        parser.parse(normal, 0, normal.size, SubtitleParser.OutputOptions.allCues(), Consumer { actual += it })
        assertEquals(1, limits); assertEquals(0, nullAnnotations)
        sameDisplay(original(normal, SubtitleParser.OutputOptions.allCues()), actual)
    }

    @Test fun explicitSeekAllowsOneIntentionalPanelReplayButRejectsOldVoiceCompletion() {
        val parsed = parse("full_persistent_sign.ass")
        val frames = RealCueTimeline.frames(parsed.groups)
        val machine = ready()
        val oldId = forward(machine, parsed, frames.first()).filterIsInstance<NarrationMachine.Effect.Speak>().single().id
        machine.reset(false); machine.setMediaReady(true)
        val mid = frames.first { it.presentationTimeUs == 5_000_000L }
        assertTrue(forward(machine, parsed, mid).none { it is NarrationMachine.Effect.Speak })
        val resumed = machine.play().filterIsInstance<NarrationMachine.Effect.Speak>().single()
        assertEquals("ACCÈS INTERDIT", resumed.text)
        assertNotEquals(oldId, resumed.id)
        assertTrue(machine.speechFinished(oldId).isEmpty())
        assertEquals(resumed.id, machine.snapshot().speakingId)
        assertEquals(listOf("ACCÈS INTERDIT"), drain(machine, listOf(resumed)))
    }

    @Test fun allAmbiguousPhysicalLayerEventsRemainPreserved() {
        val parsed = parse("full_layered_sign.ass")
        assertEquals(24, parsed.groups.single().cues.size)
        assertEquals(24, parsed.index.count)
        assertEquals(24, parsed.index.selected(external).map { it.identity }.distinct().size)
        assertEquals(24, spoken(parsed).size)
    }

    @Test fun fullExternalScanUsesTheSameOriginalIdentitiesAsLiveParsing() {
        val source = fixture("full_persistent_sign.ass")
        val full = FullSubtitleIndexer(context).scan(SavedMedia("file:///unused", "Synthetic", subtitlePath = source.path,
            subtitleMime = MimeTypes.TEXT_SSA)) {}
        val live = parse("full_persistent_sign.ass").index
        assertEquals(live.selected(external), full.selected(external))
    }

    @Test fun embeddedPacketsHaveAbsoluteTimesAndTheSameIdentitiesAsFullIndexing() {
        val parsed = extract("full_persistent_sign.mkv")
        val file = fixture("full_persistent_sign.mkv")
        val full = FullSubtitleIndexer(context).scan(SavedMedia(Uri.fromFile(file).toString(), "Synthetic")) {}
        assertEquals(4, full.count)
        assertEquals(parsed.index.selected(parsed.track), full.selected(parsed.track))
        val panel = full.selected(parsed.track).single { it.raw == "ACCÈS INTERDIT" }
        assertEquals(1_000_000L, panel.startUs); assertEquals(9_000_000L, panel.endUs)
        assertEquals(listOf("ACCÈS INTERDIT", "Bonjour.", "Nous partons.", "Très bien."), spoken(parsed))
        assertEquals(full.selected(parsed.track), extract("full_persistent_sign.mkv").index.selected(parsed.track))
        println("FIX1 embedded: actual Matroska parser, encoded Cue samples, absolute offsets and full scan agree")
    }

    @Test fun embeddedRepeatedAndSimultaneousDialoguesRemainTwoOccurrences() {
        for (name in listOf("real_repeated_dialogues.mkv", "genuine_contiguous.mkv", "simultaneous_named.mkv", "identical_reappearance.mkv")) {
            val parsed = extract(name)
            assertEquals(name, 2, parsed.index.count)
            assertEquals(name, 2, parsed.index.selected(parsed.track).map { it.identity }.distinct().size)
            assertEquals(name, 2, spoken(parsed).size)
        }
    }

    @Test fun identicalReadOrderInDifferentEmbeddedTracksNeverSharesAnOccurrenceIdentity() {
        val file = fixture("two_identical_tracks.mkv")
        val full = FullSubtitleIndexer(context).scan(SavedMedia(Uri.fromFile(file).toString(), "Synthetic")) {}
        // FFmpeg fixture maps one video, then these two identical subtitle tracks.
        val first = full.selected("2")
        val second = full.selected("3")
        assertEquals(4, first.size); assertEquals(4, second.size)
        assertEquals(first.map { it.raw }, second.map { it.raw })
        assertEquals(first.map { it.sourceToken }, second.map { it.sourceToken })
        assertTrue(first.map { it.identity }.toSet().intersect(second.map { it.identity }.toSet()).isEmpty())
    }

    // These are literal expectations, not expectations calculated by the splitter
    // under test. All punctuation and words must reach the outgoing Speak requests.
    private val preservedSegments = listOf(
        "ATTENTION…",
        "Attends, encore ; écoute : « tout doit être lu ».",
        "Pourquoi ?",
        "Parce que c’est important !",
        "Et cetera."
    )
    private val preservedText = preservedSegments.joinToString(" ")
    private val preservedDialogues = listOf(
        "Bonjour ! Tu viens ?", "Oui ; à 3,14 mètres, exactement.",
        "Très bien… Nous restons ensemble."
    )
    private fun source(stem: String, extension: String): Parsed =
        if (extension == "ass") parse("$stem.ass") else extract("$stem.mkv")
    private fun withoutWhitespace(text: String) = text.filterNot { it.isWhitespace() }
    private fun assertCompleteText(expected: List<String>, actual: List<NarrationMachine.Effect.Speak>) {
        assertEquals(withoutWhitespace(expected.joinToString(" ")),
            withoutWhitespace(actual.joinToString(" ") { it.text }))
        assertEquals("Every emitted request has a fresh callback identity", actual.size, actual.map { it.id }.distinct().size)
    }
    private fun drainComplete(machine: NarrationController, first: List<NarrationMachine.Effect>,
        heldUntilDrained: Boolean = false): List<NarrationMachine.Effect.Speak> {
        val spoken = mutableListOf<NarrationMachine.Effect.Speak>()
        fun collect(effects: List<NarrationMachine.Effect>) {
            assertFalse("Preserving a bounded synthetic fixture must not overflow or fail",
                effects.any { it is NarrationMachine.Effect.Message && it.error })
            spoken += effects.filterIsInstance<NarrationMachine.Effect.Speak>()
        }
        collect(first)
        var completions = 0
        while (machine.snapshot().speakingId != null) {
            assertTrue("Unexpected narration loop", ++completions < 1000)
            if (heldUntilDrained) {
                assertTrue(machine.snapshot().waitingForSpeech)
                assertFalse(machine.snapshot().videoRequested)
            }
            collect(machine.speechFinished(machine.snapshot().speakingId!!))
        }
        assertEquals("No unfinished segment may remain stranded", 0, machine.snapshot().pendingCount)
        assertTrue(machine.snapshot().wantsPlayback)
        assertTrue(machine.snapshot().videoRequested)
        assertFalse(machine.snapshot().waitingForSpeech)
        return spoken
    }

    @Test fun ambiguousLayersKeepAllTwentyFourCompletePunctuatedReplicasInBothSources() {
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_long_layers", extension)
            val items = parsed.index.selected(parsed.track)
            assertEquals(extension, 24, items.size)
            assertEquals(extension, 24, items.map { it.identity }.distinct().size)
            assertTrue(items.all { it.raw == preservedText })
            val frame = RealCueTimeline.frames(parsed.groups).first { it.cues.isNotEmpty() }
            assertEquals(24, frame.cues.size)
            val machine = ready()
            val first = forward(machine, parsed, frame)
            assertEquals(1, first.filterIsInstance<NarrationMachine.Effect.Speak>().size)
            assertEquals(119, machine.snapshot().pendingCount)
            machine.advance(1800) // 1.2 s source end + the real 600 ms allowed lag.
            val all = drainComplete(machine, first, heldUntilDrained = true)
            assertEquals(List(24) { preservedSegments }.flatten(), all.map { it.text })
            assertCompleteText(List(24) { preservedText }, all)
            assertTrue(all.all { it.deadlineMs == 1800L })
        }
    }

    @Test fun persistentPunctuatedPanelAndEveryOverlappingDialogueSurviveTheWholeQueue() {
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_long_persistent", extension)
            val frames = RealCueTimeline.frames(parsed.groups)
            assertEquals(7, frames.count { it.cues.isNotEmpty() })
            val panel = parsed.index.selected(parsed.track).single { it.raw == preservedText }
            assertEquals(1_000_000L, panel.startUs); assertEquals(9_000_000L, panel.endUs)
            assertEquals(4, parsed.index.count)
            val machine = ready()
            val first = forward(machine, parsed, frames.first())
            val firstId = first.filterIsInstance<NarrationMachine.Effect.Speak>().single().id
            for (frame in frames.filter { it.presentationTimeUs in 2_000_000L..3_000_000L }) {
                val changed = forward(machine, parsed, frame)
                assertTrue(changed.none { it is NarrationMachine.Effect.Speak || it is NarrationMachine.Effect.StopSpeech })
                assertEquals(firstId, machine.snapshot().speakingId)
            }
            // The first overlapping dialogue has now expired, while the panel's
            // original event remains valid. The controller must finish both.
            machine.advance(3600)
            val all = drainComplete(machine, first, heldUntilDrained = true).toMutableList()
            for (frame in frames.filter { it.presentationTimeUs > 3_000_000L }) {
                all += drainComplete(machine, forward(machine, parsed, frame))
            }
            assertCompleteText(listOf(preservedText) + preservedDialogues, all)
            assertEquals(preservedSegments, all.take(5).map { it.text })
            assertTrue(all.take(5).all { it.deadlineMs == 9600L })
            assertEquals(1, all.count { it.text == "ATTENTION…" })
        }
    }

    @Test fun matchingTextNamesLayersAndProgressiveAppearancesNeverMergeDistinctEvents() {
        val words = "Oui, absolument ; rien ne disparaît !"
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_distinct_same_words", extension)
            val items = parsed.index.selected(parsed.track)
            // Three identical physical lines, three named people, three layers,
            // and three progressive appearances are twelve independent events.
            assertEquals(extension, 12, items.size)
            assertEquals(12, items.map { it.identity }.distinct().size)
            assertTrue(items.all { it.raw == words })
            val machine = ready()
            val all = RealCueTimeline.frames(parsed.groups).flatMap {
                drainComplete(machine, forward(machine, parsed, it))
            }
            assertEquals(List(12) { words }, all.map { it.text })
            assertCompleteText(List(12) { words }, all)
        }
    }

    @Test fun bufferingAndEachManualResumePolicyKeepAllSegmentsAndFollowingEvents() {
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_long_layers", extension)
            val frame = RealCueTimeline.frames(parsed.groups).first { it.cues.isNotEmpty() }
            for (policy in NarrationMachine.ResumePolicy.entries) {
                val machine = ready().apply { setResumePolicy(policy) }
                val completed = forward(machine, parsed, frame).filterIsInstance<NarrationMachine.Effect.Speak>().single()
                val interrupted = machine.speechFinished(completed.id).filterIsInstance<NarrationMachine.Effect.Speak>().single()
                assertEquals(preservedSegments[0], completed.text)
                assertEquals(preservedSegments[1], interrupted.text)
                assertTrue(machine.setMediaReady(false).contains(NarrationMachine.Effect.StopSpeech))
                machine.pause()
                machine.setMediaReady(true)
                assertFalse(machine.snapshot().wantsPlayback)
                assertFalse(machine.snapshot().videoRequested)
                assertNull(machine.snapshot().speakingId)
                assertTrue(machine.speechFinished(interrupted.id).isEmpty())
                assertTrue(machine.speechFailed(interrupted.id, "stale test callback").isEmpty())
                val resumed = if (policy == NarrationMachine.ResumePolicy.EXPLICIT) {
                    assertTrue(machine.play().none { it is NarrationMachine.Effect.Speak })
                    assertFalse(machine.snapshot().wantsPlayback)
                    assertTrue(machine.snapshot().voiceSuspended)
                    machine.resumeVoice()
                } else machine.play()
                val restarted = resumed.filterIsInstance<NarrationMachine.Effect.Speak>().single()
                assertNotEquals(interrupted.id, restarted.id)
                machine.speechFinished(interrupted.id)
                machine.speechFailed(interrupted.id, "still stale")
                assertEquals(restarted.id, machine.snapshot().speakingId)
                machine.advance(1800)
                val allCompleted = listOf(completed) + drainComplete(machine, resumed, heldUntilDrained = true)
                val expectedSegments = if (policy == NarrationMachine.ResumePolicy.REPLICA)
                    listOf(preservedSegments[0]) + List(24) { preservedSegments }.flatten()
                else List(24) { preservedSegments }.flatten()
                assertEquals("$extension / $policy", expectedSegments, allCompleted.map { it.text })
                val expectedWhole = if (policy == NarrationMachine.ResumePolicy.REPLICA)
                    listOf(preservedSegments[0]) + List(24) { preservedText }
                else List(24) { preservedText }
                assertCompleteText(expectedWhole, allCompleted)
            }
        }
    }

    @Test fun anExplicitRetryAfterVoiceFailureKeepsTheWholeInterruptedSegmentAndQueue() {
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_long_layers", extension)
            val machine = ready()
            val frame = RealCueTimeline.frames(parsed.groups).first { it.cues.isNotEmpty() }
            val completed = forward(machine, parsed, frame).filterIsInstance<NarrationMachine.Effect.Speak>().single()
            val failed = machine.speechFinished(completed.id).filterIsInstance<NarrationMachine.Effect.Speak>().single()
            val failure = machine.speechFailed(failed.id, "synthetic engine failure")
            assertTrue(failure.any { it is NarrationMachine.Effect.Message && it.error })
            assertFalse(machine.snapshot().wantsPlayback)
            assertNull(machine.snapshot().speakingId)
            assertTrue(machine.speechFinished(failed.id).isEmpty())
            val retry = machine.play()
            val retried = retry.filterIsInstance<NarrationMachine.Effect.Speak>().single()
            assertEquals(failed.text, retried.text)
            assertNotEquals(failed.id, retried.id)
            machine.speechFailed(failed.id, "obsolete failure")
            assertEquals(retried.id, machine.snapshot().speakingId)
            machine.advance(1800)
            val all = listOf(completed) + drainComplete(machine, retry, heldUntilDrained = true)
            assertEquals(List(24) { preservedSegments }.flatten(), all.map { it.text })
            assertCompleteText(List(24) { preservedText }, all)
        }
    }

    @Test fun explicitSeekReplaysEveryActiveSourceOnceAndOldCompletionsCannotConsumeIt() {
        for (extension in listOf("ass", "mkv")) {
            val parsed = source("preserve_long_layers", extension)
            val identities = parsed.index.selected(parsed.track).map { it.identity }
            val frame = RealCueTimeline.frames(parsed.groups).first { it.cues.isNotEmpty() }
            val machine = ready()
            val old = forward(machine, parsed, frame).filterIsInstance<NarrationMachine.Effect.Speak>().single()
            machine.reset(false)
            machine.setMediaReady(true)
            // This exercises the controller after a real parsed cue is delivered
            // for an explicit seek; it is not a claim about ExoPlayer seek acks.
            assertTrue(forward(machine, parsed, frame).none { it is NarrationMachine.Effect.Speak })
            assertEquals(identities, parsed.index.selected(parsed.track).map { it.identity })
            val replay = machine.play()
            val fresh = replay.filterIsInstance<NarrationMachine.Effect.Speak>().single()
            assertNotEquals(old.id, fresh.id)
            assertEquals(preservedSegments[0], fresh.text)
            machine.speechFinished(old.id)
            machine.speechFailed(old.id, "obsolete pre-seek request")
            assertEquals(fresh.id, machine.snapshot().speakingId)
            machine.advance(1800)
            val all = drainComplete(machine, replay, heldUntilDrained = true)
            assertEquals(List(24) { preservedSegments }.flatten(), all.map { it.text })
            assertCompleteText(List(24) { preservedText }, all)
        }
    }

    @Test fun fullPreparationIndexPreservesAllPhysicalEventsAndTheEntirePunctuatedText() {
        for (stem in listOf("preserve_long_layers", "preserve_long_persistent", "preserve_distinct_same_words")) {
            for (extension in listOf("ass", "mkv")) {
                val parsed = source(stem, extension)
                val file = fixture("$stem.$extension")
                val media = if (extension == "ass") SavedMedia("file:///unused", "Synthetic",
                    subtitlePath = file.path, subtitleMime = MimeTypes.TEXT_SSA)
                else SavedMedia(Uri.fromFile(file).toString(), "Synthetic")
                val full = FullSubtitleIndexer(context).scan(media) {}
                assertFalse(full.truncated)
                assertEquals(parsed.index.selected(parsed.track), full.selected(parsed.track))
                assertEquals(parsed.index.count, full.count)
            }
        }
    }

    @Test fun comfortKeepsEveryLayerSegmentAndAllDialoguesWithoutAdaptiveDeadlines() {
        for (extension in listOf("ass", "mkv")) {
            val layers = source("preserve_long_layers", extension)
            val layerFrames = RealCueTimeline.frames(layers.groups)
            val layerMachine = ready().apply { setMode(NarrationMachine.Mode.COMFORT) }
            val layerFirst = forward(layerMachine, layers, layerFrames.first())
            assertTrue(layerMachine.snapshot().videoRequested)
            assertTrue(layerFrames.last().cues.isEmpty())
            val expired = forward(layerMachine, layers, layerFrames.last())
            assertTrue(expired.none { it is NarrationMachine.Effect.Speak || it is NarrationMachine.Effect.StopSpeech })
            val layerSpeech = drainComplete(layerMachine, layerFirst, heldUntilDrained = true)
            assertEquals(List(24) { preservedSegments }.flatten(), layerSpeech.map { it.text })
            assertTrue(layerSpeech.all { it.deadlineMs == null })
            assertCompleteText(List(24) { preservedText }, layerSpeech)

            val panel = source("preserve_long_persistent", extension)
            val frames = RealCueTimeline.frames(panel.groups).associateBy { it.presentationTimeUs }
            val machine = ready().apply { setMode(NarrationMachine.Mode.COMFORT) }
            var current = forward(machine, panel, frames.getValue(1_000_000L))
                .filterIsInstance<NarrationMachine.Effect.Speak>().single()
            assertTrue(machine.snapshot().videoRequested)
            val newDialogue = forward(machine, panel, frames.getValue(2_000_000L))
            assertTrue(newDialogue.none { it is NarrationMachine.Effect.Speak || it is NarrationMachine.Effect.StopSpeech })
            val all = mutableListOf<NarrationMachine.Effect.Speak>()
            // Confort expires the preceding replica at the next display change.
            // Do not advance the media timeline while that replica holds it.
            for (expected in preservedSegments) {
                assertEquals(expected, current.text)
                assertTrue(machine.snapshot().waitingForSpeech)
                assertFalse(machine.snapshot().videoRequested)
                all += current
                current = machine.speechFinished(current.id)
                    .filterIsInstance<NarrationMachine.Effect.Speak>().single()
            }
            assertEquals("Bonjour !", current.text)
            assertFalse(machine.snapshot().waitingForSpeech)
            assertTrue("The new unexpired dialogue permits the film to continue", machine.snapshot().videoRequested)
            all += drainComplete(machine, listOf(current))
            assertTrue(forward(machine, panel, frames.getValue(3_000_000L))
                .none { it is NarrationMachine.Effect.Speak })

            for ((appearance, disappearance) in listOf(4_000_000L to 5_000_000L, 6_000_000L to 7_000_000L)) {
                val started = forward(machine, panel, frames.getValue(appearance))
                assertTrue(machine.snapshot().videoRequested)
                val ended = forward(machine, panel, frames.getValue(disappearance))
                assertTrue(ended.none { it is NarrationMachine.Effect.Speak || it is NarrationMachine.Effect.StopSpeech })
                all += drainComplete(machine, started, heldUntilDrained = true)
            }
            assertTrue(forward(machine, panel, frames.getValue(9_000_000L))
                .none { it is NarrationMachine.Effect.Speak })
            assertEquals(0, machine.snapshot().pendingCount)
            assertNull(machine.snapshot().speakingId)
            assertTrue(machine.snapshot().videoRequested)
            assertTrue(all.all { it.deadlineMs == null })
            assertEquals(1, all.count { it.text == "ATTENTION…" })
            assertCompleteText(listOf(preservedText) + preservedDialogues, all)
        }
    }

    /** Real extractor+transcoder+encoder+decoder. The downstream TrackOutput is only
     * an in-memory sample sink, analogous to SampleQueue; it does no SSA parsing.
     */
    private fun extract(name: String): Parsed {
        val content = bytes(name)
        var cursor = 0
        val reader = DataReader { buffer, offset, length ->
            if (cursor == content.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, content.size - cursor)
                content.copyInto(buffer, offset, cursor, cursor + count); cursor += count; count
            }
        }
        var input = DefaultExtractorInput(reader, 0, content.size.toLong())
        val index = CueAheadIndex()
        val factory = factory(index)
        val candidates = factory.createExtractors(Uri.parse("file:///$name"), emptyMap())
        var chosen: Extractor? = null
        for (candidate in candidates) {
            val match = try { candidate.sniff(input) } catch (_: java.io.EOFException) { false }
            input.resetPeekPosition()
            if (match) { chosen = candidate; break }
        }
        candidates.filter { it !== chosen }.forEach { it.release() }
        val extractor = requireNotNull(chosen)
        var selected: String? = null
        val groups = mutableListOf<CuesWithTiming>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_TEXT) return DiscardingTrackOutput()
                return object : TrackOutput by DiscardingTrackOutput() {
                    val sample = ByteArrayOutputStream()
                    override fun format(format: Format) {
                        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, format.sampleMimeType)
                        selected = format.id
                    }
                    override fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        assertEquals(TrackOutput.SAMPLE_DATA_PART_MAIN, sampleDataPart)
                        sample.write(data.data, data.position, length); data.skipBytes(length)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        val all = sample.toByteArray()
                        val group = CueDecoder().decode(timeUs, all, all.size - offset - size, size)
                        groups += group
                        // Index metadata must be published before TextRenderer can observe a sample.
                        val clean = group.cues.mapNotNull { it.text?.toString() }.map { SpeechTools.filter(it, filters) }.filter { it.isNotBlank() }
                        assertNotNull(index.resolved(selected, clean, timeUs, filters))
                        sample.reset()
                        if (offset > 0) sample.write(all, all.size - offset, offset)
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(map: SeekMap) = Unit
        })
        val seek = PositionHolder()
        try {
            var loops = 0
            while (true) {
                assertTrue(++loops < 10_000)
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> { cursor = seek.position.toInt(); input = DefaultExtractorInput(reader, seek.position, content.size.toLong()) }
                }
            }
        } finally { extractor.release() }
        assertFalse(factory.truncated); assertFalse(index.truncated)
        return Parsed(index, groups, selected)
    }
}
