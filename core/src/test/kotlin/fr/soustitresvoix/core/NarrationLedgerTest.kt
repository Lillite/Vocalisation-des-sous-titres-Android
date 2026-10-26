package fr.soustitresvoix.core

import fr.soustitresvoix.core.NarrationLedger.State
import fr.soustitresvoix.core.NarrationMachine.Effect
import org.junit.Assert.*
import org.junit.Test

class NarrationLedgerTest {
    private fun ready() = NarrationMachine().apply {
        setJournalContext("synthetic-media:track-fr")
        setSpeechReady(true); setMediaReady(true); play()
    }
    private fun List<Effect>.spoken() = filterIsInstance<Effect.Speak>().single()
    private fun drain(machine: NarrationMachine, initial: List<Effect>): List<String> {
        val spoken = initial.filterIsInstance<Effect.Speak>().map { it.text }.toMutableList()
        var remaining = 100
        while (machine.snapshot().speakingId != null) {
            assertTrue("Voice failed to drain", remaining-- > 0)
            spoken += machine.speechFinished(requireNotNull(machine.snapshot().speakingId))
                .filterIsInstance<Effect.Speak>().map { it.text }
        }
        return spoken
    }
    private fun NarrationMachine.entry(identity: String) = journalSnapshot().entries.single {
        it.identityHash == NarrationLedger.hashIdentity(identity)
    }

    @Test fun completeLifecycleContainsNoSourceTextOrRawIdentity() {
        var clock = 10L
        val ledger = NarrationLedger(clockMillis = { clock++ })
        ledger.setContext("content://private/FILM_SECRET")
        val identity = "track:1000:2000:La phrase privée jamais journalisée"
        ledger.register(NarrationLedger.Occurrence(identity, 1000, 2000))
        ledger.prepared(identity); ledger.queued(identity); ledger.started(identity); ledger.completed(identity)
        val snapshot = ledger.snapshot()
        assertEquals(1, snapshot.detectedCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(listOf(State.DETECTED, State.PREPARED, State.QUEUED, State.STARTED, State.COMPLETED),
            snapshot.transitions.map { it.state })
        assertEquals(1000L, snapshot.entries.single().startUs)
        assertFalse(ledger.exportMetadata().contains("privée"))
        assertFalse(ledger.exportMetadata().contains("FILM_SECRET"))
        assertFalse(snapshot.toString().contains(identity))
    }

    @Test fun preparationCannotMoveCompletedOrRunningOccurrencesBackwards() {
        val ledger = NarrationLedger()
        ledger.started("one"); ledger.prepared("one")
        assertEquals(State.STARTED, ledger.entry("one")!!.state)
        ledger.completed("one"); ledger.prepared("one"); ledger.filtered("one")
        assertEquals(State.COMPLETED, ledger.entry("one")!!.state)
    }

    @Test fun signedSourceTimingsSurviveMetadataRoundTripWithoutCollidingWithNull() {
        val ledger = NarrationLedger().apply {
            register(NarrationLedger.Occurrence("preroll", -1_000_000, -1))
            register(NarrationLedger.Occurrence("unknown"))
        }
        val restored = NarrationLedger()
        assertTrue(restored.restoreMetadata(ledger.exportMetadata()))
        assertEquals(-1_000_000L, restored.entry("preroll")!!.startUs)
        assertEquals(-1L, restored.entry("preroll")!!.endUs)
        assertNull(restored.entry("unknown")!!.startUs)
    }

    @Test fun revisionDistinguishesEveryMutationWithinTheSameClockMillisecond() {
        val ledger = NarrationLedger(clockMillis = { 42L })
        val revisions = mutableListOf(ledger.snapshot().revision)
        ledger.register(NarrationLedger.Occurrence("one")); revisions += ledger.snapshot().revision
        ledger.queued("one"); revisions += ledger.snapshot().revision
        ledger.started("one"); revisions += ledger.snapshot().revision
        val beforeCompletion = ledger.exportMetadata()
        ledger.completed("one"); revisions += ledger.snapshot().revision
        assertNotEquals(beforeCompletion, ledger.exportMetadata())
        assertTrue(revisions.zipWithNext().all { (before, after) -> after > before })
        assertTrue(ledger.snapshot().transitions.all { it.atMillis == 42L })
        val completed = ledger.snapshot().revision
        ledger.completed("one"); ledger.register(NarrationLedger.Occurrence("one"))
        assertEquals("Idempotent notifications must not rewrite an unchanged journal", completed, ledger.snapshot().revision)
        ledger.register(NarrationLedger.Occurrence("one", 1000, 2000))
        assertTrue(ledger.snapshot().revision > completed)
        val timed = ledger.snapshot().revision
        ledger.nextGeneration()
        assertTrue(ledger.snapshot().revision > timed)
        val beforeRestore = ledger.snapshot().revision
        assertTrue(ledger.restoreMetadata(ledger.exportMetadata()))
        assertTrue(ledger.snapshot().revision > beforeRestore)
        val beforeContext = ledger.snapshot().revision
        ledger.setContext("another-track")
        assertTrue(ledger.snapshot().revision > beforeContext)
    }

    @Test fun completedCountIsUniqueWhileReplayAttemptsRemainVisible() {
        val machine = ready()
        drain(machine, machine.cues(0, listOf("Oui."), identities = listOf("one")))
        drain(machine, machine.preview())
        drain(machine, machine.replayOccurrence("one", "Oui."))
        assertEquals(1, machine.journalSnapshot().completedCount)
        assertEquals(3, machine.entry("one").completions)
        assertEquals(3, machine.entry("one").attempts)
        assertFalse(machine.snapshot().wantsPlayback)
    }

    @Test fun segmentedOccurrenceCompletesOnlyAfterItsLastSegment() {
        val machine = ready()
        val first = machine.cues(0, listOf("Bonjour. À demain."), identities = listOf("one")).spoken()
        val second = machine.speechFinished(first.id).spoken()
        assertEquals(0, machine.journalSnapshot().completedCount)
        assertEquals(1, machine.entry("one").attempts)
        machine.speechFinished(second.id)
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun persistentSignIsNotReplayedAcrossSixtyOverlapAndStyleIntervals() {
        val machine = ready()
        val initial = machine.cues(0, listOf("ACCÈS INTERDIT"), identities = listOf("sign"))
        val said = drain(machine, initial).toMutableList()
        for (i in 1..60) {
            val frame = machine.cues(i * 1_000_000L,
                listOf("ACCÈS INTERDIT", "Réplique $i."), identities = listOf("sign", "dialogue-$i"))
            said += drain(machine, frame)
        }
        assertEquals(1, said.count { it == "ACCÈS INTERDIT" })
        assertEquals(61, machine.journalSnapshot().completedCount)
        assertEquals(1, machine.entry("sign").completions)
    }

    @Test fun twoIdenticalSimultaneousLinesAndLaterRealRepeatsStayDistinct() {
        val machine = ready()
        val said = drain(machine, machine.cues(1, listOf("Oui.", "Oui."),
            identities = listOf("speaker-one", "speaker-two"))).toMutableList()
        machine.cues(2, emptyList())
        said += drain(machine, machine.cues(3, listOf("Oui."), identities = listOf("later-one")))
        assertEquals(listOf("Oui.", "Oui.", "Oui."), said)
        assertEquals(3, machine.journalSnapshot().detectedCount)
        assertEquals(3, machine.journalSnapshot().completedCount)
    }

    @Test fun sameFrameWithDifferentSourceIdentityIsStillANewOccurrence() {
        val machine = ready()
        drain(machine, machine.cues(1, listOf("Oui."), identities = listOf("one")))
        assertEquals(listOf("Oui."), drain(machine,
            machine.cues(1, listOf("Oui."), identities = listOf("two"))))
        assertEquals(2, machine.journalSnapshot().completedCount)
    }

    @Test fun translatedWordingAndRestylingKeepSourceIdentityWithinOnePass() {
        val machine = ready()
        drain(machine, machine.cues(0, listOf("Défense d’entrer"), identities = listOf("source-sign")))
        assertTrue(machine.cues(1, listOf("Entrée interdite"), identities = listOf("source-sign"))
            .none { it is Effect.Speak })
        machine.cues(2, emptyList())
        assertTrue(machine.cues(3, listOf("Entrée interdite"), identities = listOf("source-sign"))
            .none { it is Effect.Speak })
        assertEquals(1, machine.journalSnapshot().detectedCount)
        assertEquals(1, machine.entry("source-sign").attempts)
    }

    @Test fun seekStartsNewPassWithoutInflatingUniqueOccurrenceCounts() {
        val machine = ready()
        val beforeSeek = machine.cues(0, listOf("Un panneau."), identities = listOf("sign")).spoken()
        machine.reset(true); machine.setMediaReady(true)
        val afterSeek = machine.cues(0, listOf("Un panneau."), identities = listOf("sign")).spoken()
        machine.speechFinished(beforeSeek.id)
        assertEquals(afterSeek.id, machine.snapshot().speakingId)
        machine.speechFinished(afterSeek.id)
        assertEquals(1, machine.journalSnapshot().completedCount)
        assertEquals(2, machine.entry("sign").attempts)
        assertEquals(1, machine.entry("sign").interruptions)
        assertEquals(1L, machine.journalSnapshot().generation)
    }

    @Test fun errorAndStaleCallbacksCannotCompleteTheWrongOccurrence() {
        val machine = ready()
        val failed = machine.cues(0, listOf("Écoute."), identities = listOf("one")).spoken()
        machine.speechFailed(failed.id, "synthetic error")
        assertEquals(State.ERROR, machine.entry("one").state)
        val retried = machine.play().spoken()
        machine.speechFinished(failed.id); machine.speechFailed(failed.id, "stale")
        assertEquals(retried.id, machine.snapshot().speakingId)
        assertEquals(0, machine.journalSnapshot().completedCount)
        machine.speechFinished(retried.id)
        assertEquals(1, machine.entry("one").errors)
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun importIsContextBoundAtomicAndMarksUnfinishedSpeechInterrupted() {
        val source = NarrationLedger().apply {
            setContext("one"); register(NarrationLedger.Occurrence("a", 0, 10))
            completed("a"); started("b"); queued("c")
        }
        val encoded = source.exportMetadata()
        val target = NarrationLedger().apply { setContext("one") }
        assertTrue(target.restoreMetadata(encoded))
        assertEquals(1, target.snapshot().completedCount)
        assertEquals(2, target.snapshot().interruptedCount)
        assertNull(target.snapshot().current)
        val before = target.snapshot()
        assertFalse(target.restoreMetadata(encoded.replace("NARRATION-LEDGER-1", "unknown")))
        assertEquals(before, target.snapshot())
        val other = NarrationLedger().apply { setContext("another") }
        assertFalse(other.restoreMetadata(encoded))
        assertEquals(0, other.snapshot().detectedCount)
    }

    @Test fun malformedDuplicateOrOversizedJournalDoesNotReplaceExistingState() {
        val ledger = NarrationLedger().apply { completed("kept") }
        val before = ledger.snapshot()
        val encoded = ledger.exportMetadata()
        assertFalse(ledger.restoreMetadata(encoded + encoded.lineSequence().drop(3).first() + "\n"))
        assertEquals(before, ledger.snapshot())
        assertFalse(ledger.restoreMetadata(encoded.replace("COMPLETED", "UNKNOWN")))
        assertFalse(ledger.restoreMetadata("x".repeat(24_000_001)))
        assertEquals(before, ledger.snapshot())
    }

    @Test fun ledgerCapacityIsExplicitAndTransitionHistoryIsBounded() {
        val ledger = NarrationLedger(capacity = 2)
        assertTrue(ledger.register(NarrationLedger.Occurrence("one")))
        assertTrue(ledger.register(NarrationLedger.Occurrence("two")))
        assertFalse(ledger.register(NarrationLedger.Occurrence("three")))
        repeat(3000) { ledger.started("one"); ledger.completed("one") }
        assertTrue(ledger.snapshot().capacityExceeded)
        assertEquals(2, ledger.snapshot().detectedCount)
        assertEquals(4096, ledger.snapshot().transitions.size)
    }

    @Test fun recoveredLongOccurrenceIsSegmentedAndNeverStartsVideo() {
        val machine = ready()
        machine.pause()
        val text = (1..8).joinToString(" ") { "Ceci est la phrase $it." }
        val effects = machine.replayOccurrence("recovered", text)
        val said = drain(machine, effects)
        assertEquals(8, said.size)
        assertEquals(text, said.joinToString(" "))
        assertFalse(machine.snapshot().wantsPlayback)
        assertFalse(machine.snapshot().videoRequested)
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun pausingRecoveryAndObsoleteCompletionPreserveInterruptedState() {
        val machine = ready()
        val playing = machine.replayOccurrence("one", "Bonjour. À demain.").spoken()
        machine.pause(); machine.speechFinished(playing.id)
        assertEquals(State.INTERRUPTED, machine.entry("one").state)
        assertEquals(0, machine.journalSnapshot().completedCount)
        assertFalse(machine.snapshot().videoRequested)
        assertNull(machine.snapshot().speakingId)
    }

    @Test fun importedMetadataSupportsExplicitReplayWithReResolvedSourceTextOnly() {
        val previous = ready()
        previous.cues(0, listOf("Bonjour."), identities = listOf("source-one"))
        val restored = NarrationMachine().apply {
            setJournalContext("synthetic-media:track-fr")
            assertTrue(restoreJournalMetadata(previous.exportJournalMetadata()))
            setSpeechReady(true)
        }
        assertEquals(State.INTERRUPTED, restored.journalSnapshot().lastInterrupted!!.state)
        assertNull(restored.snapshot().speakingId)
        drain(restored, restored.replayOccurrence("source-one", "Bonjour."))
        assertEquals(1, restored.journalSnapshot().completedCount)
        assertFalse(restored.snapshot().wantsPlayback)
    }

    @Test fun sourceTimingUpdatesDoNotEraseLifecycleAndFilteredItemsAreAccounted() {
        val machine = ready()
        machine.registerOccurrences(listOf(NarrationLedger.Occurrence("one"), NarrationLedger.Occurrence("filtered")))
        machine.markPrepared(listOf("one")); machine.markFiltered(listOf("filtered"))
        drain(machine, machine.cues(0, listOf("Oui."), identities = listOf("one")))
        machine.registerOccurrences(listOf(NarrationLedger.Occurrence("one", 1000, 5000)))
        assertEquals(1000L, machine.entry("one").startUs)
        assertEquals(State.COMPLETED, machine.entry("one").state)
        assertEquals(1, machine.journalSnapshot().filteredCount)
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun queueOverflowIsVisibleAsErrorWithoutFalseCompletion() {
        val machine = NarrationMachine(maxPendingChars = 8).apply {
            setSpeechReady(true); setMediaReady(true); play()
        }
        val effects = machine.cues(0, listOf("Un texte beaucoup trop long."), identities = listOf("too-long"))
        assertTrue(effects.any { it is Effect.Message && it.error })
        assertEquals(State.ERROR, machine.entry("too-long").state)
        assertEquals(0, machine.journalSnapshot().completedCount)
        assertFalse(machine.snapshot().wantsPlayback)
        assertTrue(machine.snapshot().recoverableCount > 0)
        assertEquals(listOf("Un texte beaucoup trop long."), drain(machine, machine.play()))
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun overflowPreservesAlreadyQueuedAndIncomingOverlappingOccurrencesInOrder() {
        val machine = NarrationMachine(maxPendingChars = 70).apply {
            setSpeechReady(true); setMediaReady(true); play()
        }
        val first = machine.cues(0, listOf("Une phrase déjà commencée."), identities = listOf("one")).spoken()
        val extras = (1..25).map { "La réplique simultanée numéro $it est préservée." }
        val ids = extras.indices.map { "overlap-$it" }
        val overflow = machine.cues(0, listOf(first.text) + extras, identities = listOf("one") + ids)
        assertTrue(overflow.any { it is Effect.Message && it.error })
        assertFalse(machine.snapshot().wantsPlayback)
        assertTrue(machine.snapshot().recoverableCount > 0)
        machine.speechFinished(first.id)
        assertEquals(0, machine.journalSnapshot().completedCount)
        val said = drain(machine, machine.play())
        assertEquals(listOf(first.text) + extras, said)
        assertEquals(26, machine.journalSnapshot().completedCount)
        assertEquals(0, machine.snapshot().recoverableCount)
    }

    @Test fun reserveDrainsWithVideoHeldEvenBeforeAnyDisplayDeadlineExpires() {
        val machine = NarrationMachine(maxPendingChars = 20).apply {
            setSpeechReady(true); setMediaReady(true); play()
        }
        machine.cues(0, listOf("Première phrase.", "Deuxième phrase.", "Troisième phrase."),
            identities = listOf("one", "two", "three"))
        var next = machine.play().spoken()
        repeat(2) {
            assertTrue(machine.snapshot().waitingForSpeech)
            assertFalse(machine.snapshot().videoRequested)
            next = machine.speechFinished(next.id).spoken()
        }
        assertFalse(machine.snapshot().videoRequested)
        machine.speechFinished(next.id)
        assertTrue(machine.snapshot().videoRequested)
        assertEquals(3, machine.journalSnapshot().completedCount)
    }

    @Test fun aVeryLongOccurrenceIsFullyRetainedAndItsLastChunkAloneCompletesIt() {
        val machine = ready()
        val text = (1..900).joinToString(" ") { "Ceci est la phrase complète numéro $it." }
        val overflow = machine.cues(0, listOf(text), identities = listOf("long"))
        assertTrue(overflow.none { it is Effect.Speak })
        assertFalse(machine.snapshot().wantsPlayback)
        var next = machine.play().spoken()
        val said = mutableListOf(next.text)
        var segments = 0
        while (true) {
            assertEquals(0, machine.journalSnapshot().completedCount)
            assertTrue(++segments <= 900)
            val effect = machine.speechFinished(next.id).filterIsInstance<Effect.Speak>().singleOrNull() ?: break
            next = effect; said += next.text
        }
        assertEquals(text, said.joinToString(" "))
        assertEquals(900, said.size)
        assertEquals(1, machine.journalSnapshot().completedCount)
        assertEquals(0, machine.snapshot().recoverableCount)
    }

    @Test fun playerErrorKeepsVisibleQueueEmptyButExplicitRetryRestoresEveryCaption() {
        val machine = ready()
        val first = machine.cues(0, listOf("Phrase interrompue.", "Phrase en attente."),
            identities = listOf("one", "two")).spoken()
        machine.fail("controlled playback error")
        assertEquals(0, machine.snapshot().pendingCount)
        assertEquals(2, machine.snapshot().recoverableCount)
        assertNull(machine.snapshot().speakingId)
        assertFalse(machine.snapshot().videoRequested)
        val retried = machine.play()
        machine.speechFinished(first.id); machine.speechFailed(first.id, "stale")
        assertEquals(listOf("Phrase interrompue.", "Phrase en attente."), drain(machine, retried))
        assertEquals(2, machine.journalSnapshot().completedCount)
    }

    @Test fun restyledOverflowFrameCannotEnqueueTheSameReservedOccurrenceTwice() {
        val machine = NarrationMachine(maxPendingChars = 20).apply {
            setSpeechReady(true); setMediaReady(true); play()
        }
        val lines = listOf("Première phrase.", "Deuxième phrase.", "Troisième phrase.")
        val ids = listOf("one", "two", "three")
        machine.cues(0, lines, identities = ids)
        val count = machine.snapshot().recoverableCount
        repeat(60) { machine.cues(it * 1000L, lines, identities = ids) }
        assertEquals(count, machine.snapshot().recoverableCount)
        assertEquals(lines, drain(machine, machine.play()))
        assertEquals(3, machine.journalSnapshot().completedCount)
    }

    @Test fun lateProgressiveTranslationWithSameDisplayedTextUsesEveryNewSourceIdOnce() {
        val machine = ready()
        val first = machine.cues(1_000_000, listOf("Oui."), identities = listOf("sign"))
        val original = first.spoken()
        machine.cues(1_000_000, listOf("Oui.", "Oui."), identities = listOf("sign", "translated-reply"))
        repeat(60) {
            machine.cues(1_000_000, listOf("Oui.", "Oui."), identities = listOf("sign", "translated-reply"))
        }
        assertEquals(original.id, machine.snapshot().speakingId)
        assertEquals(listOf("Oui.", "Oui."), drain(machine, first))
        assertEquals(2, machine.journalSnapshot().completedCount)
    }

    @Test fun comprehensionHoldKeepsVideoStateCoherentAndStartsNextOccurrenceOnlyAfterRelease() {
        val machine = ready()
        val first = machine.cues(0, listOf("Première phrase.", "Deuxième phrase."),
            identities = listOf("one", "two")).spoken()
        assertTrue(machine.snapshot().videoRequested)
        val held = machine.setComprehensionHold(true)
        assertTrue(held.contains(Effect.Video(false)))
        assertFalse(machine.snapshot().videoRequested)
        assertTrue(machine.snapshot().waitingForSpeech)
        assertTrue(machine.speechFinished(first.id).none { it is Effect.Speak })
        assertNull(machine.snapshot().speakingId)
        assertEquals(1, machine.journalSnapshot().completedCount)
        val released = machine.setComprehensionHold(false)
        assertEquals("Deuxième phrase.", released.spoken().text)
        assertTrue(machine.snapshot().videoRequested)
    }

    @Test fun comprehensionHoldAtFinalAudioCompletionRestoresVideoWithAnExplicitEffect() {
        val machine = ready()
        val spoken = machine.cues(0, listOf("Une phrase."), identities = listOf("one")).spoken()
        machine.setComprehensionHold(true)
        machine.speechFinished(spoken.id)
        assertFalse(machine.snapshot().videoRequested)
        assertTrue(machine.setComprehensionHold(false).contains(Effect.Video(true)))
        assertTrue(machine.snapshot().videoRequested)
    }

    @Test fun manualPauseDuringComprehensionHoldCannotBeUndoneByTheDeferredCompletion() {
        val machine = ready()
        val spoken = machine.cues(0, listOf("Une phrase à conserver."), identities = listOf("one")).spoken()
        machine.setComprehensionHold(true)
        machine.pause()
        machine.speechFinished(spoken.id)
        assertTrue(machine.setComprehensionHold(false).none { it is Effect.Speak || it == Effect.Video(true) })
        assertFalse(machine.snapshot().wantsPlayback)
        assertFalse(machine.snapshot().videoRequested)
        assertEquals(listOf("Une phrase à conserver."), drain(machine, machine.play()))
        assertEquals(1, machine.journalSnapshot().completedCount)
    }

    @Test fun seekDuringComprehensionHoldCancelsItWithoutAdmittingAnObsoleteUtterance() {
        val machine = ready()
        val old = machine.cues(0, listOf("Ancienne phrase."), identities = listOf("old")).spoken()
        machine.setComprehensionHold(true)
        machine.reset(false); machine.setMediaReady(true)
        machine.cues(5_000_000, listOf("Nouvelle phrase."), identities = listOf("new"))
        machine.speechFinished(old.id); machine.setComprehensionHold(false)
        assertFalse(machine.snapshot().wantsPlayback)
        assertNull(machine.snapshot().speakingId)
        assertEquals(listOf("Nouvelle phrase."), drain(machine, machine.play()))
        assertEquals(0, machine.entry("old").completions)
        assertEquals(1, machine.entry("new").completions)
    }

    @Test fun controllerExposesLedgerAndRecoveryWithoutChangingPauseContract() {
        val controller = ExistingNarrationController()
        controller.setJournalContext("context"); controller.setNarrationLanguage("fr")
        controller.registerOccurrences(listOf(NarrationLedger.Occurrence("one", 0, 1000)))
        controller.markPrepared(listOf("one")); controller.setSpeechReady(true)
        val recovered = controller.replayOccurrence("one", "Bonjour.").spoken()
        controller.speechFinished(recovered.id)
        assertEquals(1, controller.journalSnapshot().completedCount)
        assertFalse(controller.snapshot().wantsPlayback)
        assertTrue(controller.exportJournalMetadata().startsWith("NARRATION-LEDGER-1"))
    }
}
