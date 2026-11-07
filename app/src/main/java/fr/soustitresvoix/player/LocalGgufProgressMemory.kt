package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream

/** Best-effort process-local samples, throttled across requests; never reads subtitle text. */
internal class LocalGgufProgressMemory(
    private val clock: () -> Long = System::nanoTime,
    private val reader: () -> Sample
) {
    data class Sample(val residentBytes: Long = -1, val availableRamBytes: Long = -1)
    constructor(context: Context) : this(reader = { read(context) })
    private var sampledAt: Long? = null

    @Synchronized fun sample(stage: String): Sample? {
        if (stage !in STAGES) return null
        val now = clock()
        val previous = sampledAt
        if (previous != null && now >= previous && now - previous < 1_000_000_000L) return null
        sampledAt = now
        return runCatching { reader() }.getOrNull()?.let {
            Sample(it.residentBytes.takeIf { bytes -> bytes in 0..MAX_BYTES } ?: -1,
                it.availableRamBytes.takeIf { bytes -> bytes in 0..MAX_BYTES } ?: -1)
        }
    }

    companion object {
        private const val MAX_BYTES = 1L shl 44
        private val STAGES = setOf("SERVICE", "MODEL_LOAD", "CONTEXT_INIT", "READY", "TOKENIZE", "PREFILL", "DECODE", "COMPLETE")
        private fun read(context: Context): Sample {
            val resident = runCatching {
                val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
                FileInputStream("/proc/self/statm").use { input ->
                    val bytes = ByteArray(256)
                    val size = input.read(bytes)
                    if (size !in 1 until bytes.size || input.read() != -1) -1
                    else parseResidentBytes(String(bytes, 0, size, Charsets.US_ASCII), pageSize)
                }
            }.getOrDefault(-1)
            val available = runCatching {
                val info = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) ?: return@runCatching -1L
                info.availMem.takeIf { it in 0..MAX_BYTES } ?: -1L
            }.getOrDefault(-1)
            return Sample(resident, available)
        }

        internal fun parseResidentBytes(statm: String, pageSize: Long): Long {
            if (statm.length > 255 || pageSize !in 1..1_048_576) return -1
            val fields = statm.trim().split(Regex("\\s+"))
            if (fields.size < 2 || fields.take(2).any { value -> value.isEmpty() || value.any { it !in '0'..'9' } }) return -1
            val residentPages = fields[1].toLongOrNull() ?: return -1
            return if (residentPages <= MAX_BYTES / pageSize) residentPages * pageSize else -1
        }
    }
}
