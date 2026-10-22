package fr.soustitresvoix.core

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil

/** Measures actual playback without scheduling speech or changing its timing.
 * The Android owner supplies monotonic clocks/counters and terminates on explicit
 * pause, seek, media/settings changes, playback errors, or early end-of-video. */
class PlaybackBenchmark(private val sampleCapacity: Int = 4096) {
    init { require(sampleCapacity in 1..32768) }

    enum class Metric(val label: String) {
        FIRST_AUDIO("Demande → début audio"), SYNTHESIS("Synthèse vers fichier"),
        CACHE("Lecture du cache"), HOLD("Pauses de confort terminées")
    }
    data class Identity(
        val mediaFingerprint: String, val settingsFingerprint: String,
        val device: String, val engine: String, val appVersion: String,
        val initialCacheBytes: Long
    )
    data class Counters(
        val elapsedRealtimeMs: Long, val processCpuMs: Long, val videoPositionMs: Long,
        val droppedFrames: Long, val cacheHits: Long, val cacheMisses: Long
    )
    data class Distribution(
        val observed: Long, val retained: Int,
        val p50: Long?, val p95: Long?, val p99: Long?, val maximum: Long?
    ) {
        fun summary(): String = if (observed == 0L) "aucune observation dans cet essai"
        else "n=$observed${if (observed > retained) "; ${retained} dernières retenues" else ""}, " +
            "p50=$p50 ms, p95=$p95 ms, p99=$p99 ms, max=$maximum ms"
    }
    data class Snapshot(
        val number: Long, val identity: Identity, val requestedMs: Long,
        val initialPositionMs: Long, val finalPositionMs: Long, val elapsedMs: Long,
        val processCpuMs: Long, val droppedFrames: Long, val cacheHits: Long, val cacheMisses: Long,
        val active: Boolean, val completed: Boolean, val reason: String,
        val latencies: Map<Metric, Distribution>
    ) {
        val cpuPercentOfOneCore: Double? get() = if (elapsedMs > 0) processCpuMs * 100.0 / elapsedMs else null
        val comparableKey: String get() = fingerprint(listOf(identity.mediaFingerprint,
            identity.settingsFingerprint, identity.device, identity.engine, identity.appVersion,
            initialPositionMs.toString(), requestedMs.toString()))
        fun report(): String = buildString {
            append("Essai ").append(number).append(" — ")
            append(if (active) "en cours" else if (completed) "durée cible terminée" else "interrompu")
            append('\n').append(reason)
            append("\nDurée réelle écoulée : ").append(elapsedMs).append(" ms ; extrait vidéo cible : ").append(requestedMs).append(" ms")
            append("\nPosition vidéo : ").append(initialPositionMs).append(" → ").append(finalPositionMs).append(" ms")
            append("\nIdentité locale média : ").append(identity.mediaFingerprint)
            append("\nEmpreinte réglages : ").append(identity.settingsFingerprint)
            append("\nClé de comparaison : ").append(comparableKey)
            append("\nAppareil : ").append(identity.device).append(" ; application : ").append(identity.appVersion)
            append("\nMoteur/voix : ").append(identity.engine)
            append("\nCache au départ : ").append(identity.initialCacheBytes).append(" octets (ne garantit pas les mêmes entrées)")
            append("\nImages abandonnées pendant l’essai : ").append(droppedFrames)
            append("\nLectures du cache : ").append(cacheHits).append(" ; replis directs : ").append(cacheMisses)
            append("\nCPU du lecteur : ").append(processCpuMs).append(" ms ; ")
            append(cpuPercentOfOneCore?.let { String.format(Locale.FRANCE, "%.1f %% d’un cœur", it) } ?: "durée encore nulle")
            append(" ; moteur vocal externe non inclus")
            for (metric in Metric.entries) append('\n').append(metric.label).append(" : ").append(latencies.getValue(metric).summary())
            append("\nLes latences sont les observations terminées dans cette fenêtre, en millisecondes.")
            append("\nComparer avec mêmes média, position, réglages, appareil et état de cache ; le contenu du fichier n’est pas haché.")
        }
    }
    private class Samples(private val capacity: Int) {
        val values = ArrayDeque<Long>()
        var total = 0L
        fun add(ms: Long) { total++; if (values.size == capacity) values.removeFirst(); values.addLast(ms) }
        fun snapshot(): Distribution {
            val sorted = values.sorted()
            fun p(fraction: Double): Long? = if (sorted.isEmpty()) null else sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            return Distribution(total, sorted.size, p(.50), p(.95), p(.99), sorted.lastOrNull())
        }
    }
    private class Run(
        val number: Long, val identity: Identity, val duration: Long,
        val start: Counters, capacity: Int
    ) {
        var last = start
        var active = true
        var completed = false
        var reason = "Lecture réelle, sans anticiper l’apparition des répliques"
        val samples = Metric.entries.associateWith { Samples(capacity) }
        fun snapshot() = Snapshot(number, identity, duration, start.videoPositionMs, last.videoPositionMs,
            (last.elapsedRealtimeMs - start.elapsedRealtimeMs).coerceAtLeast(0),
            (last.processCpuMs - start.processCpuMs).coerceAtLeast(0),
            (last.droppedFrames - start.droppedFrames).coerceAtLeast(0),
            (last.cacheHits - start.cacheHits).coerceAtLeast(0),
            (last.cacheMisses - start.cacheMisses).coerceAtLeast(0),
            active, completed, reason, samples.mapValues { it.value.snapshot() })
    }
    private var sequence = 0L
    private var current: Run? = null
    private val previous = ArrayDeque<Snapshot>()
    val active: Boolean @Synchronized get() = current?.active == true

    @Synchronized fun start(durationMs: Long, identity: Identity, counters: Counters): Boolean {
        require(durationMs in 1_000..3_600_000)
        require(identity.mediaFingerprint.isNotBlank() && identity.settingsFingerprint.isNotBlank())
        if (current?.active == true) return false
        current?.let {
            if (previous.size == 3) previous.removeFirst()
            previous.addLast(it.snapshot())
        }
        current = Run(++sequence, identity, durationMs, counters, sampleCapacity)
        return true
    }
    @Synchronized fun record(metric: Metric, latencyMs: Long) {
        val run = current ?: return
        if (run.active && latencyMs >= 0) run.samples.getValue(metric).add(latencyMs)
    }
    /** True only when this call ends the active run; the owner must pause playback. */
    @Synchronized fun tick(counters: Counters): Boolean {
        val run = current?.takeIf { it.active } ?: return false
        val invalid = counters.elapsedRealtimeMs < run.last.elapsedRealtimeMs ||
            counters.processCpuMs < run.last.processCpuMs || counters.droppedFrames < run.last.droppedFrames ||
            counters.cacheHits < run.last.cacheHits || counters.cacheMisses < run.last.cacheMisses
        if (invalid) {
            run.active = false; run.reason = "Compteurs réinitialisés ou horloge non monotone : comparaison interrompue"
            return true
        }
        run.last = counters
        // Compare the same portion of the film. Speech holds/buffering extend
        // real elapsed time instead of shortening the measured video excerpt.
        if (counters.videoPositionMs - run.start.videoPositionMs < run.duration) return false
        run.active = false; run.completed = true; run.reason = "Extrait vidéo cible parcouru ; l’essai est terminé"
        return true
    }
    @Synchronized fun finish(counters: Counters, reason: String) {
        val run = current?.takeIf { it.active } ?: return
        // A user interruption never becomes a success merely because its final
        // callback happens after the nominal deadline.
        if (counters.elapsedRealtimeMs >= run.last.elapsedRealtimeMs) run.last = counters
        run.active = false; run.completed = false; run.reason = reason.ifBlank { "Interruption demandée" }
    }
    @Synchronized fun snapshot(): Snapshot? = current?.snapshot()
    @Synchronized fun report(): String {
        val latest = current ?: return "Aucun essai de lecture chronométré lancé"
        return (previous.toList() + latest.snapshot()).joinToString("\n\n") { it.report() }
    }
    companion object {
        /** A length prefix avoids ambiguous concatenation without exposing URI or text. */
        fun fingerprint(fields: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (field in fields) {
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
