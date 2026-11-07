package fr.soustitresvoix.player

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One private bound model session; never starts playback, narration or network work. */
class LocalGgufTranslationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val progressMemory by lazy { LocalGgufProgressMemory(this) }
    @Volatile private var nativeSession: LocalGgufRuntime.NativeSession? = null
    @Volatile private var ownerCancelled = AtomicBoolean(false)
    private var owner: Long? = null
    private var replyBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (destroyed.get()) return@Handler true
        val session = message.data.getLong(GgufMessages.SESSION)
        when (message.what) {
            GgufMessages.RESTART_IDLE -> restartIdle(message, session)
            GgufMessages.OPEN -> open(message, session)
            GgufMessages.GENERATE -> generate(message, session)
            GgufMessages.CANCEL -> if (owner == session) endSession()
            GgufMessages.CLOSE -> {
                if (owner == session) endSession(message.replyTo, message.data.getLong(GgufMessages.REQUEST), session)
                else message.replyTo?.let { respond(it, message.data.getLong(GgufMessages.REQUEST), session, "{\"code\":\"OK\"}") }
            }
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder = inbox.binder
    private fun error(code: LocalGgufRuntime.Code, diagnostic: LocalGgufDiagnostic? = null,
        nativeMemory: LocalGgufAttemptReport.NativeMemory? = null) = JSONObject()
        .put("code", code.name).apply {
            diagnostic?.let { put("diagnostic", it.toJson()) }
            nativeMemory?.toJson()?.let { memory -> memory.keys().forEach { key -> put(key, memory.get(key)) } }
        }.toString()
    private fun respond(reply: Messenger, request: Long, session: Long, json: String) {
        if (destroyed.get()) return
        try { reply.send(Message.obtain(null, GgufMessages.RESULT).apply {
            data = Bundle().apply { putLong(GgufMessages.SESSION, session); putLong(GgufMessages.REQUEST, request); putString(GgufMessages.JSON, json) }
        }) } catch (_: RemoteException) { if (owner == session) endSession() }
    }
    private fun progress(reply: Messenger, request: Long, session: Long, startedAtMs: Long,
        startedAtNanos: Long, stage: String, percent: Int) {
        if (destroyed.get() || stage !in LocalGgufProgress.STAGES || percent !in -1..100) return
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            getSystemService(ActivityManager::class.java)?.setProcessStateSummary(
                "s=$session;r=$request;stage=$stage;p=$percent".toByteArray(Charsets.UTF_8))
        }
        val memory = progressMemory.sample(stage)
        try { reply.send(Message.obtain(null, GgufMessages.PROGRESS).apply {
            data = Bundle().apply {
                putLong(GgufMessages.SESSION, session); putLong(GgufMessages.REQUEST, request)
                putString(GgufMessages.STAGE, stage); putInt(GgufMessages.PERCENT, percent)
                putInt(GgufMessages.PROCESS_ID, android.os.Process.myPid())
                putLong(GgufMessages.STARTED_AT, startedAtMs)
                putLong(GgufMessages.ELAPSED, ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0))
                memory?.let { putLong(GgufMessages.RESIDENT_BYTES, it.residentBytes); putLong(GgufMessages.AVAILABLE_RAM_BYTES, it.availableRamBytes) }
            }
        }) } catch (_: RemoteException) { if (owner == session) ownerCancelled.set(true) }
    }
    private fun registerOwner(reply: Messenger, session: Long) {
        owner = session; ownerCancelled = AtomicBoolean(false)
        val binding = reply.binder
        replyBinder = binding
        deathRecipient = IBinder.DeathRecipient { main.post {
            if (owner == session && replyBinder == binding) endSession()
        } }.also { listener ->
            try { reply.binder.linkToDeath(listener, 0) }
            catch (_: RemoteException) { ownerCancelled.set(true) }
        }
    }
    private fun clearOwner(session: Long) {
        if (owner != session) return
        try { deathRecipient?.let { replyBinder?.unlinkToDeath(it, 0) } } catch (_: RuntimeException) { }
        deathRecipient = null; replyBinder = null; owner = null
    }
    private fun restartIdle(message: Message, session: Long) {
        val reply = message.replyTo ?: return
        val request = message.data.getLong(GgufMessages.REQUEST)
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (owner != null || running.get() || closing.get() || nativeSession != null) {
            respond(reply, request, session, error(LocalGgufRuntime.Code.BUSY)); return
        }
        if (processName != "$packageName:model_diagnostic") {
            respond(reply, request, session, error(LocalGgufRuntime.Code.BACKEND)); return
        }
        val reserved = try { LocalGgufRuntime.reserveProcessRestart() }
            catch (_: LinkageError) { false } catch (_: Exception) { false }
        // The native lease also excludes a diagnostic running in the other service.
        if (!reserved) { respond(reply, request, session, error(LocalGgufRuntime.Code.BUSY)); return }
        respond(reply, request, session, "{\"code\":\"OK\",\"restartScheduled\":true}")
        destroyed.set(true)
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 50)
    }
    private fun open(message: Message, session: Long) {
        val reply = message.replyTo ?: return
        val request = message.data.getLong(GgufMessages.REQUEST)
        if (owner != null || running.get()) { respond(reply, request, session, error(LocalGgufRuntime.Code.BUSY)); return }
        val file = privateGgufFile(this, message.data.getString(GgufMessages.PATH).orEmpty())
        val threads = message.data.getInt(GgufMessages.THREADS)
        val context = message.data.getInt(GgufMessages.CONTEXT)
        val execution = message.data.getString(GgufMessages.EXECUTION) ?: "CPU"
        val layers = message.data.getInt(GgufMessages.LAYERS, -1)
        val batch = message.data.getInt(GgufMessages.BATCH, 512)
        val microBatch = message.data.getInt(GgufMessages.MICRO_BATCH, 128)
        val compactSwa = message.data.getBoolean(GgufMessages.COMPACT_SWA, false)
        val kvCacheType = message.data.getString(GgufMessages.KV_CACHE_TYPE) ?: "F16"
        val flashAttentionPolicy = message.data.getString(GgufMessages.FLASH_ATTENTION) ?: "AUTO"
        val qualcommCompatibility = message.data.getBoolean(GgufMessages.QUALCOMM_COMPAT, false)
        val nativeCpuWeights = message.data.getBoolean(GgufMessages.NATIVE_CPU_WEIGHTS, false)
        val malformedSettings = listOf(GgufMessages.LAYERS, GgufMessages.BATCH, GgufMessages.MICRO_BATCH)
            .any { message.data.containsKey(it) && message.data.get(it) !is Int } ||
            message.data.containsKey(GgufMessages.COMPACT_SWA) && message.data.get(GgufMessages.COMPACT_SWA) !is Boolean ||
            message.data.containsKey(GgufMessages.KV_CACHE_TYPE) && message.data.get(GgufMessages.KV_CACHE_TYPE) !is String ||
            message.data.containsKey(GgufMessages.FLASH_ATTENTION) && message.data.get(GgufMessages.FLASH_ATTENTION) !is String ||
            listOf(GgufMessages.QUALCOMM_COMPAT, GgufMessages.NATIVE_CPU_WEIGHTS).any { message.data.containsKey(it) && message.data.get(it) !is Boolean }
        if (malformedSettings || file == null || threads !in 1..8 || context !in 128..2048 || execution !in setOf("AUTO", "CPU", "OPENCL", "VULKAN", "HEXAGON") ||
            layers !in -1..1024 || layers == 0 && execution != "CPU" || batch !in 16..512 || microBatch !in 16..128 || microBatch > batch || kvCacheType !in setOf("F16", "Q8_0") || flashAttentionPolicy !in setOf("AUTO", "ENABLED", "DISABLED") ||
            kvCacheType == "Q8_0" && flashAttentionPolicy == "DISABLED") { respond(reply, request, session, error(LocalGgufRuntime.Code.MODEL)); return }
        registerOwner(reply, session)
        val cancelled = ownerCancelled
        running.set(true)
        worker.execute {
            val startedAtMs = System.currentTimeMillis()
            val startedAtNanos = System.nanoTime()
            progress(reply, request, session, startedAtMs, startedAtNanos, "SERVICE", -1)
            val observer = LocalGgufProgressListener { stage, percent ->
                if (!cancelled.get()) progress(reply, request, session, startedAtMs, startedAtNanos, stage, percent)
            }
            var succeeded = false
            val json = try {
                if (cancelled.get()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
                val opened = LocalGgufRuntime.NativeSession().also { nativeSession = it }
                if (cancelled.get()) opened.cancel()
                val cacheRoot = cacheDir.canonicalFile
                val cache = File(cacheRoot, "local-gguf-opencl-kernels").canonicalFile.takeIf {
                    it.path.startsWith(cacheRoot.path + File.separator) && (it.isDirectory || it.mkdirs())
                }
                opened.open(file, threads, context, execution, layers, cache, batch, microBatch, compactSwa, observer, kvCacheType,
                    if (execution == "HEXAGON") HexagonRuntimeAssets.prepare(this, cancelled::get) else null, flashAttentionPolicy, qualcommCompatibility, nativeCpuWeights).also { LocalGgufRuntime.info(it); succeeded = true }
            } catch (failure: LocalGgufRuntime.Failure) { error(failure.code, failure.diagnostic, failure.nativeMemory) }
              catch (_: Exception) { error(LocalGgufRuntime.Code.MODEL) }
              catch (_: LinkageError) { error(LocalGgufRuntime.Code.BACKEND) }
              catch (_: OutOfMemoryError) { error(LocalGgufRuntime.Code.MEMORY) }
            if (!succeeded || cancelled.get() || destroyed.get()) {
                nativeSession?.close(); nativeSession = null; succeeded = false
            }
            main.post {
                running.set(false)
                if (!succeeded) clearOwner(session)
                respond(reply, request, session, if (cancelled.get()) error(LocalGgufRuntime.Code.CANCELLED) else json)
            }
        }
    }
    private fun generate(message: Message, session: Long) {
        val reply = message.replyTo ?: return
        val request = message.data.getLong(GgufMessages.REQUEST)
        if (owner != session || ownerCancelled.get() || nativeSession == null) { respond(reply, request, session, error(LocalGgufRuntime.Code.MODEL)); return }
        if (running.get()) { respond(reply, request, session, error(LocalGgufRuntime.Code.BUSY)); return }
        val text = message.data.getString(GgufMessages.TEXT).orEmpty()
        val tokens = message.data.getInt(GgufMessages.TOKENS)
        val special = message.data.getBoolean(GgufMessages.SPECIAL)
        val templateFlag = message.data.get(GgufMessages.MODEL_TEMPLATE)
        if (message.data.containsKey(GgufMessages.MODEL_TEMPLATE) && templateFlag !is Boolean) {
            respond(reply, request, session, error(LocalGgufRuntime.Code.TEMPLATE)); return
        }
        val useModelTemplate = templateFlag == true
        if (useModelTemplate && special) { respond(reply, request, session, error(LocalGgufRuntime.Code.TEMPLATE)); return }
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 65536 || tokens !in 1..1024) { respond(reply, request, session, error(LocalGgufRuntime.Code.INPUT_TOO_LONG)); return }
        val cancelled = ownerCancelled
        running.set(true)
        worker.execute {
            val startedAtMs = System.currentTimeMillis()
            val startedAtNanos = System.nanoTime()
            progress(reply, request, session, startedAtMs, startedAtNanos, "GENERATION", -1)
            val observer = LocalGgufProgressListener { stage, percent ->
                if (!cancelled.get()) progress(reply, request, session, startedAtMs, startedAtNanos, stage, percent)
            }
            val json = try {
                if (cancelled.get()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
                nativeSession?.let {
                    if (useModelTemplate) it.generateChat(text, tokens, observer) else it.generate(text, tokens, special, observer)
                } ?: error(LocalGgufRuntime.Code.MODEL)
            } catch (failure: LocalGgufRuntime.Failure) { error(failure.code, failure.diagnostic, failure.nativeMemory) }
              catch (_: Exception) { error(LocalGgufRuntime.Code.RESPONSE) }
              catch (_: LinkageError) { error(LocalGgufRuntime.Code.BACKEND) }
              catch (_: OutOfMemoryError) { error(LocalGgufRuntime.Code.MEMORY) }
            main.post {
                running.set(false)
                respond(reply, request, session, if (cancelled.get()) error(LocalGgufRuntime.Code.CANCELLED) else json)
            }
        }
    }
    private fun endSession(reply: Messenger? = null, request: Long = 0L, session: Long? = null) {
        val previous = owner ?: return
        ownerCancelled.set(true)
        nativeSession?.cancel()
        // Keep ownership until the queued close has released all model resources.
        closing.set(true)
        worker.execute {
            nativeSession?.close(); nativeSession = null
            closing.set(false)
            main.post {
                clearOwner(previous)
                if (reply != null && session != null) respond(reply, request, session, "{\"code\":\"OK\"}")
            }
        }
    }
    override fun onDestroy() {
        destroyed.set(true)
        ownerCancelled.set(true); nativeSession?.cancel()
        if (owner != null) clearOwner(requireNotNull(owner))
        closing.set(true)
        worker.execute { nativeSession?.close(); nativeSession = null; closing.set(false) }
        worker.shutdown()
        // A native abort or OOM cannot take the reader down. A hung decode is
        // stopped in this private model process after cooperative cancellation.
        if (running.get() || closing.get()) main.postDelayed({
            val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
                else getSystemService(ActivityManager::class.java)?.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
            if ((running.get() || closing.get()) && process == "$packageName:model_diagnostic")
                android.os.Process.killProcess(android.os.Process.myPid())
        }, 2_000)
        super.onDestroy()
    }
}
