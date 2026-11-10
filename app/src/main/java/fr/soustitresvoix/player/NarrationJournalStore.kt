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
    internal data class PairState(val revision: String, val metadata: String,
        val pending: List<NarrationLedger.SegmentResume>)
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
            val encoded = readFile(file)
            if (encoded.startsWith("$PAIR_HEADER\n")) decodePair(contextId, encoded).metadata
            else {
                val parts = encoded.split('\n', limit = 4)
                require(parts.size == 4 && parts[0] == HEADER && parts[1] == contextId)
                require(parts[2] == digest(parts[3]))
                validate(contextId, parts[3])
                parts[3]
            }
        } catch (_: Exception) {
            lastWarning = "Le bilan de narration mémorisé est illisible ou incompatible ; la reprise reste en pause."
            null
        }
    }

    /** A session record is the commit pointer. After a crash it may refer to
     * the staged slot or the promoted slot, but never to a different revision. */
    internal fun readPaired(contextId: String, revision: String): PairState? = synchronized(diskLock) {
        require(revision.matches(HASH))
        val final = file(contextId)
        val stage = stagedFile(contextId)
        readPair(contextId, final, revision) ?: readPair(contextId, stage, revision)?.also { stage.renameTo(final) }
    }

    internal fun savePaired(contextId: String, value: PairState, preserveRevision: String?, commitSession: () -> Unit) =
        synchronized(diskLock) {
            require(value.revision.matches(HASH))
            validate(contextId, value.metadata)
            require(value.pending.size <= MAX_PENDING)
            val final = file(contextId)
            val stage = stagedFile(contextId)
            check(directory.exists() || directory.mkdirs()) { "Impossible de créer le dossier des bilans de narration." }
            // A preceding process may have committed its session record before
            // promotion. Preserve that valid slot before staging a new one.
            if (preserveRevision != null && readPair(contextId, stage, preserveRevision) != null) {
                check(stage.renameTo(final)) { "La reprise précédente ne peut pas être promue." }
            }
            val body = buildString {
                append(value.metadata.length).append('\n').append(value.metadata)
                for (part in value.pending) {
                    validateSegment(part)
                    append(part.identityHash).append('\t').append(part.ordinal).append('\t')
                        .append(part.count).append('\t').append(part.textHash).append('\n')
                }
            }
            val bytes = "$PAIR_HEADER\n$contextId\n${value.revision}\n${digest(body)}\n$body".toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PAIRED_BYTES)
            checkSpace(final, bytes.size)
            writeAtomic(stage, bytes)
            try {
                commitSession()
            } catch (error: Exception) {
                AtomicFile(stage).delete()
                throw error
            }
            // The transaction already committed. If the rename fails, the
            // matching stage remains available through readPaired.
            stage.renameTo(final)
            Unit
        }

    fun save(contextId: String, encoded: String) = synchronized(diskLock) {
        validate(contextId, encoded)
        check(directory.exists() || directory.mkdirs()) { "Impossible de créer le dossier des bilans de narration." }
        val destination = file(contextId)
        val bytes = "$HEADER\n$contextId\n${digest(encoded)}\n$encoded".toByteArray(Charsets.UTF_8)
        checkSpace(destination, bytes.size)
        writeAtomic(destination, bytes)
    }

    private fun checkSpace(destination: File, bytes: Int) {
        val others = directory.listFiles().orEmpty().filter {
            it.name.matches(FILE_NAME) && it.name.removeSuffix(".staged") != destination.name
        }
        require(others.distinctBy { it.name.removeSuffix(".staged") }.size < MAX_JOURNALS) { "Trop de bilans de narration conservés." }
        check(others.sumOf { it.length() } + bytes <= MAX_TOTAL_BYTES) {
            "Le stockage des bilans de narration est plein. Supprime un ancien bilan dans Stockage."
        }
    }

    private fun writeAtomic(destination: File, bytes: ByteArray) {
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream); throw error
        }
    }

    fun remove(contextId: String) = synchronized(diskLock) {
        AtomicFile(file(contextId)).delete(); AtomicFile(stagedFile(contextId)).delete()
    }

    private fun file(contextId: String): File {
        require(contextId.matches(HASH)) { "Identité du bilan de narration invalide." }
        return File(directory, "$contextId.journal")
    }

    private fun stagedFile(contextId: String) = File(file(contextId).path + ".staged")

    private fun readFile(file: File): String = AtomicFile(file).openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_PAIRED_BYTES)
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun readPair(contextId: String, file: File, revision: String): PairState? = try {
        val encoded = readFile(file)
        val header = encoded.split('\n', limit = 4)
        if (header.size != 4 || header[0] != PAIR_HEADER || header[1] != contextId || header[2] != revision) null
        else decodePair(contextId, encoded)
    } catch (_: Exception) { null }

    private fun decodePair(contextId: String, encoded: String): PairState {
        val parts = encoded.split('\n', limit = 6)
        require(parts.size == 6 && parts[0] == PAIR_HEADER && parts[1] == contextId && parts[2].matches(HASH))
        val size = parts[4].toInt()
        require(size in 1..MAX_BYTES && parts[5].length >= size && parts[3] == digest("${parts[4]}\n${parts[5]}"))
        val metadata = parts[5].substring(0, size)
        validate(contextId, metadata)
        val pending = parts[5].substring(size).lineSequence().filter { it.isNotEmpty() }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 4)
            NarrationLedger.SegmentResume(fields[0], fields[1].toInt(), fields[2].toInt(), fields[3]).also(::validateSegment)
        }.take(MAX_PENDING + 1).toList()
        require(pending.size <= MAX_PENDING)
        return PairState(parts[2], metadata, pending)
    }

    companion object {
        private val DIRECTORY_LOCKS = HashMap<String, Any>()
        const val MAX_BYTES = 24_000_000
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_JOURNALS = 256
        private const val HEADER = "NARRATION-JOURNAL-STORE-1"
        private const val PAIR_HEADER = "NARRATION-JOURNAL-PAIR-1"
        private const val MAX_PENDING = 200_000
        private const val MAX_PAIRED_BYTES = MAX_BYTES + MAX_PENDING * 160 + 512
        private val HASH = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex("[0-9a-f]{64}\\.journal(?:\\.staged)?")

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun validateSegment(part: NarrationLedger.SegmentResume) {
            require(part.identityHash.matches(HASH) && part.textHash.matches(HASH) &&
                part.count > 0 && part.ordinal in 0 until part.count)
        }

        /** Shape validation makes accidentally passing raw subtitle text an
         * explicit refusal. Ledger.restoreMetadata remains the semantic owner. */
        private fun validate(contextId: String, encoded: String) {
            require(contextId.matches(HASH))
            require(encoded.length <= MAX_BYTES)
            val lines = encoded.lineSequence().iterator()
            require(lines.hasNext())
            val version = lines.next()
            require(version in setOf("NARRATION-LEDGER-1", "NARRATION-LEDGER-2", "NARRATION-LEDGER-3"))
            val hasSegments = version == "NARRATION-LEDGER-3"
            require(lines.hasNext() && lines.next() == contextId)
            require(lines.hasNext())
            val header = lines.next().split('\t')
            require(header.size == if (version == "NARRATION-LEDGER-1") 2 else 4)
            val generation = header[0].toLong().also { require(it >= 0) }
            require(header[1] in setOf("0", "1"))
            val pointers = if (header.size == 4) header.drop(2).map { value ->
                value.takeUnless { it == "-" }?.also { require(it.matches(HASH)) }
            } else emptyList()
            var count = 0
            val identities = HashSet<String>()
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.isEmpty()) continue
                require(++count <= 100_000)
                val fields = line.split('\t')
                require(fields.size == if (hasSegments) 13 else 10)
                require(fields[0].matches(HASH) && identities.add(fields[0]))
                fun time(index: Int) = fields[index].takeUnless { it == "-" }?.toLong()
                val start = time(1); val end = time(2)
                require(start == null || end == null || end >= start)
                NarrationLedger.State.valueOf(fields[3])
                for (index in 4..7) require(fields[index].toInt() >= 0)
                require(fields[8].toLong() in 0..generation && fields[9].toLong() >= 0)
                if (hasSegments) {
                    val completedGeneration = time(10)
                    require(completedGeneration == null || (completedGeneration in 0..fields[8].toLong() && fields[5].toInt() > 0))
                    require(fields[11].toInt() in 0..fields[12].toInt())
                }
            }
            pointers.filterNotNull().forEach { require(it in identities) }
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        }
    }
}
