package fr.soustitresvoix.player

import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Blocking bounded operations: the screen supplies a worker and cancellation. */
class RemoteBrowser(private val store: RemoteConnectionStore) {
    fun list(connectionId: String, path: String = "/", cancel: RemoteCancellation = RemoteCancellation()): List<RemoteEntry> {
        cancel.ensureActive()
        val connection = store.get(connectionId) ?: throw RemoteAccessException("Cette connexion n’existe plus.")
        val normalized = RemotePaths.normalize(path)
        return try {
            val entries = if (connection.protocol == RemoteProtocol.WEBDAV) RemoteWebDav.list(connection, normalized, cancel)
                else RemoteProtocolClients.list(connection, normalized, cancel)
            cancel.ensureActive()
            if (entries.size > MAX_ENTRIES) throw RemoteAccessException("Ce dossier contient plus de 5 000 entrées. Choisis un dossier de départ plus précis.")
            entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (error: Exception) {
            if (error is RemoteAccessException) throw error
            throw RemoteAccessException("Impossible d’ouvrir ce dossier distant. Vérifie le chemin, les identifiants et la connexion.")
        }
    }
    fun test(connectionId: String, cancel: RemoteCancellation = RemoteCancellation()) { list(connectionId, "/", cancel) }
    fun probeSftpHostKey(connection: RemoteConnection, cancel: RemoteCancellation = RemoteCancellation()): String {
        require(connection.protocol == RemoteProtocol.SFTP) { "La vérification d’empreinte concerne SFTP." }
        return RemoteProtocolClients.probeSftpHostKey(connection.validated(), cancel)
    }
    companion object { const val MAX_ENTRIES = 5_000 }
}

internal object RemoteWebDav {
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun url(connection: RemoteConnection, path: String, encodedQuery: String? = null): String {
        val base = URI(if (connection.tls) "https" else "http", null, connection.host, connection.port,
            RemotePaths.resolve(connection.rootPath, path), null, null).toASCIIString()
        return base + (encodedQuery?.let { "?$it" } ?: "")
    }
    fun list(connection: RemoteConnection, path: String, cancel: RemoteCancellation): List<RemoteEntry> {
        val endpoint = URI(url(connection, path.trimEnd('/') + "/"))
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
        val request = Request.Builder().url(endpoint.toASCIIString()).header("Depth", "1")
            .method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        if (connection.username.isNotEmpty() || connection.password.isNotEmpty()) request.header("Authorization", "Basic " +
            Base64.encodeToString("${connection.username}:${connection.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val call = http.newCall(request.build())
        cancel.register(Closeable { call.cancel() })
        try {
            cancel.ensureActive()
            return call.execute().use { response ->
            val code = response.code
            if (code != 207) throw RemoteAccessException(when (code) {
                401, 403 -> "Accès WebDAV refusé : vérifie les identifiants et les droits du dossier."
                301, 302, 307, 308 -> "Le serveur WebDAV redirige cette adresse. Utilise directement son URL finale dans le dossier de départ."
                404 -> "Le dossier WebDAV demandé n’existe pas."
                else -> "Le serveur ne renvoie pas un dossier WebDAV (HTTP $code)."
            })
            val body = response.body ?: throw RemoteAccessException("La réponse WebDAV est vide.")
            if (body.contentLength() > MAX_XML_BYTES) throw RemoteAccessException("La réponse WebDAV est trop volumineuse. Choisis un sous-dossier.")
            val bytes = body.byteStream().use { input ->
                cancel.register(input)
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    cancel.ensureActive(); val size = input.read(buffer); if (size < 0) break
                    if (output.size() + size > MAX_XML_BYTES) throw RemoteAccessException("La réponse WebDAV est trop volumineuse. Choisis un sous-dossier.")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            parse(bytes, endpoint, connection.rootPath, path, cancel)
            }
        } catch (error: Exception) {
            if (error is RemoteAccessException) throw error
            throw RemoteAccessException("Connexion WebDAV impossible. Vérifie le certificat, les identifiants et le dossier de départ.")
        } finally { call.cancel() }
    }

    internal fun parse(bytes: ByteArray, endpoint: URI, rootPath: String, virtualPath: String, cancel: RemoteCancellation): List<RemoteEntry> {
        require(bytes.size <= MAX_XML_BYTES)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val expected = RemotePaths.resolve(rootPath, virtualPath).trimEnd('/')
        val result = LinkedHashMap<String, RemoteEntry>()
        var responseDepth = -1; var href: String? = null; var directory = false; var size: Long? = null; var modified: Long? = null
        var sawStatus = false; var successfulProperty = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            cancel.ensureActive()
            if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF) throw RemoteAccessException("Réponse WebDAV XML interdite.")
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > 32) throw RemoteAccessException("Réponse WebDAV trop complexe.")
                if (parser.namespace == "DAV:") when (parser.name) {
                    "response" -> { responseDepth = parser.depth; href = null; directory = false; size = null; modified = null; sawStatus = false; successfulProperty = false }
                    "href" -> if (responseDepth >= 0) href = parser.nextText()
                    "collection" -> if (responseDepth >= 0) directory = true
                    "getcontentlength" -> if (responseDepth >= 0) size = parser.nextText().trim().toLongOrNull()?.takeIf { it >= 0 }
                    "getlastmodified" -> if (responseDepth >= 0) modified = parseDate(parser.nextText())
                    "status" -> if (responseDepth >= 0) {
                        val status = parser.nextText().split(' ').getOrNull(1)?.toIntOrNull()
                        if (status != null) { sawStatus = true; if (status in 200..299) successfulProperty = true }
                    }
                }
            }
            if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:" && parser.name == "response" && parser.depth == responseDepth) {
                if ((!sawStatus || successfulProperty) && href != null) {
                    val target = runCatching { endpoint.resolve(href) }.getOrNull()
                    if (target != null && RemoteHttp.sameOrigin(endpoint, target) && target.rawQuery == null && target.rawFragment == null) {
                        val decoded = target.path ?: ""
                        val normalized = runCatching { RemotePaths.normalize(decoded) }.getOrNull()
                        if (normalized != null && normalized != expected && RemotePaths.parent(normalized).trimEnd('/') == expected) {
                            val name = normalized.substringAfterLast('/')
                            if (name.isNotEmpty()) {
                                val child = RemotePaths.join(virtualPath, name)
                                result[child] = RemoteEntry(name, child, directory, if (directory) null else size, modified)
                                if (result.size > RemoteBrowser.MAX_ENTRIES) throw RemoteAccessException("Ce dossier contient plus de 5 000 entrées. Choisis un dossier de départ plus précis.")
                            }
                        }
                    }
                }
                responseDepth = -1
            }
            event = parser.nextToken()
        }
        return result.values.toList()
    }
    private fun parseDate(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }.parse(value)?.time
    }.getOrNull()
}
