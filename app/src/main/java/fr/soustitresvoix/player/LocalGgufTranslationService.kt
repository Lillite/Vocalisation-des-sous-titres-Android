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
    @Volatile private var nativeSession: LocalGgufRuntime.NativeSession? = null
    @Volatile private var ownerCancelled = AtomicBoolean(false)
    private var owner: Long? = null
    private var replyBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (destroyed.get()) return@Handler true
        val session = message.data.getLong(GgufMessages.SESSION)
        when (message.what) {
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
    private fun error(code: LocalGgufRuntime.Code, diagnostic: LocalGgufDiagnostic? = null) = JSONObject()
        .put("code", code.name).apply { diagnostic?.let { put("diagnostic", it.toJson()) } }.toString()
    private fun respond(reply: Messenger, request: Long, session: Long, json: String) {
        if (destroyed.get()) return
        try { reply.send(Message.obtain(null, GgufMessages.RESULT).apply {
            data = Bundle().apply { putLong(GgufMessages.SESSION, session); putLong(GgufMessages.REQUEST, request); putString(GgufMessages.JSON, json) }
        }) } catch (_: RemoteException) { if (owner == session) endSession() }
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
    private fun open(message: Message, session: Long) {
        val reply = message.replyTo ?: return
        val request = message.data.getLong(GgufMessages.REQUEST)
        if (owner != null || running.get()) { respond(reply, request, session, error(LocalGgufRuntime.Code.BUSY)); return }
        val file = privateGgufFile(this, message.data.getString(GgufMessages.PATH).orEmpty())
        val threads = message.data.getInt(GgufMessages.THREADS)
        val context = message.data.getInt(GgufMessages.CONTEXT)
        val execution = message.data.getString(GgufMessages.EXECUTION) ?: "CPU"
        val layers = message.data.getInt(GgufMessages.LAYERS, -1)
        if (file == null || threads !in 1..8 || context !in 128..2048 || execution !in setOf("AUTO", "CPU", "OPENCL") || layers !in -1..1024) { respond(reply, request, session, error(LocalGgufRuntime.Code.MODEL)); return }
        registerOwner(reply, session)
        val cancelled = ownerCancelled
        running.set(true)
        worker.execute {
            var succeeded = false
            val json = try {
                if (cancelled.get()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
                val opened = LocalGgufRuntime.NativeSession().also { nativeSession = it }
                if (cancelled.get()) opened.cancel()
                val cacheRoot = cacheDir.canonicalFile
                val cache = File(cacheRoot, "local-gguf-opencl-kernels").canonicalFile.takeIf {
                    it.path.startsWith(cacheRoot.path + File.separator) && (it.isDirectory || it.mkdirs())
                }
                opened.open(file, threads, context, execution, layers, cache).also { LocalGgufRuntime.info(it); succeeded = true }
            } catch (failure: LocalGgufRuntime.Failure) { error(failure.code, failure.diagnostic) }
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
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 65536 || tokens !in 1..1024) { respond(reply, request, session, error(LocalGgufRuntime.Code.INPUT_TOO_LONG)); return }
        val cancelled = ownerCancelled
        running.set(true)
        worker.execute {
            val json = try {
                if (cancelled.get()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
                nativeSession?.generate(text, tokens, special) ?: error(LocalGgufRuntime.Code.MODEL)
            } catch (failure: LocalGgufRuntime.Failure) { error(failure.code, failure.diagnostic) }
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
