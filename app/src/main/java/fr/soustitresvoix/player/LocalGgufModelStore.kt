package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** An already fingerprinted, application-owned copy. File paths never form cache identities. */
class LocalGgufModel internal constructor(
    internal val file: File,
    val sha256: String,
    val bytes: Long,
    internal val modifiedMillis: Long
) {
    val identity: String get() = "local-gguf-v1-$sha256"
    override fun toString(): String = "LocalGgufModel(bytes=$bytes, identity=$identity)"
}

class LocalGgufModelBusyException : IOException(
    "Le modèle local est utilisé par un autre traitement. Attends sa fin ou annule-le avant de remplacer le modèle."
)

/**
 * The diagnostic and real translation share one private copy. Its SHA-256 is
 * computed while importing, or explicitly on a worker when adopting a legacy
 * diagnostic copy. Reading current() never reads the model's weights.
 *
 * Leases are thread-independent because cancelling an Android task can occur
 * on the UI thread while the task itself owns a worker. They prevent a model
 * replacement/deletion until every current reader has finished.
 */
class LocalGgufModelStore private constructor(
    private val directory: File,
    private val context: Context?
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "local-diagnostic"), context.applicationContext)
    internal constructor(directory: File) : this(directory, null)

    internal val modelFile = File(directory, "model.gguf")
    private val metadata = AtomicFile(File(directory, "model.sha256"))
    private val gate = gateFor(directory.canonicalPath)

    fun exists(): Boolean = modelFile.isFile

    fun current(): LocalGgufModel? = synchronized(gate) {
        if (gate.writer) null else readPrepared()
    }

    /** Returns immediately with BUSY rather than blocking the Android main thread. */
    fun acquireMutationLease(): AutoCloseable = synchronized(gate) {
        if (gate.writer || gate.readers != 0) throw LocalGgufModelBusyException()
        gate.writer = true
        Lease { synchronized(gate) { gate.writer = false } }
    }

    fun acquire(model: LocalGgufModel): AutoCloseable = synchronized(gate) {
        if (gate.writer) throw LocalGgufModelBusyException()
        val prepared = readPrepared()
        if (prepared == null || prepared.sha256 != model.sha256 || prepared.bytes != model.bytes ||
            prepared.file != model.file)
            throw IOException("Le modèle sélectionné a changé. Prépare le modèle actuel avant de reprendre.")
        gate.readers++
        Lease { synchronized(gate) { gate.readers-- } }
    }

    /** Called by a diagnostic mutation while its mutation lease is held. */
    fun invalidatePrepared() = synchronized(gate) {
        check(gate.writer) { "Une modification du modèle exige un accès exclusif." }
        metadata.delete()
    }

    fun prepareCurrent(cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): LocalGgufModel =
        prepareExisting(cancelled, onProgress)

    fun prepareExisting(cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): LocalGgufModel {
        acquireMutationLease().use {
            checkCancellation(cancelled)
            readPrepared()?.let { return it }
            if (!modelFile.isFile) throw IOException("Aucun modèle GGUF privé n’est disponible.")
            val size = modelFile.length()
            val modified = modelFile.lastModified()
            val digest = MessageDigest.getInstance("SHA-256")
            modelFile.inputStream().use { input ->
                val header = ByteArray(24)
                var read = 0
                while (read < header.size) {
                    checkCancellation(cancelled)
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) throw IOException("Le modèle privé est incomplet.")
                    if (count > 0) read += count
                }
                requireHeader(header)
                digest.update(header)
                var total = header.size.toLong()
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkCancellation(cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    total += count
                    onProgress(total)
                }
                if (total != size || modelFile.length() != size || modelFile.lastModified() != modified)
                    throw IOException("Le modèle a changé pendant sa préparation.")
            }
            checkCancellation(cancelled)
            return LocalGgufModel(modelFile, hex(digest.digest()), size, modified).also(::writePrepared)
        }
    }

    fun importModel(uri: Uri, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): LocalGgufModel {
        val resolver = context?.contentResolver ?: throw IOException("Sélecteur de fichiers indisponible.")
        if (uri.scheme != "content") throw IOException("Choisis le modèle avec le sélecteur de fichiers Android.")
        val expected = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()
        return (resolver.openInputStream(uri) ?: throw IOException("Le fichier choisi n’est pas accessible.")).use {
            importModel(it, expected, cancelled, onProgress)
        }
    }

    internal fun importModel(input: InputStream, expectedBytes: Long? = null,
        cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): LocalGgufModel {
        acquireMutationLease().use {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Le stockage privé est indisponible.")
            val orphan = File(directory, "model-import.part")
            if (orphan.exists() && !orphan.delete())
                throw IOException("La copie incomplète du modèle ne peut pas être nettoyée.")
            if (expectedBytes != null && expectedBytes > directory.usableSpace)
                throw IOException("Espace insuffisant pour conserver l’ancien modèle pendant la copie.")
            val previous = readPrepared()
            // Invalid before replacing either file: process death can never bind
            // a new set of weights to the previous model's fingerprint.
            metadata.delete()
            var replaced = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val result = DigestInputStream(input, digest).use { hashing ->
                    DiagnosticModelImport.copy(hashing, modelFile, expectedBytes, cancelled, onProgress)
                }
                replaced = true
                return LocalGgufModel(modelFile, hex(digest.digest()), result.bytes, modelFile.lastModified())
                    .also(::writePrepared)
            } catch (failure: Exception) {
                // An unsuccessful stream copy retains the previous completed
                // model. Restore its lightweight identity when still applicable.
                if (!replaced && previous != null && modelFile.length() == previous.bytes &&
                    modelFile.lastModified() == previous.modifiedMillis)
                    runCatching { writePrepared(previous) }
                throw failure
            }
        }
    }

    fun remove(): Boolean {
        acquireMutationLease().use {
            val removed = !modelFile.exists() || modelFile.delete()
            if (removed) metadata.delete()
            val pending = File(directory, "model-import.part")
            val pendingRemoved = !pending.exists() || pending.delete()
            return removed && pendingRemoved
        }
    }

    private fun readPrepared(): LocalGgufModel? = try {
        if (!modelFile.isFile || metadata.baseFile.length() > 4096) null
        else metadata.openRead().use { stream ->
            DataInputStream(stream).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val digest = input.readUTF()
                val bytes = input.readLong()
                val modified = input.readLong()
                if (!SHA256.matches(digest) || bytes < 24 || bytes != modelFile.length() ||
                    modified != modelFile.lastModified() || input.read() != -1) null
                else LocalGgufModel(modelFile, digest, bytes, modified)
            }
        }
    } catch (_: Exception) { null }

    private fun writePrepared(model: LocalGgufModel) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = metadata.startWrite()
            DataOutputStream(stream).also {
                it.writeInt(MAGIC); it.writeInt(VERSION); it.writeUTF(model.sha256)
                it.writeLong(model.bytes); it.writeLong(model.modifiedMillis); it.flush()
            }
            metadata.finishWrite(stream)
        } catch (failure: Exception) {
            metadata.failWrite(stream)
            throw IOException("L’empreinte du modèle n’a pas pu être enregistrée.")
        }
    }

    private class Lease(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    private class Gate { var readers = 0; var writer = false }
    companion object {
        private const val MAGIC = 0x4c474746
        private const val VERSION = 1
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val gates = HashMap<String, Gate>()
        private fun gateFor(key: String): Gate = synchronized(gates) { gates.getOrPut(key) { Gate() } }
        private fun checkCancellation(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Import du modèle annulé.")
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun requireHeader(header: ByteArray) {
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(71, 71, 85, 70)) ||
                ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int !in 2..3)
                throw IOException("Le modèle privé ne possède pas un en-tête GGUF pris en charge.")
        }
    }
}
