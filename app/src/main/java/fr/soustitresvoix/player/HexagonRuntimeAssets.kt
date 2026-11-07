package fr.soustitresvoix.player

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Installs only the APK's pinned DSP libraries, on an explicitly requested worker operation. */
internal object HexagonRuntimeAssets {
    internal const val SOURCE_REVISION = "8ca4060b7e30e76baaa5670ee1ec1b760b7f8552"
    private const val ASSET_DIRECTORY = "hexagon"
    private const val MAX_MANIFEST_BYTES = 16_384
    private const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024
    private val LIBRARY_NAMES = setOf(73, 75, 79, 81, 85).mapTo(sortedSetOf()) { "libggml-htp-v$it.so" }
    private val HASH = Regex("[a-f0-9]{64}")
    private data class Library(val name: String, val bytes: Long, val sha256: String)

    fun prepare(context: Context, cancelled: () -> Boolean = { false }): File =
        prepare(context.applicationContext.filesDir, { context.applicationContext.assets.open(it) }, cancelled)

    /** Injectable byte source is test-only; production has no user path or download input. */
    @Synchronized
    internal fun prepare(filesDirectory: File, asset: (String) -> InputStream,
        cancelled: () -> Boolean = { false }): File {
        try {
            checkCancelled(cancelled)
            val manifest = asset("$ASSET_DIRECTORY/manifest.json").use { input ->
                val bytes = ByteArray(MAX_MANIFEST_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    checkCancelled(cancelled)
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    require(read > 0)
                    count += read
                }
                require(count in 1..MAX_MANIFEST_BYTES)
                bytes.copyOf(count)
            }
            val libraries = parseManifest(manifest)
            val base = filesDirectory.canonicalFile
            require(base.isDirectory)
            val root = confined(base, File(base, "hexagon-runtime"))
            require(root.isDirectory || root.mkdir())
            ownerDirectory(root)
            // The digest separates rebuilt payloads without overwriting a library
            // that an existing DSP session may still have mapped.
            val destination = confined(root, File(root, digest(manifest)))
            if (destination.exists()) {
                verifyDirectory(destination, libraries, cancelled)
                return destination
            }
            val staging = confined(root, File.createTempFile("stage-", ".tmp", root))
            require(staging.delete() && staging.mkdir())
            try {
                ownerDirectory(staging)
                for (library in libraries) {
                    checkCancelled(cancelled)
                    val target = confined(staging, File(staging, library.name))
                    asset("$ASSET_DIRECTORY/${library.name}").use { input ->
                        FileOutputStream(target).use { output ->
                            verifyBytes(input, library, cancelled, output)
                            output.fd.sync()
                        }
                    }
                    // Native validation also checks ownership and write bits.
                    require(target.setReadable(false, false) && target.setReadable(true, true))
                    require(target.setWritable(false, false) && target.setExecutable(false, false))
                }
                checkCancelled(cancelled)
                if (!staging.renameTo(destination)) {
                    // Another private process may have finished the same payload.
                    // Only accept its complete, reverified directory.
                    require(destination.isDirectory)
                }
                verifyDirectory(destination, libraries, cancelled)
                return destination
            } finally {
                // This path is created here and never contains user-owned files.
                staging.listFiles()?.forEach { it.delete() }
                staging.delete()
            }
        } catch (failure: LocalGgufRuntime.Failure) {
            throw failure
        } catch (_: Exception) {
            val diagnostic = LocalGgufDiagnostic.parse(JSONObject()
                .put("stage", "HEXAGON_DRIVER").put("reason", "SKEL_NOT_ACCESSIBLE").put("elapsedMs", 0)
                .put("openClStatus", JSONObject.NULL).put("directPlatforms", -1).put("icdPlatforms", -1)
                .put("gpuDevices", -1).put("probeCached", false))
            throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BACKEND, diagnostic)
        }
    }

    private fun parseManifest(bytes: ByteArray): List<Library> {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val value = JSONObject(text)
        require(value.keys().asSequence().toSet() == setOf("version", "sourceRevision", "files"))
        require(number(value, "version") == 1L && value.get("sourceRevision") == SOURCE_REVISION)
        val files = value.getJSONArray("files")
        require(files.length() == LIBRARY_NAMES.size)
        val result = (0 until files.length()).map { index ->
            val item = files.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("name", "size", "sha256"))
            val name = item.get("name") as? String ?: error("Invalid library name")
            val size = number(item, "size")
            val hash = item.get("sha256") as? String ?: error("Invalid digest")
            require(name in LIBRARY_NAMES && size in 64..MAX_LIBRARY_BYTES && HASH.matches(hash))
            Library(name, size, hash)
        }
        require(result.map { it.name }.toSet() == LIBRARY_NAMES)
        return result.sortedBy { it.name }
    }

    private fun verifyDirectory(directory: File, libraries: List<Library>, cancelled: () -> Boolean) {
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
        require(directory.list()?.toSet() == LIBRARY_NAMES)
        ownerDirectory(directory)
        for (library in libraries) {
            checkCancelled(cancelled)
            val file = confined(directory, File(directory, library.name))
            require(file.isFile && file.length() == library.bytes)
            FileInputStream(file).use { verifyBytes(it, library, cancelled) }
            require(file.setReadable(false, false) && file.setReadable(true, true))
            require(file.setWritable(false, false) && file.setExecutable(false, false))
        }
    }

    private fun verifyBytes(input: InputStream, library: Library, cancelled: () -> Boolean,
        output: FileOutputStream? = null) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32_768)
        val header = ByteArray(64)
        var count = 0L
        while (true) {
            checkCancelled(cancelled)
            val read = input.read(buffer)
            if (read < 0) break
            require(read > 0 && count + read <= library.bytes)
            if (count < header.size) {
                buffer.copyInto(header, count.toInt(), 0, minOf(read, header.size - count.toInt()))
            }
            hash.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            count += read
        }
        require(count == library.bytes && hex(hash.digest()) == library.sha256)
        // DSP skels are ELF32 little-endian ET_DYN/EM_HEXAGON, not ARM64 JNI libraries.
        require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 1.toByte() && header[5] == 1.toByte() &&
            header[16] == 3.toByte() && header[17] == 0.toByte() &&
            header[18] == 164.toByte() && header[19] == 0.toByte())
    }

    private fun ownerDirectory(directory: File) {
        require(directory.setReadable(false, false) && directory.setWritable(false, false) &&
            directory.setExecutable(false, false))
        require(directory.setReadable(true, true) && directory.setWritable(true, true) &&
            directory.setExecutable(true, true))
    }

    private fun confined(parent: File, file: File): File {
        val expected = file.absoluteFile
        require(expected.parentFile == parent && expected.canonicalFile == expected)
        return expected
    }

    private fun number(value: JSONObject, key: String): Long = when (val number = value.get(key)) {
        is Int -> number.toLong()
        is Long -> number
        else -> error("Invalid integer")
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.CANCELLED)
    }

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
