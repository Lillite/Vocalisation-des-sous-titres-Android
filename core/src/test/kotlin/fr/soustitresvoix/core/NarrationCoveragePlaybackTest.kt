package fr.soustitresvoix.core

import fr.soustitresvoix.core.NarrationMachine.Effect
import fr.soustitresvoix.core.NarrationMachine.RestoredOccurrence
import org.junit.Assert.*
import org.junit.Test

class NarrationCoveragePlaybackTest {
    private fun ready(play: Boolean = true) = NarrationMachine().apply {
        setJournalContext("test-film:fr")
        setSpeechReady(true)
        setMediaReady(true)
        if (play) play()
    }

    private fun List<Effect>.spoken() = filterIsInstance<Effect.Speak>().single()

    @Test fun translationAndScheduleHoldsDrainReadySpeechAndReleaseIndependently() {
        val machine = ready()
        val first = machine.cues(0, listOf("Bonjour. À demain."), identities = listOf("one")).spoken()
        assertTrue(machine.setTranslationHold(true).contains(Effect.Video(false)))
        assertTrue(machine.snapshot().wantsPlayback)
        machine.setScheduleHold(true)
        assertFalse(machine.setMediaReady(false).contains(Effect.StopSpeech))
        assertEquals(first.id, machine.snapshot().speakingId)
        val second = machine.speechFinished(first.id).spoken()
        assertEquals("À demain.", second.text)
        assertEquals(1, second.segmentOrdinal)
        assertTrue(machine.setTranslationHold(false).none { it == Effect.Video(true) })
        assertTrue(machine.speechFinished(second.id).none { it == Effect.Video(true) })
        assertTrue(machine.snapshot().scheduleHeld)
        assertTrue(machine.setScheduleHold(false).contains(Effect.Video(true)))
    }

    @Test fun releasingOldTechnicalHoldsDoesNotResumeAfterUserPauseOrSeek() {
        for (seek in listOf(false, true)) {
            val machine = ready()
            val first = machine.cues(0, listOf("Bonjour."), identities = listOf("one")).spoken()
            machine.setScheduleHold(true)
            machine.setTranslationHold(true)
            assertTrue((if (seek) machine.reset(false) else machine.pause()).contains(Effect.StopSpeech))
            machine.setMediaReady(true)
            val stale = machine.setTranslationHold(false) + machine.setScheduleHold(false) + machine.speechFinished(first.id)
            assertTrue(stale.none { it == Effect.Video(true) || it is Effect.Speak })
            assertFalse(machine.snapshot().wantsPlayback)
            assertFalse(machine.snapshot().translationHeld)
            assertFalse(machine.snapshot().scheduleHeld)
        }
    }

    @Test fun scheduleCanHoldTheImageBeforeTheFirstBoundaryIsAdmitted() {
        val machine = ready(play = false)
        assertTrue(machine.setScheduleHold(true).isEmpty())
        assertTrue(machine.play().none { it == Effect.Video(true) })
        assertFalse(machine.snapshot().videoRequested)
        machine.cues(0, listOf("Premier sous-titre."), identities = listOf("first")).spoken()
        assertTrue(machine.setScheduleHold(false).contains(Effect.Video(true)))
    }

    @Test fun repeatedTapsReplaceTheSamePreviewButKeepTheOtherIdenticalOccurrence() {
        val machine = ready()
        val original = machine.cues(0, listOf("Oui.", "Oui."), identities = listOf("first", "second")).spoken()
        val firstReplay = machine.preview().spoken()
        val secondReplay = machine.preview().spoken()
        assertEquals("first", secondReplay.occurrenceId)
        assertTrue(machine.speechFinished(original.id).isEmpty())
        assertTrue(machine.speechFinished(firstReplay.id).isEmpty())
        machine.speechFinished(secondReplay.id)
        val next = machine.play().spoken()
        assertEquals("second", next.occurrenceId)
        assertTrue(machine.speechFinished(next.id).none { it is Effect.Speak })
        assertEquals(2, machine.journalSnapshot().completedCount)
    }

    @Test fun exactRestoreRetainsOnlyTheUnfinishedSuffixAndOverlapInOriginalOrder() {
        val firstText = "Bonjour. À demain."
        val secondText = "Pendant ce temps."
        val original = ready()
        val first = original.cues(0, listOf(firstText, secondText), identities = listOf("first", "second")).spoken()
        val current = original.speechFinished(first.id).spoken()
        assertEquals("À demain.", current.text)
        val checkpoint = original.pendingSegments()
        assertEquals(listOf(1, 0), checkpoint.map { it.ordinal })
        assertEquals(listOf(2, 1), checkpoint.map { it.count })

        val restored = ready(play = false)
        assertTrue(restored.restoreJournalMetadata(original.exportJournalMetadata()))
        assertTrue(restored.restorePendingSegments(listOf(
            RestoredOccurrence("first", firstText, checkpoint.take(1)),
            RestoredOccurrence("second", secondText, checkpoint.drop(1))
        )).isEmpty())
        assertEquals(checkpoint, restored.pendingSegments())
        assertFalse(restored.snapshot().videoRequested)
        val continued = restored.play().spoken()
        assertEquals("À demain.", continued.text)
        assertFalse(restored.snapshot().videoRequested)
        val overlap = restored.speechFinished(continued.id).spoken()
        assertEquals("Pendant ce temps.", overlap.text)
        assertFalse(restored.snapshot().videoRequested)
        assertTrue(restored.speechFinished(overlap.id).contains(Effect.Video(true)))
        assertEquals(2, restored.journalSnapshot().completedCount)
        assertTrue(restored.pendingSegments().isEmpty())
    }

    @Test fun changedTextAndIncompleteSuffixCannotBePartiallyRestored() {
        val original = ready()
        val firstText = "Bonjour. À demain."
        original.cues(0, listOf(firstText), identities = listOf("first"))
        val segments = original.pendingSegments()
        val restored = ready(play = false)
        assertTrue(restored.restorePendingSegments(listOf(
            RestoredOccurrence("first", firstText, segments),
            RestoredOccurrence("changed", "Texte modifié.", segments)
        )).filterIsInstance<Effect.Message>().single().error)
        assertTrue(restored.pendingSegments().isEmpty())
        assertTrue(restored.restorePendingSegments(listOf(
            RestoredOccurrence("first", firstText, segments.take(1))
        )).filterIsInstance<Effect.Message>().single().error)
        assertTrue(restored.restorePendingSegments(listOf(
            RestoredOccurrence("first", firstText, segments.drop(1))
        )).filterIsInstance<Effect.Message>().single().error)
        assertTrue(restored.pendingSegments().isEmpty())
        assertFalse(restored.snapshot().wantsPlayback)
    }

    @Test fun reportReplayRetainsEveryRequestedOccurrenceWhileTheImageStaysPaused() {
        val machine = ready()
        val first = machine.replayOccurrences(listOf("one" to "Oui.", "two" to "Oui.")).spoken()
        assertEquals("one", first.occurrenceId)
        assertFalse(machine.snapshot().videoRequested)
        val second = machine.speechFinished(first.id).spoken()
        assertEquals("two", second.occurrenceId)
        machine.speechFinished(second.id)
        assertEquals(2, machine.journalSnapshot().completedCount)
        assertFalse(machine.snapshot().videoRequested)
        assertTrue(machine.play().none { it is Effect.Speak })
    }

    @Test fun reconcilingMissedOccurrencesKeepsActiveVoiceAndDoesNotDuplicateConfirmedWork() {
        val machine = ready()
        val completed = machine.cues(0, listOf("Oui."), identities = listOf("first")).spoken()
        machine.speechFinished(completed.id)
        val active = machine.cues(1, listOf("Oui."), identities = listOf("second")).spoken()
        val expected = listOf("first" to "Oui.", "second" to "Oui.", "third" to "Oui.")
        val reconciliation = machine.recoverMissedOccurrences(expected)
        assertTrue(reconciliation.contains(Effect.Video(false)))
        assertTrue(reconciliation.none { it == Effect.StopSpeech || it is Effect.Speak })
        assertTrue(machine.recoverMissedOccurrences(expected).isEmpty())
        assertEquals(active.id, machine.snapshot().speakingId)
        val recovered = machine.speechFinished(active.id).spoken()
        assertEquals("third", recovered.occurrenceId)
        assertTrue(machine.speechFinished(recovered.id).contains(Effect.Video(true)))
        assertEquals(3, machine.journalSnapshot().passCompletedCount)
        machine.pause()
        assertTrue(machine.recoverMissedOccurrences(listOf("later" to "Plus tard.")).isEmpty())
        assertTrue(machine.pendingSegments().isEmpty())
    }
}
