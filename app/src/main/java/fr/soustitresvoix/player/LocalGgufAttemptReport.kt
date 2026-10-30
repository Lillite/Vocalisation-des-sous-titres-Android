package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.time.Instant

/** Only fixed stages and numeric measurements; never a prompt, filename or native exception. */
internal data class LocalGgufProgress(
    val stage: String,
    val percent: Int,
    val processId: Int,
    val startedAtMs: Long,
    val elapsedMs: Long
) {
    fun render(): String = buildString {
        append("Dernière étape observée : "); append(STAGES.getValue(stage)); append(" ["); append(stage); append(']')
        if (percent >= 0) { append(" ; progression de cette étape : "); append(percent); append(" %") }
        append(".\nProcessus du moteur : "); append(processId)
        append(" ; temps écoulé dans cet appel : "); append(elapsedMs); append(" ms.")
        append("\nUne étape de chargement ne prouve ni un calcul GPU effectif ni une traduction terminée.")
    }

    companion object {
        val STAGES = mapOf(
            "SERVICE" to "initialisation du processus de calcul",
            "BACKEND" to "préparation du moteur",
            "OPENCL" to "préparation OpenCL",
            "VULKAN" to "préparation Vulkan",
            "MODEL_LOAD" to "chargement des poids",
            "CONTEXT_INIT" to "allocation du contexte de calcul",
            "CHAT_TEMPLATE" to "préparation du format de requête",
            "READY" to "chargement terminé",
            "GENERATION" to "calcul de la traduction")

        fun parse(bundle: Bundle, requestStartedAtMs: Long, nowMs: Long): LocalGgufProgress? {
            val stage = bundle.get(GgufMessages.STAGE) as? String ?: return null
            val percent = bundle.get(GgufMessages.PERCENT) as? Int ?: return null
            val pid = bundle.get(GgufMessages.PROCESS_ID) as? Int ?: return null
            val start = bundle.get(GgufMessages.STARTED_AT) as? Long ?: return null
            val elapsed = bundle.get(GgufMessages.ELAPSED) as? Long ?: return null
            if (stage !in STAGES || percent !in -1..100 || pid <= 0 ||
                start < requestStartedAtMs - 1000 || start > nowMs + 1000 || elapsed !in 0..86_400_000) return null
            return LocalGgufProgress(stage, percent, pid, start, elapsed)
        }
    }
}

/** Called only by our JNI bridge; contents are fixed by the pinned native wrapper. */
@androidx.annotation.Keep
internal fun interface LocalGgufProgressListener {
    fun onProgress(stage: String, percent: Int)
}

internal object LocalGgufAttemptReport {
    fun initial(config: TranslationProviderConfig, startedAtMs: Long = System.currentTimeMillis()): String = buildString {
        append("Préparation locale commencée (UTC) : "); append(Instant.ofEpochMilli(startedAtMs))
        append("\nCalcul demandé : "); append(config.localExecution.takeIf { it in setOf("AUTO", "CPU", "OPENCL", "VULKAN") } ?: "non reconnu")
        append(" ; profil : "); append(config.localProfile.label)
        append(" ; threads CPU demandés : "); append(config.localThreads)
        config.localModel?.let {
            append(".\nModèle : "); append(it.bytes); append(" octets")
            if (it.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { append(" ; SHA-256 "); append(it.sha256) }
        }
        val settings = config.localLoadSettings
        append(".\nChargement demandé : couches GPU "); append(if (settings.gpuLayers == -1) "toutes" else settings.gpuLayers.toString())
        append(" ; lot "); append(settings.batchTokens); append(" ; micro-lot "); append(settings.microBatchTokens)
        append(" ; cache d’attention locale compact : "); append(if (settings.compactSwa) "oui" else "non")
        append(".\nAucun chargement ou calcul réussi n’est encore établi pour cette préparation.")
    }

    data class ExitRecord(val pid: Int, val processName: String, val uid: Int, val timestampMs: Long,
        val reason: Int, val status: Int, val rssKiB: Long, val pssKiB: Long)

    /** Exact PID, name, own UID and this request's time interval exclude unrelated/stale kills. */
    fun matchingExit(records: List<ExitRecord>, pid: Int, processName: String, uid: Int,
        startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        if (pid <= 0 || stoppedAtMs < startedAtMs) return null
        return records.filter { it.pid == pid && it.processName == processName && it.uid == uid &&
            it.timestampMs in startedAtMs..stoppedAtMs }.maxByOrNull { it.timestampMs }
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
                if (exit.rssKiB > 0) { append("\nDernier RSS échantillonné par Android : "); append(exit.rssKiB); append(" Kio.") }
                if (exit.pssKiB > 0) { append("\nDernier PSS échantillonné par Android : "); append(exit.pssKiB); append(" Kio.") }
                append("\nCes échantillons ne mesurent pas exactement la mémoire au moment de l’arrêt ni toute la mémoire GPU.")
            }
        }
    }

    fun exitFor(context: Context, pid: Int, startedAtMs: Long, stoppedAtMs: Long): ExitRecord? {
        if (Build.VERSION.SDK_INT < 30 || pid <= 0) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val records = manager.getHistoricalProcessExitReasons(context.packageName, pid, 16).map { entry ->
                ExitRecord(entry.pid, entry.processName, entry.realUid, entry.timestamp,
                    entry.reason, entry.status, entry.rss, entry.pss)
            }
            matchingExit(records, pid, "${context.packageName}:model_diagnostic", Process.myUid(), startedAtMs, stoppedAtMs)
        }.getOrNull()
    }
}
