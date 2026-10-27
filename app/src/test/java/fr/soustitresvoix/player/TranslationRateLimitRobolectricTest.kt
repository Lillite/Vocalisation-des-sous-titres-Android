package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationRateLimitRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = TranslationProviderConfig(TranslationProvider.DEEPL_FREE, "synthetic-test-key")
    private class Clock {
        var now = 0L
        var afterSleep: () -> Unit = {}
        val pacing = TranslationRequestPacing({ now }, { now += it; afterSleep() }, { 0 })
    }
    private class Transport(val clock: Clock, val respond: (Int, TranslationHttpRequest) -> TranslationHttpResponse) : TranslationHttpTransport {
        val requests = mutableListOf<TranslationHttpRequest>()
        val times = mutableListOf<Long>()
        override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
            requests += request; times += clock.now
            return respond(requests.size, request)
        }
        override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean) = post(request, cancelled)
    }
    private fun translated(request: TranslationHttpRequest): TranslationHttpResponse {
        val texts = JSONObject(request.body).getJSONArray("text")
        return TranslationHttpResponse(200, JSONObject().put("translations", JSONArray().apply {
            for (i in 0 until texts.length()) put(JSONObject().put("text", "Traduit ${texts.getString(i)}"))
        }).toString())
    }
    private fun failure(reason: TranslationFailure.Reason, action: () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (e: TranslationFailure) { assertEquals(reason, e.reason); assertNull(e.cause) }
    }
    private fun track() = TranslationPlan.create((0 until 45).map {
        TranslationCue("id-$it", it * 3_000_000L, it * 3_000_000L + 2_000_000L,
            if (it in 19..21) "Repeated sign" else "Source $it")
    }, "en", "fr", config.provider.id)

    @Test fun rateRejectionsRetryTheSameBatchInOrderThenContinueWithCompleteTrack() {
        val clock = Clock()
        val transport = Transport(clock) { n, request -> when(n) {
            1 -> TranslationHttpResponse(429, "private error body")
            2 -> TranslationHttpResponse(529, "", 3_000)
            else -> translated(request)
        } }
        val plan = track()
        val output = TranslationRunner(temporary.newFolder(), temporary.newFolder())
            .run(plan, TranslationEngines.create(config, transport, clock.pacing), { false }) { _, _ -> }
        assertEquals(plan.cues.map { it.id }, output.cues.map { it.id })
        assertEquals(plan.cues.map { it.startUs to it.endUs }, output.cues.map { it.startUs to it.endUs })
        assertEquals(plan.texts.map { "Traduit $it" }, output.cues.map { it.text })
        assertEquals(listOf(0L, 1_000L, 4_000L, 4_500L, 5_000L), transport.times)
        assertSame(transport.requests[0], transport.requests[1])
        assertSame(transport.requests[1], transport.requests[2])
    }
    @Test fun repeatedRateLimitStopsAfterFiveRetriesWithoutClaimingQuotaExhaustion() {
        val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(429, "") }
        failure(TranslationFailure.Reason.RATE_LIMIT) {
            TranslationEngines.create(config, transport, clock.pacing).translate(listOf("Hello"), "en", "fr") { false }
        }
        assertEquals(6, transport.requests.size)
        assertEquals(31_000L, clock.now)
    }
    @Test fun serverDelayBeyondBudgetNeverCausesAnEarlyRetry() {
        val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(429, "", 120_001) }
        failure(TranslationFailure.Reason.RATE_LIMIT) {
            TranslationEngines.create(config, transport, clock.pacing).translate(listOf("Hello"), "en", "fr") { false }
        }
        assertEquals(1, transport.requests.size); assertEquals(0L, clock.now)
    }
    @Test fun cancellationDuringBackoffPreventsAnySecondRequest() {
        val clock = Clock(); var cancelled = false; clock.afterSleep = { cancelled = true }
        val transport = Transport(clock) { _, _ -> TranslationHttpResponse(429, "") }
        try {
            TranslationEngines.create(config, transport, clock.pacing).translate(listOf("Hello"), "en", "fr") { cancelled }
            fail("Cancellation expected")
        } catch (_: TranslationCancelledException) { }
        assertEquals(1, transport.requests.size); assertTrue(clock.now <= 100)
    }
    @Test fun closingEngineDuringBackoffPreventsAnySecondRequest() {
        val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(529, "") }
        val engine = TranslationEngines.create(config, transport, clock.pacing)
        clock.afterSleep = { engine.close() }
        try { engine.translate(listOf("Hello"), "en", "fr") { false }; fail("Closed engine") }
        catch (_: TranslationCancelledException) { }
        assertEquals(1, transport.requests.size)
    }
    @Test fun interruptedWaitRestoresInterruptAndNeverResends() {
        val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(429, "") }
        val pacing = TranslationRequestPacing({ clock.now }, { throw InterruptedException() }, { 0 })
        try {
            try { TranslationEngines.create(config, transport, pacing).translate(listOf("Hello"), "en", "fr") { false }; fail() }
            catch (_: TranslationCancelledException) { assertTrue(Thread.currentThread().isInterrupted) }
            assertEquals(1, transport.requests.size)
        } finally { Thread.interrupted() }
    }
    @Test fun quotaAndUncertainFailuresAreNeverRetried() {
        for (status in listOf(456, 401, 403, 503)) {
            val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(status, "private text") }
            val reason = when(status) { 456 -> TranslationFailure.Reason.QUOTA; 503 -> TranslationFailure.Reason.SERVER; else -> TranslationFailure.Reason.AUTHENTICATION }
            failure(reason) { TranslationEngines.create(config, transport, clock.pacing).translate(listOf("Hello"), "en", "fr") { false } }
            assertEquals(1, transport.requests.size)
        }
        val clock = Clock(); val transport = Transport(clock) { _, _ -> throw IOException("private network error") }
        failure(TranslationFailure.Reason.NETWORK) { TranslationEngines.create(config, transport, clock.pacing).translate(listOf("Hello"), "en", "fr") { false } }
        assertEquals(1, transport.requests.size)
    }
    @Test fun rejectedBatchKeepsPrefixAndResumeDoesNotSendItAgain() {
        val clock = Clock(); val transport = Transport(clock) { n, request -> if(n == 1) translated(request) else TranslationHttpResponse(456, "") }
        val plan = track(); val cache = temporary.newFolder(); val checkpoints = temporary.newFolder()
        failure(TranslationFailure.Reason.QUOTA) {
            TranslationRunner(cache, checkpoints).run(plan, TranslationEngines.create(config, transport, clock.pacing), { false }) { _, _ -> }
        }
        val resumed = TranslationRunner(cache, checkpoints)
        assertEquals(20 to false, resumed.checkpointSummary(plan))
        val resumedTransport = Transport(clock) { _, request -> translated(request) }
        val output = resumed.run(plan, TranslationEngines.create(config, resumedTransport, clock.pacing), { false }) { _, _ -> }
        assertEquals(45, output.cues.size); assertEquals(2, resumedTransport.requests.size)
        assertEquals(plan.texts[20], JSONObject(resumedTransport.requests.first().body).getJSONArray("text").getString(0))
    }
    @Test fun exhaustedRateRetriesLeaveAKnownRefusalCheckpoint() {
        val clock = Clock(); val transport = Transport(clock) { n, request -> if(n == 1) translated(request) else TranslationHttpResponse(429, "") }
        val plan = track(); val runner = TranslationRunner(temporary.newFolder(), temporary.newFolder())
        failure(TranslationFailure.Reason.RATE_LIMIT) {
            runner.run(plan, TranslationEngines.create(config, transport, clock.pacing), { false }) { _, _ -> }
        }
        assertEquals(20 to false, runner.checkpointSummary(plan)); assertEquals(7, transport.requests.size)
    }
    @Test fun retryAfterAcceptsSecondsOrHttpDateAndBoundsUntrustedValues() {
        assertEquals(2_000L, TranslationRequestPacing.retryAfterMillis(" 2 ", 0))
        assertEquals(5_000L, TranslationRequestPacing.retryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 0))
        assertEquals(0L, TranslationRequestPacing.retryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 6_000))
        assertEquals(Long.MAX_VALUE, TranslationRequestPacing.retryAfterMillis("9".repeat(40), 0))
        for(value in listOf(null, "", "bad header", "-5", "1.5", "x".repeat(129))) assertNull(TranslationRequestPacing.retryAfterMillis(value, 0))
    }
    @Test fun quotaLookupDistinguishesRateLimitWithoutRetrying() {
        for ((status, reason) in listOf(429 to TranslationFailure.Reason.RATE_LIMIT, 529 to TranslationFailure.Reason.RATE_LIMIT, 456 to TranslationFailure.Reason.QUOTA)) {
            val clock = Clock(); val transport = Transport(clock) { _, _ -> TranslationHttpResponse(status, "") }
            failure(reason) { TranslationEngines.usage(config, transport = transport) }
            assertEquals(1, transport.requests.size)
        }
    }
}
