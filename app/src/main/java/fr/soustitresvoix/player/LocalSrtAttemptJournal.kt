package fr.soustitresvoix.player

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** One bounded private diagnostic, separate from the runner's durable translated text. */
internal class LocalSrtAttemptJournal(private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(
        val sourceKey: String,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val ownerPid: Int,
        val modelSha256: String,
        val modelBytes: Long,
        val mode: String,
        val profile: String,
        val threads: Int,
        val gpuLayers: Int,
        val batch: Int,
        val microBatch: Int,
        val compactSwa: Boolean,
        val done: Int = 0,
        val total: Int = 0,
        val state: String = "RUNNING",
        val phase: String = "INDEXING",
        val processId: Int = 0,
        val progress: LocalGgufProgress? = null,
        val backend: String = "",
        val gpuObserved: Boolean = false,
        val promptMs: Long = 0,
        val generationMs: Long = 0,
        val endToEndMs: Long = 0,
        val promptTokens: Int = 0,
        val generatedTokens: Int = 0,
        val cachedTokens: Int = 0,
        val evaluatedTokens: Int = 0,
        val currentRssBytes: Long = 0,
        val peakRssBytes: Long = 0,
        val availableRamBytes: Long = 0,
        val graphNodes: Long = 0,
        val failureCode: String = "",
        val sourceLanguage: String = "",
        val contextPolicy: String = "recent-two",
        val configurationId: String = "",
        val requestedKvCacheType: String = "F16",
        val cacheTypeK: String = "",
        val cacheTypeV: String = "",
        val flashAttentionPolicy: String = "",
        val startResidentBytes: Long = -1,
        val prefillResidentBytes: Long = -1,
        val cacheResetReason: String = "",
        val cacheResets: Long = 0,
        val workspaceGrowths: Long = 0,
        val workspaceBytes: Long = 0
    ) {
        fun measured(value: LocalTranslationPerformance, nowMs: Long): Entry {
            if (value.phase == "fallback") return copy(updatedAtMs = nowMs, phase = "fallback",
                processId = 0, progress = null, backend = "", gpuObserved = false,
                promptMs = 0, generationMs = 0, endToEndMs = 0, promptTokens = 0, generatedTokens = 0,
                cachedTokens = 0, evaluatedTokens = 0, currentRssBytes = 0, peakRssBytes = 0,
                availableRamBytes = 0, graphNodes = 0, cacheTypeK = "", cacheTypeV = "",
                flashAttentionPolicy = "", startResidentBytes = -1, prefillResidentBytes = -1,
                cacheResetReason = "", cacheResets = 0, workspaceGrowths = 0, workspaceBytes = 0)
            val next = copy(updatedAtMs = nowMs,
                phase = value.phase.takeIf { it in PHASES } ?: phase,
                processId = value.processId.takeIf { it > 0 } ?: value.progress?.processId ?: processId,
                progress = value.progress ?: progress,
                backend = value.backend.takeIf { it in BACKENDS && it.isNotEmpty() } ?: backend,
                cacheTypeK = value.cacheTypeK.takeIf { it in CACHE_TYPES && it.isNotEmpty() } ?: cacheTypeK,
                cacheTypeV = value.cacheTypeV.takeIf { it in CACHE_TYPES && it.isNotEmpty() } ?: cacheTypeV,
                flashAttentionPolicy = value.flashAttentionPolicy.takeIf { it in FLASH_POLICIES && it.isNotEmpty() } ?: flashAttentionPolicy)
            // Progress has no new native memory sample. Keep the previous sample
            // explicitly as a last observation instead of replacing it with zero.
            if (value.phase != "load" && value.phase != "generation") return next
            return next.copy(gpuObserved = value.gpuObserved,
                promptMs = value.promptMs.coerceIn(0, MAX_DURATION),
                generationMs = value.generationMs.coerceIn(0, MAX_DURATION),
                endToEndMs = value.endToEndMs.coerceIn(0, MAX_DURATION),
                promptTokens = value.promptTokens.coerceIn(0, MAX_COUNT),
                generatedTokens = value.generatedTokens.coerceIn(0, MAX_COUNT),
                cachedTokens = value.cachedPromptTokens.coerceIn(0, MAX_COUNT),
                evaluatedTokens = value.promptEvaluatedTokens.coerceIn(0, MAX_COUNT),
                currentRssBytes = value.currentRssBytes.coerceIn(0, MAX_MEMORY),
                peakRssBytes = value.peakRssBytes.coerceIn(0, MAX_MEMORY),
                availableRamBytes = value.availableRamBytes.coerceIn(0, MAX_MEMORY),
                graphNodes = value.gpuGraphNodes.coerceIn(0, MAX_NODES),
                startResidentBytes = value.startResidentBytes.coerceIn(-1, MAX_MEMORY),
                prefillResidentBytes = value.prefillResidentBytes.coerceIn(-1, MAX_MEMORY),
                cacheResetReason = value.cacheResetReason.takeIf { it in RESET_REASONS }.orEmpty(),
                cacheResets = value.cacheResets.coerceIn(0, MAX_NODES),
                workspaceGrowths = value.workspaceGrowths.coerceIn(0, MAX_NODES),
                workspaceBytes = value.workspaceBytes.coerceIn(0, MAX_MEMORY))
        }

        fun render(context: Context? = null, nowMs: Long = System.currentTimeMillis()): String = buildString {
            append("Dernière préparation locale conservée (UTC) : "); append(Instant.ofEpochMilli(startedAtMs))
            append("\nÉtat enregistré : "); append(when (state) {
                "COMPLETED" -> "préparation terminée ; relance explicite nécessaire pour retrouver son export"
                "FAILED" -> "préparation arrêtée sur une erreur"
                "CANCELLED" -> "préparation arrêtée sans déclaration de fin"
                else -> "préparation interrompue avant sa clôture ; la cause n’est pas encore établie"
            })
            append(".\nOccurrences validées au dernier enregistrement : "); append(done); append(" / "); append(total)
            if (state == "RUNNING" && total > done) {
                append(" ; occurrence suivante à vérifier : "); append(done + 1)
            }
            append(".\nCalcul demandé : "); append(mode); append(" ; profil : "); append(profile)
            append(" ; threads CPU : "); append(threads)
            if (sourceLanguage.isNotEmpty()) { append(" ; langue source : "); append(sourceLanguage) }
            append(".\nModèle : "); append(modelBytes); append(" octets")
            if (modelSha256.isNotEmpty()) { append(" ; SHA-256 "); append(modelSha256) }
            append(".\nRéglages : couches GPU "); append(if (mode == "CPU") "0" else if (gpuLayers == -1) "toutes" else gpuLayers)
            append(" ; lot "); append(batch); append(" ; micro-lot "); append(microBatch)
            append(" ; cache compact demandé : "); append(if (compactSwa) "oui" else "non"); append('.')
            append("\nCache K/V demandé : "); append(requestedKvCacheType)
            if (cacheTypeK.isNotEmpty() && cacheTypeV.isNotEmpty()) {
                append(" ; types K/V rapportés : "); append(cacheTypeK); append('/'); append(cacheTypeV)
            }
            if (flashAttentionPolicy.isNotEmpty()) { append(" ; politique Flash Attention rapportée : "); append(flashAttentionPolicy) }
            append(".\nContexte : "); append(LocalGgufContextPolicy.entries.first { it.id == contextPolicy }.label)
            if (configurationId.isNotEmpty()) { append("\nIdentité de configuration : "); append(configurationId) }
            progress?.let { append('\n'); append(it.render()) }
            if (phase == "generation" || phase == "cache") {
                append('\n')
                append(LocalTranslationPerformance(phase = if (phase == "cache") "cache" else "generation",
                    requestedMode = mode, backend = backend, gpuObserved = gpuObserved,
                    promptMs = promptMs, generationMs = generationMs, endToEndMs = endToEndMs,
                    promptTokens = promptTokens, generatedTokens = generatedTokens,
                    cachedPromptTokens = cachedTokens, promptEvaluatedTokens = evaluatedTokens,
                    currentRssBytes = currentRssBytes, peakRssBytes = peakRssBytes,
                    availableRamBytes = availableRamBytes, gpuGraphNodes = graphNodes,
                    cacheTypeK = cacheTypeK, cacheTypeV = cacheTypeV, flashAttentionPolicy = flashAttentionPolicy,
                    startResidentBytes = startResidentBytes, prefillResidentBytes = prefillResidentBytes,
                    cacheResetReason = cacheResetReason, cacheResets = cacheResets,
                    workspaceGrowths = workspaceGrowths, workspaceBytes = workspaceBytes).render())
            } else if (currentRssBytes > 0 || peakRssBytes > 0 || availableRamBytes > 0) {
                append("\nDernier relevé mémoire conservé : RSS "); append(currentRssBytes)
                append(" octets ; pic du processus "); append(peakRssBytes)
                append(" octets ; RAM disponible "); append(availableRamBytes); append(" octets.")
            }
            TranslationFailure.Reason.entries.firstOrNull { it.name == failureCode }?.let {
                append("\nErreur enregistrée : "); append(it.message)
            }
            if ((state == "RUNNING" || failureCode == "LOCAL_PROCESS_DIED") && context != null) {
                // Progress is flushed at least once per phase/occurrence. Limit the
                // search interval so a much later PID reuse cannot explain this task.
                val end = minOf(nowMs, updatedAtMs + MAX_EXIT_DELAY)
                val engineStart = maxOf(startedAtMs, progress?.startedAtMs ?: startedAtMs)
                val exit = LocalGgufAttemptReport.exitFor(context, processId, engineStart, end)
                    ?: LocalGgufAttemptReport.exitForApplication(context, ownerPid, startedAtMs, end)
                if (exit != null) {
                    append('\n'); append(LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED,
                        null, (exit.timestampMs - startedAtMs).coerceAtLeast(0), exit))
                } else append("\nAucun motif Android corrélé n’est disponible ; aucune cause de l’interruption n’est affirmée.")
            }
            append("\nRelance explicitement avec le même fichier, modèle, langue et réglages pour reprendre les résultats validés ; aucun calcul ni export complet ne redémarre seul.")
        }
    }

    private val file = AtomicFile(File(directory, "attempt.json"))
    private var lastWriteAtMs = Long.MIN_VALUE
    var failedWrite: Boolean = false
        private set

    fun beginAttempt() { lastWriteAtMs = Long.MIN_VALUE; failedWrite = false }

    /** Called only on the translation worker, never a UI or native callback thread. */
    fun save(entry: Entry, force: Boolean = false): Boolean {
        val now = clock()
        if (!force && lastWriteAtMs != Long.MIN_VALUE && now >= lastWriteAtMs && now - lastWriteAtMs < MIN_WRITE_INTERVAL) return true
        return try {
            require(valid(entry, now))
            val bytes = encode(entry).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException()
            val output = file.startWrite()
            try {
                output.write(bytes)
                // AtomicFile.finishWrite syncs the file; do not fsync twice.
                file.finishWrite(output)
            } catch (error: Exception) { file.failWrite(output); throw error }
            lastWriteAtMs = now
            true
        } catch (_: Exception) { failedWrite = true; false }
    }

    fun clear() { file.delete(); beginAttempt() }

    fun read(sourceKey: String, nowMs: Long = clock()): Entry? = try {
        file.openRead().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val read = input.read(bytes, size, bytes.size - size)
                if (read < 0) break
                size += read
            }
            require(size in 1..MAX_BYTES)
            val entry = decode(JSONObject(String(bytes, 0, size, Charsets.UTF_8)))
            entry.takeIf { it.sourceKey == sourceKey && valid(it, nowMs) }
        }
    } catch (_: Exception) { null }

    companion object {
        const val WRITE_WARNING = "Le diagnostic persistant n’a pas pu être enregistré ; les résultats de traduction sont gérés séparément."
        private const val MAX_BYTES = 16_384
        private const val MIN_WRITE_INTERVAL = 1_000L
        private const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_EXIT_DELAY = 30 * 60 * 1000L
        private const val MAX_DURATION = 86_400_000L
        private const val MAX_COUNT = 1_000_000
        private const val MAX_MEMORY = 1L shl 44
        private const val MAX_NODES = 1_000_000_000L
        private val HASH = Regex("[a-f0-9]{64}")
        private val MODES = setOf("AUTO", "CPU", "OPENCL", "VULKAN")
        private val BACKENDS = setOf("", "CPU", "OPENCL", "VULKAN")
        private val PHASES = setOf("INDEXING", "loading", "progress", "load", "generation", "fallback", "cache")
        private val STATES = setOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
        private val PROFILES = LocalGgufProfile.entries.mapTo(HashSet()) { it.name }
        private val FAILURES = TranslationFailure.Reason.entries.mapTo(HashSet()) { it.name } + ""
        private val LANGUAGE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        private val CONTEXT_POLICIES = LocalGgufContextPolicy.entries.mapTo(HashSet()) { it.id }
        private val CONFIGURATION = Regex("local-gguf-v[0-9]+-[a-f0-9]{64}")
        private val CACHE_TYPES = setOf("", "F16", "Q8_0")
        private val FLASH_POLICIES = setOf("", "AUTO", "ENABLED")
        private val RESET_REASONS = setOf("", "EMPTY", "PREFIX_CHANGED", "WINDOW_EXPIRED", "REMOVE_FAILED", "REUSED", "FAILURE")

        fun sourceKey(file: File): String? = file.name.substringAfter("source-", "").substringBefore('.')
            .takeIf(HASH::matches)

        fun begin(sourceKey: String, config: TranslationProviderConfig,
            nowMs: Long = System.currentTimeMillis(), ownerPid: Int = Process.myPid(), sourceLanguage: String = ""): Entry {
            val settings = config.localLoadSettings
            return Entry(sourceKey, nowMs, nowMs, ownerPid,
                config.localModel?.sha256?.lowercase()?.takeIf(HASH::matches).orEmpty(),
                config.localModel?.bytes ?: 0, config.localExecution, config.localProfile.name,
                config.localThreads, settings.gpuLayers, settings.batchTokens, settings.microBatchTokens, settings.compactSwa,
                sourceLanguage = sourceLanguage.takeIf { it.length <= 35 && LANGUAGE.matches(it) }.orEmpty(),
                contextPolicy = config.localContextPolicy.id, requestedKvCacheType = settings.kvCacheType)
        }

        private fun valid(entry: Entry, nowMs: Long): Boolean = with(entry) {
            HASH.matches(sourceKey) && (modelSha256.isEmpty() || HASH.matches(modelSha256)) &&
                startedAtMs >= 0 && updatedAtMs >= startedAtMs && updatedAtMs <= nowMs + 1000 && nowMs - updatedAtMs <= MAX_AGE &&
                ownerPid > 0 && processId >= 0 && modelBytes in 0..MAX_MEMORY && mode in MODES &&
                profile in PROFILES && threads in 1..8 && gpuLayers in -1..1024 &&
                batch in 16..512 && microBatch in 16..128 && microBatch <= batch &&
                done in 0..MAX_COUNT && total in done..MAX_COUNT && state in STATES && phase in PHASES && backend in BACKENDS &&
                promptMs in 0..MAX_DURATION && generationMs in 0..MAX_DURATION && endToEndMs in 0..MAX_DURATION &&
                promptTokens in 0..MAX_COUNT && generatedTokens in 0..MAX_COUNT && cachedTokens in 0..MAX_COUNT && evaluatedTokens in 0..MAX_COUNT &&
                currentRssBytes in 0..MAX_MEMORY && peakRssBytes in 0..MAX_MEMORY && availableRamBytes in 0..MAX_MEMORY && graphNodes in 0..MAX_NODES &&
                failureCode in FAILURES && (sourceLanguage.isEmpty() || sourceLanguage.length <= 35 && LANGUAGE.matches(sourceLanguage)) &&
                contextPolicy in CONTEXT_POLICIES && (configurationId.isEmpty() || CONFIGURATION.matches(configurationId)) &&
                requestedKvCacheType in CACHE_TYPES && requestedKvCacheType.isNotEmpty() && cacheTypeK in CACHE_TYPES &&
                cacheTypeV in CACHE_TYPES && flashAttentionPolicy in FLASH_POLICIES &&
                startResidentBytes in -1..MAX_MEMORY && prefillResidentBytes in -1..MAX_MEMORY && cacheResetReason in RESET_REASONS &&
                cacheResets in 0..MAX_NODES && workspaceGrowths in 0..MAX_NODES && workspaceBytes in 0..MAX_MEMORY &&
                (progress == null || progress.stage in LocalGgufProgress.STAGES && progress.percent in -1..100 &&
                    progress.processId > 0 && progress.startedAtMs in startedAtMs..(updatedAtMs + 1000) &&
                    progress.elapsedMs in 0..MAX_DURATION)
        }

        private fun encode(e: Entry): JSONObject = with(e) {
            JSONObject().put("version", 1).put("sourceKey", sourceKey).put("started", startedAtMs).put("updated", updatedAtMs)
                .put("ownerPid", ownerPid).put("modelSha256", modelSha256).put("modelBytes", modelBytes).put("mode", mode)
                .put("profile", profile).put("threads", threads).put("layers", gpuLayers).put("batch", batch).put("micro", microBatch)
                .put("compact", compactSwa).put("done", done).put("total", total).put("state", state).put("phase", phase)
                .put("pid", processId).put("backend", backend).put("gpu", gpuObserved).put("promptMs", promptMs)
                .put("generationMs", generationMs).put("endToEndMs", endToEndMs).put("promptTokens", promptTokens)
                .put("generatedTokens", generatedTokens).put("cachedTokens", cachedTokens).put("evaluatedTokens", evaluatedTokens)
                .put("rss", currentRssBytes).put("peak", peakRssBytes).put("available", availableRamBytes).put("nodes", graphNodes)
                .put("failure", failureCode).put("sourceLanguage", sourceLanguage)
                .put("contextPolicy", contextPolicy).put("configuration", configurationId).put("requestedKv", requestedKvCacheType)
                .put("cacheK", cacheTypeK).put("cacheV", cacheTypeV).put("flash", flashAttentionPolicy)
                .put("startRss", startResidentBytes).put("prefillRss", prefillResidentBytes).put("resetReason", cacheResetReason)
                .put("resets", cacheResets).put("workspaceGrowths", workspaceGrowths).put("workspaceBytes", workspaceBytes)
                .apply { progress?.let { put("progress", JSONObject().put("stage", it.stage).put("percent", it.percent)
                    .put("pid", it.processId).put("started", it.startedAtMs).put("elapsed", it.elapsedMs)) } }
        }

        private fun longValue(x: JSONObject, key: String): Long = when (val value = x.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("Invalid numeric diagnostic")
        }
        private fun intValue(x: JSONObject, key: String): Int = longValue(x, key).let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); it.toInt()
        }
        private fun stringValue(x: JSONObject, key: String): String = x.get(key) as? String ?: error("Invalid string diagnostic")
        private fun booleanValue(x: JSONObject, key: String): Boolean = x.get(key) as? Boolean ?: error("Invalid boolean diagnostic")

        private fun decode(x: JSONObject): Entry {
            require(intValue(x, "version") == 1)
            val p = if (x.has("progress")) x.get("progress") as? JSONObject ?: error("Invalid progress") else null
            return Entry(stringValue(x, "sourceKey"), longValue(x, "started"), longValue(x, "updated"), intValue(x, "ownerPid"),
                stringValue(x, "modelSha256"), longValue(x, "modelBytes"), stringValue(x, "mode"), stringValue(x, "profile"),
                intValue(x, "threads"), intValue(x, "layers"), intValue(x, "batch"), intValue(x, "micro"), booleanValue(x, "compact"),
                intValue(x, "done"), intValue(x, "total"), stringValue(x, "state"), stringValue(x, "phase"), intValue(x, "pid"),
                p?.let { LocalGgufProgress(stringValue(it, "stage"), intValue(it, "percent"), intValue(it, "pid"), longValue(it, "started"), longValue(it, "elapsed")) },
                stringValue(x, "backend"), booleanValue(x, "gpu"), longValue(x, "promptMs"), longValue(x, "generationMs"), longValue(x, "endToEndMs"),
                intValue(x, "promptTokens"), intValue(x, "generatedTokens"), intValue(x, "cachedTokens"), intValue(x, "evaluatedTokens"),
                longValue(x, "rss"), longValue(x, "peak"), longValue(x, "available"), longValue(x, "nodes"),
                stringValue(x, "failure"), stringValue(x, "sourceLanguage"), stringValue(x, "contextPolicy"),
                stringValue(x, "configuration"), stringValue(x, "requestedKv"), stringValue(x, "cacheK"),
                stringValue(x, "cacheV"), stringValue(x, "flash"), longValue(x, "startRss"), longValue(x, "prefillRss"),
                stringValue(x, "resetReason"), longValue(x, "resets"), longValue(x, "workspaceGrowths"), longValue(x, "workspaceBytes"))
        }
    }
}
