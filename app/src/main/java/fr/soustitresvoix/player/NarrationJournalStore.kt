package fr.soustitresvoix.player

import android.content.Context
import android.util.AtomicFile
import fr.soustitresvoix.core.NarrationLedger
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Durable storage for the text-free NarrationLedger format. contextId is the
 * ledger's currentContextHash, not a URI, filename or subtitle string. Keep I/O
 * off the player/UI thread; callers may coalesce position saves. */
class NarrationJournalStore(context: Context) {
    private val directory = File(context.filesDir, "narration-journals-v1")
    // An old Activity writer and a new owner may briefly coexist. AtomicFile
    // replacement/read recovery must share a lock across store instances.
    private val diskLock = synchronized(DIRECTORY_LOCKS) {
        DIRECTORY_LOCKS.getOrPut(directory.absolutePath) { Any() }
    }
    var lastWarning: String? = null
        private set

    fun read(contextId: String): String? = synchronized(diskLock) {
        lastWarning = null
        val file = file(contextId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            val bytes = AtomicFile(file).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES + 256)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val parts = bytes.toString(Charsets.UTF_8).split('\n', limit = 4)
            require(parts.size == 4 && parts[0] == HEADER && parts[1] == contextId)
            require(parts[2] == digest(parts[3]))
            validate(contextId, parts[3])
            parts[3]
        } catch (_: Exception) {
            lastWarning = "Le bilan de narration mémorisé est illisible ou incompatible ; la reprise reste en pause."
            null
        }
    }

    fun save(contextId: String, encoded: String) = synchronized(diskLock) {
        validate(contextId, encoded)
        check(directory.exists() || directory.mkdirs()) { "Impossible de créer le dossier des bilans de narration." }
        val destination = file(contextId)
        val bytes = "$HEADER\n$contextId\n${digest(encoded)}\n$encoded".toByteArray(Charsets.UTF_8)
        val others = directory.listFiles().orEmpty().filter { it.name.matches(FILE_NAME) && it != destination }
        require(others.size < MAX_JOURNALS) { "Trop de bilans de narration conservés." }
        check(others.sumOf { it.length() } + bytes.size <= MAX_TOTAL_BYTES) {
            "Le stockage des bilans de narration est plein. Supprime un ancien bilan dans Stockage."
        }
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream); throw error
        }
    }

    fun remove(contextId: String) = synchronized(diskLock) { AtomicFile(file(contextId)).delete() }

    private fun file(contextId: String): File {
        require(contextId.matches(HASH)) { "Identité du bilan de narration invalide." }
        return File(directory, "$contextId.journal")
    }

    companion object {
        private val DIRECTORY_LOCKS = HashMap<String, Any>()
        const val MAX_BYTES = 24_000_000
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_JOURNALS = 256
        private const val HEADER = "NARRATION-JOURNAL-STORE-1"
        private val HASH = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex("[0-9a-f]{64}\\.journal")

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Shape validation makes accidentally passing raw subtitle text an
         * explicit refusal. Ledger.restoreMetadata remains the semantic owner. */
        private fun validate(contextId: String, encoded: String) {
            require(contextId.matches(HASH))
            require(encoded.length <= MAX_BYTES)
            val lines = encoded.lineSequence().iterator()
            require(lines.hasNext() && lines.next() == "NARRATION-LEDGER-1")
            require(lines.hasNext() && lines.next() == contextId)
            require(lines.hasNext())
            val header = lines.next().split('\t')
            require(header.size == 2)
            val generation = header[0].toLong().also { require(it >= 0) }
            require(header[1] in setOf("0", "1"))
            var count = 0
            val identities = HashSet<String>()
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.isEmpty()) continue
                require(++count <= 100_000)
                val fields = line.split('\t')
                require(fields.size == 10 && fields[0].matches(HASH) && identities.add(fields[0]))
                fun time(index: Int) = fields[index].takeUnless { it == "-" }?.toLong()
                val start = time(1); val end = time(2)
                require(start == null || end == null || end >= start)
                NarrationLedger.State.valueOf(fields[3])
                for (index in 4..7) require(fields[index].toInt() >= 0)
                require(fields[8].toLong() in 0..generation && fields[9].toLong() >= 0)
            }
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        }
    }
}
