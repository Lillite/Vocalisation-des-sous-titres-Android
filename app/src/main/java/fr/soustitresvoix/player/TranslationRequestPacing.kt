package fr.soustitresvoix.player

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

/** One translation worker, bounded waits, retries only for explicit rate-limit rejections.
 * Network failures may have consumed quota and are deliberately never retried here. */
internal class TranslationRequestPacing(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val jitterMillis: () -> Long = { ThreadLocalRandom.current().nextLong(251) }
) {
    private var nextRequestAt: Long? = null

    fun postDeepL(transport: TranslationHttpTransport, request: TranslationHttpRequest,
        cancelled: () -> Boolean): TranslationHttpResponse {
        checkCancelled(cancelled)
        nextRequestAt?.let { waitMillis((it - clockMillis()).coerceAtLeast(0), cancelled) }
        var retries = 0
        var waited = 0L
        while (true) {
            checkCancelled(cancelled)
            val response = transport.post(request, cancelled)
            checkCancelled(cancelled)
            nextRequestAt = clockMillis() + MINIMUM_GAP_MS
            if (response.status != 429 && response.status != 529) return response
            if (retries >= MAX_RETRIES) return response
            val delay = (response.retryAfterMillis?.takeIf { it >= 0 }
                ?: ((1_000L shl retries) + jitterMillis().coerceIn(0, 250)))
                .coerceAtLeast(MINIMUM_GAP_MS)
            // Never retry earlier than the server asks just to fit our local bound.
            if (delay > MAX_TOTAL_WAIT_MS - waited) return response
            waitMillis(delay, cancelled)
            waited += delay
            retries++
        }
    }

    private fun waitMillis(duration: Long, cancelled: () -> Boolean) {
        var remaining = duration
        while (remaining > 0) {
            checkCancelled(cancelled)
            val step = minOf(remaining, 100L)
            try { sleepMillis(step) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TranslationCancelledException()
            }
            remaining -= step
        }
        checkCancelled(cancelled)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }

    companion object {
        const val MINIMUM_GAP_MS = 500L
        const val MAX_RETRIES = 5
        const val MAX_TOTAL_WAIT_MS = 120_000L

        /** Keep only a duration; headers and server error bodies never enter diagnostics. */
        fun retryAfterMillis(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            val value = header?.trim()?.takeIf { it.length in 1..128 } ?: return null
            if (value.all { it in '0'..'9' }) {
                val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
                return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
            }
            return try {
                (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
            } catch (_: Exception) { null }
        }
    }
}
