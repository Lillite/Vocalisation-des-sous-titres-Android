package fr.soustitresvoix.player

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleWebTranslationRobolectricTest {
    private class Clock {
        val now = AtomicLong()
        var onSleep: () -> Unit = { }
        val pacing = GoogleRequestPacing({ now.get() }, { now.addAndGet(it); onSleep() }, { 0 })
    }
    private class Transport(private val clock: Clock) : TranslationHttpTransport {
        val requests = mutableListOf<TranslationHttpRequest>()
        val times = mutableListOf<Long>()
        var closes = 0
        var reply: (TranslationHttpRequest, Int) -> TranslationHttpResponse = { request, _ ->
            TranslationHttpResponse(200, body(source(request), "Français ${source(request)}"))
        }
        override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse =
            throw AssertionError("Google web must use its fixed GET endpoint")
        override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
            assertEquals("https", request.uri.scheme)
            assertEquals("translate.googleapis.com", request.uri.host)
            assertEquals("/translate_a/single", request.uri.path)
            assertTrue(request.headers.keys.none { it.contains("key", true) || it.contains("authorization", true) })
            requests += request; times += clock.now.get()
            return reply(request, requests.size)
        }
        override fun close() { closes++ }
    }
    private fun engine(transport: Transport, clock: Clock, interval: Long = 1_000) = GoogleWebTranslationEngine(
        TranslationProviderConfig(TranslationProvider.GOOGLE_WEB,
            execution = TranslationExecutionOptions(googleIntervalMillis = interval)), transport, clock.pacing)

    private fun failure(reason: TranslationFailure.Reason, action: () -> Unit) {
        try { action(); fail("Expected application-owned failure") }
        catch (error: TranslationFailure) { assertEquals(reason, error.reason); assertNull(error.cause) }
    }

    @Test fun splittingPreservesEveryCodePointWhitespaceAndLineBreakWithinUriLimits() {
        val texts = listOf(
            (0 until 400).joinToString("\n") { "日本語😀 ligne-$it\t世界" },
            "Start" + " ".repeat(2_600) + "End\r\n",
            "😀".repeat(6_000), "漢".repeat(8_000),
            "a".repeat(2_399) + "😀" + "b".repeat(1_300)
        )
        for (text in texts) {
            val pieces = GoogleWebTranslationEngine.splitSource(text)
            assertEquals(text, pieces.joinToString(""))
            assertTrue(pieces.all { it.length <= 1_200 && URLEncoder.encode(it, "UTF-8").length <= 6_000 })
            assertTrue(pieces.all { Charsets.UTF_8.newEncoder().canEncode(it) })
        }
    }

    @Test fun longUnicodeTrackTextCanRoundTripWithoutDeletingOrAddingChunkBoundaries() {
        val clock = Clock(); val transport = Transport(clock)
        transport.reply = { request, _ -> TranslationHttpResponse(200, body(source(request), source(request))) }
        val text = "Opening\n" + " ".repeat(2_600) +
            (0 until 400).joinToString("\r\n") { "日本語😀 phrase-$it\t世界" } + "\nEnd"
        engine(transport, clock).use {
            assertEquals(listOf(text), it.translate(listOf(text), "en", "fr") { false })
        }
        assertTrue(transport.requests.isNotEmpty())
        assertTrue(transport.requests.all { source(it).isNotBlank() })
    }

    @Test fun allInputsAreValidatedBeforeTheFirstRequest() {
        val clock = Clock(); val transport = Transport(clock)
        engine(transport, clock).use { client ->
            for (invalid in listOf("", "\u0000", "\uD800", "x".repeat(24_001))) {
                failure(TranslationFailure.Reason.CONFIGURATION) {
                    client.translate(listOf("Valid", invalid), "en", "fr") { false }
                }
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun unspacedCjkSourceChunksCannotGlueTranslatedFrenchWordsTogether() {
        val clock = Clock(); val transport = Transport(clock)
        transport.reply = { request, count -> TranslationHttpResponse(200,
            body(source(request), if (count == 1) "Bonjour" else "monde")) }
        engine(transport, clock).use {
            assertEquals(listOf("Bonjour\nmonde"), it.translate(listOf("漢".repeat(1_100)), "ja", "fr") { false })
        }
        assertEquals("漢".repeat(1_100), transport.requests.joinToString("") { source(it) })
    }

    @Test fun missingOrEmptyFinalSegmentsAndChangedSourceEchoAreRejected() {
        val source = "First. Last."
        val invalid = listOf(
            body("First.", "Premier."),
            segments("Premier." to "First. ", "" to "Last."),
            segments("Dernier." to "Last. ", "Premier." to "First."),
            body("First. Lost.", "Premier. Dernier."),
            body(source, "\uD800"),
            "[]", "not json"
        )
        for (response in invalid) {
            val clock = Clock(); val transport = Transport(clock)
            transport.reply = { _, _ -> TranslationHttpResponse(200, response) }
            engine(transport, clock).use {
                failure(TranslationFailure.Reason.RESPONSE) { it.translate(listOf(source), "en", "fr") { false } }
            }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun orderedSegmentsAndOuterSourceWhitespaceArePreserved() {
        val clock = Clock(); val transport = Transport(clock)
        transport.reply = { _, _ -> TranslationHttpResponse(200,
            segments("Bonjour\n" to "Hello\n", "Monde" to "World")) }
        engine(transport, clock).use {
            assertEquals(listOf("  Bonjour\nMonde\n"), it.translate(listOf("  Hello\nWorld\n"), "en", "fr") { false })
        }
    }

    @Test fun repeatedExactTextsUseOneRequestButRetainEveryOccurrenceAndLanguageScope() {
        val clock = Clock(); val transport = Transport(clock)
        engine(transport, clock).use {
            assertEquals(listOf("Français EXIT", "Français EXIT", "Français Goodbye"),
                it.translate(listOf("EXIT", "EXIT", "Goodbye"), "en", "fr") { false })
            assertEquals(2, transport.requests.size)
            it.translate(listOf("EXIT"), "en", "fr") { false }
            assertEquals(2, transport.requests.size)
            it.translate(listOf("Exit"), "en", "fr") { false }
            it.translate(listOf("EXIT"), "de", "fr") { false }
            assertEquals(4, transport.requests.size)
        }
    }

    @Test fun cacheEvictsLeastRecentlyUsedTextAfter512Entries() {
        val clock = Clock(); val transport = Transport(clock)
        engine(transport, clock).use {
            repeat(512) { index -> it.translate(listOf("Unique-$index"), "en", "fr") { false } }
            it.translate(listOf("Unique-0"), "en", "fr") { false }
            it.translate(listOf("Unique-512"), "en", "fr") { false }
            it.translate(listOf("Unique-0"), "en", "fr") { false }
            assertEquals(513, transport.requests.size)
            it.translate(listOf("Unique-1"), "en", "fr") { false }
            assertEquals(514, transport.requests.size)
        }
    }

    @Test fun cacheCharacterBudgetEvictsLongResultsEvenBelow512Entries() {
        val clock = Clock(); val transport = Transport(clock)
        transport.reply = { request, _ -> TranslationHttpResponse(200, body(source(request), "T".repeat(3_000))) }
        engine(transport, clock).use {
            repeat(100) { index -> it.translate(listOf("Unique-$index"), "en", "fr") { false } }
            it.translate(listOf("Unique-99"), "en", "fr") { false }
            assertEquals(100, transport.requests.size)
            it.translate(listOf("Unique-0"), "en", "fr") { false }
            assertEquals(101, transport.requests.size)
        }
    }

    @Test fun retryAfterIsRespectedWithAtMostThreeRetries() {
        val clock = Clock(); val transport = Transport(clock)
        transport.reply = { _, _ -> TranslationHttpResponse(429, "", 5_000) }
        engine(transport, clock).use {
            failure(TranslationFailure.Reason.RATE_LIMIT) { it.translate(listOf("Hello"), "en", "fr") { false } }
        }
        assertEquals(listOf(0L, 5_000L, 10_000L, 18_000L), transport.times)
    }

    @Test fun hugeRetryAfterSuspendsTheSharedQueueWithoutOverflowOrPrematureRetry() {
        for (delay in listOf(120_001L, Long.MAX_VALUE)) {
            val clock = Clock().apply { now.set(10_000) }
            val first = Transport(clock).apply { reply = { _, _ -> TranslationHttpResponse(429, "", delay) } }
            engine(first, clock).use {
                failure(TranslationFailure.Reason.RATE_LIMIT) { it.translate(listOf("Hello"), "en", "fr") { false } }
            }
            val second = Transport(clock)
            engine(second, clock).use {
                failure(TranslationFailure.Reason.RATE_LIMIT) { it.translate(listOf("Later"), "en", "fr") { false } }
            }
            assertEquals(1, first.requests.size); assertTrue(second.requests.isEmpty())
            assertEquals(10_000L, clock.now.get())
        }
    }

    @Test fun cumulativeWaitIncludesInitialPacingAndNeverExceeds120Seconds() {
        val clock = Clock(); val transport = Transport(clock)
        engine(transport, clock).use {
            it.translate(listOf("First"), "en", "fr") { false }
            transport.reply = { _, _ -> TranslationHttpResponse(429, "", 50_000) }
            failure(TranslationFailure.Reason.RATE_LIMIT) { it.translate(listOf("Second"), "en", "fr") { false } }
        }
        assertEquals(listOf(0L, 1_000L, 51_000L, 101_000L), transport.times)
        assertTrue(clock.now.get() <= 120_000)
    }

    @Test fun transientServerResponsesRetryButNetworkExceptionsAreRedactedAndNotRetried() {
        for (status in listOf(500, 502, 503, 504, 529)) {
            val clock = Clock(); val transport = Transport(clock)
            transport.reply = { request, count -> if (count == 1) TranslationHttpResponse(status, "")
                else TranslationHttpResponse(200, body(source(request), "Bonjour")) }
            engine(transport, clock).use { assertEquals(listOf("Bonjour"), it.translate(listOf("Hello"), "en", "fr") { false }) }
            assertEquals(2, transport.requests.size)
        }
        val clock = Clock(); val transport = Transport(clock)
        val secret = "synthetic-private-subtitle"
        transport.reply = { request, _ -> throw IOException("$secret ${request.uri}") }
        engine(transport, clock).use {
            try { it.translate(listOf(secret), "en", "fr") { false }; fail("Expected network failure") }
            catch (error: TranslationFailure) {
                assertEquals(TranslationFailure.Reason.NETWORK, error.reason)
                assertFalse(error.toString().contains(secret)); assertNull(error.cause)
            }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun forbiddenAndHtmlBlockPagesSuspendWithoutRetryAndShareTheCooldown() {
        val replies = listOf(TranslationHttpResponse(403, ""),
            TranslationHttpResponse(429, "<!doctype html><html>Requêtes trop nombreuses</html>"),
            TranslationHttpResponse(200, "<html>unusual traffic CAPTCHA</html>"),
            TranslationHttpResponse(429, "", googleBlocked = true))
        for (reply in replies) {
            val clock = Clock(); val first = Transport(clock).apply { this.reply = { _, _ -> reply } }
            engine(first, clock).use {
                failure(TranslationFailure.Reason.GOOGLE_BLOCKED) { it.translate(listOf("Hello"), "en", "fr") { false } }
            }
            assertEquals(1, first.requests.size)
            val second = Transport(clock)
            engine(second, clock).use { it.translate(listOf("Later"), "en", "fr") { false } }
            assertEquals(listOf(60_000L), second.times)
        }
    }

    @Test fun cancellationDuringBackoffStopsWithoutAnotherRequest() {
        val clock = Clock(); val transport = Transport(clock)
        val cancelled = AtomicBoolean(false)
        clock.onSleep = { cancelled.set(true) }
        transport.reply = { _, _ -> TranslationHttpResponse(429, "", 30_000) }
        val engine = engine(transport, clock)
        try {
            engine.translate(listOf("Hello"), "en", "fr") { cancelled.get() }
            fail("Expected cancellation")
        } catch (_: TranslationCancelledException) { }
        finally { engine.close(); engine.close() }
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.closes)
        assertEquals(100L, clock.now.get())
    }

    @Test fun globalQueueSerializesEnginesAndAQueuedEngineCanBeClosed() {
        val clock = Clock(); val first = Transport(clock); val second = Transport(clock)
        val firstEntered = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        first.reply = { request, _ ->
            firstEntered.countDown(); assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            TranslationHttpResponse(200, body(source(request), "Premier"))
        }
        second.reply = { request, _ -> secondEntered.countDown(); TranslationHttpResponse(200, body(source(request), "Deuxième")) }
        val a = engine(first, clock); val b = engine(second, clock)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val taskA = workers.submit<List<String>> { a.translate(listOf("First"), "en", "fr") { false } }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val taskB = workers.submit<Boolean> {
                secondStarted.countDown()
                try { b.translate(listOf("Second"), "en", "fr") { false }; false }
                catch (_: TranslationCancelledException) { true }
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
            b.close()
            assertTrue(taskB.get(2, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertEquals(listOf("Premier"), taskA.get(2, TimeUnit.SECONDS))
            assertTrue(second.requests.isEmpty())
        } finally { releaseFirst.countDown(); a.close(); b.close(); workers.shutdownNow() }
    }

    @Test fun adaptiveGapIsSharedAndRelaxesAfterEightSuccessfulRequests() {
        val clock = Clock(); val first = Transport(clock)
        first.reply = { request, count -> if (count == 1) TranslationHttpResponse(429, "")
            else TranslationHttpResponse(200, body(source(request), "Bonjour")) }
        engine(first, clock).use { it.translate(listOf("First"), "en", "fr") { false } }
        val second = Transport(clock)
        engine(second, clock, interval = 500).use { client ->
            repeat(9) { client.translate(listOf("Later-$it"), "en", "fr") { false } }
        }
        assertEquals(4_000L, second.times.first())
        assertEquals(1_750L, second.times.last() - second.times[second.times.lastIndex - 1])
    }

    @Test fun loweringTheUserIntervalDoesNotInventAnAdaptivePenaltyAfterSuccessfulRequests() {
        val clock = Clock(); val first = Transport(clock)
        engine(first, clock, interval = 10_000).use { client ->
            repeat(8) { client.translate(listOf("Slow-$it"), "en", "fr") { false } }
        }
        val second = Transport(clock)
        engine(second, clock, interval = 500).use {
            it.translate(listOf("Fast-one"), "en", "fr") { false }
            it.translate(listOf("Fast-two"), "en", "fr") { false }
        }
        assertEquals(500L, second.times[1] - second.times[0])
    }

    companion object {
        private fun source(request: TranslationHttpRequest): String = URLDecoder.decode(
            request.uri.rawQuery.split('&').first { it.startsWith("q=") }.substring(2), "UTF-8")
        private fun body(source: String, translated: String): String = segments(translated to source)
        private fun segments(vararg values: Pair<String, String>): String {
            val segments = JSONArray()
            values.forEach { (translated, source) -> segments.put(JSONArray().put(translated).put(source)) }
            return JSONArray().put(segments).toString()
        }
    }
}
