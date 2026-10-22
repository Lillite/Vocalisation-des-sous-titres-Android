package fr.soustitresvoix.player

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import fr.soustitresvoix.core.SubtitleImport
import fr.soustitresvoix.core.NarrationMachine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Accessible library controls and an independent immersive video presentation. */
@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var prefs: PlayerPreferences
    internal lateinit var session: PlaybackSession; private set
    private lateinit var playerView: PlayerView
    private lateinit var playButton: Button
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var titleView: TextView
    private lateinit var positionView: TextView
    private lateinit var positionSlider: SeekBar
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var requestToken = 0L
    private var stopped = false
    private var closed = false
    private var fullscreen = false
    private var cinema: CinemaView? = null
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var scrubbing = false
    private var message = "Ouvre une vidéo ou lance l’essai intégré. Les vidéos et les sous-titres restent sur le téléphone."
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
        fullscreen = savedInstanceState?.getBoolean("fullscreen", false) ?: false
        savedOrientation = savedInstanceState?.getInt("savedOrientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
        session = PlaybackSession(this, prefs, onChanged = { refreshUi() },
            onMessage = { text, error -> showMessage(text, error) }, onCaption = { })
        buildUi()
        if (!handleViewIntent(intent)) prefs.lastMedia()?.let { saved ->
            val scheme = Uri.parse(saved.uri).scheme
            if (scheme == "content" || scheme == "android.resource") session.open(saved) else prefs.clearMedia()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleViewIntent(intent) }
    private fun handleViewIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "content") return false
        rememberPermission(uri, intent.flags); openVideo(uri); return true
    }
    override fun onResume() {
        super.onResume(); stopped = false
        // External engines can update their package or voice data while this
        // activity is paused. Revalidate before allowing the next playback.
        session.refreshSettings(); applyWindowMode()
        main.removeCallbacks(ticker); main.post(ticker)
    }
    override fun onPause() {
        stopped = true; main.removeCallbacks(ticker)
        if (::session.isInitialized) session.pause()
        super.onPause()
    }
    override fun onDestroy() {
        closed = true; requestToken++; cinema?.close()
        main.removeCallbacksAndMessages(null); io.shutdownNow()
        if (::playerView.isInitialized) playerView.player = null
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); buildUi() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fullscreen", fullscreen); outState.putInt("savedOrientation", savedOrientation)
        super.onSaveInstanceState(outState)
    }
    private fun handleBack() {
        if (cinema?.back() == true) return
        if (fullscreen) setFullscreen(false) else finish()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyWindowMode() }
    internal fun setFullscreen(value: Boolean) {
        if (fullscreen == value) return
        if (value) savedOrientation = requestedOrientation
        fullscreen = value
        requestedOrientation = if (!value) savedOrientation else when (prefs.int("orientation", 1, 0, 2)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        buildUi()
    }
    private fun applyWindowMode() {
        // getDecorView initializes PhoneWindow's decor before requesting insets.
        // On Android 16 getInsetsController() otherwise dereferences a null decor
        // during onCreate; onWindowFocusChanged reapplies this after attachment.
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (fullscreen) {
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 20f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(64)
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { action() }
    }
    private fun buildUi() {
        session.finishPlaybackBenchmark("Disposition de l’écran modifiée : essai interrompu")
        cinema?.close(); cinema = null
        if (::playerView.isInitialized) (playerView.parent as? ViewGroup)?.removeView(playerView)
        else playerView = PlayerView(this).apply {
            useController = false; player = session.player
            isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (!closed) session.visual.setRenderSize(right - left, bottom - top)
            }
        }
        session.renderConfiguration = {
            val surface = playerView.videoSurfaceView
            listOf("plein écran=$fullscreen", "cadrage=${playerView.resizeMode}",
                "vue=${playerView.width}x${playerView.height}", "surface=${surface?.width}x${surface?.height}",
                "échelle=${surface?.scaleX},${surface?.scaleY}",
                "déplacement=${surface?.translationX},${surface?.translationY}")
        }
        applyWindowMode(); applyCaptionStyle()
        if (fullscreen) {
            cinema = CinemaView(this, session, prefs, playerView, { setFullscreen(false) },
                { settingsDialog() }, { chooseTrack(C.TRACK_TYPE_TEXT) }, { chooseTrack(C.TRACK_TYPE_AUDIO) })
            setContentView(cinema); cinema?.requestApplyInsets(); refreshUi(); return
        }
        // The preview must never inherit a crop chosen in the cinema view.
        // CinemaView restores the explicit per-video choice when entered again.
        playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        session.visual.setFramingMode(0)
        if (session.visual.margins.nonzero()) session.visual.crop(fr.soustitresvoix.core.BlackBars.Margins())
        playerView.subtitleView?.setPadding(0, 0, 0, 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18)); setPadding(dp(8), dp(8), dp(8), dp(8))
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
        titleView = text(session.media?.title ?: "Sous-titres en voix — Cinéma", 22f).apply { maxLines = 2 }
        root.addView(titleView)
        root.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f))
        statusView = text("En pause", 18f); root.addView(statusView)
        positionView = text("00:00 / 00:00", 16f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(positionView)
        positionSlider = SeekBar(this).apply {
            id = View.generateViewId(); max = 1000; minimumHeight = dp(48); contentDescription = "Position de la vidéo"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true; session.pause() }
                override fun onStopTrackingTouch(seekBar: SeekBar) { scrubbing = false; seekFraction(seekBar.progress) }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !scrubbing) seekFraction(progress)
                }
            })
        }
        root.addView(positionSlider)
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addNav(view: View) { navigation.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        addNav(button("Reculer\n10 s") { session.seek(session.player.currentPosition - 10_000) })
        playButton = button("Lire") {
            val state = session.machine.snapshot()
            if (state.wantsPlayback || state.speakingId != null) session.pause() else session.play()
        }.apply { id = R.id.play_button }
        addNav(playButton)
        addNav(button("Avancer\n10 s") { session.seek(session.player.currentPosition + 10_000) })
        root.addView(navigation)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageView = text(message, 18f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(if (messageIsError) Color.rgb(255, 205, 110) else Color.LTGRAY)
        }
        column.addView(messageView)
        column.addView(button("Plein écran cinéma") { setFullscreen(true) })
        column.addView(button("Ouvrir une vidéo") { pickDocument(OPEN_VIDEO) }.apply { id = R.id.open_video })
        column.addView(button("Essai intégré : sous-titres et mire de cadrage") {
            requestToken++
            session.open(SavedMedia("android.resource://$packageName/${R.raw.demonstration}", "Mire : quatre coins, cercle et sous-titres")); session.play()
        }.apply { id = R.id.demo_button })
        column.addView(button("Ajouter un fichier de sous-titres") {
            if (session.media == null) showMessage("Ouvre d’abord une vidéo.", false) else pickDocument(OPEN_SUBTITLE)
        })
        column.addView(button("Choisir la piste de sous-titres") { chooseTrack(C.TRACK_TYPE_TEXT) })
        column.addView(button("Choisir la piste audio du film") { chooseTrack(C.TRACK_TYPE_AUDIO) })
        column.addView(button("Répéter la dernière phrase — met en pause") { session.repeatLast() })
        column.addView(button("Reprendre explicitement la voix") { session.resumeVoice() })
        column.addView(button("Réglages de la voix et du confort") { settingsDialog() })
        column.addView(button("Aide et limites de cette version") { helpDialog() })
        root.addView(ScrollView(this).apply { isFillViewport = false; addView(column) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f))
        setContentView(root); root.requestApplyInsets(); refreshUi()
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
        if (closed || !::session.isInitialized) return
        val state = session.machine.snapshot()
        if (state.wantsPlayback || state.speakingId != null || session.isPreparing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session.prepareTick()
        cinema?.let { it.refresh(); return }
        if (!::playButton.isInitialized) return
        playButton.text = when { state.wantsPlayback -> "Pause"; state.speakingId != null -> "Arrêter la voix"; else -> "Lire" }
        val status = when {
            state.voiceSuspended -> "Voix en attente : utilise Reprendre explicitement la voix"
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
        titleView.text = session.media?.title ?: "Sous-titres en voix — Cinéma"
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
            messageView.text = value; messageView.setTextColor(if (error) Color.rgb(255, 205, 110) else Color.LTGRAY)
        }
        if (error && fullscreen && !closed) setFullscreen(false)
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
        when (requestCode) { OPEN_VIDEO -> openVideo(uri); OPEN_SUBTITLE -> importSubtitle(uri) }
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
        session.pause(); val token = ++requestToken; showMessage("Ouverture du fichier…", false)
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
        session.pause(); val token = ++requestToken; showMessage("Vérification du fichier de sous-titres…", false)
        io.execute {
            var created: File? = null
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
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
        AlertDialog.Builder(this).setTitle("Choisir l’encodage du fichier").setItems(names) { _, which -> importSubtitle(uri, names[which]) }
            .setNegativeButton("Annuler", null).show()
        showMessage("L’encodage n’est pas reconnu automatiquement. Windows-1252 est fréquent pour d’anciens fichiers français.", false)
    }
    private fun chooseTrack(type: Int) {
        session.pause()
        data class Entry(val label: String, val group: Tracks.Group?, val index: Int = 0, val disabled: Boolean = false)
        val entries = mutableListOf(Entry("Sélection automatique", null))
        if (type == C.TRACK_TYPE_TEXT) entries += Entry("Désactiver les sous-titres", null, disabled = true)
        session.player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) if (group.isTrackSupported(i)) {
                val f = group.getTrackFormat(i)
                val language = f.language?.let { Locale.forLanguageTag(it).getDisplayName(Locale.FRENCH) } ?: "langue non indiquée"
                val selected = if (group.isTrackSelected(i)) " — sélectionnée" else ""
                entries += Entry("${f.label ?: "Piste ${entries.size}"} — $language — ${f.sampleMimeType ?: "format inconnu"}$selected", group, i)
            }
        }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_TEXT) "Piste de sous-titres" else "Piste audio du film")
            .setItems(entries.map { it.label }.toTypedArray()) { _, index ->
                val e = entries[index]; session.selectTrack(type, e.group, e.index, e.disabled)
                showMessage("Piste choisie. Vérifie sa langue, puis appuie sur Lire.", false)
            }.setNegativeButton("Fermer", null).show()
    }
    private fun settingsDialog() {
        session.pause()
        var pendingBenchmarkSeconds: Int? = null
        lateinit var dialog: AlertDialog
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        fun check(label: String, value: Boolean, change: (Boolean) -> Unit) {
            column.addView(CheckBox(this).apply {
                text = label; textSize = 19f; minHeight = dp(56); isChecked = value
                setOnCheckedChangeListener { _, checked -> change(checked) }
            })
        }
        check("Vocaliser les sous-titres", prefs.narration) { session.beforeSettingsChange(); prefs.narration = it }
        val modeButton = button("Mode vocal : ${prefs.mode}") { }
        modeButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Synchronisation de la voix")
                .setItems(arrayOf("Confort : phrases complètes", "Continu : peut couper les phrases", "Adaptatif : accélération plafonnée puis pause")) { _, index ->
                    session.beforeSettingsChange(); prefs.setMode(NarrationMachine.Mode.values()[index]); session.refreshSettings(); modeButton.text = "Mode vocal : ${prefs.mode}"
                }.show()
        }
        column.addView(modeButton)
        column.addView(text("Confort et Adaptatif conservent les phrases complètes. Le mode Continu peut couper les phrases trop longues.", 16f))
        check("Afficher aussi les sous-titres à l’écran", prefs.showSubtitles) { prefs.showSubtitles = it }
        check("Ne pas lire les indications entre crochets, par exemple [musique]", prefs.skipDescriptions) { session.beforeSettingsChange(); prefs.skipDescriptions = it }
        fun slider(label: String, from: Int, to: Int, value: Int, unit: String, change: (Int) -> Unit) {
            val caption = text("$label : $value$unit", 18f); column.addView(caption)
            column.addView(SeekBar(this).apply {
                id = View.generateViewId(); caption.labelFor = id
                contentDescription = label; max = to - from; progress = value - from; minimumHeight = dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                        caption.text = "$label : ${p + from}$unit"; seekBar.contentDescription = caption.text
                        if (fromUser) change(p + from)
                    }
                })
            })
        }
        slider("Vitesse de la voix", 60, 200, (prefs.speechRate * 100).roundToInt(), " %") { session.beforeSettingsChange(); prefs.speechRate = it / 100f }
        slider("Volume de la voix", 10, 100, (prefs.voiceVolume * 100).roundToInt(), " %") { prefs.voiceVolume = it / 100f }
        slider("Volume habituel du film", 0, 100, (prefs.movieVolume * 100).roundToInt(), " %") { prefs.movieVolume = it / 100f }
        slider("Son du film conservé pendant la voix", 0, 100, (prefs.duckVolume * 100).roundToInt(), " %") { prefs.duckVolume = it / 100f }
        slider("Taille des sous-titres", 22, 60, prefs.subtitleSp.toInt(), " sp") { prefs.subtitleSp = it.toFloat() }
        column.addView(button("Choisir une voix hors ligne") { voiceDialog() })
        column.addView(button("Écouter un essai de la voix") { session.refreshSettings(); session.previewVoice() })
        column.addView(button("Ouvrir les réglages vocaux Android") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        column.addView(button("Retirer le fichier de sous-titres ajouté") {
            session.attachSubtitle(null, null); showMessage("Fichier ajouté retiré. Les pistes intégrées restent disponibles.", false)
        })
        AdvancedSettings(this, session, prefs) { seconds ->
            pendingBenchmarkSeconds = seconds
            dialog.dismiss()
        }.populate(column)
        dialog = AlertDialog.Builder(this).setTitle("Voix et confort")
            .setView(ScrollView(this).apply { addView(column) }).setPositiveButton("Appliquer et fermer") { _, _ -> }.create()
        dialog.setOnDismissListener {
            if (!closed) {
                if (!session.isPreparing) session.refreshSettings()
                applyCaptionStyle(); applyWindowMode()
                pendingBenchmarkSeconds?.let { session.startPlaybackBenchmark(it) }
            }
        }
        dialog.show()
    }
    private fun voiceDialog() {
        session.speech.applyPreferences()
        val choices = session.speech.choices
        if (choices.isEmpty()) { showMessage("Installe une voix locale dans le moteur choisi, puis actualise les voix.", true); return }
        AlertDialog.Builder(this).setTitle("Voix installées utilisables hors ligne")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.name == prefs.voiceName }) { dialog, index ->
                session.beforeSettingsChange(); prefs.voiceName = choices[index].name; session.refreshSettings(); dialog.dismiss()
            }.setNegativeButton("Fermer", null).show()
    }
    private fun helpDialog() {
        session.pause()
        AlertDialog.Builder(this).setTitle("Sous-titres en voix 0.3.2 — mode d’emploi et limites")
            .setMessage("Ouvre une vidéo, choisis une piste textuelle et une voix dans la même langue. Le lecteur ne traduit pas.\n\nÉtirement intégral : toute l’image est visible, avec proportions modifiées. La protection interdit le zoom et les déplacements accidentels. Un recadrage de marges exige une confirmation distincte et peut être annulé.\n\nAdaptatif : la vitesse est calculée à partir du WAV préparé ou d’une estimation calibrée en lecture directe. Le plafond est réglable de 100 à 250 % de la vitesse de base, 180 % au départ. L’algorithme réserve le temps des segments restants et respecte la tolérance de retard choisie. Une pause reste possible si la phrase ne tient pas au plafond choisi ; aucun dialogue n’est résumé.\n\nPréparation : indexer le texte, puis préparer cinq minutes ou toute la vidéo. Le film reste en pause et ne reprend que sur commande. Le cache persiste mais sa capacité est limitée ; préparer toute la vidéo ne garantit pas que tous les fichiers audio tiennent encore en cache. Reste dans l’application pendant la préparation.\n\nLes filtres restent facultatifs. La ponctuation et les silences naturels ne sont pas supprimés. Si un modèle est modifié à l’intérieur d’un moteur, utilise le bouton signalant sa mise à jour.\n\nPGS, VobSub et sous-titres incrustés ne sont pas vocalisés. Pas de DRM, d’OCR, de traduction, d’audiodescription ni de lecture en arrière-plan. Les essais de décodeurs utilisent ceux déjà présents sur le téléphone. Les effets visuels sont réservés au SDR reconnu.\n\nLe lecteur n’a pas d’autorisation Internet. Les moteurs vocaux sont des applications externes. Aucun runtime neuronal vocal GPU/NPU n’est intégré. Les profils règlent l’anticipation et le cache, pas le matériel interne d’un moteur tiers.\n\nÉdition 0.3 installable à côté de la 0.1 et de la 0.2, avec réglages séparés. Aucun fichier original n’est modifié.")
            .setPositiveButton("Fermer", null).show()
    }
    companion object { private const val OPEN_VIDEO = 10; private const val OPEN_SUBTITLE = 11 }
}
