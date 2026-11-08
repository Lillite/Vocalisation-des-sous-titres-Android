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
    fun saveDirectUrl(rawUrl: String): Uri = synchronized(LOCK) {
        val url = validatedDirectUrl(rawUrl)
        require(prefs.all.keys.count { it.startsWith("direct.") } < 256) { "La limite de 256 liens enregistrés est atteinte. Supprime un lien ancien." }
        val id = UUID.randomUUID().toString()
        write("direct.$id", url)
        directUri(id, url)
    }
    /** Generic opaque identities only; callers must never display the underlying URL. */
    fun listDirectUrls(): List<Uri> = synchronized(LOCK) {
        prefs.all.keys.filter { it.startsWith("direct.") }.sorted().map { key ->
            directUri(key.removePrefix("direct."), read(key) ?: throw RemoteAccessException("Un lien enregistré est inaccessible."))
        }
    }
    fun deleteDirectUrl(uri: Uri) = synchronized(LOCK) {
        require(uri.scheme == RemoteMediaUris.DIRECT_SCHEME)
        check(prefs.edit().remove("direct.${uri.host}").commit()) { "La suppression du lien a échoué." }
    }
    internal fun directUrl(uri: Uri): String = synchronized(LOCK) {
        require(uri.scheme == RemoteMediaUris.DIRECT_SCHEME)
        val root = read("direct.${uri.host}") ?: throw RemoteAccessException("Ce lien réseau a été supprimé. Ouvre un nouveau lien.")
        val original = Uri.parse(root)
        // Manifest children keep their relative identity; credentials stay in the vault.
        val initialPath = "/media" + original.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }?.let { ".$it" }.orEmpty()
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
