package fr.soustitresvoix.player

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.util.Locale

internal data class DocumentTreeRoot(val uri: Uri, val id: String, val name: String)
internal data class DocumentTreeEntry(
    val uri: Uri,
    val id: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long?,
    val virtual: Boolean
) {
    val isDirectory get() = mime == Document.MIME_TYPE_DIR
}
internal data class DocumentTreeListing(val entries: List<DocumentTreeEntry>, val unavailableCount: Int, val loading: Boolean)

/** Browse only the content URIs supplied by a provider under a user-selected tree grant. */
internal class DocumentTreeBrowser(private val resolver: ContentResolver) {
    fun persist(uri: Uri) {
        // Some providers grant only session access. The current tree stays usable.
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
    }

    fun roots(sessionUris: Set<Uri>, cancel: LocalBrowseCancellation): List<DocumentTreeRoot> {
        val persisted = resolver.persistedUriPermissions.asSequence().filter { it.isReadPermission }.map { it.uri }
        return (persisted + sessionUris.asSequence()).distinct().filter(::isTree).map { root(it, cancel) }
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.uri.toString() })).toList()
    }

    fun root(uri: Uri, cancel: LocalBrowseCancellation): DocumentTreeRoot {
        require(isTree(uri)) { "Choisis un dossier avec le sélecteur Android." }
        cancel.ensureActive()
        val id = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val name = runCatching {
            resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null, cancel.signal)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
        cancel.ensureActive()
        return DocumentTreeRoot(uri, id, name ?: "Dossier Android autorisé")
    }

    fun list(root: DocumentTreeRoot, id: String, cancel: LocalBrowseCancellation): DocumentTreeListing {
        cancel.ensureActive()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, id)
        val rows = LinkedHashMap<String, DocumentTreeEntry>()
        var count = 0
        val loading = try {
            val cursor = resolver.query(children, COLUMNS, null, null, null, cancel.signal)
                ?: throw LocalBrowseException("Le fournisseur Android n’a pas renvoyé le dossier. Réautorise-le ou choisis un fichier.")
            cursor.use {
                val idColumn = it.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val sizeColumn = it.getColumnIndex(Document.COLUMN_SIZE)
                val flagsColumn = it.getColumnIndex(Document.COLUMN_FLAGS)
                if (idColumn < 0) throw LocalBrowseException("Ce fournisseur Android ne permet pas de parcourir le dossier. Choisis un fichier directement.")
                while (it.moveToNext()) {
                    cancel.ensureActive()
                    if (++count > LocalFileBrowser.MAX_ENTRIES) {
                        throw LocalBrowseException("Ce dossier contient plus de ${LocalFileBrowser.MAX_ENTRIES} éléments. Choisis un sous-dossier dans Android.")
                    }
                    val childId = it.getString(idColumn)?.takeIf { value -> value.isNotBlank() && value != id } ?: continue
                    val name = if (nameColumn >= 0) it.getString(nameColumn)?.takeIf(String::isNotBlank) else null
                    val mime = if (mimeColumn >= 0) it.getString(mimeColumn) else null
                    val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn).takeIf { value -> value >= 0 } else null
                    val virtual = flagsColumn >= 0 && it.getInt(flagsColumn) and Document.FLAG_VIRTUAL_DOCUMENT != 0
                    rows.putIfAbsent(childId, DocumentTreeEntry(DocumentsContract.buildDocumentUriUsingTree(root.uri, childId),
                        childId, name ?: "Élément sans nom", mime, size, virtual))
                }
                it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)
            }
        } catch (_: SecurityException) {
            throw LocalBrowseException("Android refuse l’accès à ce dossier. Réautorise-le avec le sélecteur Android.")
        }
        cancel.ensureActive()
        return DocumentTreeListing(rows.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }, { it.name }, { it.id })),
            count - rows.size, loading)
    }

    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
        fun isTree(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrEmpty() &&
            uri.pathSegments.size == 2 && DocumentsContract.isTreeUri(uri)
    }
}
