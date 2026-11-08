package fr.soustitresvoix.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
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
    private val onConnectionUpdated: (String) -> Unit = {}
) : AutoCloseable {
    private val themed = activity
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = RemoteConnectionStore(activity.applicationContext)
    private val remote = RemoteBrowser(store)
    private val local = LocalFileBrowser()
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
    private var localPage: LocalFilePage? = null
    private var pageIndex = 0
    private var connectionId: String? = null
    private var remotePath = "/"
    private var remoteEntries = emptyList<RemoteEntry>()
    private var remotePage = 0
    private var status = "Choisis un stockage ou un serveur."
    private var column: LinearLayout? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    fun createView(): View {
        val body = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        column = body
        render()
        if (roots.isEmpty() && !busy && !servers) refresh()
        return ScrollView(themed).apply { isFillViewport = true; addView(body) }
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
        } else loadLocal()
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
        body.addView(text(LocalFileAccess.permissionExplanation(Build.VERSION.SDK_INT, permission)))
        if (!permission) body.addView(button(if (Build.VERSION.SDK_INT >= 30) "Autoriser l’accès à tous les fichiers" else "Autoriser la lecture des fichiers") { requestAccess() })
        body.addView(button("Actualiser les stockages et le dossier") { loadLocal() })
        val selectedRoot = root
        val selectedDirectory = directory
        if (selectedRoot == null || selectedDirectory == null) {
            if (permission) roots.forEach { storage -> body.addView(button(storage.name) {
                root = storage; directory = storage.file; pageIndex = 0; loadLocal()
            }) }
            return
        }
        body.addView(text("${selectedRoot.name} · ${selectedDirectory.absolutePath}").apply { setTextIsSelectable(true) })
        body.addView(button("Tous les stockages") {
            root = null; directory = null; localPage = null; pageIndex = 0; loadLocal()
        })
        if (selectedDirectory.absolutePath != selectedRoot.file.absolutePath) body.addView(button("Dossier parent") {
            directory = selectedDirectory.parentFile ?: selectedRoot.file; pageIndex = 0; loadLocal()
        })
        localPage?.let { page ->
            if (page.entries.isEmpty()) body.addView(text("Ce dossier est vide ou ne contient aucun élément accessible."))
            page.entries.forEach { entry ->
                body.addView(button((if (entry.isDirectory) "Dossier · " else "Fichier · ") + entry.name) {
                    if (entry.isDirectory) { directory = entry.file; pageIndex = 0; loadLocal() }
                    else openFile(entry.uri, entry.name)
                })
            }
            body.addView(text("Page ${page.page + 1} · ${page.total} éléments accessibles. Dossiers puis noms, par ordre alphabétique."))
            if (page.excludedCount > 0) body.addView(text("${page.excludedCount} élément(s) exclu(s) : dossiers privés Android ou liens sortant du stockage choisi."))
            if (page.page > 0) body.addView(button("Page précédente") { pageIndex--; loadLocal() })
            if (page.hasNext) body.addView(button("Page suivante") { pageIndex++; loadLocal() })
        }
    }

    private fun renderServers(body: LinearLayout) {
        body.addView(button("Ouvrir une URL vidéo / HLS / DASH / RTSP") { directUrlDialog() })
        body.addView(button("Gérer les liens réseau enregistrés") { manageDirectUrls() })
        body.addView(button("Ajouter un serveur") { connectionDialog(null) })
        body.addView(button("Actualiser") { refresh() })
        val id = connectionId
        if (id == null) {
            if (connections.isEmpty() && !busy) body.addView(text("Aucun serveur enregistré. Protocoles : SFTP, FTP, FTPS explicite, WebDAV et SMB."))
            connections.forEach { connection ->
                body.addView(button("${connection.label} · ${connection.protocol.name}") {
                    connectionId = connection.id; remotePath = "/"; remotePage = 0; loadRemote(connection.id, remotePath)
                })
                val actions = LinearLayout(themed)
                actions.addView(button("Modifier") { connectionDialog(connection) }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(button("Supprimer") { confirmDelete(connection) }, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(actions)
            }
            return
        }
        val connection = connections.find { it.id == id }
        body.addView(text("${connection?.label ?: "Serveur"} · $remotePath").apply { setTextIsSelectable(true) })
        body.addView(button("Tous les serveurs") { connectionId = null; remoteEntries = emptyList(); loadConnections() })
        body.addView(button("Tester la connexion") { testConnection(id) })
        if (remotePath != "/") body.addView(button("Dossier parent") {
            remotePage = 0; loadRemote(id, RemotePaths.parent(remotePath))
        })
        if (remoteEntries.isEmpty() && !busy) body.addView(text("Ce dossier ne contient aucun élément affichable."))
        remoteEntries.drop(remotePage * PAGE_SIZE).take(PAGE_SIZE).forEach { entry ->
            body.addView(button((if (entry.isDirectory) "Dossier · " else "Fichier · ") + entry.name) {
                if (entry.isDirectory) { remotePage = 0; loadRemote(id, entry.path) }
                else openFile(RemoteMediaUris.create(id, entry.path), entry.name)
            })
        }
        if (remoteEntries.isNotEmpty()) body.addView(text("Page ${remotePage + 1} · ${remoteEntries.size} éléments. Dossiers puis noms, par ordre alphabétique."))
        if (remotePage > 0) body.addView(button("Page précédente") { remotePage--; render() })
        if ((remotePage + 1) * PAGE_SIZE < remoteEntries.size) body.addView(button("Page suivante") { remotePage++; render() })
    }

    private fun switchSection(showServers: Boolean) {
        if (servers == showServers) return
        cancelWork(); servers = showServers; status = "Choisis un stockage ou un serveur."; refresh()
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
        localPage = null
        permission = LocalFileAccess.granted(activity)
        if (!permission) { cancelWork(); roots = emptyList(); status = "Le sélecteur Android permet d’ouvrir un fichier sans accès général au stockage."; render(); return }
        val selectedRoot = root; val selectedDirectory = directory; val selectedPage = pageIndex
        work("Lecture du dossier…", "Impossible de lire ce dossier. Vérifie l’autorisation et le support de stockage, ou utilise le sélecteur Android.", { _, cancellation ->
            val available = local.roots(activity.applicationContext, cancellation)
            val listing = if (selectedRoot != null && selectedDirectory != null) local.list(selectedRoot.file, selectedDirectory, selectedPage, cancel = cancellation) else null
            available to listing
        }) { (available, listing) ->
            roots = available; localPage = listing
            status = if (listing != null) "Dossier chargé." else if (available.isEmpty()) "Aucun stockage partagé disponible. Utilise le sélecteur Android." else "Choisis un stockage."
        }
    }

    private fun loadConnections() {
        work("Lecture des connexions enregistrées…", "Le stockage chiffré des connexions est inaccessible sur cet appareil.", { _, _ -> store.list() }) {
            connections = it; status = "${it.size} serveur(s) enregistré(s)."
        }
    }

    private fun loadRemote(id: String, path: String) {
        remoteEntries = emptyList()
        work("Lecture du dossier distant…", "Impossible de lire ce dossier distant. Vérifie le serveur, les identifiants, le partage, la connexion et l’empreinte SFTP. Un dossier de plus de 5 000 éléments doit être ouvert dans un sous-dossier.", { cancellation, _ ->
            store.list() to remote.list(id, path, cancellation).sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase(java.util.Locale.ROOT) }, { it.name }, { it.path }))
        }) { (saved, entries) ->
            connections = saved; remotePath = path; remoteEntries = entries; remotePage = 0; status = "Dossier distant chargé."
        }
    }

    private fun testConnection(id: String) {
        work("Test de connexion…", "Le test a échoué. Vérifie le serveur, le protocole, les identifiants et l’empreinte SFTP.", { cancellation, _ -> remote.test(id, cancellation) }) {
            status = "Connexion vérifiée."
        }
    }

    private fun openFile(uri: Uri, name: String) {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        when {
            extension == "srt" -> onSubtitle(uri, name)
            extension in VIDEO_EXTENSIONS -> onMedia(SavedMedia(uri.toString(), name))
            else -> showDialog(MaterialAlertDialogBuilder(themed).setTitle(name)
                .setMessage("Ce type de fichier n’est pas identifié. Choisis comment l’ouvrir ; le lecteur vérifiera son contenu.")
                .setPositiveButton("Vidéo") { _, _ -> onMedia(SavedMedia(uri.toString(), name)) }
                .setNeutralButton("Sous-titres SRT") { _, _ -> onSubtitle(uri, name) }
                .setNegativeButton("Annuler", null))
        }
    }

    private fun directUrlDialog() {
        val form = form()
        val url = field(form, "URL directe HTTP, HTTPS ou RTSP (HLS .m3u8 / DASH .mpd acceptés)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        form.addView(text("Le lien complet est protégé dans le stockage chiffré de l’application. Il n’apparaît ni dans le titre du lecteur ni dans les diagnostics. Un lien de page Web n’est pas une URL vidéo directe."))
        val builder = MaterialAlertDialogBuilder(themed).setTitle("Ouvrir un lien réseau").setView(scroll(form)).setPositiveButton("Ouvrir", null).setNegativeButton("Annuler", null)
        val current = builder.create(); dialog?.dismiss(); dialog = current
        current.setOnShowListener { current.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val raw = url.text.toString()
            if (runCatching { RemoteConnectionStore.validatedDirectUrl(raw) }.isFailure) { url.error = "Utilise une URL directe HTTP, HTTPS ou RTSP valide, sans espace ni fragment."; return@setOnClickListener }
            url.text?.clear(); current.dismiss()
            work("Protection du lien…", "Impossible de protéger ce lien réseau sur cet appareil.", { _, _ -> store.saveDirectUrl(raw) }) { opaque ->
                status = "Lien réseau prêt."; onMedia(SavedMedia(opaque.toString(), directUrlLabel(opaque)))
            }
        } }
        current.show()
        sizeDialogButtons(current)
    }

    private fun manageDirectUrls() {
        work("Lecture des liens enregistrés…", "Les liens enregistrés sont inaccessibles sur cet appareil.", { _, _ -> store.listDirectUrls() }) { links ->
            if (links.isEmpty()) {
                status = "Aucun lien réseau enregistré."
            } else {
                val labels = links.map { uri ->
                    val extension = uri.lastPathSegment?.substringAfterLast('.', "").orEmpty().uppercase(java.util.Locale.ROOT)
                    directUrlLabel(uri) + if (extension.isNotEmpty()) " · $extension" else ""
                }.toTypedArray()
                showDialog(MaterialAlertDialogBuilder(themed).setTitle("Liens réseau enregistrés")
                    .setItems(labels) { _, index -> confirmDeleteDirectUrl(links[index], labels[index]) }
                    .setNegativeButton("Fermer", null))
            }
        }
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
                    status = if (it is java.io.IOException && it.message?.startsWith("Ce dossier contient plus de ") == true) it.message!! else failure
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
        closed = true; cancelWork(); dialog?.dismiss(); dialog = null; column = null; worker.shutdownNow()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun directUrlLabel(uri: Uri) = "Lien réseau · ${uri.host.orEmpty().take(8)}"
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
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mpeg", "mpg", "ts", "m2ts", "mts", "wmv", "flv", "ogv", "3gp", "3g2", "m3u8", "mpd")
    }
}
