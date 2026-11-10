package fr.soustitresvoix.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.storage.StorageManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalFileAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        // The no-argument framework overload indexes its first external
        // directory without checking that one exists. Query mounted volumes
        // explicitly so an available card can be used even without the primary.
        try {
            val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
            volumes.any { volume ->
                val directory = volume.directory
                directory != null && runCatching { Environment.isExternalStorageManager(directory) }.getOrDefault(false)
            }
        } catch (_: RuntimeException) { false }
    } else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** This is shared storage access; Android's private application directories remain excluded. */
    fun permissionExplanation(sdk: Int, granted: Boolean): String = when {
        granted -> "Accès au stockage partagé autorisé. Les dossiers privés des autres applications, dont Android/data, restent exclus."
        sdk >= 30 -> "Pour parcourir le stockage partagé ici, autorise « Accès à tous les fichiers » dans les réglages Android. Le sélecteur Android reste disponible sans cette autorisation."
        else -> "Pour parcourir le stockage partagé ici, autorise la lecture des fichiers. Le sélecteur Android reste disponible sans cette autorisation."
    }
}

internal class LocalBrowseCancellation : Closeable {
    private val cancelled = AtomicBoolean()
    val signal = CancellationSignal()
    fun ensureActive() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Navigation annulée.") }
    override fun close() { if (cancelled.compareAndSet(false, true)) signal.cancel() }
}

/** Only deliberately written messages may be shown from the worker. */
internal class LocalBrowseException(message: String) : IOException(message)

internal data class LocalStorageRoot(val name: String, val file: File)
internal data class LocalFileEntry(val name: String, val file: File, val isDirectory: Boolean, val sizeBytes: Long?) {
    val uri: Uri get() = Uri.fromFile(file)
}
internal data class LocalFilePage(val entries: List<LocalFileEntry>, val total: Int, val page: Int, val pageSize: Int, val excludedCount: Int = 0) {
    val hasNext: Boolean get() = (page + 1) * pageSize < total
}
internal data class LocalFileListing(val entries: List<LocalFileEntry>, val excludedCount: Int)

/** Lists one level only, off the UI thread. Memory and traversal are explicitly bounded. */
internal class LocalFileBrowser {
    @Suppress("DEPRECATION")
    fun roots(context: Context, cancel: LocalBrowseCancellation): List<LocalStorageRoot> {
        cancel.ensureActive()
        val candidates = mutableListOf(LocalStorageRoot("Stockage partagé", Environment.getExternalStorageDirectory()))
        val manager = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) manager?.storageVolumes?.forEach { volume ->
            cancel.ensureActive()
            volume.directory?.let { candidates += LocalStorageRoot(volume.getDescription(context), it) }
        } else context.getExternalFilesDirs(null).filterNotNull().forEach { files ->
            // getExternalFilesDirs exposes mounted volumes on Android 8–10 without probing arbitrary paths.
            val suffix = "/Android/data/${context.packageName}/files"
            if (files.absolutePath.endsWith(suffix)) candidates += LocalStorageRoot(
                if (candidates.first().file.absolutePath == files.absolutePath.removeSuffix(suffix)) "Stockage partagé" else "Carte SD / stockage externe",
                File(files.absolutePath.removeSuffix(suffix)))
        }
        val seen = HashSet<String>()
        return candidates.filter { root ->
            cancel.ensureActive()
            root.file.isDirectory && runCatching { seen.add(root.file.canonicalPath) }.getOrDefault(false)
        }
    }

    fun list(root: File, directory: File, page: Int = 0, pageSize: Int = 100,
             cancel: LocalBrowseCancellation = LocalBrowseCancellation()): LocalFilePage {
        require(page >= 0 && pageSize in 1..200 && page.toLong() * pageSize < MAX_ENTRIES) { "Page de dossier invalide." }
        val listing = scan(root, directory, cancel)
        return LocalFilePage(listing.entries.drop(page * pageSize).take(pageSize), listing.entries.size, page, pageSize, listing.excludedCount)
    }

    /** A bounded snapshot is reused when paging or filtering a directory. */
    fun scan(root: File, directory: File, cancel: LocalBrowseCancellation): LocalFileListing {
        cancel.ensureActive()
        val canonicalRoot = root.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        require(isInside(canonicalRoot, canonicalDirectory)) { "Ce dossier sort du stockage choisi." }
        require(!isPrivateAndroidDirectory(canonicalRoot, canonicalDirectory)) { "Android protège ce dossier privé. Choisis un fichier avec le sélecteur Android." }
        if (!canonicalDirectory.isDirectory || !canonicalDirectory.canRead()) throw LocalBrowseException("Ce dossier est inaccessible. Vérifie l’autorisation ou utilise le sélecteur Android.")
        val entries = ArrayList<LocalFileEntry>()
        var count = 0
        try {
            Files.newDirectoryStream(canonicalDirectory.toPath()).use { paths -> paths.forEach { path ->
                cancel.ensureActive()
                count++
                if (count > MAX_ENTRIES) throw LocalBrowseException("Ce dossier contient plus de $MAX_ENTRIES éléments. Ouvre un sous-dossier avec le sélecteur Android ; aucun résultat partiel n’est affiché.")
                val file = path.toFile()
                val target = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                if (!isInside(canonicalRoot, target) || isPrivateAndroidDirectory(canonicalRoot, target)) return@forEach
                val directoryEntry = file.isDirectory
                entries += LocalFileEntry(file.name, file, directoryEntry, if (directoryEntry) null else file.length())
            } }
        } catch (_: SecurityException) { throw LocalBrowseException("Android refuse l’accès à ce dossier. Utilise le sélecteur Android.") }
        cancel.ensureActive()
        entries.sortWith(ENTRY_ORDER)
        return LocalFileListing(entries, count - entries.size)
    }

    /** Recheck the selected path because the directory may have changed since listing. */
    fun readableFile(root: File, file: File, cancel: LocalBrowseCancellation): Uri {
        cancel.ensureActive()
        val volume = root.canonicalFile
        val target = file.canonicalFile
        if (!isInside(volume, target) || isPrivateAndroidDirectory(volume, target)) {
            throw LocalBrowseException("Ce fichier sort du stockage autorisé ou appartient à un dossier privé Android.")
        }
        if (!target.isFile || !target.canRead()) throw LocalBrowseException("Ce fichier n’est plus accessible. Actualise le dossier ou utilise le sélecteur Android.")
        return Uri.fromFile(target)
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        internal val ENTRY_ORDER = compareBy<LocalFileEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }, { it.name }, { it.file.absolutePath })
        internal fun isInside(root: File, file: File): Boolean = file == root || file.toPath().startsWith(root.toPath())
        private fun isPrivateAndroidDirectory(root: File, file: File): Boolean {
            val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            return relative == "Android/data" || relative.startsWith("Android/data/") || relative == "Android/obb" || relative.startsWith("Android/obb/")
        }
    }
}
