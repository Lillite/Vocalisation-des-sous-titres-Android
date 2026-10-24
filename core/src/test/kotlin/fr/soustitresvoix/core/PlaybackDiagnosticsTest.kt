package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    private fun decoder(name: String) = CapabilityDiagnostic(
        id = "video.decoder",
        status = CapabilityStatus.SUPPORTED,
        assessmentSource = DiagnosticSource.PLAYER_STATE,
        reason = "The current backend exposes its initialized decoder",
        requested = DiagnosticFact(DiagnosticValue.Text("prefer-software"), DiagnosticSource.USER_PREFERENCE),
        configured = DiagnosticFact(DiagnosticValue.Text("software-first"), DiagnosticSource.PLAYER_CONFIGURATION),
        observed = DiagnosticFact(DiagnosticValue.Text(name), DiagnosticSource.DECODER_EVENT)
    )

    @Test fun availabilityAndEachEvidenceStageKeepTheirOwnMeaningAndProvenance() {
        val store = PlaybackDiagnosticStore()
        val requested = DiagnosticFact(DiagnosticValue.Decimal(0.25), DiagnosticSource.USER_PREFERENCE)
        val configured = DiagnosticFact(DiagnosticValue.Decimal(0.0), DiagnosticSource.PLAYER_CONFIGURATION)
        val adjustment = CapabilityDiagnostic(
            id = "video.adjustment",
            status = CapabilityStatus.CONDITIONAL,
            assessmentSource = DiagnosticSource.APPLICATION_POLICY,
            reason = "The current media conditions restrict the configured adjustment",
            requested = requested,
            configured = configured
        )
        val count = DiagnosticFact(DiagnosticValue.Count(128), DiagnosticSource.AUDIO_PROCESSOR,
            "Cumulative accepted samples; not proof of instantaneous attenuation")
        val audio = CapabilityDiagnostic(
            id = "audio.attenuation",
            status = CapabilityStatus.UNKNOWN,
            assessmentSource = DiagnosticSource.NOT_EXPOSED,
            reason = "Instantaneous attenuation is not reported",
            requested = DiagnosticFact(DiagnosticValue.Flag(true), DiagnosticSource.USER_PREFERENCE),
            observed = count
        )
        assertTrue(store.publish(store.generation, listOf(adjustment, audio)))
        val report = store.snapshot().capabilities
        assertEquals(requested, report[0].requested)
        assertEquals(configured, report[0].configured)
        assertNull("Configuration must not become an observed result", report[0].observed)
        assertEquals(DiagnosticSource.APPLICATION_POLICY, report[0].assessmentSource)
        assertEquals(CapabilityStatus.UNKNOWN, report[1].status)
        assertEquals(DiagnosticSource.NOT_EXPOSED, report[1].assessmentSource)
        assertNull("A request must not become a configuration", report[1].configured)
        assertEquals(count, report[1].observed)

        // Even a positive support assessment is not evidence that a setting ran.
        assertTrue(store.publish(store.generation,
            listOf(adjustment.copy(status = CapabilityStatus.SUPPORTED))))
        assertNull(store.snapshot().capabilities.single().observed)
        assertEquals(CapabilityStatus.CONDITIONAL, report[0].status)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { DiagnosticValue.Decimal(value) }
        }
    }

    @Test fun invalidationDropsOldEvidenceAndRejectsItsOriginalGenerationTicket() {
        val store = PlaybackDiagnosticStore()
        val oldTicket = store.generation
        assertTrue(store.publish(oldTicket, listOf(decoder("old.decoder"))))
        val nextTicket = store.invalidate()
        assertEquals(oldTicket + 1, nextTicket)
        assertEquals(nextTicket, store.snapshot().generation)
        assertFalse(store.snapshot().closed)
        assertTrue(store.snapshot().capabilities.isEmpty())

        assertFalse("A late callback must not restore the previous media's decoder",
            store.publish(oldTicket, listOf(decoder("late.old.decoder"))))
        assertTrue(store.snapshot().capabilities.isEmpty())
        val waiting = CapabilityDiagnostic("video.decoder", CapabilityStatus.UNKNOWN,
            DiagnosticSource.PLAYER_STATE, "Waiting for this media's decoder event")
        assertTrue(store.publish(nextTicket, listOf(waiting)))
        assertNull(store.snapshot().capabilities.single().observed)
        assertTrue(store.publish(nextTicket, listOf(decoder("current.decoder"))))
        store.invalidate()
        assertFalse(store.publish(nextTicket, listOf(decoder("late.current.decoder"))))
        assertTrue(store.snapshot().capabilities.isEmpty())
    }

    @Test fun reportsOwnTheirListsAndAmbiguousDuplicateIdsCannotReplaceAValidReport() {
        val store = PlaybackDiagnosticStore()
        val original = decoder("first.decoder")
        val input = mutableListOf(original)
        assertTrue(store.publish(store.generation, input))
        val published = store.snapshot()
        input.clear()
        input += decoder("caller.changed.decoder")
        assertEquals(listOf(original), store.snapshot().capabilities)
        assertEquals(listOf(original), published.capabilities)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.capabilities as MutableList<CapabilityDiagnostic>).clear()
        }

        val directInput = mutableListOf(original)
        val directSnapshot = PlaybackDiagnosticSnapshot(7L, false, directInput)
        directInput.clear()
        assertEquals(listOf(original), directSnapshot.capabilities)
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(store.generation, listOf(original, original.copy(reason = "Duplicate key")))
        }
        assertEquals("A rejected publication leaves the previous report intact",
            listOf(original), store.snapshot().capabilities)

        val replacement = decoder("replacement.decoder")
        assertTrue(store.publish(store.generation, listOf(replacement)))
        assertEquals(listOf(replacement), store.snapshot().capabilities)
        assertEquals("A point-in-time snapshot must not follow later publications",
            listOf(original), published.capabilities)
    }

    @Test fun closingInvalidatesEvidenceAndCannotBeUndoneByPublishingOrInvalidating() {
        val store = PlaybackDiagnosticStore()
        val originalTicket = store.generation
        assertTrue(store.publish(originalTicket, listOf(decoder("before.close"))))
        store.close()
        val closedTicket = store.generation
        assertEquals(originalTicket + 1, closedTicket)
        assertTrue(store.snapshot().closed)
        assertTrue(store.snapshot().capabilities.isEmpty())
        assertFalse(store.publish(originalTicket, listOf(decoder("old.callback"))))
        assertFalse("Even a fresh-looking ticket cannot reopen a closed session",
            store.publish(closedTicket, listOf(decoder("after.close"))))
        assertEquals(closedTicket, store.invalidate())
        store.close()
        assertEquals("Repeated close must be idempotent", closedTicket, store.generation)
        assertTrue(store.snapshot().closed)
        assertTrue(store.snapshot().capabilities.isEmpty())
    }
}
