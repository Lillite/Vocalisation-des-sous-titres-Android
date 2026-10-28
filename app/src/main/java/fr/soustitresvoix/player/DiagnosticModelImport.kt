package fr.soustitresvoix.player

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Copy a user-selected file, never move/delete its source. The original model
 * is replaced only after the entire stream has been copied and its basic GGUF
 * header checked. This is NOT validation of tensors, compatibility or trust. */
internal object DiagnosticModelImport {
    data class Result(val bytes: Long, val formatVersion: Int)

    fun copy(input: InputStream, destination: File, expectedBytes: Long? = null,
        cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Result {
        require(destination.parentFile?.isDirectory == true) { "Répertoire privé indisponible." }
        val pending = File(destination.parentFile, "model-import.part")
        fun checkCancellation() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw InterruptedIOException("Import annulé.")
        }
        try {
            checkCancellation()
            val header = ByteArray(24)
            var headerRead = 0
            while (headerRead < header.size) {
                checkCancellation()
                val count = input.read(header, headerRead, header.size - headerRead)
                if (count < 0) throw IllegalArgumentException("Fichier trop court pour un en-tête GGUF.")
                if (count > 0) headerRead += count
            }
            require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(71, 71, 85, 70))) {
                "Le fichier choisi ne possède pas un en-tête GGUF."
            }
            val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(version in 2..3) { "Version GGUF non prise en charge par cet import." }
            var total = header.size.toLong()
            pending.outputStream().use { output ->
                output.write(header)
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    total += count
                    onProgress(total)
                }
                output.flush()
                output.fd.sync()
            }
            checkCancellation()
            if (expectedBytes != null && expectedBytes >= 0)
                require(total == expectedBytes) { "Copie incomplète : taille différente de celle annoncée par le fournisseur de fichiers." }
            try {
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return Result(total, version)
        } finally { pending.delete() }
    }
}
