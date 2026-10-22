package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test

class ImageCommandsTest {
    private fun baseContext() = ImageCommandContext(
        generation = 7L,
        closed = false,
        hdrProtected = false,
        availability = CapabilityStatus.SUPPORTED,
        requested = ImageSettings(.1f, -.05f)
    )

    private fun accepted(command: ImageCommand, context: ImageCommandContext): ImageCommandDecision.Accepted {
        val decision = ImageCommandPolicy.validate(ImageCommandRequest(context.generation, command), context)
        assertTrue("Expected acceptance for $command, got $decision", decision is ImageCommandDecision.Accepted)
        return decision as ImageCommandDecision.Accepted
    }

    private fun rejected(request: ImageCommandRequest, context: ImageCommandContext, reason: ImageCommandRejection) {
        assertEquals(ImageCommandDecision.Rejected(request.generation, reason), ImageCommandPolicy.validate(request, context))
    }

    @Test fun adjustmentValidationIsFiniteBoundedAtomicAndNeverClamps() {
        val context = baseContext()
        for (contrast in listOf(-.3f, 0f, .3f)) {
            for (brightness in listOf(-.2f, 0f, .2f)) {
                val decision = accepted(ImageCommand.SetAdjustments(contrast, brightness), context)
                assertEquals(ImageSettings(contrast, brightness), decision.requested)
                assertEquals(ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION, decision.disposition)
                assertEquals(context.generation, decision.generation)
                assertTrue(decision.changed)
            }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (command in listOf(ImageCommand.SetAdjustments(value, .1f), ImageCommand.SetAdjustments(.2f, value))) {
                rejected(ImageCommandRequest(context.generation, command), context, ImageCommandRejection.NON_FINITE_VALUE)
            }
        }
        val outside = listOf(
            ImageCommand.SetAdjustments(Math.nextDown(-.3f), 0f),
            ImageCommand.SetAdjustments(Math.nextUp(.3f), 0f),
            ImageCommand.SetAdjustments(0f, Math.nextDown(-.2f)),
            ImageCommand.SetAdjustments(0f, Math.nextUp(.2f))
        )
        for (command in outside)
            rejected(ImageCommandRequest(context.generation, command), context, ImageCommandRejection.OUT_OF_RANGE)

        // Numerical errors are specific even when later capability/HDR checks
        // would reject the same request. Neither member is partially applied.
        val blocked = context.copy(availability = CapabilityStatus.UNKNOWN, hdrProtected = true)
        rejected(ImageCommandRequest(context.generation, ImageCommand.SetAdjustments(.4f, Float.NaN)),
            blocked, ImageCommandRejection.NON_FINITE_VALUE)
        rejected(ImageCommandRequest(context.generation, ImageCommand.SetAdjustments(.4f, .1f)),
            blocked, ImageCommandRejection.OUT_OF_RANGE)
        assertEquals(ImageSettings(.1f, -.05f), context.requested)
        assertEquals(context.requested, blocked.requested)
    }

    @Test fun generationAndClosureWinBeforeOtherReasonsAndNeverRetagARequest() {
        val context = baseContext().copy(availability = CapabilityStatus.UNAVAILABLE, hdrProtected = true)
        val old = ImageCommandRequest(context.generation - 1, ImageCommand.SetAdjustments(Float.NaN, .4f))
        rejected(old, context, ImageCommandRejection.STALE_MEDIA)
        rejected(old, context.copy(closed = true), ImageCommandRejection.CLOSED)
        rejected(old.copy(generation = context.generation), context.copy(closed = true), ImageCommandRejection.CLOSED)

        // A new opening has a distinct generation even if its URI is unchanged.
        val current = baseContext().copy(generation = 8L, requested = ImageSettings())
        for (command in listOf(ImageCommand.SetAdjustments(.2f, .1f), ImageCommand.SetOriginalComparison(true))) {
            val previousRequest = ImageCommandRequest(7L, command)
            rejected(previousRequest, current, ImageCommandRejection.STALE_MEDIA)
            rejected(previousRequest, current.copy(closed = true), ImageCommandRejection.CLOSED)
            rejected(previousRequest.copy(generation = 8L), current.copy(closed = true), ImageCommandRejection.CLOSED)
            assertEquals(8L, accepted(command, current).generation)
        }
        assertEquals(ImageSettings(), current.requested)
    }

    @Test fun availabilityAndHdrGuardsPreserveOriginalComparisonAndBypassRequests() {
        val adjustment = ImageCommand.SetAdjustments(-.2f, .15f)
        val commands = listOf(adjustment, ImageCommand.SetOriginalComparison(true), ImageCommand.SetOriginalComparison(false))
        for ((availability, reason) in listOf(
            CapabilityStatus.UNAVAILABLE to ImageCommandRejection.CAPABILITY_UNAVAILABLE,
            CapabilityStatus.UNKNOWN to ImageCommandRejection.CAPABILITY_UNKNOWN
        )) {
            for (hdr in listOf(false, true)) for (command in commands) {
                val context = baseContext().copy(availability = availability, hdrProtected = hdr)
                rejected(ImageCommandRequest(context.generation, command), context, reason)
            }
        }
        for (availability in listOf(CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL)) {
            val context = baseContext().copy(availability = availability)
            assertEquals(ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION, accepted(adjustment, context).disposition)
            val bypassed = context.copy(requested = context.requested.copy(originalComparison = true))
            val retained = accepted(adjustment, bypassed)
            assertEquals(ImageSettings(-.2f, .15f, true), retained.requested)
            assertEquals(ImageCommandDisposition.MASKED_BY_ORIGINAL_COMPARISON, retained.disposition)
            assertTrue(retained.changed)

            val comparison = accepted(ImageCommand.SetOriginalComparison(true), context)
            assertEquals(context.requested.copy(originalComparison = true), comparison.requested)
            assertEquals(ImageCommandDisposition.MASKED_BY_ORIGINAL_COMPARISON, comparison.disposition)
            val restored = accepted(ImageCommand.SetOriginalComparison(false), bypassed)
            assertEquals(context.requested, restored.requested)
            assertEquals(ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION, restored.disposition)

            for (original in listOf(false, true)) {
                val hdr = context.copy(hdrProtected = true,
                    requested = context.requested.copy(originalComparison = original))
                rejected(ImageCommandRequest(hdr.generation, adjustment), hdr, ImageCommandRejection.HDR_PROTECTED)
                for (enabled in listOf(false, true)) {
                    val result = accepted(ImageCommand.SetOriginalComparison(enabled), hdr)
                    assertEquals(hdr.requested.copy(originalComparison = enabled), result.requested)
                    assertEquals(ImageCommandDisposition.MASKED_BY_HDR, result.disposition)
                    assertEquals(enabled != original, result.changed)
                }
            }
        }
    }

    @Test fun acceptanceIsIdempotentAndNeverCreatesOrUpdatesASubmittedPlan() {
        val context = baseContext()
        val lastSubmitted = SubmittedImageAdjustments(context.generation, 4L, 0f, 0f)
        val snapshot = ImageCommandSnapshot(context, lastSubmitted)
        val changed = accepted(ImageCommand.SetAdjustments(.2f, .1f), context)
        assertTrue(changed.changed)
        assertEquals(ImageSettings(.2f, .1f), changed.requested)
        assertEquals(ImageSettings(.1f, -.05f), snapshot.context.requested)
        assertEquals(SubmittedImageAdjustments(7L, 4L, 0f, 0f), snapshot.lastSubmitted)

        val acceptedContext = context.copy(requested = changed.requested)
        val unchanged = accepted(ImageCommand.SetAdjustments(.2f, .1f), acceptedContext)
        assertFalse("Repeating the same request must not restart application work", unchanged.changed)
        assertEquals(changed.requested, unchanged.requested)
        assertFalse(accepted(ImageCommand.SetOriginalComparison(false), acceptedContext).changed)
        val bypassed = acceptedContext.copy(requested = acceptedContext.requested.copy(originalComparison = true))
        assertFalse(accepted(ImageCommand.SetOriginalComparison(true), bypassed).changed)
        assertFalse(accepted(ImageCommand.SetAdjustments(.2f, .1f), bypassed).changed)
        assertFalse("Signed zero is the same numerical correction",
            accepted(ImageCommand.SetAdjustments(-0f, -0f), context.copy(requested = ImageSettings())).changed)

        val unsent = ImageCommandSnapshot(acceptedContext, null)
        accepted(ImageCommand.SetOriginalComparison(true), unsent.context)
        assertNull("Pure validation cannot acknowledge a renderer submission", unsent.lastSubmitted)
        assertEquals(4L, requireNotNull(snapshot.lastSubmitted).sequence)
    }
}
