package fr.soustitresvoix.player

import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Optional, unofficial Google web client. One source occurrence has one validated result.
 * Protocol references: Subtitle Edit GoogleTranslateV1 and googletrans (see project guide).
 * No proxy, HTML scraping, alternate endpoint, credential or automatic provider switch.
 */
internal class GoogleWebTranslationEngine(
    private val config: TranslationProviderConfig,
    private val transport: TranslationHttpTransport,
    private val pacing: GoogleRequestPacing = GoogleRequestPacing.shared
) : TranslationEngine {
    override val id = TranslationEngines.translationIdentity(config)
    override val maxBatchItems = 1 // Commit every successful occurrence for precise resumption.
    override val maxBatchCharacters = 24_000
    private val closed = AtomicBoolean(false)
    private val memo = LinkedHashMap<String, String>(16, 0.75f, true)
    private var memoCharacters = 0

    override fun close() { if (closed.compareAndSet(false, true)) transport.close() }

    @Synchronized override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        cancelled: () -> Boolean): List<String> {
        val stopped = { closed.get() || cancelled() || Thread.currentThread().isInterrupted }
        fun check() { if (stopped()) throw TranslationCancelledException() }
        try {
            check()
            if (config.provider != TranslationProvider.GOOGLE_WEB || texts.isEmpty() || texts.size > 20 ||
                texts.sumOf { it.length.toLong() } > maxBatchCharacters)
                throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
            TranslationEngines.validateForRequest(config, sourceLanguage, targetLanguage)
            // Validate ALL source strings before making the first request.
            val pieces = texts.map { splitSource(it) }
            val source = sourceLanguage.lowercase(Locale.ROOT)
            val target = targetLanguage.lowercase(Locale.ROOT)
            return pieces.map { chunks ->
                val translatedChunks = chunks.map piece@ { part ->
                    check()
                    // Whitespace-only pieces need no network request, but remain
                    // part of the subtitle instead of disappearing at a split.
                    if (part.isBlank()) return@piece part
                    val key = "$source\u0000$target\u0000$part"
                    memo[key]?.let { return@piece it }
                    val query = "client=gtx&sl=$source&tl=$target&dt=t&q=" + URLEncoder.encode(part, "UTF-8")
                    val request = TranslationHttpRequest(URI("https://translate.googleapis.com/translate_a/single?$query"),
                        mapOf("Accept" to "application/json", "User-Agent" to "SousTitresVoix/0.5.2"), "")
                    val response = pacing.get(transport, request, config.execution.googleIntervalMillis, stopped)
                    check()
                    if (response.googleBlocked || isBlockPage(response.body) || response.status == 403 ||
                        response.status == 429 && isHtmlPage(response.body))
                        throw TranslationFailure(TranslationFailure.Reason.GOOGLE_BLOCKED)
                    when (response.status) {
                        200 -> Unit
                        429, 529 -> throw TranslationFailure(TranslationFailure.Reason.RATE_LIMIT)
                        in 300..399 -> throw TranslationFailure(TranslationFailure.Reason.REDIRECT)
                        400, 404, 413, 422 -> throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
                        401 -> throw TranslationFailure(TranslationFailure.Reason.GOOGLE_BLOCKED)
                        else -> throw TranslationFailure(TranslationFailure.Reason.SERVER)
                    }
                    val translated = parse(response.body, part)
                    // Google may normalize outer whitespace. Preserve the source
                    // chunk boundaries so adjacent translated words cannot merge,
                    // without injecting a new line at every size-based split.
                    val translation = part.takeWhile { it.isWhitespace() } +
                        translated.trim { it.isWhitespace() } + part.takeLastWhile { it.isWhitespace() }
                    check()
                    remember(key, translation)
                    translation
                }
                val translations = buildString {
                    translatedChunks.forEach { fragment ->
                        // A size split inside unspaced CJK source must not glue
                        // the resulting French words together. Existing source
                        // whitespace has already been preserved above.
                        if (isNotEmpty() && fragment.isNotEmpty() &&
                            !last().isWhitespace() && !fragment.first().isWhitespace()) append('\n')
                        append(fragment)
                    }
                }
                if (translations.isBlank() || translations.length > 24_000 || !validUnicode(translations))
                    throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                check()
                translations
            }
        } catch (cancel: TranslationCancelledException) { throw cancel }
        catch (failure: TranslationFailure) { throw failure }
        catch (_: org.json.JSONException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
        catch (_: Exception) { check(); throw TranslationFailure(TranslationFailure.Reason.NETWORK) }
    }

    private fun remember(key: String, translation: String) {
        val old = memo.put(key, translation)
        if (old != null) memoCharacters -= key.length + old.length
        memoCharacters += key.length + translation.length
        while (memo.size > 512 || memoCharacters > 262_144) {
            val iterator = memo.entries.iterator()
            val first = iterator.next()
            memoCharacters -= first.key.length + first.value.length
            iterator.remove()
        }
    }

    companion object {
        /** A small URI and bounded UTF-16 length also cover CJK, emoji and non-Latin scripts. */
        internal fun splitSource(text: String): List<String> {
            if (text.isBlank() || text.length > 24_000 || !validUnicode(text))
                throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
            val output = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = start; var encoded = 0; var boundary = -1
                while (end < text.length) {
                    val code = text.codePointAt(end)
                    val length = Character.charCount(code)
                    val bytes = when { code < 0x80 -> 1; code < 0x800 -> 2; code < 0x10000 -> 3; else -> 4 }
                    val uriLength = if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
                        code in '0'.code..'9'.code || code in intArrayOf(32, 45, 46, 95, 42)) 1 else bytes * 3
                    if (end + length - start > 1_200 || encoded + uriLength > 6_000) break
                    end += length; encoded += uriLength
                    if (Character.isWhitespace(code)) boundary = end
                }
                if (end < text.length && boundary > start && end - boundary < 250) end = boundary
                if (end <= start) throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
                val part = text.substring(start, end)
                output += part
                start = end
            }
            return output
        }

        internal fun isHtmlPage(body: String): Boolean {
            val prefix = body.take(8_192).lowercase(Locale.ROOT)
            return prefix.contains("<html") || prefix.contains("<!doctype")
        }

        internal fun isBlockPage(body: String): Boolean {
            val prefix = body.take(8_192).lowercase(Locale.ROOT)
            return isHtmlPage(prefix) &&
                (prefix.contains("unusual traffic") || prefix.contains("automated queries") ||
                    prefix.contains("/sorry/") || prefix.contains("captcha"))
        }

        internal fun parse(body: String, source: String): String {
            if (body.toByteArray(Charsets.UTF_8).size > HttpsTranslationTransport.MAX_RESPONSE_BYTES)
                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            val root = JSONArray(body)
            val segments = root.get(0) as? JSONArray ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            if (segments.length() !in 1..10_000) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            val translated = StringBuilder(); val echoed = StringBuilder()
            repeat(segments.length()) { index ->
                val segment = segments.get(index) as? JSONArray ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                val text = segment.get(0) as? String ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                val original = segment.get(1) as? String ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                if (!validUnicode(text) || !validUnicode(original)) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                if (original.isNotBlank() && text.isBlank()) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                translated.append(text); echoed.append(original)
                if (translated.length > 24_000) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            }
            // Never accept a response silently missing part of a dialogue or sign.
            fun normalize(value: String) = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
            if (normalize(echoed.toString()) != normalize(source) || translated.isBlank())
                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            return translated.toString()
        }

        private fun validUnicode(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val character = text[i++]
                if (character == '\u0000' || Character.isLowSurrogate(character)) return false
                if (Character.isHighSurrogate(character) && (i >= text.length || !Character.isLowSurrogate(text[i++]))) return false
            }
            return true
        }
    }
}

/** Shared by sessions and comparisons: only one Google request is active in this process. */
internal class GoogleRequestPacing(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val jitterMillis: () -> Long = { ThreadLocalRandom.current().nextLong(251) }
) {
    private val lock = ReentrantLock(true)
    private var nextRequestAt: Long? = null
    private var adaptiveGap = 0L
    private var successes = 0

    fun get(transport: TranslationHttpTransport, request: TranslationHttpRequest, minimumGap: Long,
        cancelled: () -> Boolean): TranslationHttpResponse {
        check(cancelled)
        if (minimumGap !in 500L..10_000L) throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        try {
            while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) check(cancelled)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw TranslationCancelledException() }
        try {
            var waited = 0L
            val initialWait = nextRequestAt?.let { runCatching { Math.subtractExact(it, clockMillis()) }
                .getOrDefault(Long.MAX_VALUE).coerceAtLeast(0) } ?: 0L
            if (initialWait > MAX_WAIT) throw TranslationFailure(TranslationFailure.Reason.RATE_LIMIT)
            wait(initialWait, cancelled); waited += initialWait
            var attempt = 0
            while (true) {
                check(cancelled)
                val response = try { transport.get(request, cancelled) }
                finally { nextRequestAt = deadlineAfter(maxOf(minimumGap, adaptiveGap)) }
                check(cancelled)
                if (response.googleBlocked || GoogleWebTranslationEngine.isBlockPage(response.body) || response.status == 403 ||
                    response.status == 429 && GoogleWebTranslationEngine.isHtmlPage(response.body)) {
                    nextRequestAt = deadlineAfter(maxOf(60_000L, response.retryAfterMillis ?: 0L))
                    return response
                }
                if (response.status !in setOf(429, 500, 502, 503, 504, 529)) {
                    if (response.status == 200 && ++successes >= 8) {
                        // A user's slow interval must not permanently become an
                        // adaptive penalty for another session after recovery.
                        adaptiveGap = (adaptiveGap - 250L).coerceAtLeast(0); successes = 0
                    }
                    return response
                }
                successes = 0
                adaptiveGap = maxOf(minimumGap, adaptiveGap).times(2).coerceAtMost(10_000L)
                val delay = maxOf(adaptiveGap, response.retryAfterMillis?.coerceAtLeast(0)
                    ?: ((2_000L shl attempt) + jitterMillis().coerceIn(0, 250)))
                // Respect a long Retry-After: suspend rather than retry prematurely.
                nextRequestAt = deadlineAfter(delay)
                if (attempt >= 3 || delay > MAX_WAIT - waited) return response
                wait(delay, cancelled); waited += delay; attempt++
            }
        } finally { lock.unlock() }
    }

    private fun wait(duration: Long, cancelled: () -> Boolean) {
        var remaining = duration
        while (remaining > 0) {
            check(cancelled)
            val part = minOf(100L, remaining)
            try { sleepMillis(part) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); throw TranslationCancelledException()
            }
            remaining -= part
        }
        check(cancelled)
    }
    private fun check(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }
    private fun deadlineAfter(delay: Long): Long = runCatching { Math.addExact(clockMillis(), delay) }.getOrDefault(Long.MAX_VALUE)
    companion object {
        private const val MAX_WAIT = 120_000L
        val shared = GoogleRequestPacing()
    }
}
