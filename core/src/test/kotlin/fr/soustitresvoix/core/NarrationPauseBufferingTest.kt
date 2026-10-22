package fr.soustitresvoix.core

import fr.soustitresvoix.core.NarrationMachine.Effect
import fr.soustitresvoix.core.NarrationMachine.ResumePolicy
import org.junit.Assert.*
import org.junit.Test

/** Manual pause must keep its selected policy after a technical interruption. */
class NarrationPauseBufferingTest {
    private fun ready(policy: ResumePolicy) = NarrationMachine().apply {
        setResumePolicy(policy); setSpeechReady(true); setMediaReady(true); play()
    }
    private fun List<Effect>.spoken() = filterIsInstance<Effect.Speak>().single()
    private fun assertPaused(machine: NarrationMachine) {
        assertFalse(machine.snapshot().wantsPlayback)
        assertFalse(machine.snapshot().videoRequested)
        assertNull(machine.snapshot().speakingId)
    }

    @Test fun explicitResumeStillBlocksOrdinaryPlayAfterBufferingAndManualPause() {
        val machine = ready(ResumePolicy.EXPLICIT)
        val interrupted = machine.cues(1, listOf("Bonjour.")).spoken()
        machine.setMediaReady(false)
        machine.pause()
        assertTrue(machine.snapshot().voiceSuspended)
        machine.setMediaReady(true)
        machine.setMediaReady(true)
        machine.speechFinished(interrupted.id)
        assertPaused(machine)
        assertTrue(machine.play().filterIsInstance<Effect.Speak>().isEmpty())
        assertPaused(machine)
        val resumed = machine.resumeVoice().spoken()
        assertEquals("Bonjour.", resumed.text)
        assertNotEquals(interrupted.id, resumed.id)
        machine.speechFinished(interrupted.id)
        machine.speechFailed(interrupted.id, "stale")
        assertEquals(resumed.id, machine.snapshot().speakingId)
    }

    @Test fun replicaResumeRestartsItsFirstSegmentAndKeepsTheFollowingReplica() {
        val machine = ready(ResumePolicy.REPLICA)
        val first = machine.cues(1, listOf("Bonjour. À demain.", "Une autre réplique.")).spoken()
        val interrupted = machine.speechFinished(first.id).spoken()
        assertEquals("À demain.", interrupted.text)
        machine.setMediaReady(false)
        machine.pause()
        machine.setMediaReady(true)
        machine.speechFinished(interrupted.id)
        assertPaused(machine)
        val restarted = machine.play().spoken()
        assertEquals("Bonjour.", restarted.text)
        assertEquals("À demain.", restarted.remainingText)
        val second = machine.speechFinished(restarted.id).spoken()
        assertEquals("À demain.", second.text)
        val following = machine.speechFinished(second.id).spoken()
        assertEquals("Une autre réplique.", following.text)
        assertTrue(machine.speechFinished(following.id).filterIsInstance<Effect.Speak>().isEmpty())
        assertEquals(0, machine.snapshot().pendingCount)
    }

    @Test fun segmentResumeKeepsOnlyTheInterruptedSegment() {
        val machine = ready(ResumePolicy.SEGMENT)
        val first = machine.cues(1, listOf("Bonjour. À demain.", "Une autre réplique.")).spoken()
        val interrupted = machine.speechFinished(first.id).spoken()
        machine.setMediaReady(false)
        machine.pause()
        machine.setMediaReady(true)
        assertPaused(machine)
        val resumed = machine.play().spoken()
        assertEquals("À demain.", resumed.text)
        assertNotEquals(interrupted.id, resumed.id)
        assertEquals("Une autre réplique.", machine.speechFinished(resumed.id).spoken().text)
    }

    @Test fun voiceAvailabilityRetryAlsoRespectsReplicaResumeAfterManualPause() {
        val machine = ready(ResumePolicy.REPLICA)
        val first = machine.cues(1, listOf("Bonjour. À demain.")).spoken()
        val interrupted = machine.speechFinished(first.id).spoken()
        machine.setSpeechReady(false)
        machine.pause()
        machine.setSpeechReady(true)
        machine.speechFinished(interrupted.id)
        assertPaused(machine)
        assertEquals("Bonjour.", machine.play().spoken().text)
    }

    @Test fun failedSegmentAndThenManualPauseKeepsExplicitResumeRequired() {
        val machine = ready(ResumePolicy.EXPLICIT)
        val interrupted = machine.cues(1, listOf("Bonjour.")).spoken()
        machine.speechFailed(interrupted.id, "retry required")
        machine.pause()
        assertTrue(machine.snapshot().voiceSuspended)
        machine.setSpeechReady(true)
        machine.play()
        assertPaused(machine)
        assertEquals("Bonjour.", machine.resumeVoice().spoken().text)
    }

    @Test fun aQueuedReplicaThatNeverStartedDoesNotAcquireExplicitSuspension() {
        val machine = NarrationMachine().apply {
            setResumePolicy(ResumePolicy.EXPLICIT); setSpeechReady(true); play()
        }
        machine.cues(1, listOf("Pas encore commencée."))
        machine.pause()
        assertFalse(machine.snapshot().voiceSuspended)
        machine.setMediaReady(true)
        assertEquals("Pas encore commencée.", machine.play().spoken().text)
    }

    @Test fun aNewQueuedReplicaIsNotConfusedWithTheEarlierCompletedReplica() {
        val machine = ready(ResumePolicy.EXPLICIT)
        val completed = machine.cues(1, listOf("Déjà terminée.")).spoken()
        machine.speechFinished(completed.id)
        machine.setMediaReady(false)
        machine.cues(2, listOf("Nouvelle réplique."))
        machine.pause()
        assertFalse(machine.snapshot().voiceSuspended)
        machine.setMediaReady(true)
        assertEquals("Nouvelle réplique.", machine.play().spoken().text)
    }

    @Test fun pausingAPreviewDoesNotChangeTheMovieResumePolicy() {
        val machine = ready(ResumePolicy.EXPLICIT)
        val movie = machine.cues(1, listOf("La réplique du film.")).spoken()
        val preview = machine.preview("Un essai distinct.").spoken()
        machine.pause()
        assertFalse(machine.snapshot().voiceSuspended)
        machine.speechFinished(preview.id)
        machine.speechFinished(movie.id)
        assertPaused(machine)
        assertEquals("La réplique du film.", machine.play().spoken().text)
    }
}
