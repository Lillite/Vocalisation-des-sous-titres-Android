package fr.soustitresvoix.player

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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** CPU only. Text and paths stay inside the application's private processes. */
internal object LocalGgufRuntime {
    enum class Code { BUSY, MODEL, CONTEXT, INPUT_TOO_LONG, OUTPUT_LIMIT, CANCELLED, DIED, TIMEOUT, RESPONSE, BACKEND }
    class Failure(val code: Code) : Exception("Local GGUF: ${code.name}")
    data class Info(val name: String, val architecture: String, val fileType: String,
        val contextTokens: Int, val modelBytes: Long, val modelParameters: Long,
        val loadMs: Long = 0, val peakRssBytes: Long = 0, val processId: Int = 0)
    data class Generation(val output: String, val promptTokens: Int, val generatedTokens: Int,
        val stopReason: String, val promptMs: Long, val generationMs: Long, val peakRssBytes: Long)
    private val loaded by lazy {
        try { System.loadLibrary("subtitle_model_probe"); true }
        catch (_: LinkageError) { false }
        catch (_: SecurityException) { false }
    }
    internal fun response(json: String): JSONObject = try {
        if (json.length > GgufMessages.MAX_JSON) throw Failure(Code.RESPONSE)
        JSONObject(json).also {
            val code = it.getString("code")
            if (code != "OK") throw Failure(runCatching { Code.valueOf(code) }.getOrDefault(Code.RESPONSE))
        }
    } catch (failure: Failure) { throw failure }
      catch (_: Exception) { throw Failure(Code.RESPONSE) }
    internal fun info(json: String): Info {
        val x = response(json)
        return try {
            Info(x.getString("name").take(512), x.getString("architecture").take(128),
                x.getString("fileType").take(128), x.getInt("contextTokens"), x.getLong("modelBytes"),
                x.getLong("modelParameters"), x.getLong("loadMs"), x.getLong("peakRssBytes"), x.optInt("processId")).also {
                if (it.contextTokens !in 128..2048 || it.modelBytes <= 0 || it.modelParameters <= 0 ||
                    it.loadMs < 0 || it.peakRssBytes < 0)
                    throw Failure(Code.RESPONSE)
            }
        } catch (failure: Failure) { throw failure }
          catch (_: Exception) { throw Failure(Code.RESPONSE) }
    }
    internal fun generation(json: String): Generation {
        val x = response(json)
        return try {
            Generation(x.getString("output"), x.getInt("promptTokens"), x.getInt("generatedTokens"),
                x.getString("stopReason"), x.getLong("promptMs"), x.getLong("generationMs"), x.getLong("peakRssBytes")).also {
                if (it.stopReason != "end_of_generation" || it.output.toByteArray(Charsets.UTF_8).size > 65536 ||
                    it.promptTokens !in 1..2048 || it.generatedTokens !in 0..1024 ||
                    it.promptMs < 0 || it.generationMs < 0 || it.peakRssBytes < 0) throw Failure(Code.RESPONSE)
            }
        } catch (failure: Failure) { throw failure }
          catch (_: Exception) { throw Failure(Code.RESPONSE) }
    }
    internal class NativeSession : AutoCloseable {
        private val handle = AtomicLong(0)
        init {
            if (!loaded) throw Failure(Code.BACKEND)
            handle.set(nativeCreate())
            if (handle.get() == 0L) throw Failure(Code.MODEL)
        }
        private fun decode(bytes: ByteArray?): String {
            if (bytes == null || bytes.size > GgufMessages.MAX_JSON) throw Failure(Code.RESPONSE)
            return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw Failure(Code.RESPONSE) }
        }
        fun open(file: File, threads: Int, contextTokens: Int): String {
            val id = handle.get().takeIf { it != 0L } ?: throw Failure(Code.CANCELLED)
            return decode(nativeOpen(id, file.absolutePath.toByteArray(Charsets.UTF_8), threads, contextTokens))
        }
        fun generate(prompt: String, maxOutputTokens: Int, addSpecialTokens: Boolean): String {
            val id = handle.get().takeIf { it != 0L } ?: throw Failure(Code.CANCELLED)
            return decode(nativeGenerate(id, prompt.toByteArray(Charsets.UTF_8), maxOutputTokens, addSpecialTokens))
        }
        fun cancel() { handle.get().takeIf { it != 0L }?.let(::nativeCancel) }
        override fun close() { handle.getAndSet(0L).takeIf { it != 0L }?.let(::nativeDestroy) }
    }

    /** open/generate block only a translation worker. close/cancel never wait for native code. */
    class Client(context: Context) : AutoCloseable {
        private val app = context.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val operation = Any()
        private val state = Any()
        private val closed = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val session = nextSession.incrementAndGet()
        private var nextRequest = 0L
        private var pending: Pending? = null
        private var remote: Messenger? = null
        private var bound = false
        private var connection: ServiceConnection? = null
        private var opened = false
        private var broken = false
        private class Pending(val id: Long, val what: Int, val arguments: Bundle) {
            val ready = CountDownLatch(1)
            @Volatile var json: String? = null
            @Volatile var failure: Code? = null
            var sent = false
            fun fail(code: Code) { failure = code; ready.countDown() }
        }
        private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == GgufMessages.RESULT && message.data.getLong(GgufMessages.SESSION) == session) {
                synchronized(state) {
                    val request = pending
                    if (!closed.get() && request != null && request.id == message.data.getLong(GgufMessages.REQUEST)) {
                        request.json = message.data.getString(GgufMessages.JSON)
                        request.ready.countDown()
                    }
                }
            }
            true
        })
        fun open(file: File, threads: Int = 4, contextTokens: Int = 2048,
            cancelled: () -> Boolean = { false }): Info = onWorker {
            if (opened) throw Failure(Code.CONTEXT)
            if (threads !in 1..8 || contextTokens !in 128..2048) throw Failure(Code.CONTEXT)
            val privateFile = privateGgufFile(app, file.absolutePath) ?: throw Failure(Code.MODEL)
            info(perform(GgufMessages.OPEN, Bundle().apply {
                putString(GgufMessages.PATH, privateFile.absolutePath); putInt(GgufMessages.THREADS, threads)
                putInt(GgufMessages.CONTEXT, contextTokens)
            }, GgufMessages.OPEN_TIMEOUT_MS, cancelled)).also { opened = true }
        }
        fun generate(prompt: String, maxOutputTokens: Int = 512, addSpecialTokens: Boolean = false,
            cancelled: () -> Boolean = { false }): Generation = onWorker {
            if (!opened) throw Failure(Code.MODEL)
            if (prompt.isEmpty() || prompt.toByteArray(Charsets.UTF_8).size > 65536) throw Failure(Code.INPUT_TOO_LONG)
            if (maxOutputTokens !in 1..1024) throw Failure(Code.OUTPUT_LIMIT)
            generation(perform(GgufMessages.GENERATE, Bundle().apply {
                putString(GgufMessages.TEXT, prompt); putInt(GgufMessages.TOKENS, maxOutputTokens)
                putBoolean(GgufMessages.SPECIAL, addSpecialTokens)
            }, GgufMessages.GENERATION_TIMEOUT_MS, cancelled))
        }
        private fun <T> onWorker(action: () -> T): T {
            check(Looper.myLooper() != Looper.getMainLooper()) { "GGUF inference must run on a worker" }
            return synchronized(operation) { action() }
        }
        private fun perform(what: Int, args: Bundle, timeoutMs: Long, isCancelled: () -> Boolean): String {
            if (Looper.myLooper() == Looper.getMainLooper()) throw IllegalStateException("GGUF inference must run on a worker")
            if (closed.get() || cancelled.get() || isCancelled()) throw Failure(Code.CANCELLED)
            val request = synchronized(state) {
                if (broken) throw Failure(Code.DIED)
                Pending(++nextRequest, what, args).also { pending = it }
            }
            main.post { ensureBinding(request) }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                while (!request.ready.await(50, TimeUnit.MILLISECONDS)) {
                    if (closed.get() || cancelled.get() || isCancelled()) { cancel(); throw Failure(Code.CANCELLED) }
                    if (System.nanoTime() >= deadline) { abort(Code.TIMEOUT); throw Failure(Code.TIMEOUT) }
                }
                if (closed.get() || cancelled.get() || isCancelled()) throw Failure(Code.CANCELLED)
                request.failure?.let { throw Failure(it) }
                return request.json ?: throw Failure(Code.RESPONSE)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); cancel(); throw Failure(Code.CANCELLED)
            } finally { synchronized(state) { if (pending === request) pending = null } }
        }
        private fun ensureBinding(request: Pending) {
            if (closed.get() || cancelled.get()) { request.fail(Code.CANCELLED); return }
            if (synchronized(state) { pending !== request }) return
            if (remote != null) { send(request); return }
            if (bound) return
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (closed.get() || cancelled.get()) return
                    remote = Messenger(service)
                    synchronized(state) { pending }?.let(::send)
                }
                override fun onServiceDisconnected(name: ComponentName) = lost()
                override fun onBindingDied(name: ComponentName) = lost()
                override fun onNullBinding(name: ComponentName) = lost()
            }
            connection = callback
            try {
                bound = app.bindService(Intent(app, LocalGgufTranslationService::class.java), callback, Context.BIND_AUTO_CREATE)
                if (!bound) lost()
            } catch (_: RuntimeException) { lost() }
        }
        private fun send(request: Pending) {
            if (request.sent || closed.get() || cancelled.get()) return
            request.sent = true
            try { remote?.send(Message.obtain(null, request.what).apply {
                replyTo = replies
                data = Bundle(request.arguments).apply {
                    putLong(GgufMessages.SESSION, session); putLong(GgufMessages.REQUEST, request.id)
                }
            }) ?: lost() } catch (_: RemoteException) { lost() }
        }
        private fun lost() {
            synchronized(state) { broken = true; pending?.fail(Code.DIED) }
            clearBinding()
        }
        private fun signal(what: Int) {
            try { remote?.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putLong(GgufMessages.SESSION, session) }
            }) } catch (_: RemoteException) { }
        }
        private fun abort(code: Code) {
            cancelled.set(true)
            synchronized(state) { pending?.fail(code) }
            main.post { signal(GgufMessages.CANCEL); signal(GgufMessages.CLOSE); clearBinding() }
        }
        fun cancel() = abort(Code.CANCELLED)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            abort(Code.CANCELLED)
        }
        private fun clearBinding() {
            if (bound) try { connection?.let(app::unbindService) } catch (_: IllegalArgumentException) { }
            bound = false; remote = null; connection = null
        }
    }
    // A reader-process restart must not reuse session 1 while the private model
    // process is still delivering a previous Binder death/cancellation callback.
    private val nextSession = AtomicLong(android.os.SystemClock.elapsedRealtimeNanos())
    private external fun nativeCreate(): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeOpen(handle: Long, path: ByteArray, threads: Int, context: Int): ByteArray?
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, tokens: Int, addSpecial: Boolean): ByteArray?
}

internal object GgufMessages {
    const val OPEN = 1; const val GENERATE = 2; const val CANCEL = 3; const val CLOSE = 4; const val RESULT = 5
    const val SESSION = "session"; const val REQUEST = "request"; const val PATH = "path"; const val THREADS = "threads"
    const val CONTEXT = "context"; const val TEXT = "text"; const val TOKENS = "tokens"; const val SPECIAL = "special"; const val JSON = "json"
    const val MAX_JSON = 196608
    const val OPEN_TIMEOUT_MS = 5 * 60 * 1000L
    const val GENERATION_TIMEOUT_MS = 30 * 60 * 1000L
}
internal fun privateGgufFile(context: Context, path: String): File? = try {
    val file = File(path).canonicalFile
    val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
    file.takeIf { it.isFile && it.length() >= 24 && roots.any { root -> it.path.startsWith(root.path + File.separator) } }
} catch (_: Exception) { null }
