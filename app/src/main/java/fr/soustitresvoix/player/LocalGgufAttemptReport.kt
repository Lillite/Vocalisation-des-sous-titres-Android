package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.time.Instant

/** Only fixed stages and numeric measurements; never a prompt, filename or native exception. */
data class LocalGgufProgress(
    val stage: String,
    val percent: Int,
    val processId: Int,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val residentBytes: Long = -1,
    val availableRamBytes: Long = -1
) {
    fun render(): String = buildString {
        append("Dernière étape observée : "); append(STAGES.getValue(stage)); append(" ["); append(stage); append(']')
        if (percent >= 0) { append(" ; progression de cette étape : "); append(percent); append(" %") }
        append(".\nProcessus du moteur : "); append(processId)
        append(" ; temps écoulé dans cet appel : "); append(elapsedMs); append(" ms.")
        if (residentBytes >= 0 || availableRamBytes >= 0) {
            append("\nDernier échantillon reçu du processus moteur : RSS ")
            append(if (residentBytes >= 0) residentBytes.toString() else "indisponible")
            append(" octets ; RAM disponible ")
            append(if (availableRamBytes >= 0) availableRamBytes.toString() else "indisponible")
            append(" octets. Ce relevé ponctuel ne garantit pas le pic exact ni toute la mémoire GPU.")
        }
        append(if (stage in CALCULATION_STAGES)
            "\nUne étape de calcul ne prouve pas qu’une occurrence complète a été validée ni que sa traduction est fidèle."
        else "\nUne étape de chargement ne prouve ni un calcul GPU effectif ni une traduction terminée.")
    }

    companion object {
        private val CALCULATION_STAGES = setOf("GENERATION", "TOKENIZE", "CACHE_REUSE", "PREFILL", "DECODE", "COMPLETE")
        val STAGES = mapOf(
            "SERVICE" to "initialisation du processus de calcul",
            "BACKEND" to "préparation du moteur",
            "OPENCL" to "préparation OpenCL",
            "VULKAN" to "préparation Vulkan",
            "HEXAGON" to "préparation Hexagon",
            "HEXAGON_DRIVER" to "accès au pilote Hexagon",
            "HEXAGON_DEVICE" to "détection du périphérique Hexagon",
            "MODEL_LOAD" to "chargement des poids",
            "CONTEXT_INIT" to "allocation du contexte de calcul",
            "CHAT_TEMPLATE" to "préparation du format de requête",
            "READY" to "chargement terminé",
            "GENERATION" to "calcul de la traduction",
            "TOKENIZE" to "préparation des tokens de la requête",
            "CACHE_REUSE" to "vérification et réutilisation du préfixe d’attention",
            "PREFILL" to "calcul du contexte de la requête",
            "DECODE" to "génération de la réponse",
            "COMPLETE" to "appel de calcul terminé")

        fun parse(bundle: Bundle, requestStartedAtMs: Long, nowMs: Long): LocalGgufProgress? {
            val stage = bundle.get(GgufMessages.STAGE) as? String ?: return null
            val percent = bundle.get(GgufMessages.PERCENT) as? Int ?: return null
            val pid = bundle.get(GgufMessages.PROCESS_ID) as? Int ?: return null
            val start = bundle.get(GgufMessages.STARTED_AT) as? Long ?: return null
            val elapsed = bundle.get(GgufMessages.ELAPSED) as? Long ?: return null
            if (stage !in STAGES || percent !in -1..100 || pid <= 0 ||
                start < requestStartedAtMs - 1000 || start > nowMs + 1000 || elapsed !in 0..86_400_000) return null
            fun sample(key: String): Long? = if (!bundle.containsKey(key)) -1 else when (val value = bundle.get(key)) {
                is Long -> value.takeIf { it in -1..(1L shl 44) }
                else -> null
            }
            val resident = sample(GgufMessages.RESIDENT_BYTES) ?: return null
            val available = sample(GgufMessages.AVAILABLE_RAM_BYTES) ?: return null
            return LocalGgufProgress(stage, percent, pid, start, elapsed, resident, available)
        }
    }
}

/** Called only by our JNI bridge; contents are fixed by the pinned native wrapper. */
@androidx.annotation.Keep
internal fun interface LocalGgufProgressListener {
    fun onProgress(stage: String, percent: Int)
}

internal object LocalGgufAttemptReport {
    private val MODES = setOf("AUTO", "CPU", "OPENCL", "VULKAN", "HEXAGON")
    private val SHA256 = Regex("[a-fA-F0-9]{64}")
    fun initial(config: TranslationProviderConfig, startedAtMs: Long = System.currentTimeMillis(),
        pendingStatus: Boolean = true): String = buildString {
        append("Préparation locale commencée (UTC) : "); append(Instant.ofEpochMilli(startedAtMs))
        append("\nCalcul demandé : "); append(config.localExecution.takeIf { it in MODES } ?: "non reconnu")
        append(" ; profil : "); append(config.localProfile.label)
        append(" ; threads CPU demandés : "); append(config.localThreads)
        config.localModel?.let {
            append(".\nModèle : "); append(it.bytes); append(" octets")
            if (SHA256.matches(it.sha256)) { append(" ; SHA-256 "); append(it.sha256) }
        }
        val settings = config.localLoadSettings
        append(if (config.localExecution == "HEXAGON") ".\nChargement demandé : couches NPU " else ".\nChargement demandé : couches GPU ")
        append(if (config.localExecution == "CPU") "0 (calcul CPU explicite)"
            else if (settings.gpuLayers == -1) "toutes" else settings.gpuLayers.toString())
        append(" ; lot "); append(settings.batchTokens); append(" ; micro-lot "); append(settings.microBatchTokens)
        append(" ; cache d’attention locale compact : "); append(if (settings.compactSwa) "oui" else "non")
        append(" ; cache K/V demandé : "); append(settings.kvCacheType)
        append(" ; contexte : "); append(config.localContextPolicy.label)
        append('.')
        if (pendingStatus) append("\nAucun chargement ou calcul réussi n’est encore établi pour cette préparation.")
    }

    data class ExitRecord(val pid: Int, val processName: String, val uid: Int, val timestampMs: Long,
        val reason: Int, val status: Int, val rssKiB: Long, val pssKiB: Long,
        val memoryLimiter: Boolean = false, val summary: ProcessSummary? = null)

    data class ProcessSummary(val session: Long, val request: Long, val stage: String, val percent: Int)

    /** Never retain arbitrary process bytes, text, paths or an Android free-form description. */
    internal fun processSummary(bytes: ByteArray?): ProcessSummary? {
        if (bytes == null || bytes.size !in 1..128 || bytes.any { it.toInt() !in 32..126 }) return null
        val match = Regex("s=([0-9]{1,19});r=([0-9]{1,19});stage=([A-Z_]+);p=(-?[0-9]{1,3})")
            .matchEntire(bytes.toString(Charsets.US_ASCII)) ?: return null
        val session = match.groupValues[1].toLongOrNull() ?: return null
        val request = match.groupValues[2].toLongOrNull() ?: return null
        val stage = match.groupValues[3]
        val percent = match.groupValues[4].toIntOrNull() ?: return null
        if (session <= 0 || request <= 0 || stage !in LocalGgufProgress.STAGES || percent !in -1..100) return null
        return ProcessSummary(session, request, stage, percent)
    }

    /** Retain only the documented fixed marker, never Android's free-form description. */
    fun memoryLimiterMarker(reason: Int, description: String?): Boolean =
        (reason == 13 || reason == 17) && description?.contains("MemoryLimiter:AnonSwap") == true

    /** Exact PID, name, own UID and this request's time interval exclude unrelated/stale kills. */
    fun matchingExit(records: List<ExitRecord>, pid: Int, processName: String, uid: Int,
        startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        if (pid <= 0 || stoppedAtMs < startedAtMs) return null
        var latest: ExitRecord? = null
        for (record in records) {
            if (record.pid == pid && record.processName == processName && record.uid == uid &&
                record.timestampMs in startedAtMs..stoppedAtMs &&
                (latest == null || record.timestampMs > latest.timestampMs)) latest = record
        }
        return latest
    }

    fun exitLabel(reason: Int): String = when (reason) {
        0 -> "cause inconnue rapportée par Android"
        1 -> "sortie volontaire du processus"
        2 -> "arrêt par signal ; ce signal seul ne prouve pas un manque de mémoire"
        3 -> "arrêt pour pression mémoire rapporté par Android"
        4 -> "crash de l’application rapporté par Android"
        5 -> "crash natif rapporté par Android"
        6 -> "absence de réponse rapportée par Android"
        7 -> "échec d’initialisation rapporté par Android"
        8 -> "changement de permission rapporté par Android"
        9 -> "consommation excessive de ressources rapportée par Android"
        10 -> "arrêt demandé par l’utilisateur rapporté par Android"
        11 -> "arrêt de l’utilisateur Android"
        12 -> "arrêt d’une dépendance rapporté par Android"
        13 -> "autre cause rapportée par Android"
        14 -> "arrêt par le mécanisme de gel Android"
        15 -> "changement d’état de l’application"
        16 -> "mise à jour de l’application"
        17 -> "limite mémoire du processus rapportée par Android"
        else -> "cause Android non reconnue"
    }

    fun failure(code: LocalGgufRuntime.Code, progress: LocalGgufProgress?, elapsedMs: Long,
        exit: ExitRecord? = null): String = buildString {
        append("Fin de l’appel local : "); append(when (code) {
            LocalGgufRuntime.Code.DIED -> "arrêt ou perte du processus de calcul [DIED]"
            LocalGgufRuntime.Code.TIMEOUT -> "délai de l’appel dépassé [TIMEOUT]"
            else -> "échec [${code.name}]"
        })
        append(".\nDurée observée par l’application : "); append(elapsedMs.coerceAtLeast(0)); append(" ms.")
        progress?.let { append('\n'); append(it.render()) }
        if (code == LocalGgufRuntime.Code.DIED) {
            if (exit == null) append("\nAucun motif d’arrêt Android corrélé à cet appel n’est disponible ; la cause reste indéterminée.")
            else {
                append("\nMotif d’arrêt Android : "); append(exitLabel(exit.reason)); append(" ["); append(exit.reason); append("]")
                append(" ; code ou signal : "); append(exit.status); append('.')
                exit.summary?.let { summary ->
                    append("\nRésumé Android corrélé : session "); append(summary.session)
                    append(" ; requête "); append(summary.request); append(" ; étape ["); append(summary.stage); append(']')
                    append(" ; progression "); append(summary.percent); append(" %.")
                }
                if (exit.memoryLimiter) append("\nAndroid a également fourni le marqueur du limiteur mémoire par application [MemoryLimiter:AnonSwap].")
                if (exit.rssKiB > 0) { append("\nDernier RSS échantillonné par Android : "); append(exit.rssKiB); append(" Kio.") }
                if (exit.pssKiB > 0) { append("\nDernier PSS échantillonné par Android : "); append(exit.pssKiB); append(" Kio.") }
                append("\nCes échantillons ne mesurent pas exactement la mémoire au moment de l’arrêt ni toute la mémoire GPU.")
            }
        }
    }

    fun exitFor(context: Context, pid: Int, startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        return exitForProcess(context, pid, "${context.packageName}:model_diagnostic", startedAtMs, stoppedAtMs)
    }

    fun exitForApplication(context: Context, pid: Int, startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        return exitForProcess(context, pid, context.packageName, startedAtMs, stoppedAtMs)
    }

    private fun exitForProcess(context: Context, pid: Int, name: String,
        startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        if (Build.VERSION.SDK_INT < 30 || pid <= 0) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val records = manager.getHistoricalProcessExitReasons(context.packageName, pid, 16).map { entry ->
                ExitRecord(entry.pid, entry.processName, entry.realUid, entry.timestamp,
                    entry.reason, entry.status, entry.rss, entry.pss,
                    memoryLimiterMarker(entry.reason, entry.description), processSummary(runCatching { entry.processStateSummary }.getOrNull()))
            }
            matchingExit(records, pid, name, Process.myUid(), startedAtMs, stoppedAtMs)
        }.getOrNull()
    }
}
