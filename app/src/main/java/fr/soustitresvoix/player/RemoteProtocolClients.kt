package fr.soustitresvoix.player

import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.RemoteResourceFilter
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFileEntryParser
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.ftp.parser.DefaultFTPFileEntryParserFactory
import org.apache.commons.net.ftp.parser.MLSxEntryParser
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Duration
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** Total file length, or -1 when the server does not publish one. */
internal interface RemoteRead : Closeable {
    val length: Long
    val resolvedUri: android.net.Uri? get() = null
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Protocol clients are confined to the caller's worker. No account data enters a URI. */
internal object RemoteProtocolClients {
    private const val TIMEOUT_MS = 15_000
    private const val LIST_LIMIT = 5_000
    private const val LIST_TOO_LARGE = "Ce dossier dépasse 5 000 entrées. Ouvre un sous-dossier plus précis."
    private const val CONNECT_ERROR = "Connexion au serveur impossible. Vérifie le protocole, l’adresse et les identifiants."

    fun list(connection: RemoteConnection, path: String, cancel: RemoteCancellation): List<RemoteEntry> {
        cancel.ensureActive()
        return guarded {
            when (connection.protocol) {
                RemoteProtocol.FTP, RemoteProtocol.FTPS -> ftpList(connection, path, cancel)
                RemoteProtocol.SFTP -> sftpList(connection, path, cancel)
                RemoteProtocol.SMB -> smbList(connection, path, cancel)
                else -> throw RemoteAccessException("Ce protocole n’est pas géré par ce transport.")
            }
        }
    }

    fun open(connection: RemoteConnection, path: String, position: Long, cancel: RemoteCancellation): RemoteRead {
        require(position >= 0)
        cancel.ensureActive()
        return guarded {
            when (connection.protocol) {
                RemoteProtocol.FTP, RemoteProtocol.FTPS -> ftpOpen(connection, path, position, cancel)
                RemoteProtocol.SFTP -> sftpOpen(connection, path, position, cancel)
                RemoteProtocol.SMB -> smbOpen(connection, path, position, cancel)
                else -> throw RemoteAccessException("Ce protocole n’est pas géré par ce transport.")
            }
        }
    }

    /** The key is collected before authentication and the handshake is rejected. */
    fun probeSftpHostKey(connection: RemoteConnection, cancel: RemoteCancellation): String {
        require(connection.protocol == RemoteProtocol.SFTP)
        var observed: String? = null
        val ssh = sshClient(cancel)
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                observed = fingerprint(key)
                return false
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            try { ssh.connect(connection.host, connection.port) } catch (error: IOException) {
                cancel.ensureActive()
                if (observed == null) throw RemoteAccessException(CONNECT_ERROR, error)
            }
            cancel.ensureActive()
            return observed ?: throw RemoteAccessException("L’empreinte du serveur SFTP n’a pas pu être relevée.")
        } finally { runCatching { ssh.close() } }
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (error: RemoteAccessException) {
        throw error
    } catch (error: Exception) {
        throw RemoteAccessException(CONNECT_ERROR, error)
    }

    private fun fingerprint(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }

    private fun sshClient(cancel: RemoteCancellation): SSHClient = SSHClient().also { ssh ->
        ssh.connectTimeout = TIMEOUT_MS
        ssh.timeout = TIMEOUT_MS
        ssh.socketFactory = BoundedSocketFactory(cancel)
        cancel.register(ssh)
    }

    private fun authenticatedSftp(connection: RemoteConnection, cancel: RemoteCancellation): SSHClient {
        if (!connection.hostKeySha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?"))) {
            throw RemoteAccessException("Vérifie et enregistre l’empreinte SHA256 du serveur SFTP avant de te connecter.")
        }
        val ssh = sshClient(cancel)
        var rejectedKey = false
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val expected = connection.hostKeySha256.removeSuffix("=").toByteArray(Charsets.US_ASCII)
                val actual = fingerprint(key).toByteArray(Charsets.US_ASCII)
                return MessageDigest.isEqual(expected, actual).also { rejectedKey = !it }
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(connection.host, connection.port)
            cancel.ensureActive()
            ssh.authPassword(connection.username, connection.password)
            cancel.ensureActive()
            return ssh
        } catch (error: Exception) {
            runCatching { ssh.close() }
            if (rejectedKey) throw RemoteAccessException("L’empreinte du serveur SFTP a changé ou ne correspond pas. Connexion refusée.", error)
            throw error
        }
    }

    private fun sftpList(connection: RemoteConnection, path: String, cancel: RemoteCancellation): List<RemoteEntry> {
        val directory = RemotePaths.normalize(path)
        authenticatedSftp(connection, cancel).use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                var count = 0
                val entries = sftp.ls(RemotePaths.resolve(connection.rootPath, directory), RemoteResourceFilter { item ->
                    cancel.ensureActive()
                    if (++count > LIST_LIMIT + 2) throw RemoteAccessException(LIST_TOO_LARGE)
                    safeName(item.name) && (item.isDirectory || item.isRegularFile)
                })
                if (entries.size > LIST_LIMIT) throw RemoteAccessException(LIST_TOO_LARGE)
                return entries.map { item ->
                    val attrs = item.attributes
                    RemoteEntry(item.name, childPath(directory, item.name), item.isDirectory,
                        attrs.size.takeIf { attrs.has(FileAttributes.Flag.SIZE) },
                        (attrs.mtime * 1000).takeIf { attrs.has(FileAttributes.Flag.ACMODTIME) })
                }
            }
        }
    }

    private fun sftpOpen(connection: RemoteConnection, path: String, position: Long, cancel: RemoteCancellation): RemoteRead {
        val resources = CloseGroup()
        try {
            val ssh = authenticatedSftp(connection, cancel).also(resources::add)
            val sftp = ssh.newSFTPClient().also(resources::add)
            val file = sftp.open(RemotePaths.resolve(connection.rootPath, path)).also(resources::add)
            val total = file.length()
            if (position > total) throw RemoteAccessException("La position demandée dépasse la fin du fichier distant.")
            var cursor = position
            return object : RemoteRead {
                override val length: Long = total
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.ensureActive()
                    if (length == 0) return 0
                    if (cursor >= total) return -1
                    return file.read(cursor, buffer, offset, length.coerceAtMost(64 * 1024)).also {
                        if (it <= 0) throw RemoteAccessException("Le fichier SFTP s’est interrompu avant sa fin.")
                        if (it > 0) cursor += it
                        cancel.ensureActive()
                    }
                }
                override fun close() = resources.close()
            }
        } catch (error: Exception) { resources.close(); throw error }
    }

    /** Access to the data socket keeps raw listings bounded before a library can accumulate them. */
    private interface FtpListingSocket { fun listingSocket(command: String, path: String): Socket? }
    private class PlainFtp : FTPClient(), FtpListingSocket {
        override fun listingSocket(command: String, path: String): Socket? = _openDataConnection_(command, path)
    }
    private class TlsFtp : FTPSClient(false), FtpListingSocket {
        override fun listingSocket(command: String, path: String): Socket? = _openDataConnection_(command, path)
        override fun _openDataConnection_(command: String, path: String?): Socket? {
            val socket = super._openDataConnection_(command, path)
            // Commons Net applies endpoint checking only to the control socket.
            // Bind its independently protected data channel to the certificate whose
            // CA chain and hostname the control handshake already verified.
            if (socket is SSLSocket) {
                val control = (_socket_ as? SSLSocket)?.session?.peerCertificates?.firstOrNull()?.encoded
                val data = socket.session.peerCertificates.firstOrNull()?.encoded
                if (control == null || data == null || !MessageDigest.isEqual(control, data)) {
                    runCatching { socket.close() }
                    throw SSLHandshakeException("FTPS data certificate differs from verified control certificate")
                }
            }
            return socket
        }
    }

    private fun ftpClient(connection: RemoteConnection, cancel: RemoteCancellation): FTPClient {
        val client: FTPClient = if (connection.protocol == RemoteProtocol.FTPS) TlsFtp().apply {
            isEndpointCheckingEnabled = true
            // Commons Net's default checks expiry only; null selects the platform CA store.
            trustManager = null
        } else PlainFtp()
        cancel.register(Closeable { runCatching { client.disconnect() } })
        try {
            client.connectTimeout = TIMEOUT_MS
            client.defaultTimeout = TIMEOUT_MS
            client.setDataTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            client.controlEncoding = "UTF-8"
            client.setAutodetectUTF8(true)
            client.connect(connection.host, connection.port)
            client.soTimeout = TIMEOUT_MS
            cancel.ensureActive()
            if (!FTPReply.isPositiveCompletion(client.replyCode) || !client.login(connection.username, connection.password)) {
                throw RemoteAccessException("Authentification FTP refusée. Vérifie les identifiants.")
            }
            if (client is FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
            client.enterLocalPassiveMode()
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw RemoteAccessException("Le serveur FTP refuse le transfert binaire.")
            cancel.ensureActive()
            return client
        } catch (error: Exception) { runCatching { client.disconnect() }; throw error }
    }

    private fun ftpList(connection: RemoteConnection, path: String, cancel: RemoteCancellation): List<RemoteEntry> {
        val directory = RemotePaths.normalize(path)
        val client = ftpClient(connection, cancel)
        try {
            val full = RemotePaths.resolve(connection.rootPath, directory)
            val transport = client as FtpListingSocket
            var socket = transport.listingSocket("MLSD", full)
            val parser: FTPFileEntryParser
            if (socket != null) parser = MLSxEntryParser.getInstance() else {
                if (client.replyCode !in setOf(500, 501, 502, 504)) throw RemoteAccessException("Le dossier distant est inaccessible.")
                parser = DefaultFTPFileEntryParserFactory().createFileEntryParser(client.systemType)
                socket = transport.listingSocket("LIST", full)
            }
            val data = socket ?: throw RemoteAccessException("Le serveur FTP refuse la liste du dossier.")
            cancel.register(data)
            val result = ArrayList<RemoteEntry>()
            var lines = 0
            var totalChars = 0
            data.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                while (true) {
                    cancel.ensureActive()
                    val line = boundedLine(reader) ?: break
                    if (++lines > LIST_LIMIT + 3 || (totalChars + line.length).also { totalChars = it } > 8 * 1024 * 1024) {
                        throw RemoteAccessException(LIST_TOO_LARGE)
                    }
                    val item = parser.parseFTPEntry(line) ?: continue
                    if (!safeName(item.name) || item.isSymbolicLink || !(item.isDirectory || item.isFile)) continue
                    if (result.size >= LIST_LIMIT) throw RemoteAccessException(LIST_TOO_LARGE)
                    result += RemoteEntry(item.name, childPath(directory, item.name), item.isDirectory,
                        item.size.takeIf { size -> size >= 0 }, item.timestamp?.timeInMillis)
                }
            }
            if (!client.completePendingCommand()) throw RemoteAccessException("La liste du dossier FTP est incomplète.")
            return result
        } finally { runCatching { client.disconnect() } }
    }

    private fun boundedLine(reader: BufferedReader): String? {
        val result = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return if (result.isEmpty()) null else result.toString()
            if (next == '\n'.code) return result.toString().removeSuffix("\r")
            if (result.length >= 16 * 1024) throw RemoteAccessException("Une entrée du dossier distant est trop longue.")
            result.append(next.toChar())
        }
    }

    private fun ftpOpen(connection: RemoteConnection, path: String, position: Long, cancel: RemoteCancellation): RemoteRead {
        val client = ftpClient(connection, cancel)
        try {
            val full = RemotePaths.resolve(connection.rootPath, path)
            val total = client.getSize(full)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
            if (total >= 0 && position > total) throw RemoteAccessException("La position demandée dépasse la fin du fichier distant.")
            if (total >= 0 && position == total) return object : RemoteRead {
                override val length: Long = total
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.ensureActive()
                    return if (length == 0) 0 else -1
                }
                override fun close() { runCatching { client.disconnect() } }
            }
            client.restartOffset = position
            val input = client.retrieveFileStream(full) ?: throw RemoteAccessException("Le serveur FTP refuse la lecture ou la reprise du fichier.")
            cancel.register(input)
            val closed = AtomicBoolean(false)
            return object : RemoteRead {
                override val length: Long = total
                private var endChecked = false
                private var cursor = position
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.ensureActive()
                    val read = input.read(buffer, offset, length)
                    if (read > 0) cursor += read
                    if (read < 0 && !endChecked) {
                        endChecked = true
                        input.close()
                        if (!client.completePendingCommand()) throw RemoteAccessException("Le transfert FTP s’est interrompu avant sa fin.")
                        if (total >= 0 && cursor < total) throw RemoteAccessException("Le fichier FTP s’est interrompu avant sa fin.")
                    }
                    cancel.ensureActive()
                    return read
                }
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        runCatching { input.close() }
                        // An early close is a seek/cancellation, not a completed transfer.
                        runCatching { client.disconnect() }
                    }
                }
            }
        } catch (error: Exception) { runCatching { client.disconnect() }; throw error }
    }

    private class CloseGroup : Closeable {
        private val entries = ArrayList<AutoCloseable>()
        private val closed = AtomicBoolean(false)
        fun add(closeable: AutoCloseable) { entries += closeable }
        override fun close() { if (closed.compareAndSet(false, true)) entries.asReversed().forEach { runCatching { it.close() } } }
    }

    /** SMBJ otherwise connects through a SocketFactory without a connect timeout. */
    private class BoundedSocketFactory(private val cancel: RemoteCancellation) : SocketFactory() {
        override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))
        override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
        override fun createSocket(): Socket = Socket().also { cancel.register(it) }
        private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
            val socket = createSocket()
            try {
                cancel.ensureActive()
                if (local != null) socket.bind(local)
                socket.connect(remote, TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                cancel.ensureActive()
                return socket
            } catch (error: Exception) { runCatching { socket.close() }; throw error }
        }
    }

    private fun smbShare(connection: RemoteConnection, cancel: RemoteCancellation, resources: CloseGroup): DiskShare {
        val config = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            .withSigningRequired(true)
            .withTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .withSocketFactory(BoundedSocketFactory(cancel)).build()
        val client = SMBClient(config).also(resources::add).also { cancel.register(it) }
        val network = client.connect(connection.host, connection.port).also(resources::add)
        cancel.ensureActive()
        val password = connection.password.toCharArray()
        val session = try { network.authenticate(AuthenticationContext(connection.username, password, connection.domain)) }
            finally { password.fill('\u0000') }
        resources.add(session)
        val share = session.connectShare(connection.share)
        resources.add(share)
        return share as? DiskShare ?: throw RemoteAccessException("Le partage SMB demandé n’est pas un dossier de fichiers.")
    }

    private fun smbPath(connection: RemoteConnection, path: String): String =
        RemotePaths.resolve(connection.rootPath, path).trimStart('/').replace('/', '\\')

    private fun smbList(connection: RemoteConnection, path: String, cancel: RemoteCancellation): List<RemoteEntry> {
        val directoryPath = RemotePaths.normalize(path)
        CloseGroup().use { resources ->
            val share = smbShare(connection, cancel, resources)
            val directory = share.openDirectory(smbPath(connection, directoryPath),
                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            resources.add(directory)
            val result = ArrayList<RemoteEntry>()
            val iterator = directory.iterator()
            var count = 0
            while (iterator.hasNext()) {
                cancel.ensureActive()
                val item = iterator.next()
                if (++count > LIST_LIMIT + 2) throw RemoteAccessException(LIST_TOO_LARGE)
                if (!safeName(item.fileName) || item.fileAttributes and 0x400L != 0L) continue // no reparse links
                val isDirectory = item.fileAttributes and 0x10L != 0L
                result += RemoteEntry(item.fileName, childPath(directoryPath, item.fileName), isDirectory,
                    item.endOfFile, item.lastWriteTime.toEpochMillis())
            }
            if (result.size > LIST_LIMIT) throw RemoteAccessException(LIST_TOO_LARGE)
            return result
        }
    }

    private fun smbOpen(connection: RemoteConnection, path: String, position: Long, cancel: RemoteCancellation): RemoteRead {
        val resources = CloseGroup()
        try {
            val share = smbShare(connection, cancel, resources)
            val file = share.openFile(smbPath(connection, path),
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            resources.add(file)
            val total = file.getFileInformation(FileStandardInformation::class.java).endOfFile
            if (position > total) throw RemoteAccessException("La position demandée dépasse la fin du fichier distant.")
            var cursor = position
            return object : RemoteRead {
                override val length: Long = total
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.ensureActive()
                    if (length == 0) return 0
                    if (cursor >= total) return -1
                    return file.read(buffer, cursor, offset, length.coerceAtMost(64 * 1024)).also {
                        if (it <= 0) throw RemoteAccessException("Le fichier SMB s’est interrompu avant sa fin.")
                        if (it > 0) cursor += it
                        cancel.ensureActive()
                    }
                }
                override fun close() = resources.close()
            }
        } catch (error: Exception) { resources.close(); throw error }
    }

    private fun safeName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun childPath(parent: String, name: String): String = RemotePaths.normalize(parent.trimEnd('/') + "/" + name)

}
