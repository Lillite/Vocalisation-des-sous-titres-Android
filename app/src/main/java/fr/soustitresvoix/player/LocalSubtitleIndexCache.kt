package fr.soustitresvoix.player

import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SpeechTools
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Reuse finite local text indices, with source validation independent of a saved title. */
@UnstableApi
internal class LocalSubtitleIndexCache(private val directory: File) {
    fun identity(media: SavedMedia): String? = runCatching {
        val subtitle = media.subtitlePath?.takeIf { media.subtitleMime != null }?.let(::File)
        val uri = Uri.parse(media.uri)
        val file = (subtitle ?: uri.path?.takeIf { uri.scheme == "file" }?.let(::File))
            ?.canonicalFile ?: return null
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        if (!attributes.isRegularFile || attributes.size() <= 0L) return null
        if (subtitle != null && attributes.size() > 8L * 1024 * 1024) return null
        val digest = MessageDigest.getInstance("SHA-256")
        if (subtitle != null) {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 8L * 1024 * 1024)
                    digest.update(buffer, 0, count)
                }
                require(total == attributes.size())
            }
        } else {
            // File identity, high precision modification time and samples at
            // separated offsets reject replacement without rereading the film.
            if (attributes.fileKey() == null || attributes.lastModifiedTime().toMillis() <= 0) return null
            RandomAccessFile(file, "r").use { source ->
                val length = source.length()
                require(length == attributes.size())
                val buffer = ByteArray(minOf(64 * 1024L, length).toInt())
                for (offset in listOf(0L, (length - buffer.size) / 2, length - buffer.size).distinct()) {
                    source.seek(offset)
                    source.readFully(buffer)
                    digest.update(buffer)
                }
            }
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        require(attributes.fileKey() == after.fileKey() && attributes.size() == after.size() &&
            attributes.lastModifiedTime() == after.lastModifiedTime())
        val content = digest.digest().joinToString("") { "%02x".format(it) }
        SpeechTools.key("local-subtitle-index-v1",
            listOf(file.path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(),
                media.subtitleMime, content).joinToString("\n"))
    }.getOrNull()

    fun read(key: String): CueAheadIndex? = synchronized(lock) {
        runCatching {
            val entry = file(key)
            require(entry.baseFile.length() in 1..MAX_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            entry.openRead().buffered().use { stream ->
                val checked = DigestInputStream(stream, digest)
                val input = DataInputStream(checked)
                require(input.readInt() == FORMAT && input.readUTF() == key)
                val tracks = List(input.readInt().also { require(it in 0..1024) }) { input.readUTF() }.toSet()
                var characters = 0
                val items = List(input.readInt().also { require(it in 0..100_000) }) {
                    val track = input.readOptional()
                    val start = input.readLong()
                    val end = input.readLong()
                    val ordinal = input.readInt()
                    val token = input.readOptional()
                    val bytes = input.readInt().also { require(it in 1..96_000) }
                    val raw = ByteArray(bytes).also(input::readFully).toString(Charsets.UTF_8)
                    characters += raw.length
                    require(characters <= 12_000_000 && ordinal >= 0)
                    CueAheadIndex.Item(track, start, end, raw, ordinal, token)
                }
                checked.on(false)
                val expected = ByteArray(32).also(input::readFully)
                require(MessageDigest.isEqual(expected, digest.digest()) && input.read() == -1)
                CueAheadIndex.restored(items, tracks).also { entry.baseFile.setLastModified(System.currentTimeMillis()) }
            }
        }.getOrNull()
    }

    fun write(key: String, index: CueAheadIndex) = synchronized(lock) {
        if (index.truncated || index.count == 0) return@synchronized
        runCatching {
            val entry = file(key)
            directory.mkdirs()
            val raw = entry.startWrite()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val checked = DigestOutputStream(raw, digest)
                val output = DataOutputStream(checked.buffered())
                output.writeInt(FORMAT)
                output.writeUTF(key)
                val tracks = index.indexedTrackIds()
                output.writeInt(tracks.size)
                tracks.sorted().forEach(output::writeUTF)
                val items = index.allIndexedItems()
                output.writeInt(items.size)
                items.forEach { item ->
                    output.writeOptional(item.track)
                    output.writeLong(item.startUs)
                    output.writeLong(item.endUs)
                    output.writeInt(item.ordinal)
                    output.writeOptional(item.sourceToken)
                    val bytes = item.raw.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                output.flush()
                require(raw.channel.size() + 32 <= MAX_BYTES)
                checked.on(false)
                output.write(digest.digest())
                output.flush()
                entry.finishWrite(raw)
            } catch (error: Exception) {
                entry.failWrite(raw)
                throw error
            }
            directory.listFiles { candidate -> candidate.extension == "index" }.orEmpty()
                .sortedByDescending { it.lastModified() }.drop(4).forEach { AtomicFile(it).delete() }
        }
        Unit
    }

    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return AtomicFile(File(directory, "$key.index"))
    }
    private fun DataInputStream.readOptional() = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
    private companion object {
        val lock = Any()
        const val FORMAT = 0x53564931
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
