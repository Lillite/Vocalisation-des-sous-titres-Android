package fr.soustitresvoix.player

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded real CPU experiment, deliberately disconnected from production narration. */
internal object LocalModelProbe {
    enum class Status { COMPLETED, CANCELLED, FAILED, BUSY }
    data class Result(
        val status: Status,
        val detail: String,
        val output: String = "",
        val backend: String = "non exécuté",
        val loadMs: Long = 0,
        val promptMs: Long = 0,
        val generationMs: Long = 0,
        val generatedTokens: Int = 0,
        val promptTokens: Int = 0,
        val contextTokens: Int = 512,
        val outputLimit: Int = 32,
        val threads: Int = 0,
        val processId: Int = 0,
        val peakRssBytes: Long = 0,
        val modelBytes: Long = 0,
        val modelParameters: Long = 0,
        val architecture: String = "",
        val stopReason: String = "not_started",
        val promptKind: String = "synthetic_plain_text",
        val runtimeRevision: String = REVISION
    ) {
        fun render(): String = buildString {
            append("Essai local de modèle — ")
            append(when (status) {
                Status.COMPLETED -> "calcul terminé"
                Status.CANCELLED -> "annulé"
                Status.FAILED -> "échec"
                Status.BUSY -> "déjà en cours"
            })
            append("\n"); append(detail)
            append("\nCalcul observé : ")
            append(if (backend == "CPU" && promptTokens > 0) "CPU" else "non établi")
            append("\nGPU : non exécuté dans cette version.\nNPU : non exécuté dans cette version.")
            append("\nMoteur : llama.cpp, révision "); append(runtimeRevision.take(12))
            if (architecture.isNotEmpty()) { append("\nArchitecture chargée : "); append(architecture) }
            append("\nContexte de cet essai : "); append(contextTokens); append(" tokens ; sortie limitée à ")
            append(outputLimit); append(" tokens.")
            if (threads > 0) { append("\nThreads de calcul demandés : "); append(threads) }
            if (modelBytes > 0) {
                append("\nPoids chargés : "); append(String.format(Locale.FRANCE, "%.1f Mio", modelBytes / 1048576.0))
                append(" ; paramètres : "); append(modelParameters)
            }
            append("\nChargement : "); append(loadMs); append(" ms.")
            append("\nCalcul du texte d’entrée : "); append(promptMs); append(" ms, "); append(promptTokens); append(" tokens.")
            append("\nGénération : "); append(generationMs); append(" ms, "); append(generatedTokens); append(" tokens produits.")
            if (generationMs > 0 && generatedTokens > 0) {
                append("\nDébit de cet essai : ")
                append(String.format(Locale.FRANCE, "%.2f tokens par seconde", generatedTokens * 1000.0 / generationMs))
            }
            if (peakRssBytes > 0) {
                append("\nPic de mémoire résidente du processus depuis son démarrage : ")
                append(String.format(Locale.FRANCE, "%.1f Mio", peakRssBytes / 1048576.0))
                append(". Ce n’est pas la mémoire du modèle seul.")
            }
            append("\nFin : "); append(when (stopReason) {
                "end_of_generation" -> "fin signalée par le modèle"
                "token_limit" -> "borne de sortie de la sonde atteinte ; réponse éventuellement incomplète"
                "byte_limit" -> "borne de taille de la sonde atteinte ; réponse éventuellement incomplète"
                "cancelled" -> "annulation"
                "error" -> "erreur"
                else -> "calcul non établi"
            })
            if (output.isNotEmpty()) { append("\n\nSortie sur le texte synthétique uniquement :\n"); append(output) }
            append("\n\nCet essai ne mesure pas la qualité de traduction, ne valide pas l’OCR et ne garantit pas le traitement d’un film entier.")
        }
        fun toJson(): String = JSONObject().apply {
            put("status", status.name); put("detail", detail); put("output", output)
            put("backend", backend); put("loadMs", loadMs); put("promptMs", promptMs)
            put("generationMs", generationMs); put("generatedTokens", generatedTokens)
            put("promptTokens", promptTokens); put("contextTokens", contextTokens); put("outputLimit", outputLimit); put("threads", threads); put("processId", processId)
            put("peakRssBytes", peakRssBytes); put("modelBytes", modelBytes)
            put("modelParameters", modelParameters); put("architecture", architecture)
            put("stopReason", stopReason); put("promptKind", promptKind); put("runtimeRevision", runtimeRevision)
        }.toString()
    }
    const val REVISION = "8ca4060b7e30e76baaa5670ee1ec1b760b7f8552"
    const val SYNTHETIC_TEXT = "The train leaves at seven."
    private val loaded by lazy {
        try { System.loadLibrary("subtitle_model_probe"); true }
        catch (_: UnsatisfiedLinkError) { false }
        catch (_: SecurityException) { false }
    }
    fun interface StageListener { fun onStage(stage: String) }
    class Cancellation {
        private val lock = Any()
        private var handle = 0L
        private var cancelled = false
        fun cancel() = synchronized(lock) {
            cancelled = true
            if (handle != 0L) nativeCancel(handle)
        }
        internal fun attach(value: Long) = synchronized(lock) {
            handle = value
            if (cancelled) nativeCancel(value)
        }
        internal fun detach() = synchronized(lock) { handle = 0L }
        internal fun isCancelled(): Boolean = synchronized(lock) { cancelled }
    }

    fun run(file: File, cancellation: Cancellation = Cancellation(), threads: Int = 2,
        maxOutputTokens: Int = 32, onStage: (String) -> Unit = {}): Result {
        if (cancellation.isCancelled()) return Result(Status.CANCELLED, "Essai annulé avant chargement.")
        if (!loaded) return Result(Status.FAILED, "Bibliothèque native CPU indisponible sur cet appareil.", backend = "non exécuté")
        if (!file.isFile || file.length() < 24) return Result(Status.FAILED, "Fichier de modèle absent ou incomplet.", backend = "non exécuté")
        var handle = 0L
        return try {
            handle = nativeCreate()
            if (handle == 0L) return Result(Status.FAILED, "La session native n’a pas pu être créée ; mémoire indisponible.")
            cancellation.attach(handle)
            val listener = StageListener { stage -> onStage(stage) }
            val bytes = nativeRun(handle, file.absolutePath.toByteArray(Charsets.UTF_8), threads.coerceIn(1, 8), maxOutputTokens.coerceIn(1, 64), listener)
            if (bytes == null) Result(Status.FAILED, "Le moteur n’a renvoyé aucun résultat exploitable.")
            else fromJson(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            Result(Status.FAILED, "L’essai local a échoué ; le modèle n’est pas déclaré validé.")
        } catch (_: LinkageError) {
            Result(Status.FAILED, "La bibliothèque native ne peut pas exécuter cet essai.", backend = "non exécuté")
        } finally {
            cancellation.detach()
            if (handle != 0L) nativeDestroy(handle)
        }
    }

    internal fun fromJson(json: String): Result = try {
        val x = JSONObject(json)
        Result(
            status = Status.valueOf(x.getString("status")), detail = x.getString("detail"),
            output = x.optString("output").take(8192), backend = x.optString("backend", "non exécuté"),
            loadMs = x.optLong("loadMs"), promptMs = x.optLong("promptMs"), generationMs = x.optLong("generationMs"),
            generatedTokens = x.optInt("generatedTokens"), promptTokens = x.optInt("promptTokens"),
            contextTokens = x.optInt("contextTokens", 512), outputLimit = x.optInt("outputLimit", 32), threads = x.optInt("threads"), processId = x.optInt("processId"),
            peakRssBytes = x.optLong("peakRssBytes"), modelBytes = x.optLong("modelBytes"),
            modelParameters = x.optLong("modelParameters"), architecture = x.optString("architecture").take(128),
            stopReason = x.optString("stopReason"), promptKind = x.optString("promptKind"),
            runtimeRevision = x.optString("runtimeRevision", REVISION)
        )
    } catch (_: Exception) { Result(Status.FAILED, "Réponse du moteur non exploitable.", backend = "non exécuté") }

    private external fun nativeCreate(): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRun(handle: Long, path: ByteArray, threads: Int, maxTokens: Int, listener: StageListener): ByteArray?
}

/** Messages are private to this package's non-exported bound service. */
private object ProbeMessages {
    const val START = 1
    const val CANCEL = 2
    const val STAGE = 3
    const val RESULT = 4
    const val ID = "id"
    const val PATH = "path"
    const val THREADS = "threads"
    const val TOKENS = "tokens"
    const val TEXT = "text"
    const val JSON = "json"
    const val TIMEOUT_MS = 10 * 60 * 1000L
}

/** Callbacks always arrive on main; stale replies cannot replace a newer run. */
internal class LocalModelProbeClient(context: Context,
    private val onStage: (String) -> Unit,
    private val onResult: (LocalModelProbe.Result) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var nextId = 0L
    private var activeId: Long? = null
    private var bound = false
    private var sent = false
    private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    private var deadline: Runnable? = null
    private var cancelDeadline: Runnable? = null
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getLong(ProbeMessages.ID)
        if (!closed && activeId == id) {
            when (message.what) {
                ProbeMessages.STAGE -> onStage(message.data.getString(ProbeMessages.TEXT).orEmpty())
                ProbeMessages.RESULT -> finish(id, LocalModelProbe.fromJson(message.data.getString(ProbeMessages.JSON).orEmpty()))
            }
        }
        true
    })

    fun start(file: File, threads: Int = 2, maxOutputTokens: Int = 32) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Start diagnostic on main thread" }
        if (closed) return
        if (activeId != null) { onStage("Un essai est déjà en cours."); return }
        val privateFile = privateModelFile(app, file.absolutePath)
        if (privateFile == null) { onResult(LocalModelProbe.Result(LocalModelProbe.Status.FAILED,
            "Sélectionnez un modèle copié dans l’espace privé de l’application.", backend = "non exécuté")); return }
        val id = ++nextId
        activeId = id
        sent = false
        val binder = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed || activeId != id) return
                remote = Messenger(service)
                val request = Message.obtain(null, ProbeMessages.START).apply {
                    replyTo = replies
                    data = Bundle().apply {
                        putLong(ProbeMessages.ID, id); putString(ProbeMessages.PATH, privateFile.absolutePath)
                        putInt(ProbeMessages.THREADS, threads.coerceIn(1, 8)); putInt(ProbeMessages.TOKENS, maxOutputTokens.coerceIn(1, 64))
                    }
                }
                try { remote?.send(request); sent = true }
                catch (_: RemoteException) { lost(id) }
            }
            override fun onServiceDisconnected(name: ComponentName) { lost(id) }
            override fun onBindingDied(name: ComponentName) { lost(id) }
            override fun onNullBinding(name: ComponentName) { lost(id) }
        }
        connection = binder
        onStage("Ouverture du processus séparé de diagnostic…")
        if (closed || activeId != id) return
        try {
            bound = app.bindService(Intent(app, LocalModelProbeService::class.java), binder, Context.BIND_AUTO_CREATE)
            if (!bound) { finish(id, LocalModelProbe.Result(LocalModelProbe.Status.FAILED,
                "Le service de diagnostic n’a pas pu démarrer.", backend = "non exécuté")); return }
        } catch (_: RuntimeException) {
            finish(id, LocalModelProbe.Result(LocalModelProbe.Status.FAILED,
                "Le service de diagnostic n’est pas accessible.", backend = "non exécuté")); return
        }
        if (activeId != id || closed) return
        deadline = Runnable {
            if (activeId == id) {
                sendCancel(id)
                finish(id, LocalModelProbe.Result(LocalModelProbe.Status.CANCELLED,
                    "Essai arrêté après dix minutes ; aucun résultat de performance validé.", backend = "non établi"))
            }
        }.also { main.postDelayed(it, ProbeMessages.TIMEOUT_MS) }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val id = activeId ?: return
        if (!sent) { finish(id, LocalModelProbe.Result(LocalModelProbe.Status.CANCELLED, "Essai annulé avant démarrage.", backend = "non exécuté")); return }
        sendCancel(id)
        onStage("Annulation de l’essai et libération du modèle…")
        cancelDeadline?.let(main::removeCallbacks)
        cancelDeadline = Runnable {
            if (activeId == id) finish(id, LocalModelProbe.Result(LocalModelProbe.Status.CANCELLED,
                "Annulation demandée ; arrêt du processus isolé demandé.", backend = "non établi"))
        }.also { main.postDelayed(it, 5_000) }
    }

    private fun sendCancel(id: Long) {
        try { remote?.send(Message.obtain(null, ProbeMessages.CANCEL).apply {
            data = Bundle().apply { putLong(ProbeMessages.ID, id) }
        }) } catch (_: RemoteException) { /* Unbind still releases the isolated process. */ }
    }
    private fun lost(id: Long) {
        if (!closed && activeId == id) finish(id, LocalModelProbe.Result(LocalModelProbe.Status.FAILED,
            "Le processus du modèle s’est arrêté. Une limite mémoire ou une erreur native est possible ; le lecteur est conservé.", backend = "non établi"))
    }
    private fun finish(id: Long, result: LocalModelProbe.Result) {
        if (closed || activeId != id) return
        clearBinding()
        activeId = null
        onResult(result)
    }
    private fun clearBinding() {
        deadline?.let(main::removeCallbacks); deadline = null
        cancelDeadline?.let(main::removeCallbacks); cancelDeadline = null
        if (bound) try { connection?.let(app::unbindService) } catch (_: IllegalArgumentException) { }
        bound = false; sent = false; remote = null; connection = null
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        activeId?.let(::sendCancel)
        closed = true
        activeId = null
        clearBinding()
    }
}

/** The process boundary contains native OOM/crash; this service is never exported. */
class LocalModelProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private var activeId: Long? = null
    private var cancellation: LocalModelProbe.Cancellation? = null
    private val running = AtomicBoolean(false)
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ProbeMessages.START -> start(message)
            ProbeMessages.CANCEL -> if (activeId == message.data.getLong(ProbeMessages.ID)) cancellation?.cancel()
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder = inbox.binder
    private fun send(reply: Messenger, what: Int, id: Long, key: String, value: String) {
        if (destroyed.get()) return
        try { reply.send(Message.obtain(null, what).apply {
            data = Bundle().apply { putLong(ProbeMessages.ID, id); putString(key, value) }
        }) } catch (_: RemoteException) { cancellation?.cancel() }
    }
    private fun start(message: Message) {
        val reply = message.replyTo ?: return
        val id = message.data.getLong(ProbeMessages.ID)
        if (running.get()) {
            send(reply, ProbeMessages.RESULT, id, ProbeMessages.JSON,
                LocalModelProbe.Result(LocalModelProbe.Status.BUSY, "Un essai local est déjà en cours.", backend = "non exécuté").toJson())
            return
        }
        val file = privateModelFile(this, message.data.getString(ProbeMessages.PATH).orEmpty())
        if (file == null) {
            send(reply, ProbeMessages.RESULT, id, ProbeMessages.JSON,
                LocalModelProbe.Result(LocalModelProbe.Status.FAILED, "Fichier privé absent ou invalide.", backend = "non exécuté").toJson())
            return
        }
        val threads = message.data.getInt(ProbeMessages.THREADS, 2).coerceIn(1, 8)
        val tokens = message.data.getInt(ProbeMessages.TOKENS, 32).coerceIn(1, 64)
        val token = LocalModelProbe.Cancellation()
        cancellation = token; activeId = id; running.set(true)
        worker.execute {
            val result = LocalModelProbe.run(file, token, threads, tokens) { stage ->
                send(reply, ProbeMessages.STAGE, id, ProbeMessages.TEXT, stage)
            }
            main.post {
                running.set(false)
                if (activeId == id) { activeId = null; cancellation = null }
                send(reply, ProbeMessages.RESULT, id, ProbeMessages.JSON, result.toJson())
            }
        }
    }
    override fun onDestroy() {
        destroyed.set(true)
        cancellation?.cancel()
        worker.shutdownNow()
        // Native libraries can abort or ignore cancellation under severe pressure.
        // A runaway probe must not keep using memory after its screen has closed.
        // Never kill the reader process (also keeps in-process tests safe).
        if (running.get()) main.postDelayed({
            val processName = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
                else getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
            if (running.get() && processName == "$packageName:model_diagnostic")
                android.os.Process.killProcess(android.os.Process.myPid())
        }, 2_000)
        super.onDestroy()
    }
}

private fun privateModelFile(context: Context, path: String): File? = try {
    val file = File(path).canonicalFile
    val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
    file.takeIf { it.isFile && it.length() >= 24 && roots.any { root ->
        it.path.startsWith(root.path + File.separator)
    } }
} catch (_: Exception) { null }
