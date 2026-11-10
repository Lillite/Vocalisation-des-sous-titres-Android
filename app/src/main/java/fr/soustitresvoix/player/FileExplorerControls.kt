package fr.soustitresvoix.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** In-app shared-storage and server navigation. All listing and credential I/O run on a worker. */
internal class FileExplorerControls(
    private val activity: ComponentActivity,
    private val onMedia: (SavedMedia) -> Unit,
    private val onSubtitle: (Uri, String) -> Unit,
    private val chooseSystemFile: () -> Unit,
    private val requestLegacyPermission: () -> Unit,
    private val onDirectUrlDeleted: (Uri) -> Unit = {},
    private val onConnectionDeleted: (String) -> Unit = {},
    private val onConnectionUpdated: (String) -> Unit = {},
    private val onDirectTitleChanged: (Uri, String) -> Unit = { _, _ -> },
    private val store: RemoteConnectionStore = RemoteConnectionStore(activity.applicationContext)
) : AutoCloseable {
    private val themed = activity
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val remote = RemoteBrowser(store)
    private val local = LocalFileBrowser()
    private val documents = DocumentTreeBrowser(activity.contentResolver)
    private var future: Future<*>? = null
    private var remoteCancel: RemoteCancellation? = null
    private var localCancel: LocalBrowseCancellation? = null
    private var generation = 0L
    private var closed = false
    private var busy = false
    private var permission = LocalFileAccess.granted(activity)
    private var servers = false
    private var roots = emptyList<LocalStorageRoot>()
    private var connections = emptyList<RemoteConnection>()
    private var root: LocalStorageRoot? = null
    private var directory: File? = null
    private var localListing: LocalFileListing? = null
    private val sessionTrees = linkedSetOf<Uri>()
    private var documentRoots = emptyList<DocumentTreeRoot>()
    private var documentRoot: DocumentTreeRoot? = null
    private var documentTrail = emptyList<DocumentTreeEntry>()
    private var documentListing: DocumentTreeListing? = null
    private var pageIndex = 0
    private var connectionId: String? = null
    private var remotePath = "/"
    private var remoteEntries = emptyList<RemoteEntry>()
    private var remotePage = 0
    private var remoteLoaded = false
    private var nameFilter = ""
    private var status = "Choisis un stockage ou un serveur."
    private var column: LinearLayout? = null
    private var viewport: ScrollView? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private val treePicker = activity.activityResultRegistry.register("explorer-document-tree", ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (!closed && uri != null && DocumentTreeBrowser.isTree(uri)) {
            sessionTrees += uri
            servers = false; root = null; directory = null; localListing = null
            changeDirectory()
            loadDocumentFolder(DocumentTreeRoot(uri, DocumentsContract.getTreeDocumentId(uri), "Dossier Android autorisé"), emptyList(), persist = true)
        }
    }

    fun createView(): View {
        val previousScroll = viewport?.scrollY ?: 0
        val body = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        column = body
        val scroll = ScrollView(themed).apply { isFillViewport = true; addView(body) }
        viewport = scroll
        render()
        if (roots.isEmpty() && documentRoots.isEmpty() && documentRoot == null && !busy && !servers) refresh()
        if (previousScroll > 0) scroll.post { if (viewport === scroll) scroll.scrollTo(0, previousScroll) }
        return scroll
    }

    fun onResume() {
        if (closed) return
        val current = LocalFileAccess.granted(activity)
        if (permission != current) { permission = current; refresh() }
    }

    /** A UI rebuild can ask for a refresh without creating another browser instance. */
    fun refresh() {
        if (closed) return
        if (servers) {
            val id = connectionId
            if (id == null) loadConnections() else loadRemote(id, remotePath)
        } else documentRoot?.let { loadDocumentFolder(it, documentTrail) } ?: loadLocal()
    }

    private fun render() {
        val body = column ?: return
        body.removeAllViews()
        body.addView(text("Explorer", 25f))
        body.addView(text("Parcours les fichiers du téléphone, une carte SD ou tes serveurs. Les vidéos sont lues sans copie intégrale préalable."))
        val tabs = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(button("Téléphone", !servers) { switchSection(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button("Serveurs", servers) { switchSection(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(tabs)
        body.addView(button("Choisir avec Android") { chooseSystemFile() })
        body.addView(text(status).apply {
            tag = "explorer-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        if (busy) body.addView(button("Annuler le chargement") {
            cancelWork(); status = "Chargement annulé. Actualise pour réessayer."; render()
        })
        if (servers) renderServers(body) else renderLocal(body)
    }

    private fun renderLocal(body: LinearLayout) {
        documentRoot?.let { renderDocumentFolder(body, it); return }
        body.addView(button("Actualiser les stockages et le dossier") { refresh() })
        val selectedRoot = root
        val selectedDirectory = directory
        if (selectedRoot == null || selectedDirectory == null) {
            body.addView(text(LocalFileAccess.permissionExplanation(Build.VERSION.SDK_INT, permission)))
            if (!permission) body.addView(button(if (Build.VERSION.SDK_INT >= 30) "Autoriser l’accès à tous les fichiers" else "Autoriser la lecture des fichiers") { requestAccess() })
            if (permission) roots.forEach { storage -> body.addView(button(storage.name) {
                root = storage; directory = storage.file; changeDirectory(); loadLocal()
            }) }
            body.addView(button("Ajouter un dossier avec Android") {
                try { treePicker.launch(null) }
                catch (_: ActivityNotFoundException) { status = "Aucun sélecteur de dossiers Android n’est disponible. Choisis un fichier directement."; render() }
            })
            documentRoots.forEach { folder -> body.addView(button("Dossier autorisé · ${folder.name}") {
                changeDirectory(); loadDocumentFolder(folder, emptyList())
            }) }
            return
        }
        val relative = selectedDirectory.relativeToOrNull(selectedRoot.file)?.path.orEmpty()
        body.addView(directoryTitle(listOf(selectedRoot.name, relative).filter(String::isNotEmpty).joinToString(" / ")))
        body.addView(button("Tous les stockages") {
            root = null; directory = null; localListing = null; changeDirectory(); loadLocal()
        })
        if (selectedDirectory.absolutePath != selectedRoot.file.absolutePath) body.addView(button("Dossier parent") {
            directory = selectedDirectory.parentFile ?: selectedRoot.file; changeDirectory(); loadLocal()
        })
        localListing?.let { listing ->
            renderEntries(body, listing.entries, LocalFileEntry::name, pageIndex, { pageIndex = it }) { entry ->
                entryButton(entry.name, entry.isDirectory, entry.sizeBytes) {
                    if (entry.isDirectory) { directory = entry.file; changeDirectory(); loadLocal() }
                    else openLocalFile(selectedRoot, entry)
                }
            }
            if (listing.excludedCount > 0) body.addView(text("${listing.excludedCount} élément(s) exclu(s) : accès privé, lien inaccessible ou cible hors du stockage choisi."))
        }
    }

    private fun renderDocumentFolder(body: LinearLayout, selected: DocumentTreeRoot) {
        body.addView(directoryTitle((listOf(selected.name) + documentTrail.map { it.name }).joinToString(" / ")))
        body.addView(button("Tous les stockages") {
            documentRoot = null; documentTrail = emptyList(); documentListing = null
            changeDirectory(); loadLocal()
        })
        body.addView(button("Actualiser le dossier") { refresh() })
        if (documentTrail.isNotEmpty()) body.addView(button("Dossier parent") {
            changeDirectory(); loadDocumentFolder(selected, documentTrail.dropLast(1))
        })
        documentListing?.let { listing ->
            if (listing.loading) body.addView(text("Le fournisseur Android charge encore ce dossier. La liste est partielle. Actualise-la pour vérifier les autres éléments."))
            if (listing.unavailableCount > 0) body.addView(text("${listing.unavailableCount} entrée(s) sans identifiant exploitable ou en double."))
            renderEntries(body, listing.entries, DocumentTreeEntry::name, pageIndex, { pageIndex = it }) { entry ->
                entryButton(entry.name, entry.isDirectory, entry.sizeBytes) {
                    when {
                        entry.isDirectory -> {
                            if (entry.id == selected.id || documentTrail.any { it.id == entry.id }) {
                                status = "Ce dossier renvoie vers un parent déjà ouvert."; render()
                            } else { changeDirectory(); loadDocumentFolder(selected, documentTrail + entry) }
                        }
                        entry.virtual -> { status = "Ce document virtuel n’est pas directement lisible. Exporte une copie comme fichier depuis son fournisseur."; render() }
                        else -> openFile(entry.uri, entry.name, entry.mime)
                    }
                }
            }
        }
    }

    private fun renderServers(body: LinearLayout) {
        body.addView(button("Ouvrir une URL vidéo / HLS / DASH / RTSP") { directUrlDialog() })
        body.addView(button("Historique des liens réseau") { manageDirectUrls() })
        body.addView(button("Ajouter un serveur") { connectionDialog(null) })
        body.addView(button("Actualiser") { refresh() })
        val id = connectionId
        if (id == null) {
            if (connections.isEmpty() && !busy) body.addView(text("Aucun serveur enregistré. Protocoles : SFTP, FTP, FTPS explicite, WebDAV et SMB."))
            connections.forEach { connection ->
                body.addView(button("${connection.label} · ${connection.protocol.name}") {
                    connectionId = connection.id; remotePath = "/"; changeDirectory(); loadRemote(connection.id, remotePath)
                })
                val actions = LinearLayout(themed)
                actions.addView(button("Modifier") { connectionDialog(connection) }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(button("Supprimer") { confirmDelete(connection) }, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(actions)
            }
            return
        }
        val connection = connections.find { it.id == id }
        body.addView(directoryTitle("${connection?.label ?: "Serveur"} · $remotePath"))
        body.addView(button("Tous les serveurs") { connectionId = null; remoteEntries = emptyList(); remoteLoaded = false; changeDirectory(); loadConnections() })
        body.addView(button("Tester la connexion") { testConnection(id) })
        body.addView(button("Aller à un dossier") { remoteFolderDialog(id) })
        if (remotePath != "/") body.addView(button("Dossier parent") {
            changeDirectory(); loadRemote(id, RemotePaths.parent(remotePath))
        })
        if (remoteLoaded) renderEntries(body, remoteEntries, RemoteEntry::name, remotePage, { remotePage = it }) { entry ->
            entryButton(entry.name, entry.isDirectory, entry.sizeBytes) {
                if (entry.isDirectory) { changeDirectory(); loadRemote(id, entry.path) }
                else openFile(RemoteMediaUris.create(id, entry.path), entry.name)
            }
        }
    }

    private fun changeDirectory() {
        nameFilter = ""; pageIndex = 0; remotePage = 0
        viewport?.scrollTo(0, 0)
    }

    private fun directoryTitle(value: String) = text(value).apply { setTextIsSelectable(true) }

    private fun entryButton(name: String, isDirectory: Boolean, size: Long?, open: () -> Unit): View {
        val kind = if (isDirectory) "Dossier" else "Fichier"
        val suffix = if (!isDirectory && size != null && size >= 0) " · ${Formatter.formatShortFileSize(activity, size)}" else ""
        return button("$kind · $name$suffix", action = open)
    }

    private fun <T> renderEntries(body: LinearLayout, entries: List<T>, name: (T) -> String, requestedPage: Int,
                                  setPage: (Int) -> Unit, row: (T) -> View) {
        val matches = if (nameFilter.isBlank()) entries else entries.filter { name(it).contains(nameFilter, ignoreCase = true) }
        val pages = (matches.size + PAGE_SIZE - 1) / PAGE_SIZE
        val page = requestedPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
        setPage(page)
        if (entries.isNotEmpty()) body.addView(button("Filtrer les noms du dossier") { filterDialog() })
        if (nameFilter.isNotBlank()) body.addView(button("Effacer le filtre : $nameFilter") { changeDirectory(); render() })
        if (matches.isEmpty()) body.addView(text(if (nameFilter.isBlank()) "Ce dossier ne contient aucun élément accessible." else "Aucun nom ne correspond au filtre."))
        else body.addView(text("Page ${page + 1} sur $pages · ${matches.size} élément(s)${if (nameFilter.isNotBlank()) " sur ${entries.size}" else " accessibles"}."))
        matches.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEach { body.addView(row(it)) }
        if (page > 0) body.addView(button("Page précédente") { setPage(page - 1); render(); viewport?.scrollTo(0, 0) })
        if (page + 1 < pages) body.addView(button("Page suivante") { setPage(page + 1); render(); viewport?.scrollTo(0, 0) })
    }

    private fun filterDialog() {
        val form = form()
        val query = field(form, "Nom ou partie du nom", nameFilter)
        showDialog(MaterialAlertDialogBuilder(themed).setTitle("Filtrer les noms du dossier").setView(scroll(form))
            .setPositiveButton("Appliquer") { _, _ ->
                changeDirectory(); nameFilter = query.text.toString().trim(); render()
            }.setNegativeButton("Annuler", null))
    }

    private fun remoteFolderDialog(id: String) {
        val form = form()
        val path = field(form, "Chemin depuis la racine de cette connexion", remotePath)
        showDialog(MaterialAlertDialogBuilder(themed).setTitle("Aller à un dossier distant").setView(scroll(form))
            .setPositiveButton("Ouvrir") { _, _ ->
                val requested = runCatching { RemotePaths.normalize(path.text.toString()) }.getOrNull()
                if (requested == null) { status = "Chemin invalide. Indique un dossier de cette connexion sans segments « . » ou « .. »."; render() }
                else { changeDirectory(); loadRemote(id, requested) }
            }.setNegativeButton("Annuler", null))
    }

    private fun switchSection(showServers: Boolean) {
        if (servers == showServers) return
        cancelWork(); servers = showServers; changeDirectory(); status = "Choisis un stockage ou un serveur."; refresh()
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT < 30) { requestLegacyPermission(); return }
        try { activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
        catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (_: ActivityNotFoundException) { status = "Ce téléphone ne propose pas ce réglage. Utilise « Choisir avec Android »."; render() }
        }
    }

    private fun loadLocal() {
        localListing = null
        permission = LocalFileAccess.granted(activity)
        if (!permission) { roots = emptyList(); root = null; directory = null }
        val selectedRoot = root; val selectedDirectory = directory; val canBrowse = permission
        val trees = sessionTrees.toSet()
        work("Lecture du dossier…", "Impossible de lire ce dossier. Vérifie l’autorisation et le support de stockage, ou utilise le sélecteur Android.", { _, cancellation ->
            val available = if (canBrowse) local.roots(activity.applicationContext, cancellation) else emptyList()
            val listing = if (selectedRoot != null && selectedDirectory != null) local.scan(selectedRoot.file, selectedDirectory, cancellation) else null
            Triple(available, listing, if (selectedRoot == null) documents.roots(trees, cancellation) else null)
        }) { (available, listing, grantedTrees) ->
            roots = available; localListing = listing
            if (grantedTrees != null) documentRoots = grantedTrees
            status = when {
                listing != null -> "Dossier chargé. Les noms masqués accessibles sont inclus."
                available.isNotEmpty() || documentRoots.isNotEmpty() -> "Choisis un stockage ou un dossier autorisé."
                else -> "Choisis un fichier ou autorise un dossier avec Android."
            }
        }
    }

    private fun loadDocumentFolder(selected: DocumentTreeRoot, trail: List<DocumentTreeEntry>, persist: Boolean = false) {
        documentRoot = selected; documentTrail = trail; documentListing = null
        work("Lecture du dossier Android…", "Impossible de lire ce dossier Android. Réautorise-le ou choisis un fichier directement.", { _, cancellation ->
            if (persist) documents.persist(selected.uri)
            val folder = if (persist) documents.root(selected.uri, cancellation) else selected
            folder to documents.list(folder, trail.lastOrNull()?.id ?: folder.id, cancellation)
        }) { (folder, listing) ->
            documentRoot = folder; documentListing = listing
            status = if (listing.loading) "Liste fournie en partie par Android." else "Dossier Android chargé."
        }
    }

    private fun openLocalFile(storage: LocalStorageRoot, entry: LocalFileEntry) {
        work("Vérification de l’accès au fichier…", "Impossible d’accéder à ce fichier. Utilise « Choisir avec Android ».", { _, cancellation ->
            if (!LocalFileAccess.granted(activity.applicationContext)) throw LocalBrowseException("L’accès au stockage a été retiré. Utilise « Choisir avec Android ».")
            local.readableFile(storage.file, entry.file, cancellation)
        }) { uri -> status = "Fichier accessible."; openFile(uri, entry.name) }
    }

    private fun loadConnections() {
        work("Lecture des connexions enregistrées…", "Le stockage chiffré des connexions est inaccessible sur cet appareil.", { _, _ -> store.list() }) {
            connections = it; status = "${it.size} serveur(s) enregistré(s)."
        }
    }

    private fun loadRemote(id: String, path: String) {
        remotePath = path; remoteLoaded = false; remoteEntries = emptyList()
        work("Lecture du dossier distant…", "Impossible de lire ce dossier distant. Vérifie le serveur, les identifiants, le partage, la connexion et l’empreinte SFTP.", { cancellation, _ ->
            store.list() to remote.list(id, path, cancellation).sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase(java.util.Locale.ROOT) }, { it.name }, { it.path }))
        }) { (saved, entries) ->
            connections = saved; remoteEntries = entries; remoteLoaded = true; status = "Dossier distant chargé. La visibilité dépend des droits accordés par le serveur."
        }
    }

    private fun testConnection(id: String) {
        work("Test de connexion…", "Le test a échoué. Vérifie le serveur, le protocole, les identifiants et l’empreinte SFTP.", { cancellation, _ -> remote.test(id, cancellation) }) {
            status = "Connexion vérifiée."
        }
    }

    private fun openFile(uri: Uri, name: String, mime: String? = null) {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        when {
            extension in SUBTITLE_EXTENSIONS -> onSubtitle(uri, name)
            extension in MEDIA_EXTENSIONS -> onMedia(SavedMedia(uri.toString(), name))
            mime?.let { it in SUBTITLE_MIME_TYPES } == true -> onSubtitle(uri, name)
            mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> onMedia(SavedMedia(uri.toString(), name))
            else -> showDialog(MaterialAlertDialogBuilder(themed).setTitle(name)
                .setMessage("Ce type de fichier n’est pas identifié. Choisis comment l’ouvrir ; le lecteur vérifiera son contenu.")
                .setPositiveButton("Lire comme média") { _, _ -> onMedia(SavedMedia(uri.toString(), name)) }
                .setNeutralButton("Importer comme sous-titres") { _, _ -> onSubtitle(uri, name) }
                .setNegativeButton("Annuler", null))
        }
    }

    private fun directUrlDialog() {
        val form = form()
        val url = field(form, "URL directe HTTP, HTTPS ou RTSP (HLS .m3u8 / DASH .mpd acceptés)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val title = field(form, "Titre de la vidéo (facultatif)", "")
        form.addView(text("Le lien complet est protégé dans le stockage chiffré de l’application. Il n’apparaît ni dans le titre du lecteur ni dans les diagnostics. Un lien de page Web n’est pas une URL vidéo directe."))
        val builder = MaterialAlertDialogBuilder(themed).setTitle("Ouvrir un lien réseau").setView(scroll(form)).setPositiveButton("Ouvrir", null).setNegativeButton("Annuler", null)
        val current = builder.create(); dialog?.dismiss(); dialog = current
        current.setOnShowListener { current.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val raw = url.text.toString()
            if (runCatching { RemoteConnectionStore.validatedDirectUrl(raw) }.isFailure) { url.error = "Utilise une URL directe HTTP, HTTPS ou RTSP valide, sans espace ni fragment."; return@setOnClickListener }
            val display = title.text.toString().trim()
            if (!validDirectTitle(title, display)) return@setOnClickListener
            url.text?.clear(); current.dismiss()
            work("Protection du lien…", "Impossible de protéger ce lien réseau sur cet appareil.", { _, _ ->
                store.directLink(store.saveDirectUrl(raw, display))
            }) { link ->
                status = "Lien réseau prêt."; onMedia(link.media())
            }
        } }
        current.show()
        sizeDialogButtons(current)
    }

    private fun manageDirectUrls(manage: Boolean = false) {
        work("Lecture des liens enregistrés…", "Les liens enregistrés sont inaccessibles sur cet appareil.", { _, _ -> store.listDirectLinks() }) { links ->
            if (links.isEmpty()) {
                status = "Aucun lien réseau enregistré."
            } else {
                val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                val labels = links.mapIndexed { index, link ->
                    val lastOpened = link.lastOpenedMs.takeIf { it > 0 }?.let { date.format(java.util.Date(it)) }
                    "${link.title}\n${lastOpened?.let { "Dernière ouverture : $it" } ?: "Lien ancien ${index + 1}"}"
                }.toTypedArray()
                showDialog(MaterialAlertDialogBuilder(themed).setTitle(if (manage) "Gérer les liens réseau" else "Rouvrir un lien réseau")
                    .setItems(labels) { _, index ->
                        val link = links[index]
                        if (manage) manageDirectLink(link) else reopenDirectLink(link.uri)
                    }
                    .setNeutralButton(if (manage) "Revenir à la lecture" else "Renommer ou supprimer") { _, _ -> manageDirectUrls(!manage) }
                    .setNegativeButton("Fermer", null))
            }
        }
    }

    private fun reopenDirectLink(uri: Uri) {
        work("Réouverture du lien…", "Ce lien enregistré est inaccessible sur cet appareil.", { _, _ ->
            store.reopenDirectLink(uri)
        }) { link ->
            status = "Lien réseau prêt."
            onMedia(link.media())
        }
    }

    private fun manageDirectLink(link: DirectNetworkLink) {
        showDialog(MaterialAlertDialogBuilder(themed).setTitle(link.title)
            .setItems(arrayOf("Renommer la vidéo", "Supprimer le lien")) { _, index ->
                if (index == 0) renameDirectLink(link) else confirmDeleteDirectUrl(link.uri, link.title)
            }.setNegativeButton("Fermer", null))
    }

    private fun renameDirectLink(link: DirectNetworkLink) {
        val form = form()
        val title = field(form, "Titre (vide : utiliser le titre détecté)", link.customTitle ?: link.title)
        val current = MaterialAlertDialogBuilder(themed).setTitle("Renommer la vidéo")
            .setView(scroll(form)).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog?.dismiss(); dialog = current
        current.setOnShowListener {
            current.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val display = title.text.toString().trim()
                if (!validDirectTitle(title, display)) return@setOnClickListener
                current.dismiss()
                work("Enregistrement du titre…", "Le titre n’a pas pu être enregistré.", { _, _ ->
                    store.renameDirectLink(link.uri, display).also { renamed ->
                        notifyMutation { onDirectTitleChanged(renamed.uri, renamed.title) }
                    }
                }) { status = "Titre enregistré." }
            }
        }
        current.show()
        sizeDialogButtons(current)
    }

    private fun validDirectTitle(field: EditText, title: String): Boolean {
        if (title.isEmpty() || NetworkMediaTitles.userTitle(title) != null) return true
        field.error = "Saisis un titre de 200 caractères au maximum, sans adresse ni identifiants."
        return false
    }

    private fun confirmDeleteDirectUrl(uri: Uri, label: String) {
        showDialog(MaterialAlertDialogBuilder(themed).setTitle("Supprimer ce lien réseau ?")
            .setMessage("$label\n\nLe lien protégé sera supprimé de l’application. Les reprises et déplacements utilisant ce lien ne seront plus disponibles. La vidéo d’origine est conservée sur le serveur.")
            .setPositiveButton("Supprimer le lien") { _, _ ->
                work("Suppression du lien…", "Le lien n’a pas pu être supprimé.", { _, _ ->
                    store.deleteDirectUrl(uri); notifyMutation { onDirectUrlDeleted(uri) }
                }) {
                    status = "Lien réseau supprimé."
                }
            }.setNegativeButton("Conserver", null))
    }

    private fun connectionDialog(existing: RemoteConnection?) {
        val form = form()
        val label = field(form, "Nom affiché", existing?.label.orEmpty())
        val protocols = RemoteProtocol.entries
        form.addView(text("Protocole"))
        val protocol = Spinner(themed).apply {
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, protocols.map { it.name })
            setSelection(protocols.indexOf(existing?.protocol ?: RemoteProtocol.SFTP)); minimumHeight = dp(56)
            contentDescription = "Protocole du serveur"
        }.also { form.addView(it) }
        val host = field(form, "Hôte (sans protocole ni chemin)", existing?.host.orEmpty())
        val port = field(form, "Port (vide : port par défaut du protocole)", existing?.port?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val username = field(form, "Utilisateur (FTP anonyme : anonymous)", existing?.username.orEmpty())
        val password = field(form, "Mot de passe", existing?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val path = field(form, "Dossier initial", existing?.rootPath ?: "/")
        val share = field(form, "Partage SMB (obligatoire pour SMB)", existing?.share.orEmpty())
        val domain = field(form, "Domaine SMB (facultatif)", existing?.domain.orEmpty())
        val tls = CheckBox(themed).apply {
            text = "WebDAV : utiliser HTTPS (décoché : HTTP)"; isChecked = existing?.tls ?: true; minHeight = dp(56)
        }.also { form.addView(it) }
        val verifyKey = CheckBox(themed).apply {
            text = "SFTP : vérifier à nouveau l’empreinte du serveur"; minHeight = dp(56)
        }.also { form.addView(it) }
        form.addView(text("FTP et HTTP transmettent les identifiants sans chiffrement. FTPS utilise TLS explicite. SFTP exige une empreinte de serveur vérifiée avant toute authentification. Les clés privées SSH ne sont pas prises en charge."))
        val validation = text("").also { form.addView(it) }
        val current = MaterialAlertDialogBuilder(themed).setTitle(if (existing == null) "Ajouter un serveur" else "Modifier le serveur")
            .setView(scroll(form)).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog?.dismiss(); dialog = current
        current.setOnShowListener { current.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val selected = protocols[protocol.selectedItemPosition]
            val hostValue = host.text.toString().trim()
            val portValue = port.text.toString().trim().let { if (it.isEmpty()) if (selected == RemoteProtocol.WEBDAV && !tls.isChecked) 80 else selected.defaultPort else it.toIntOrNull() ?: -1 }
            val sameIdentity = existing?.protocol == selected && existing.host == hostValue && existing.port == portValue
            val candidate = RemoteConnection(id = existing?.id ?: java.util.UUID.randomUUID().toString(), label = label.text.toString().trim(), protocol = selected,
                host = hostValue, port = portValue, username = username.text.toString(), password = password.text.toString(), rootPath = path.text.toString(),
                share = share.text.toString(), domain = domain.text.toString(), tls = tls.isChecked, hostKeySha256 = if (sameIdentity && !verifyKey.isChecked) existing?.hostKeySha256.orEmpty() else "")
            try { candidate.validated() } catch (error: IllegalArgumentException) { validation.text = error.message ?: "Vérifie les champs du serveur."; return@setOnClickListener }
            current.dismiss()
            if (selected == RemoteProtocol.SFTP && candidate.hostKeySha256.isEmpty()) confirmSftp(candidate, existing != null) else saveConnection(candidate, existing != null)
        } }
        current.show()
        sizeDialogButtons(current)
    }

    private fun confirmSftp(connection: RemoteConnection, updated: Boolean) {
        work("Lecture de l’empreinte SFTP sans authentification…", "Impossible de lire l’empreinte SFTP. Aucun identifiant n’a été envoyé.", { cancellation, _ -> remote.probeSftpHostKey(connection, cancellation) }) { fingerprint ->
            status = "Empreinte SFTP à vérifier avant enregistrement."
            showDialog(MaterialAlertDialogBuilder(themed).setTitle("Vérifier le serveur SFTP")
                .setMessage("Compare cette empreinte avec celle fournie par l’administrateur du serveur, par un canal indépendant.\n\n$fingerprint\n\nN’accepte que si elles correspondent. Aucune clé inconnue ne sera acceptée automatiquement.")
                .setPositiveButton("Empreinte vérifiée, enregistrer") { _, _ -> saveConnection(connection.copy(hostKeySha256 = fingerprint), updated) }
                .setNegativeButton("Refuser", null))
        }
    }

    private fun saveConnection(connection: RemoteConnection, updated: Boolean) {
        work("Protection de la connexion…", "Impossible de protéger et enregistrer cette connexion sur cet appareil.", { _, _ ->
            store.save(connection)
            if (updated) notifyMutation { onConnectionUpdated(connection.id) }
        }) {
            connections = (connections.filterNot { saved -> saved.id == connection.id } + connection).sortedBy { saved -> saved.label.lowercase(java.util.Locale.ROOT) }
            connectionId = null; status = "Connexion enregistrée."
        }
    }

    private fun confirmDelete(connection: RemoteConnection) {
        showDialog(MaterialAlertDialogBuilder(themed).setTitle("Supprimer la connexion ?")
            .setMessage("Supprimer « ${connection.label} » de cette application ? Les fichiers du serveur seront conservés. Une vidéo utilisant cette connexion ne pourra plus être rouverte avec son ancien lien.")
            .setPositiveButton("Supprimer") { _, _ ->
                work("Suppression de la connexion…", "La connexion n’a pas pu être supprimée.", { _, _ ->
                    store.delete(connection.id); notifyMutation { onConnectionDeleted(connection.id) }
                }) {
                    connections = connections.filterNot { saved -> saved.id == connection.id }
                    status = "Connexion supprimée."
                }
            }.setNegativeButton("Conserver", null))
    }

    /** Generation checks prevent cancelled or replaced tasks from opening an old media or replacing current rows. */
    private fun <T> work(loading: String, failure: String, task: (RemoteCancellation, LocalBrowseCancellation) -> T, success: (T) -> Unit) {
        cancelWork()
        val owner = generation
        val remoteToken = RemoteCancellation(); val localToken = LocalBrowseCancellation()
        remoteCancel = remoteToken; localCancel = localToken
        busy = true; status = loading; render()
        future = worker.submit {
            val result = runCatching { task(remoteToken, localToken) }
            remoteToken.close(); localToken.close()
            main.post {
                if (closed || owner != generation) return@post
                busy = false; remoteCancel = null; localCancel = null; future = null
                result.fold(onSuccess = { value -> success(value) }, onFailure = {
                    status = if (it is LocalBrowseException || it is RemoteAccessException) it.message ?: failure else failure
                })
                render()
            }
        }
    }

    private fun cancelWork() {
        generation++; remoteCancel?.close(); localCancel?.close(); future?.cancel(true)
        remoteCancel = null; localCancel = null; future = null; busy = false
    }

    // A committed edit/deletion must invalidate active playback even if a newer
    // browser listing has replaced this task before its UI result is delivered.
    private fun notifyMutation(action: () -> Unit) { main.post { if (!closed) action() } }

    override fun close() {
        if (closed) return
        closed = true; cancelWork(); treePicker.unregister(); dialog?.dismiss(); dialog = null
        column = null; viewport = null; worker.shutdownNow()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(themed).apply {
        text = value; textSize = size; setTextColor(MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)); setPadding(dp(4), dp(10), dp(4), dp(10))
    }
    private fun button(value: String, selected: Boolean = false, action: () -> Unit) = MaterialButton(themed).apply {
        text = value; textSize = 16f; isAllCaps = false; minHeight = dp(56); minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(MaterialColors.getColor(themed, if (selected) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSecondaryContainer, Color.BLACK))
        backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(themed, if (selected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY))
        setOnClickListener { if (!closed) action() }
    }
    private fun form() = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
    private fun scroll(form: LinearLayout) = ScrollView(themed).apply { addView(form) }
    private fun field(form: LinearLayout, label: String, initial: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS): EditText {
        val input = EditText(themed).apply {
            // setSingleLine replaces TextView's transformation. Set the password
            // input type afterwards so Android reinstates its masking transform.
            id = View.generateViewId(); setText(initial); isSingleLine = true; inputType = type; textSize = 17f
            minHeight = dp(56); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextColor(MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
        }
        form.addView(text(label).apply { labelFor = input.id }); form.addView(input)
        return input
    }
    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        dialog?.dismiss(); dialog = builder.show().also { sizeDialogButtons(it) }
    }
    private fun sizeDialogButtons(current: androidx.appcompat.app.AlertDialog) {
        listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE,
            android.content.DialogInterface.BUTTON_NEUTRAL).forEach { which ->
            current.getButton(which)?.let { it.minHeight = dp(56); it.minimumHeight = dp(56); it.isAllCaps = false }
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
        private val SUBTITLE_MIME_TYPES = setOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/x-ass")
        private val MEDIA_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mpeg", "mpg", "ts", "m2ts", "mts", "wmv", "flv", "ogv", "3gp", "3g2", "m3u8", "mpd",
            "mp3", "m4a", "mka", "aac", "flac", "wav", "ogg", "oga", "opus", "ac3", "eac3")
    }
}
