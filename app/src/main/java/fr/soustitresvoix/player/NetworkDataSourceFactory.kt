package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** The same transport is used for playback and full subtitle extraction. */
@UnstableApi
class NetworkDataSourceFactory private constructor(context: Context, private val store: RemoteConnectionStore,
    private val mediaAuthorization: MediaHttpAuthorization?) : DataSource.Factory {
    constructor(context: Context, store: RemoteConnectionStore = RemoteConnectionStore(context)) : this(context, store, null)
    private val context = context.applicationContext
    override fun createDataSource(): DataSource = RoutingSource(context, store, mediaAuthorization)

    /** Each media owns its Basic credentials. Manifest children receive them only
     * on the original HTTP origin; another media never inherits this scope. */
    fun forMediaUri(uri: Uri): NetworkDataSourceFactory {
        val authentication = when (uri.scheme) {
            RemoteMediaUris.DIRECT_SCHEME -> MediaHttpAuthorization.fromUrl(store.directUrl(uri))
            "http", "https" -> MediaHttpAuthorization.fromUrl(uri.toString())
            RemoteMediaUris.SERVER_SCHEME -> store.get(uri.host.orEmpty())?.takeIf { it.protocol == RemoteProtocol.WEBDAV }
                ?.let { connection -> MediaHttpAuthorization.fromCredentials(RemoteWebDav.url(connection, "/"), connection.username, connection.password) }
            else -> null
        }
        return NetworkDataSourceFactory(context, store, authentication)
    }
    /** Sidecar import on the caller's worker, with the same authenticated transport. */
    fun readBounded(uri: Uri, maxBytes: Int, cancellation: RemoteCancellation = RemoteCancellation()): ByteArray {
        require(maxBytes in 1..8_388_608) { "La limite d’import des sous-titres est de 8 Mio." }
        val source = createDataSource()
        cancellation.register(Closeable { source.close() })
        try {
            cancellation.ensureActive()
            val length = source.open(DataSpec.Builder().setUri(uri).build())
            if (length > maxBytes) throw RemoteAccessException("Le fichier de sous-titres dépasse la limite de 8 Mio.")
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                cancellation.ensureActive()
                val count = source.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
                if (count < 0) return output.toByteArray()
                if (output.size() + count > maxBytes) throw RemoteAccessException("Le fichier de sous-titres dépasse la limite de 8 Mio.")
                output.write(buffer, 0, count)
            }
        } finally { source.close() }
    }

    private class RoutingSource(private val context: Context, private val store: RemoteConnectionStore,
        private val mediaAuthorization: MediaHttpAuthorization?) : DataSource {
        private val listeners = ArrayList<TransferListener>()
        @Volatile private var delegate: DataSource? = null
        override fun addTransferListener(listener: TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
        override fun open(dataSpec: DataSpec): Long {
            check(delegate == null)
            val network = RemoteMediaUris.isRemote(dataSpec.uri) || dataSpec.uri.scheme?.lowercase() in setOf("http", "https")
            val selected = if (network) RemoteSource(store, mediaAuthorization) else DefaultDataSource(context, false)
            delegate = selected
            listeners.forEach(selected::addTransferListener)
            return try { selected.open(dataSpec) } catch (error: Exception) {
                runCatching { close() }
                throw error
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: throw IllegalStateException("Source fermée.")
        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
        override fun close() { val previous = delegate; delegate = null; previous?.close() }
    }

    private class RemoteSource(private val store: RemoteConnectionStore, private val mediaAuthorization: MediaHttpAuthorization?) : BaseDataSource(true) {
        @Volatile private var cancellation: RemoteCancellation? = null
        private var reader: RemoteRead? = null
        private var currentUri: Uri? = null
        private var remaining = C.LENGTH_UNSET.toLong()
        private var opened = false
        override fun open(dataSpec: DataSpec): Long {
            require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) { "Seule la lecture réseau est disponible." }
            transferInitializing(dataSpec)
            val task = RemoteCancellation(); cancellation = task; currentUri = dataSpec.uri
            try {
                val uri = dataSpec.uri
                val selected = when (uri.scheme) {
                    RemoteMediaUris.SERVER_SCHEME -> {
                        val connection = store.get(uri.host ?: "") ?: throw RemoteAccessException("Cette connexion a été supprimée. Recrée-la pour lire le fichier.")
                        val path = RemotePaths.normalize(uri.path ?: "/")
                        if (connection.protocol == RemoteProtocol.WEBDAV) {
                            RemoteHttp.open(RemoteWebDav.url(connection, path, uri.encodedQuery), dataSpec.position, dataSpec.length, task,
                                dataSpec.httpRequestHeaders, connection.username, connection.password)
                        } else RemoteProtocolClients.open(connection, path, dataSpec.position, task)
                    }
                    RemoteMediaUris.DIRECT_SCHEME -> openHttp(store.directUrl(uri), dataSpec, task)
                    "http", "https" -> openHttp(uri.toString(), dataSpec, task)
                    else -> throw RemoteAccessException("Ce protocole réseau ne peut pas être lu.")
                }
                task.ensureActive(); reader = selected
                // The manifest parser needs the final HTTP base to resolve relative
                // segment URLs. SavedMedia retains its opaque vault URI exclusively.
                if (uri.scheme != RemoteMediaUris.SERVER_SCHEME) currentUri = selected.resolvedUri ?: uri
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                    else if (selected.length >= 0) (selected.length - dataSpec.position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
                opened = true; transferStarted(dataSpec)
                return remaining
            } catch (error: Exception) {
                runCatching { close() }
                if (error is RemoteAccessException) throw error
                throw RemoteAccessException("Lecture réseau impossible. Vérifie la connexion et les droits du fichier.")
            }
        }
        private fun openHttp(rawUrl: String, dataSpec: DataSpec, task: RemoteCancellation): RemoteRead {
            val target = URI(rawUrl)
            val inherited = mediaAuthorization?.takeIf { target.rawUserInfo == null && RemoteHttp.sameOrigin(it.origin, target) }
            return RemoteHttp.open(rawUrl, dataSpec.position, dataSpec.length, task, dataSpec.httpRequestHeaders,
                inherited?.username.orEmpty(), inherited?.password.orEmpty())
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            cancellation?.ensureActive()
            val count = try { reader?.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
                ?: throw RemoteAccessException("La source réseau est fermée.") }
            catch (error: Exception) { throw if (error is RemoteAccessException) error else RemoteAccessException("Lecture réseau interrompue. Vérifie le serveur puis réessaie.") }
            if (count < 0) {
                if (remaining > 0) throw RemoteAccessException("Le serveur a fermé le fichier avant sa fin. La lecture et l’indexation sont incomplètes.")
                return C.RESULT_END_OF_INPUT
            }
            if (remaining >= 0) remaining -= count
            bytesTransferred(count)
            return count
        }
        override fun getUri(): Uri? = currentUri
        override fun close() {
            val previous = cancellation; cancellation = null; previous?.close()
            val read = reader; reader = null
            try { read?.close() } finally { currentUri = null; if (opened) { opened = false; transferEnded() } }
        }
    }

    private class MediaHttpAuthorization(val origin: URI, val username: String, val password: String) {
        override fun toString() = "MediaHttpAuthorization(<private>)"
        companion object {
            fun fromUrl(rawUrl: String): MediaHttpAuthorization? {
                val target = URI(rawUrl)
                if (target.scheme?.lowercase() !in setOf("http", "https")) return null
                val userInfo = target.rawUserInfo ?: return null
                return fromCredentials(rawUrl, Uri.decode(userInfo.substringBefore(':')), Uri.decode(userInfo.substringAfter(':', "")))
            }
            fun fromCredentials(rawUrl: String, username: String, password: String): MediaHttpAuthorization? {
                if (username.isEmpty() && password.isEmpty()) return null
                val target = URI(rawUrl)
                val origin = URI(target.scheme, null, target.host, target.port, "/", null, null)
                return MediaHttpAuthorization(origin, username, password)
            }
        }
    }
}

/** Platform TLS, bounded redirects/timeouts, and real byte-range validation. */
internal object RemoteHttp {
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000
    fun open(rawUrl: String, position: Long, requestedLength: Long, cancel: RemoteCancellation,
        headers: Map<String, String> = emptyMap(), username: String = "", password: String = ""): RemoteRead {
        var target = URI(rawUrl)
        var authorization = if (username.isNotEmpty() || password.isNotEmpty()) basic(username, password)
            else target.rawUserInfo?.let { basic(Uri.decode(it.substringBefore(':')), Uri.decode(it.substringAfter(':', ""))) }
        var connection: HttpURLConnection? = null
        try {
            for (redirect in 0..5) {
                cancel.ensureActive()
                require(target.scheme?.lowercase() in setOf("https", "http") && target.host != null) { "URL HTTP invalide." }
                // Preserve signed query/path octets exactly. Rebuilding from decoded
                // URI.path/query would turn %2F into '/', invalidating some signatures.
                val cleanTarget = URI(target.scheme + "://" + target.rawAuthority.substringAfterLast('@') +
                    target.rawPath.orEmpty() + (target.rawQuery?.let { "?$it" } ?: ""))
                val current = URL(cleanTarget.toASCIIString()).openConnection() as HttpURLConnection
                connection = current
                cancel.register(Closeable { current.disconnect() })
                current.connectTimeout = CONNECT_TIMEOUT; current.readTimeout = READ_TIMEOUT; current.instanceFollowRedirects = false
                current.setRequestProperty("User-Agent", "SousTitresVoix/1.0")
                current.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) ->
                    require(name.none { it.isISOControl() } && value.none { it == '\r' || it == '\n' })
                    if (!name.equals("Authorization", true) && !name.equals("Cookie", true) && !name.equals("Range", true)) current.setRequestProperty(name, value)
                }
                authorization?.let { current.setRequestProperty("Authorization", it) }
                if (position > 0 || requestedLength > 0) current.setRequestProperty("Range", "bytes=$position-" + if (requestedLength > 0) Math.addExact(position, requestedLength - 1).toString() else "")
                val status = current.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) throw RemoteAccessException("Le serveur renvoie trop de redirections.")
                    val next = cleanTarget.resolve(current.getHeaderField("Location") ?: throw RemoteAccessException("Redirection réseau invalide."))
                    if (cleanTarget.scheme.equals("https", true) && !next.scheme.equals("https", true)) throw RemoteAccessException("Le serveur tente de remplacer HTTPS par une connexion non chiffrée.")
                    if (!sameOrigin(cleanTarget, next)) authorization = null
                    target = next; current.disconnect(); continue
                }
                if (status == 416) {
                    val total = current.getHeaderField("Content-Range")?.let { Regex("bytes \\*/(\\d+)").matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
                    if (total == position) {
                        current.disconnect()
                        return object : RemoteRead {
                            override val length = position
                            override val resolvedUri: Uri = Uri.parse(cleanTarget.toASCIIString())
                            override fun read(buffer: ByteArray, offset: Int, length: Int) = if (length == 0) 0 else -1
                            override fun close() = Unit
                        }
                    }
                }
                if (status !in 200..299) throw RemoteAccessException(when (status) {
                    401, 403 -> "Accès réseau refusé : vérifie les identifiants et les droits du fichier."
                    404 -> "Le fichier réseau n’existe plus ou le lien a expiré."
                    416 -> "Le serveur refuse ce déplacement dans le fichier."
                    429 -> "Le serveur limite les accès. Attends avant de réessayer."
                    else -> "Le serveur a refusé la lecture (HTTP $status)."
                })
                val range = current.getHeaderField("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it.trim()) }
                val total = if (status == 206) {
                    if (range == null || range.groupValues[1].toLongOrNull() != position) throw RemoteAccessException("Le serveur renvoie une plage de fichier incorrecte.")
                    range.groupValues[3].toLongOrNull() ?: -1L
                } else current.contentLengthLong
                if (total >= 0 && position > total) throw RemoteAccessException("Le déplacement dépasse la fin du fichier réseau.")
                val stream = current.inputStream
                cancel.register(stream)
                if (status != 206 && position > 0) skipFully(stream, position, cancel)
                return object : RemoteRead {
                    override val length = total
                    override val resolvedUri: Uri = Uri.parse(cleanTarget.toASCIIString())
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { cancel.ensureActive(); return stream.read(buffer, offset, length) }
                    override fun close() { try { stream.close() } finally { current.disconnect() } }
                }
            }
            throw RemoteAccessException("Redirection réseau invalide.")
        } catch (error: Exception) {
            connection?.disconnect()
            if (error is RemoteAccessException) throw error
            throw RemoteAccessException("Connexion HTTP impossible. Vérifie le lien, le certificat et l’accès au serveur.")
        }
    }
    internal fun sameOrigin(a: URI, b: URI) = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    private fun effectivePort(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun basic(username: String, password: String) = "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun skipFully(input: InputStream, bytes: Long, cancel: RemoteCancellation) {
        var remaining = bytes
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            cancel.ensureActive()
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException()
            remaining -= read
        }
    }
}
