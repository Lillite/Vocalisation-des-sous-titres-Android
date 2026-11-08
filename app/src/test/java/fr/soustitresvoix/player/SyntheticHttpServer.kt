package fr.soustitresvoix.player

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real loopback HTTP/1.1 fixture. Uses the socket APIs shared by Android and
 * the JVM, without a desktop-only HTTP module or an external service. */
internal class SyntheticHttpServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val contexts = LinkedHashMap<String, (Exchange) -> Unit>()
    private val closed = AtomicBoolean()
    private val active = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    private var thread: Thread? = null
    fun createContext(path: String, handler: (Exchange) -> Unit) { check(thread == null); contexts[path] = handler }
    fun start() {
        check(thread == null)
        thread = Thread({
            while (!closed.get()) {
                try {
                    server.accept().use { socket ->
                        active.set(socket)
                        try { serve(socket) } finally { active.compareAndSet(socket, null) }
                    }
                } catch (error: Throwable) {
                    if (!closed.get() && error !is SocketException) failure.compareAndSet(null, error)
                }
            }
        }, "synthetic-loopback-http").apply { isDaemon = true; start() }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input)?.split(' ') ?: return
        require(request.size == 3)
        val headers = Headers()
        var headerCount = 0
        while (true) {
            val line = readLine(input) ?: throw java.io.EOFException()
            if (line.isEmpty()) break
            require(++headerCount <= 100)
            val colon = line.indexOf(':'); require(colon > 0)
            headers.add(line.substring(0, colon), line.substring(colon + 1).trim())
        }
        val bodyLength = headers.getFirst("Content-Length")?.toInt() ?: 0
        require(bodyLength in 0..65_536)
        val body = ByteArray(bodyLength)
        var received = 0
        while (received < bodyLength) {
            val count = input.read(body, received, bodyLength - received)
            if (count < 0) throw java.io.EOFException()
            received += count
        }
        Exchange(socket, request[0], URI(request[1]), headers, ByteArrayInputStream(body)).use { exchange ->
            val handler = contexts.entries.filter { exchange.requestURI.path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            if (handler == null) exchange.sendResponseHeaders(404, -1) else handler(exchange)
        }
    }
    override fun close() {
        closed.set(true); active.getAndSet(null)?.close(); server.close(); thread?.join(2_000)
        check(thread?.isAlive != true) { "The synthetic HTTP fixture did not stop." }
        failure.get()?.let { throw AssertionError("The synthetic HTTP handler failed.", it) }
    }
    internal class Headers {
        val values = LinkedHashMap<String, MutableList<String>>()
        fun add(name: String, value: String) { values.getOrPut(name.lowercase(java.util.Locale.ROOT)) { ArrayList() }.add(value) }
        fun getFirst(name: String): String? = values[name.lowercase(java.util.Locale.ROOT)]?.firstOrNull()
    }
    internal class Exchange(private val socket: Socket, val requestMethod: String, val requestURI: URI,
        val requestHeaders: Headers, val requestBody: InputStream) : Closeable {
        val responseHeaders = Headers()
        val responseBody get() = socket.getOutputStream()
        fun sendResponseHeaders(status: Int, length: Long) {
            val header = buildString {
                append("HTTP/1.1 $status Fixture\r\nContent-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n")
                responseHeaders.values.forEach { (name, values) -> values.forEach { append("$name: $it\r\n") } }
                append("\r\n")
            }
            responseBody.write(header.toByteArray(Charsets.ISO_8859_1)); responseBody.flush()
        }
        override fun close() { requestBody.close(); socket.close() }
    }
    companion object {
        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (bytes.size() == 0) null else throw java.io.EOFException()
                if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                require(bytes.size() < 16_384)
                bytes.write(next)
            }
        }
    }
}
