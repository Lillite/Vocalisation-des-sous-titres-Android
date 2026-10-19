package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import fr.soustitresvoix.core.SubtitleImport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Native text-labelled controls: no gesture or icon is required to use the app. */
@UnstableApi
class MainActivity : Activity() {
    private lateinit var prefs: PlayerPreferences
    internal lateinit var session: PlaybackSession; private set
    private lateinit var playerView: PlayerView
    private lateinit var playButton: Button
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var titleView: TextView
    private lateinit var positionView: TextView
    private lateinit var positionSlider: SeekBar
    private lateinit var extraControls: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var requestToken = 0L
    private var stopped = false
    private var closed = false
    private var fullscreen = false
    private var scrubbing = false
    private var message = "Ouvre une vidéo ou lance l’essai intégré. Aucun compte ni connexion Internet n’est nécessaire au lecteur."
    private var messageIsError = false
    private var lastSaveMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (closed || stopped) return
            refreshUi()
            if (android.os.SystemClock.elapsedRealtime() - lastSaveMs > 5000) {
                session.savePosition(); lastSaveMs = android.os.SystemClock.elapsedRealtime()
            }
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        prefs = PlayerPreferences(this)
        session = PlaybackSession(this, prefs, onChanged = { refreshUi() },
            onMessage = { text, error -> showMessage(text, error) }, onCaption = { })
        buildUi()
        if (!handleViewIntent(intent)) prefs.lastMedia()?.let { saved ->
            val scheme = Uri.parse(saved.uri).scheme
            if (scheme == "content" || scheme == "android.resource") session.open(saved)
            else prefs.clearMedia()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleViewIntent(intent)
    }
    private fun handleViewIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "content") return false
        rememberPermission(uri, intent.flags); openVideo(uri); return true
    }
    override fun onResume() {
        super.onResume(); stopped = false
        main.removeCallbacks(ticker); main.post(ticker)
    }
    override fun onPause() {
        stopped = true; main.removeCallbacks(ticker)
        if (::session.isInitialized) session.pause()
        super.onPause()
    }
    override fun onDestroy() {
        closed = true; requestToken++
        main.removeCallbacksAndMessages(null); io.shutdownNow()
        if (::playerView.isInitialized) playerView.player = null
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); buildUi()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 20f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(64)
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { action() }
    }
    private fun buildUi() {
        if (::playerView.isInitialized) playerView.player = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(i.left + dp(8), i.top + dp(8), i.right + dp(8), i.bottom + dp(8))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft + dp(8), insets.systemWindowInsetTop + dp(8),
                    insets.systemWindowInsetRight + dp(8), insets.systemWindowInsetBottom + dp(8))
            }
            insets
        }
        titleView = text(session.media?.title ?: "Sous-titres en voix", 22f)
        titleView.maxLines = 2
        if (!fullscreen) root.addView(titleView)
        playerView = PlayerView(this).apply {
            useController = false; player = session.player
            // Controls below are the single accessible playback control surface.
            isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        applyCaptionStyle()
        root.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, if (fullscreen) 1f else 0.9f))
        statusView = text("En pause", 18f)
        root.addView(statusView)
        positionView = text("00:00 / 00:00", 16f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (!fullscreen) root.addView(positionView)
        positionSlider = SeekBar(this).apply {
            id = View.generateViewId(); max = 1000; minimumHeight = dp(48)
            contentDescription = "Position de la vidéo"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true; session.pause() }
                override fun onStopTrackingTouch(seekBar: SeekBar) { scrubbing = false; seekFraction(seekBar.progress) }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !scrubbing) seekFraction(progress)
                }
            })
        }
        if (!fullscreen) root.addView(positionSlider)
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addNav(view: View) { navigation.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        addNav(button("Reculer\n10 s") { session.seek(session.player.currentPosition - 10_000) })
        playButton = button("Lire") {
            val state = session.machine.snapshot()
            if (state.wantsPlayback || state.speakingId != null) session.pause() else session.play()
        }
        playButton.id = R.id.play_button
        addNav(playButton)
        addNav(button("Avancer\n10 s") { session.seek(session.player.currentPosition + 10_000) })
        root.addView(navigation)
        if (fullscreen) {
            root.addView(button("Quitter le plein écran") { fullscreen = false; buildUi() })
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageView = text(message, 18f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(if (messageIsError) Color.rgb(255, 205, 110) else Color.LTGRAY)
        }
        column.addView(messageView)
        column.addView(button("Ouvrir une vidéo") { pickDocument(OPEN_VIDEO) }.apply { id = R.id.open_video })
        column.addView(button("Essai intégré : vidéo sous-titrée") {
            requestToken++
            session.open(SavedMedia("android.resource://$packageName/${R.raw.demonstration}", "Essai intégré"))
            session.play()
        }.apply { id = R.id.demo_button })
        column.addView(button("Ajouter un fichier de sous-titres") {
            if (session.media == null) showMessage("Ouvre d’abord une vidéo.", false) else pickDocument(OPEN_SUBTITLE)
        })
        column.addView(button("Choisir la piste de sous-titres") { chooseTrack(C.TRACK_TYPE_TEXT) })
        column.addView(button("Choisir la piste audio du film") { chooseTrack(C.TRACK_TYPE_AUDIO) })
        column.addView(button("Répéter la dernière phrase — met en pause") { session.repeatLast() })
        column.addView(button("Réglages de la voix et du confort") { settingsDialog() })
        column.addView(button("Plein écran") { fullscreen = true; buildUi() })
        column.addView(button("Aide et limites de cette version") { helpDialog() })
        extraControls = ScrollView(this).apply { isFillViewport = false; addView(column) }
        if (!fullscreen) root.addView(extraControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f))
        setContentView(root)
        root.requestApplyInsets()
        refreshUi()
    }
    private fun applyCaptionStyle() {
        if (!::playerView.isInitialized) return
        playerView.subtitleView?.apply {
            visibility = if (prefs.showSubtitles) View.VISIBLE else View.INVISIBLE
            setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.subtitleSp)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.BLACK, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    private fun refreshUi() {
        if (closed || !::session.isInitialized || !::playButton.isInitialized) return
        val state = session.machine.snapshot()
        playButton.text = when {
            state.wantsPlayback -> "Pause"
            state.speakingId != null -> "Arrêter la voix"
            else -> "Lire"
        }
        val status = when {
            state.waitingForSpeech -> "Pause de la vidéo pour terminer la phrase"
            state.speakingId != null && !state.wantsPlayback -> "Écoute de la phrase — vidéo en pause"
            state.wantsPlayback && session.player.playbackState == Player.STATE_BUFFERING -> "Chargement de la vidéo"
            state.wantsPlayback -> if (state.enabled) "Lecture avec sous-titres vocalisés" else "Lecture sans vocalisation"
            else -> "En pause"
        }
        if (statusView.text.toString() != status) statusView.text = status
        val duration = session.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val pos = session.player.currentPosition.coerceAtLeast(0)
        positionView.text = "${clock(pos)} / ${clock(duration)}"
        positionSlider.isEnabled = duration > 0 && session.player.isCurrentMediaItemSeekable
        if (!scrubbing && duration > 0) positionSlider.progress = (pos.toDouble() / duration * 1000).toInt().coerceIn(0, 1000)
        positionSlider.contentDescription = "Position ${clock(pos)} sur ${clock(duration)}"
        titleView.text = session.media?.title ?: "Sous-titres en voix"
        if (state.wantsPlayback || state.speakingId != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }
    private fun seekFraction(progress: Int) {
        val duration = session.player.duration
        if (duration != C.TIME_UNSET && duration > 0) session.seek((duration.toDouble() * progress / 1000).toLong())
    }
    private fun showMessage(value: String, error: Boolean) {
        message = value; messageIsError = error
        if (::messageView.isInitialized) {
            messageView.text = value
            messageView.setTextColor(if (error) Color.rgb(255, 205, 110) else Color.LTGRAY)
        }
        // Errors are not hidden behind the fullscreen interface.
        if (error && fullscreen && !closed) { fullscreen = false; buildUi() }
    }
    @Suppress("DEPRECATION")
    private fun pickDocument(request: Int) {
        session.pause()
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (request == OPEN_VIDEO) pick.putExtra(Intent.EXTRA_MIME_TYPES,
            arrayOf("video/*", "application/x-matroska", "application/octet-stream"))
        try { startActivityForResult(pick, request) }
        catch (_: ActivityNotFoundException) { showMessage("Aucun sélecteur de fichiers Android n’est disponible.", true) }
    }
    @Deprecated("Activity result bridge for the native Activity implementation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.scheme != "content") { showMessage("Choisis un fichier avec le sélecteur Android.", true); return }
        rememberPermission(uri, data.flags)
        when (requestCode) {
            OPEN_VIDEO -> openVideo(uri)
            OPEN_SUBTITLE -> importSubtitle(uri)
        }
    }
    private fun rememberPermission(uri: Uri, flags: Int) {
        val read = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (read != 0) try { contentResolver.takePersistableUriPermission(uri, read) }
        catch (_: SecurityException) { /* Some providers only grant session access. */ }
    }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "Fichier"
        }
        return "Fichier"
    }
    private fun openVideo(uri: Uri) {
        session.pause(); val token = ++requestToken
        showMessage("Ouverture du fichier…", false)
        io.execute {
            try {
                val name = displayName(uri)
                main.post {
                    if (closed || token != requestToken) return@post
                    val previous = prefs.lastMedia()?.takeIf { it.uri == uri.toString() }
                    session.open(previous?.copy(title = name) ?: SavedMedia(uri.toString(), name))
                    showMessage("Vidéo ouverte. Choisis une piste de sous-titres, puis appuie sur Lire.", false)
                }
            } catch (_: Exception) {
                main.post { if (!closed && token == requestToken) showMessage("Impossible d’accéder à ce fichier. Sélectionne-le à nouveau ou copie-le dans le stockage du téléphone.", true) }
            }
        }
    }
    private fun importSubtitle(uri: Uri, charset: String? = null) {
        if (session.media == null) return
        session.pause(); val token = ++requestToken
        showMessage("Vérification du fichier de sous-titres…", false)
        io.execute {
            var created: File? = null
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= SubtitleImport.MAX_BYTES) { "Ce fichier dépasse 8 Mio." }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IllegalArgumentException("Impossible d’ouvrir le fichier.")
                val decoded = SubtitleImport.decode(bytes, displayName(uri), charset)
                val directory = File(filesDir, "subtitles").apply { mkdirs() }
                val file = File.createTempFile("subtitle_", ".${decoded.kind.extension}", directory)
                created = file; file.writeText(decoded.text, Charsets.UTF_8)
                main.post {
                    if (closed || token != requestToken) { file.delete(); return@post }
                    session.attachSubtitle(file.absolutePath, decoded.kind.mime)
                    // Only app-owned imported subtitle copies are eligible for cleanup.
                    io.execute {
                        directory.listFiles()?.filter { it != file }?.sortedByDescending { it.lastModified() }
                            ?.drop(2)?.forEach { it.delete() }
                    }
                    showMessage("Sous-titres ajoutés (${decoded.kind.extension.uppercase()}, ${decoded.charset}). Appuie sur Lire. La langue de la voix se choisit dans les réglages.", false)
                }
            } catch (_: SubtitleImport.EncodingRequired) {
                main.post { if (!closed && token == requestToken) chooseEncoding(uri) }
            } catch (e: Exception) {
                created?.delete()
                main.post { if (!closed && token == requestToken) showMessage(e.message ?: "Le fichier de sous-titres ne peut pas être importé.", true) }
            }
        }
    }
    private fun chooseEncoding(uri: Uri) {
        val names = arrayOf("windows-1252", "UTF-8", "UTF-16LE", "UTF-16BE", "Shift_JIS", "GB18030", "Big5", "windows-1256", "windows-1251")
        AlertDialog.Builder(this).setTitle("Choisir l’encodage du fichier")
            .setItems(names) { _, which -> importSubtitle(uri, names[which]) }
            .setNegativeButton("Annuler", null).show()
        showMessage("L’encodage n’est pas reconnu automatiquement. Windows-1252 est fréquent pour d’anciens fichiers français ; les autres langues peuvent employer un autre encodage.", false)
    }
    private fun chooseTrack(type: Int) {
        session.pause()
        data class Entry(val label: String, val group: Tracks.Group?, val index: Int = 0, val disabled: Boolean = false)
        val entries = mutableListOf(Entry("Sélection automatique", null))
        if (type == C.TRACK_TYPE_TEXT) entries += Entry("Désactiver les sous-titres", null, disabled = true)
        session.player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) {
                    val f = group.getTrackFormat(i)
                    val language = f.language?.let { Locale.forLanguageTag(it).getDisplayName(Locale.FRENCH) } ?: "langue non indiquée"
                    val selected = if (group.isTrackSelected(i)) " — sélectionnée" else ""
                    entries += Entry("${f.label ?: "Piste ${entries.size}"} — $language — ${f.sampleMimeType ?: "format inconnu"}$selected", group, i)
                }
            }
        }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_TEXT) "Piste de sous-titres" else "Piste audio du film")
            .setItems(entries.map { it.label }.toTypedArray()) { _, index ->
                val e = entries[index]; session.selectTrack(type, e.group, e.index, e.disabled)
                showMessage("Piste choisie. Vérifie que sa langue correspond à celle de la voix, puis appuie sur Lire.", false)
            }.setNegativeButton("Fermer", null).show()
    }
    private fun settingsDialog() {
        session.pause()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        fun check(label: String, value: Boolean, change: (Boolean) -> Unit) {
            column.addView(CheckBox(this).apply {
                text = label; textSize = 19f; minHeight = dp(56); isChecked = value
                setOnCheckedChangeListener { _, checked -> change(checked) }
            })
        }
        check("Vocaliser les sous-titres", prefs.narration) { prefs.narration = it }
        check("Mode confort : terminer chaque phrase, avec une pause de la vidéo si nécessaire", prefs.comfort) { prefs.comfort = it }
        column.addView(text("Sans le mode confort, la vidéo continue mais les phrases trop longues peuvent être coupées.", 16f))
        check("Afficher aussi les sous-titres à l’écran", prefs.showSubtitles) { prefs.showSubtitles = it }
        check("Ne pas lire les indications entre crochets, par exemple [musique]", prefs.skipDescriptions) { prefs.skipDescriptions = it }
        fun slider(label: String, from: Int, to: Int, value: Int, unit: String, change: (Int) -> Unit) {
            val caption = text("$label : $value$unit", 18f); column.addView(caption)
            column.addView(SeekBar(this).apply {
                id = View.generateViewId(); caption.labelFor = id
                contentDescription = label; max = to - from; progress = value - from; minimumHeight = dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                        caption.text = "$label : ${p + from}$unit"
                        seekBar.contentDescription = caption.text
                        if (fromUser) change(p + from)
                    }
                })
            })
        }
        slider("Vitesse de la voix", 60, 200, (prefs.speechRate * 100).toInt(), " %") { prefs.speechRate = it / 100f }
        slider("Volume de la voix", 10, 100, (prefs.voiceVolume * 100).toInt(), " %") { prefs.voiceVolume = it / 100f }
        slider("Volume habituel du film", 0, 100, (prefs.movieVolume * 100).toInt(), " %") { prefs.movieVolume = it / 100f }
        slider("Son du film conservé pendant la voix", 0, 100, (prefs.duckVolume * 100).toInt(), " %") { prefs.duckVolume = it / 100f }
        slider("Taille des sous-titres", 22, 60, prefs.subtitleSp.toInt(), " sp") { prefs.subtitleSp = it.toFloat() }
        column.addView(button("Choisir une voix hors ligne") { voiceDialog() })
        column.addView(button("Écouter un essai de la voix") { session.refreshSettings(); session.previewVoice() })
        column.addView(button("Ouvrir les réglages vocaux Android") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        column.addView(button("Retirer le fichier de sous-titres ajouté") {
            session.attachSubtitle(null, null); showMessage("Fichier ajouté retiré. Les pistes intégrées à la vidéo restent disponibles.", false)
        })
        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this).setTitle("Voix et confort").setView(scroll)
            .setPositiveButton("Appliquer et fermer") { _, _ -> }.create()
        dialog.setOnDismissListener { if (!closed) { session.refreshSettings(); applyCaptionStyle() } }
        dialog.show()
    }
    private fun voiceDialog() {
        session.speech.applyPreferences()
        val choices = session.speech.choices
        if (choices.isEmpty()) { showMessage("Installe d’abord une voix hors ligne dans les réglages vocaux Android.", true); return }
        AlertDialog.Builder(this).setTitle("Voix installées utilisables hors ligne")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.name == prefs.voiceName }) { dialog, index ->
                prefs.voiceName = choices[index].name; session.refreshSettings(); dialog.dismiss()
            }.setNegativeButton("Fermer", null).show()
    }
    private fun helpDialog() {
        session.pause()
        AlertDialog.Builder(this).setTitle("Première version de test")
            .setMessage("1. Ouvre une vidéo locale, par exemple MKV, MP4 ou WebM.\n\n2. Choisis sa piste de sous-titres, ou ajoute un fichier SRT, VTT, ASS/SSA.\n\n3. Choisis une voix dans la langue des sous-titres. Le lecteur ne traduit pas le texte.\n\n4. Appuie sur Lire. Le mode confort évite de couper les phrases, mais peut interrompre brièvement la vidéo.\n\nLes sous-titres en images (PGS/VobSub) et ceux incrustés dans l’image ne sont pas vocalisés. Le support des codecs audio et vidéo dépend du téléphone. Les fichiers protégés par DRM et les applications de streaming ne sont pas pris en charge.\n\nL’application demande des voix locales et n’a pas d’autorisation Internet. Le moteur vocal installé reste un composant externe. La lecture s’arrête lorsque l’application passe en arrière-plan.\n\nLes boutons ont des libellés pour TalkBack. Les sous-titres ne sont pas annoncés une seconde fois par l’interface.\n\nCette application vocalise le texte ; elle ne fournit pas d’audiodescription des scènes.")
            .setPositiveButton("Fermer", null).show()
    }
    companion object { private const val OPEN_VIDEO = 10; private const val OPEN_SUBTITLE = 11 }
}
