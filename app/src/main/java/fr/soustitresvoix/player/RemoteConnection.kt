package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class RemoteProtocol(val defaultPort: Int) {
    SFTP(22), FTP(21), FTPS(21), WEBDAV(443), SMB(445)
}

/** Credentials are never embedded in a playback URI or in a diagnostic. */
data class RemoteConnection(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val rootPath: String = "/",
    val share: String = "",
    val domain: String = "",
    val tls: Boolean = true,
    val hostKeySha256: String = ""
) {
    fun validated(): RemoteConnection {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Identifiant de connexion invalide." }
        require(label.isNotBlank() && label.length <= 200 && label.none { it.isISOControl() }) { "Nom de connexion invalide." }
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" || it.isISOControl() }) { "Hôte invalide : renseigne seulement son nom ou son adresse." }
        require(port in 1..65535) { "Port invalide." }
        require(runCatching { java.net.URI("remote", null, host, port, "/", null, null).host != null }.getOrDefault(false)) { "Hôte invalide : sépare l’adresse et le port." }
        require(listOf(username, password, domain).all { it.length <= 4096 && '\u0000' !in it && '\r' !in it && '\n' !in it }) { "Identifiants invalides." }
        RemotePaths.normalize(rootPath)
        require(share.length <= 255 && share.none { it in "/\\" || it.isISOControl() }) { "Nom de partage SMB invalide." }
        if (protocol == RemoteProtocol.SMB) require(share.isNotBlank()) { "Indique le nom du partage SMB." }
        require(hostKeySha256.isEmpty() || hostKeySha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?"))) { "Empreinte SFTP invalide (SHA256 attendue)." }
        return copy(rootPath = RemotePaths.normalize(rootPath))
    }

    override fun toString() = "RemoteConnection(id=$id, protocol=$protocol, credentials=<private>)"
}

data class RemoteEntry(val name: String, val path: String, val isDirectory: Boolean,
    val sizeBytes: Long? = null, val modifiedMs: Long? = null)

internal data class DirectNetworkLink(val uri: Uri, val title: String, val lastOpenedMs: Long,
    val customTitle: String?) {
    fun media() = SavedMedia(uri.toString(), title)
}

class RemoteAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A task may close this handle from its UI owner to interrupt sockets and reads. */
class RemoteCancellation : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val handles = CopyOnWriteArrayList<Closeable>()
    fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw RemoteAccessException("Accès réseau annulé.")
    }
    fun register(handle: Closeable) {
        handles.add(handle)
        if (cancelled.get()) { runCatching { handle.close() }; ensureActive() }
    }
    override fun close() {
        cancelled.set(true)
        handles.reversed().forEach { runCatching { it.close() } }
        handles.clear()
    }
}

object RemotePaths {
    fun normalize(path: String): String {
        require(path.length <= 8192 && path.none { it.isISOControl() || it == '\\' }) { "Chemin distant invalide." }
        val pieces = path.split('/').filter { it.isNotEmpty() }
        require(pieces.none { it == "." || it == ".." }) { "Le chemin doit rester dans le dossier choisi." }
        return "/" + pieces.joinToString("/")
    }
    fun join(parent: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "Nom de fichier distant invalide." }
        return normalize(normalize(parent).trimEnd('/') + "/" + name)
    }
    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifEmpty { "/" }
    fun resolve(root: String, path: String): String = normalize(normalize(root).trimEnd('/') + normalize(path))
}

object RemoteMediaUris {
    const val SERVER_SCHEME = "remote-media"
    const val DIRECT_SCHEME = "network-media"
    fun create(connectionId: String, path: String): Uri {
        require(connectionId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return Uri.Builder().scheme(SERVER_SCHEME).authority(connectionId).path(RemotePaths.normalize(path)).build()
    }
    fun isRemote(uri: Uri): Boolean = uri.scheme == SERVER_SCHEME || uri.scheme == DIRECT_SCHEME
    fun safeDisplay(uri: String): String {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            SERVER_SCHEME -> "Fichier sur serveur enregistré"
            DIRECT_SCHEME -> "Lien réseau enregistré"
            "http", "https", "rtsp", "rtsps", "ftp", "ftps", "sftp", "smb" -> "Source réseau (${parsed.scheme})"
            else -> uri
        }
    }
}

internal interface RemoteSecretCipher {
    fun encrypt(plaintext: ByteArray, identity: String): String
    fun decrypt(value: String, identity: String): ByteArray
}

/** Hardware-backed when offered by Android; no password, token or URL in preferences. */
internal class KeystoreRemoteSecretCipher : RemoteSecretCipher {
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun encrypt(plaintext: ByteArray, identity: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
    }
    override fun decrypt(value: String, identity: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size in 29..MAX_ENCRYPTED_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
    companion object {
        private val KEY_LOCK = Any()
        private const val KEY_ALIAS = "remote-connections-aes-gcm-v1"
        private const val MAX_ENCRYPTED_BYTES = 64 * 1024
    }
}

/** Entire connection records and direct signed URLs are encrypted using Android Keystore. */
class RemoteConnectionStore private constructor(context: Context, private val cipher: RemoteSecretCipher) {
    constructor(context: Context) : this(context, KeystoreRemoteSecretCipher())
    private val prefs = context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE)

    private data class DirectRecord(val url: String, val lastOpenedMs: Long = 0, val customTitle: String? = null,
        val metadataTitle: String? = null, val headerTitle: String? = null) {
        override fun toString() = "DirectRecord(<private>)"
    }

    fun list(): List<RemoteConnection> = synchronized(LOCK) {
        prefs.all.keys.filter { it.startsWith("connection.") }.map { readConnection(it.removePrefix("connection.")) }
            .sortedBy { it.label.lowercase() }
    }
    fun get(id: String): RemoteConnection? = synchronized(LOCK) {
        if (!prefs.contains("connection.$id")) null else readConnection(id)
    }
    fun save(connection: RemoteConnection) = synchronized(LOCK) {
        val value = connection.validated()
        require(prefs.contains("connection.${value.id}") || prefs.all.keys.count { it.startsWith("connection.") } < 64) { "La limite de 64 connexions est atteinte." }
        val json = JSONObject().put("id", value.id).put("label", value.label).put("protocol", value.protocol.name)
            .put("host", value.host).put("port", value.port).put("username", value.username).put("password", value.password)
            .put("rootPath", value.rootPath).put("share", value.share).put("domain", value.domain).put("tls", value.tls).put("hostKeySha256", value.hostKeySha256)
            .put("revision", UUID.randomUUID().toString())
        write("connection.${value.id}", json.toString())
    }
    fun delete(id: String) = synchronized(LOCK) { check(prefs.edit().remove("connection.$id").commit()) { "La suppression de la connexion a échoué." } }
    fun saveDirectUrl(rawUrl: String, title: String = ""): Uri = synchronized(LOCK) {
        val url = validatedDirectUrl(rawUrl)
        require(prefs.all.keys.count { it.startsWith("direct.") } < 256) { "La limite de 256 liens enregistrés est atteinte. Supprime un lien ancien." }
        val id = UUID.randomUUID().toString()
        writeDirect(id, DirectRecord(url, System.currentTimeMillis(), validatedTitle(title)))
        directUri(id, url)
    }
    /** Generic opaque identities only; callers must never display the underlying URL. */
    fun listDirectUrls(): List<Uri> = listDirectLinks().map { it.uri }

    internal fun listDirectLinks(): List<DirectNetworkLink> = synchronized(LOCK) {
        prefs.all.keys.filter { it.startsWith("direct.") }.map { key ->
            val id = key.removePrefix("direct.")
            directLink(id, readDirect(id))
        }.sortedWith(compareByDescending<DirectNetworkLink> { it.lastOpenedMs }.thenBy { it.title }.thenBy { it.uri.toString() })
    }

    internal fun directLink(uri: Uri): DirectNetworkLink = synchronized(LOCK) {
        val id = directId(uri)
        directLink(id, readDirect(id))
    }

    internal fun reopenDirectLink(uri: Uri): DirectNetworkLink = synchronized(LOCK) {
        val id = directId(uri)
        val record = readDirect(id)
        val reopened = record.copy(lastOpenedMs = System.currentTimeMillis())
        // The address is already protected. A timestamp write failure need not block playback.
        val result = runCatching { writeDirect(id, reopened); reopened }.getOrDefault(record)
        directLink(id, result)
    }

    internal fun renameDirectLink(uri: Uri, title: String): DirectNetworkLink = synchronized(LOCK) {
        val id = directId(uri)
        val renamed = readDirect(id).copy(customTitle = validatedTitle(title))
        writeDirect(id, renamed)
        directLink(id, renamed)
    }

    /** Only the initial request can name a saved film. Ignore names on manifest children. */
    internal fun offerDirectHeaderTitle(uri: Uri, title: String) = synchronized(LOCK) {
        val id = directId(uri)
        val record = readDirect(id)
        val safeTitle = NetworkMediaTitles.metadata(title)
        if (safeTitle != null && uri == directUri(id, record.url) && record.headerTitle != safeTitle) {
            writeDirect(id, record.copy(headerTitle = safeTitle))
        }
    }

    internal fun offerDirectMetadataTitle(uri: Uri, title: String): DirectNetworkLink = synchronized(LOCK) {
        val id = directId(uri)
        val record = readDirect(id)
        val safeTitle = NetworkMediaTitles.metadata(title) ?: return@synchronized directLink(id, record)
        val updated = record.copy(metadataTitle = safeTitle)
        if (updated != record) writeDirect(id, updated)
        directLink(id, updated)
    }

    fun deleteDirectUrl(uri: Uri) = synchronized(LOCK) {
        check(prefs.edit().remove("direct.${directId(uri)}").commit()) { "La suppression du lien a échoué." }
    }
    internal fun directUrl(uri: Uri): String = synchronized(LOCK) {
        val id = directId(uri)
        val root = readDirect(id).url
        // Manifest children keep their relative identity; credentials stay in the vault.
        val initialPath = directUri(id, root).path
        if (uri.path == initialPath && uri.query == null) root else {
            val base = java.net.URI(root)
            val relative = (uri.encodedPath ?: "/").removePrefix("/") + (uri.encodedQuery?.let { "?$it" } ?: "")
            base.resolve(".").resolve(relative).toASCIIString()
        }
    }
    /** Local identity only. A changed connection invalidates old subtitle/session caches. */
    fun sourceRevision(uri: Uri): String = synchronized(LOCK) {
        when (uri.scheme) {
            RemoteMediaUris.SERVER_SCHEME -> {
                val json = read("connection.${uri.host}") ?: return "missing"
                JSONObject(json).optString("revision", "legacy")
            }
            RemoteMediaUris.DIRECT_SCHEME -> if (prefs.contains("direct.${uri.host}")) uri.host.orEmpty() else "missing"
            else -> ""
        }
    }
    private fun readConnection(id: String): RemoteConnection {
        try {
            val json = JSONObject(read("connection.$id") ?: throw IOException())
            return RemoteConnection(json.getString("id"), json.getString("label"), RemoteProtocol.valueOf(json.getString("protocol")),
                json.getString("host"), json.getInt("port"), json.getString("username"), json.getString("password"),
                json.getString("rootPath"), json.getString("share"), json.getString("domain"), json.getBoolean("tls"), json.getString("hostKeySha256")).validated()
        } catch (_: Exception) { throw RemoteAccessException("Une connexion enregistrée est illisible. Supprime-la puis recrée-la ; les fichiers du serveur sont conservés.") }
    }
    private fun directId(uri: Uri): String {
        require(uri.scheme == RemoteMediaUris.DIRECT_SCHEME)
        return requireNotNull(uri.host)
    }

    private fun readDirect(id: String): DirectRecord {
        val stored = read("direct.$id") ?: throw RemoteAccessException("Ce lien réseau a été supprimé. Ouvre un nouveau lien.")
        try {
            // 1.0.0 stored the complete URL as encrypted text, with the same key and ID.
            if (!stored.startsWith('{')) return DirectRecord(validatedDirectUrl(stored))
            val json = JSONObject(stored)
            fun optionalTitle(key: String) = json.optString(key).let(NetworkMediaTitles::userTitle)
            return DirectRecord(validatedDirectUrl(json.getString("url")), json.optLong("lastOpenedMs", 0),
                optionalTitle("customTitle"), optionalTitle("metadataTitle"), optionalTitle("headerTitle"))
        } catch (_: Exception) {
            throw RemoteAccessException("Un lien enregistré est illisible sur cet appareil.")
        }
    }

    private fun writeDirect(id: String, record: DirectRecord) {
        val json = JSONObject().put("url", record.url).put("lastOpenedMs", record.lastOpenedMs)
            .put("customTitle", record.customTitle).put("metadataTitle", record.metadataTitle).put("headerTitle", record.headerTitle)
        write("direct.$id", json.toString())
    }

    private fun directLink(id: String, record: DirectRecord) = DirectNetworkLink(directUri(id, record.url),
        record.customTitle ?: record.metadataTitle ?: record.headerTitle ?: NetworkMediaTitles.urlPath(record.url) ?: NetworkMediaTitles.FALLBACK,
        record.lastOpenedMs, record.customTitle)

    private fun validatedTitle(title: String): String? = if (title.isBlank()) null else
        requireNotNull(NetworkMediaTitles.userTitle(title)) { "Saisis un titre de 200 caractères au maximum, sans adresse ni identifiants." }

    private fun directUri(id: String, rawUrl: String): Uri {
        val extension = Uri.parse(rawUrl).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return Uri.Builder().scheme(RemoteMediaUris.DIRECT_SCHEME).authority(id)
            .path("/media" + extension?.let { ".$it" }.orEmpty()).build()
    }
    private fun write(key: String, value: String) {
        try { check(prefs.edit().putString(key, cipher.encrypt(value.toByteArray(Charsets.UTF_8), key)).commit()) }
        catch (_: Exception) { throw RemoteAccessException("Impossible de protéger et enregistrer cette connexion sur cet appareil.") }
    }
    private fun read(key: String): String? {
        val value = prefs.getString(key, null) ?: return null
        return try { cipher.decrypt(value, key).toString(Charsets.UTF_8) }
        catch (_: Exception) { throw RemoteAccessException("Le stockage chiffré réseau est inaccessible. Recrée cette connexion sur cet appareil.") }
    }
    companion object {
        private val LOCK = Any()
        internal fun forTest(context: Context, cipher: RemoteSecretCipher) = RemoteConnectionStore(context, cipher)
        internal fun validatedDirectUrl(value: String): String {
            val trimmed = value.trim()
            require(trimmed.length in 1..16384 && trimmed.none { it.isISOControl() }) { "Lien réseau invalide." }
            val uri = runCatching { java.net.URI(trimmed) }.getOrElse { throw IllegalArgumentException("Lien invalide : encode les espaces (%20).") }
            require(uri.scheme?.lowercase() in setOf("http", "https", "rtsp") && !uri.host.isNullOrBlank() && uri.port in -1..65535 && uri.port != 0) { "Utilise une URL directe HTTP, HTTPS ou RTSP valide." }
            require(uri.fragment == null) { "Le lien ne doit pas contenir de fragment." }
            return trimmed
        }
    }
}
