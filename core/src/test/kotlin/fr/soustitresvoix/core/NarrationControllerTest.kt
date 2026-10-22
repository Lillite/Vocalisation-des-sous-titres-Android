package fr.soustitresvoix.core

import fr.soustitresvoix.core.NarrationMachine.Effect
import fr.soustitresvoix.core.NarrationMachine.Mode
import fr.soustitresvoix.core.NarrationMachine.ResumePolicy
import org.junit.Assert.*
import org.junit.Test

/** Compare complete user-visible traces with the unchanged machine, then assert
 * literal source text and lifecycle invariants independently of that comparison. */
class NarrationControllerTest {
    private class Trace {
        val reference = NarrationMachine()
        val controller: NarrationController = ExistingNarrationController()

        fun step(label: String, old: NarrationMachine.() -> List<Effect>,
            adapted: NarrationController.() -> List<Effect>): List<Effect> {
            val expected = reference.old()
            val actual = controller.adapted()
            assertEquals("$label: ordered effects changed", expected, actual)
            assertEquals("$label: state changed", reference.snapshot(), controller.snapshot())
            assertEquals("$label: queued text changed", reference.pendingTexts(), controller.pendingTexts())
            assertEquals("$label: timing plan changed", reference.pendingPlan(), controller.pendingPlan())
            return actual
        }

        fun ready(mode: Mode = Mode.COMFORT) {
            step("mode", { setMode(mode) }, { setMode(mode) })
            step("voice ready", { setSpeechReady(true) }, { setSpeechReady(true) })
            step("media ready", { setMediaReady(true) }, { setMediaReady(true) })
            step("play", { play() }, { play() })
        }

        fun complete(id: String) = step("complete $id", { speechFinished(id) }, { speechFinished(id) })

        fun drain(initial: List<Effect>): List<String> {
            val spoken = initial.filterIsInstance<Effect.Speak>().map { it.text }.toMutableList()
            var count = 0
            while (controller.snapshot().speakingId != null) {
                check(++count < 100) { "Narration did not drain" }
                spoken += complete(requireNotNull(controller.snapshot().speakingId))
                    .filterIsInstance<Effect.Speak>().map { it.text }
            }
            assertEquals(0, controller.snapshot().pendingCount)
            return spoken
        }
    }

    @Test fun comfortAndAdaptiveKeepPanelsOverlapsAndEveryRealRepeatInOrder() {
        for (mode in listOf(Mode.COMFORT, Mode.ADAPTIVE)) {
            val trace = Trace().apply { ready(mode) }
            val panel = "Panneau — accès réservé."
            val first = trace.step("panel", {
                cues(1_000_000, listOf(panel), identities = listOf("sign"), deadlinesMs = listOf(5000L))
            }, {
                cues(1_000_000, listOf(panel), identities = listOf("sign"), deadlinesMs = listOf(5000L))
            })
            val originalId = first.filterIsInstance<Effect.Speak>().single().id
            trace.step("overlap", {
                cues(2_000_000, listOf(panel, "Oui."), identities = listOf("sign", "reply"),
                    deadlinesMs = listOf(5000L, 2600L))
            }, {
                cues(2_000_000, listOf(panel, "Oui."), identities = listOf("sign", "reply"),
                    deadlinesMs = listOf(5000L, 2600L))
            })
            val unchanged = trace.step("same visible occurrences", {
                cues(2_500_000, listOf(panel, "Oui."), identities = listOf("sign", "reply"),
                    deadlinesMs = listOf(5000L, 2600L))
            }, {
                cues(2_500_000, listOf(panel, "Oui."), identities = listOf("sign", "reply"),
                    deadlinesMs = listOf(5000L, 2600L))
            })
            assertTrue(unchanged.none { it is Effect.Speak || it is Effect.StopSpeech })
            assertEquals(originalId, trace.controller.snapshot().speakingId)
            trace.step("expired overlapping dialogue", { advance(2600) }, { advance(2600) })
            assertTrue(trace.controller.snapshot().waitingForSpeech)
            assertFalse(trace.controller.snapshot().videoRequested)
            val all = trace.drain(first).toMutableList()
            for (occurrence in 1..2) {
                trace.step("blank before repeat", { cues(6_000_000L + occurrence * 2_000_000L, emptyList()) },
                    { cues(6_000_000L + occurrence * 2_000_000L, emptyList()) })
                val repeat = trace.step("distinct repeat $occurrence", {
                    cues(7_000_000L + occurrence * 2_000_000L, listOf("Oui."), identities = listOf("repeat-$occurrence"))
                }, {
                    cues(7_000_000L + occurrence * 2_000_000L, listOf("Oui."), identities = listOf("repeat-$occurrence"))
                })
                all += trace.drain(repeat)
            }
            assertEquals("$mode source events", listOf(panel, "Oui.", "Oui.", "Oui."), all)
        }
    }

    @Test fun everyResumePolicySurvivesBufferingPauseAndStaleVoiceCallbacks() {
        for (policy in ResumePolicy.entries) {
            val trace = Trace().apply { ready() }
            trace.step("resume policy", { setResumePolicy(policy) }, { setResumePolicy(policy) })
            val first = trace.step("two complete source events", {
                cues(1_000_000, listOf("Bonjour. À demain.", "Autre réplique."))
            }, {
                cues(1_000_000, listOf("Bonjour. À demain.", "Autre réplique."))
            }).filterIsInstance<Effect.Speak>().single()
            assertEquals("Bonjour.", first.text)
            val interrupted = trace.complete(first.id).filterIsInstance<Effect.Speak>().single()
            assertEquals("À demain.", interrupted.text)
            trace.step("buffering", { setMediaReady(false) }, { setMediaReady(false) })
            trace.step("manual pause", { pause() }, { pause() })
            trace.step("buffer recovered", { setMediaReady(true) }, { setMediaReady(true) })
            trace.complete(interrupted.id)
            trace.step("old error", { speechFailed(interrupted.id, "old callback") },
                { speechFailed(interrupted.id, "old callback") })
            assertFalse(trace.controller.snapshot().wantsPlayback)
            assertFalse(trace.controller.snapshot().videoRequested)
            assertNull(trace.controller.snapshot().speakingId)
            val resumed = if (policy == ResumePolicy.EXPLICIT) {
                val ordinaryPlay = trace.step("ordinary play remains blocked", { play() }, { play() })
                assertTrue(ordinaryPlay.none { it is Effect.Speak })
                assertFalse(trace.controller.snapshot().wantsPlayback)
                trace.step("explicit voice resume", { resumeVoice() }, { resumeVoice() })
            } else trace.step("explicit play", { play() }, { play() })
            val newId = resumed.filterIsInstance<Effect.Speak>().single().id
            assertNotEquals(interrupted.id, newId)
            trace.complete(interrupted.id)
            assertEquals(newId, trace.controller.snapshot().speakingId)
            val expected = if (policy == ResumePolicy.REPLICA)
                listOf("Bonjour.", "À demain.", "Autre réplique.")
            else listOf("À demain.", "Autre réplique.")
            assertEquals("$policy preserved queue", expected, trace.drain(resumed))
        }
    }

    @Test fun seekReplacementAndBothPreviewCommandsCannotReuseAnOldUtterance() {
        val trace = Trace().apply { ready(Mode.ADAPTIVE) }
        val old = trace.step("old media cue", {
            cues(1_000_000, listOf("Réplique avant déplacement."), identities = listOf("old"))
        }, {
            cues(1_000_000, listOf("Réplique avant déplacement."), identities = listOf("old"))
        }).filterIsInstance<Effect.Speak>().single()
        trace.step("seek reset", { reset(true) }, { reset(true) })
        trace.step("seek ready", { setMediaReady(true) }, { setMediaReady(true) })
        val fresh = trace.step("new media cue", {
            cues(2_000_000, listOf("Réplique après déplacement."), identities = listOf("new"))
        }, {
            cues(2_000_000, listOf("Réplique après déplacement."), identities = listOf("new"))
        }).filterIsInstance<Effect.Speak>().single()
        trace.complete(old.id)
        trace.step("old failure after seek", { speechFailed(old.id, "old callback") },
            { speechFailed(old.id, "old callback") })
        assertEquals(fresh.id, trace.controller.snapshot().speakingId)
        assertEquals("Réplique après déplacement.", trace.step("repeat last replica", { preview() },
            { preview() }).filterIsInstance<Effect.Speak>().single().text)
        val explicit = trace.step("separate voice preview", { preview("Essai distinct.") },
            { preview("Essai distinct.") }).filterIsInstance<Effect.Speak>().single()
        assertEquals("Essai distinct.", explicit.text)
        assertTrue(explicit.preview)
        trace.complete(fresh.id)
        assertEquals(explicit.id, trace.controller.snapshot().speakingId)
        trace.step("replacement reset defaults to pause", { reset() }, { reset() })
        trace.step("replacement ready", { setMediaReady(true) }, { setMediaReady(true) })
        trace.complete(explicit.id)
        assertFalse(trace.controller.snapshot().wantsPlayback)
        assertFalse(trace.controller.snapshot().videoRequested)
        assertNull(trace.controller.snapshot().speakingId)
        assertEquals(0, trace.controller.snapshot().pendingCount)
    }
}
