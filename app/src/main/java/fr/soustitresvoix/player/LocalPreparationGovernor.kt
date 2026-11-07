package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Cooperative guards at complete request boundaries, never a claim about a crash's cause. */
class LocalPreparationGovernor(
    private val sample: () -> Sample,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wait: (Long) -> Unit = Thread::sleep
) {
    data class Sample(val lowMemory: Boolean? = null, val thermalStatus: Int? = null,
        val thermalHeadroom: Float? = null)
    enum class Kind(val phase: String, val message: String) {
        MEMORY("waiting-memory", "Attente de mémoire disponible ; les occurrences validées sont conservées."),
        THERMAL("waiting-thermal", "Pause thermique du calcul local ; les occurrences validées sont conservées.")
    }
    class Pause(val kind: Kind) : Exception()
    class Expired(val kind: Kind) : Exception()
    private var sampledAt = Long.MIN_VALUE
    private var last = Sample()

    /** A headroom probe is never performed more than once in ten seconds. */
    fun reason(): Kind? {
        val now = elapsed()
        if (sampledAt == Long.MIN_VALUE || now < sampledAt || now - sampledAt >= SAMPLE_INTERVAL_MS) {
            last = runCatching(sample).getOrDefault(Sample())
            sampledAt = now
        }
        return when {
            last.lowMemory == true -> Kind.MEMORY
            (last.thermalStatus ?: -1) >= 3 ||
                last.thermalHeadroom?.takeIf(Float::isFinite)?.let { it >= 0.95f } == true -> Kind.THERMAL
            else -> null
        }
    }

    fun beforeRequest(cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        reason()?.let { throw Pause(it) }
    }

    /** The caller must release its model before entering this bounded wait. */
    fun awaitReady(kind: Kind, cancelled: () -> Boolean, onWaiting: (Kind) -> Unit) {
        val started = elapsed()
        onWaiting(kind)
        while (true) {
            checkCancelled(cancelled)
            val current = reason() ?: return
            val now = elapsed()
            if (now < started || now - started >= MAX_WAIT_MS) throw Expired(current)
            wait(CANCEL_POLL_MS)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val MAX_WAIT_MS = 60_000L
        private const val CANCEL_POLL_MS = 200L

        fun android(context: Context): LocalPreparationGovernor {
            val app = context.applicationContext
            return LocalPreparationGovernor(sample = {
                val memory = runCatching {
                    val manager = app.getSystemService(ActivityManager::class.java)
                    ActivityManager.MemoryInfo().also { requireNotNull(manager).getMemoryInfo(it) }
                }.getOrNull()
                val power = app.getSystemService(PowerManager::class.java)
                val thermal = if (Build.VERSION.SDK_INT >= 29)
                    runCatching { power?.currentThermalStatus }.getOrNull() else null
                val headroom = if (Build.VERSION.SDK_INT >= 30)
                    runCatching { power?.getThermalHeadroom(0) }.getOrNull() else null
                Sample(memory?.lowMemory, thermal, headroom)
            })
        }
    }
}
