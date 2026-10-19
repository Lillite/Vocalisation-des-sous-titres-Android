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
    private lateinit var rootLayout: LinearLayout
    private lateinit var videoHost: FrameLayout
    private lateinit var cinema: CinemaControls
    private val normalViews = mutableListOf<View>()
    private var previousOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousBrightness = -1f
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var scrubbing = false
    private var message = "Ouvre une vidéo ou lance l’essai intégré. Aucun compte ni connexion Internet n’est nécessaire au lecteur."
    private var messageIsError = false
    private var lastSaveMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (closed || stopped) return
            refreshUi(); session.tickPreparation()
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
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { if (!handleCinemaBack()) finish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }
        if (savedInstanceState?.getBoolean("cinema") == true) setFullscreen(true)
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
        if (::rootLayout.isInitialized) applyWindowMode()
        main.removeCallbacks(ticker); main.post(ticker)
    }
    override fun onPause() {
        stopped = true; main.removeCallbacks(ticker)
        if (::session.isInitialized) session.pause()
        super.onPause()
    }
    override fun onDestroy() {
        closed = true; requestToken++
        if (::cinema.isInitialized) cinema.dispose()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        main.removeCallbacksAndMessages(null); io.shutdownNow()
        if (::playerView.isInitialized) playerView.player = null
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::cinema.isInitialized) { cinema.setZoom(1f); applyWindowMode(); rootLayout.requestApplyInsets() }
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
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
        }
        val root = rootLayout
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int; val top: Int; val right: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = i.left; top = i.top; right = i.right; bottom = i.bottom
            } else {
                @Suppress("DEPRECATION")
                val v = intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                left = v[0]; top = v[1]; right = v[2]; bottom = v[3]
            }
            if (fullscreen) {
                view.setPadding(0, 0, 0, 0)
                cinema.safeInsets(left, top, right, bottom)
                playerView.subtitleView?.setPadding(left + dp(12), top, right + dp(12), bottom + dp(8))
            } else {
                view.setPadding(left + dp(8), top + dp(8), right + dp(8), bottom + dp(8))
                cinema.safeInsets(0, 0, 0, 0)
                playerView.subtitleView?.setPadding(0, 0, 0, 0)
            }
            insets
        }
        fun normal(view: View) { normalViews += view; root.addView(view) }
        titleView = text(session.media?.title ?: "Sous-titres en voix — Cinéma", 22f).apply { maxLines = 2 }
        normal(titleView)
        playerView = PlayerView(this).apply {
            useController = false; player = session.player
            isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipChildren = true; clipToPadding = true
        }
        applyCaptionStyle()
        videoHost = FrameLayout(this).apply { id = R.id.video_host; setBackgroundColor(Color.BLACK); clipChildren = true }
        videoHost.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        cinema = CinemaControls(this, playerView, session, prefs, { setFullscreen(false) }, { cinemaMenu() }).apply { visibility = View.GONE }
        videoHost.addView(cinema, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoHost, LinearLayout.LayoutParams(-1, 0, 0.9f))
        statusView = text("En pause", 18f); normal(statusView)
        positionView = text("00:00 / 00:00", 16f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }; normal(positionView)
        positionSlider = SeekBar(this).apply {
            max = 1000; minimumHeight = dp(48); contentDescription = "Position de la vidéo"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) { scrubbing = true; session.pause() }
                override fun onStopTrackingTouch(s: SeekBar) { scrubbing = false; seekFraction(s.progress) }
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser && !scrubbing) seekFraction(progress) }
            })
        }; normal(positionSlider)
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addNav(view: View) { navigation.addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }
        addNav(button("Reculer 10 s") { session.seek(session.player.currentPosition - 10_000) })
        playButton = button("Lire") {
            val state = session.machine.snapshot()
            if (state.wantsPlayback || state.speakingId != null) session.pause()
            else if (state.voiceResumeRequired) session.resumeVoice() else session.play()
        }.apply { id = R.id.play_button }
        addNav(playButton); addNav(button("Avancer 10 s") { session.seek(session.player.currentPosition + 10_000) }); normal(navigation)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageView = text(message, 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; column.addView(messageView)
        column.addView(button("Plein écran cinéma") { setFullscreen(true) }.apply { id = R.id.enter_cinema })
        column.addView(button("Ouvrir une vidéo") { pickDocument(OPEN_VIDEO) }.apply { id = R.id.open_video })
        column.addView(button("Essai intégré : vidéo sous-titrée") {
            requestToken++; session.open(SavedMedia("android.resource://$packageName/${R.raw.demonstration}", "Essai intégré")); session.play()
        }.apply { id = R.id.demo_button })
        column.addView(button("Ajouter un fichier de sous-titres") {
            if (session.media == null) showMessage("Ouvre d’abord une vidéo.", false) else pickDocument(OPEN_SUBTITLE)
        })
        column.addView(button("Choisir la piste de sous-titres") { chooseTrack(C.TRACK_TYPE_TEXT) })
        column.addView(button("Choisir la piste audio du film") { chooseTrack(C.TRACK_TYPE_AUDIO) })
        column.addView(button("Répéter la dernière phrase — met en pause") { session.repeatLast() })
        column.addView(button("Reprendre la voix") { session.resumeVoice() })
        column.addView(button("Réglages de la voix et du confort") { settingsDialog() })
        column.addView(button("Moteurs vocaux : choisir et télécharger") { engineDialog() })
        column.addView(button("Diagnostic et performances mesurées") { diagnosticsDialog() })
        column.addView(button("Aide et limites de cette version") { helpDialog() })
        extraControls = ScrollView(this).apply { addView(column) }; normalViews += extraControls
        root.addView(extraControls, LinearLayout.LayoutParams(-1, 0, 1.1f))
        setContentView(root); applyWindowMode(); root.requestApplyInsets(); refreshUi()
    }
    internal fun setFullscreen(value: Boolean) {
        if (value == fullscreen) return
        if (value) { previousOrientation = requestedOrientation; previousBrightness = window.attributes.screenBrightness }
        fullscreen = value
        normalViews.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        cinema.visibility = if (value) View.VISIBLE else View.GONE
        (videoHost.layoutParams as LinearLayout.LayoutParams).apply { weight = if (value) 1f else 0.9f }
        if (value) {
            requestedOrientation = when (prefs.cinemaOrientation) {
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            cinema.setVisible(true)
        } else {
            cinema.setLocked(false); cinema.setZoom(1f)
            requestedOrientation = previousOrientation
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
        applyWindowMode(); rootLayout.requestApplyInsets(); rootLayout.requestLayout()
    }
    @Suppress("DEPRECATION")
    private fun applyWindowMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = if (fullscreen) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT }
            window.insetsController?.apply {
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (fullscreen) View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && ::rootLayout.isInitialized) applyWindowMode() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("cinema", fullscreen); super.onSaveInstanceState(outState) }
    internal fun handleCinemaBack(): Boolean {
        if (!fullscreen) return false
        if (cinema.locked) cinema.setLocked(false) else setFullscreen(false)
        return true
    }
    @Deprecated("Used by Android versions before predictive back")
    override fun onBackPressed() { if (!handleCinemaBack()) super.onBackPressed() }
    private fun cinemaMenu() {
        cinema.setMenuOpen(true)
        val labels = arrayOf("Réglages rapides : son et voix", "Piste de sous-titres", "Piste audio", "Voix, adaptation et filtres", "Orientation, gestes et commandes", "Diagnostic des performances")
        val dialog = AlertDialog.Builder(this).setTitle("Lecture cinéma").setItems(labels) { _, i ->
            when (i) { 0 -> quickSettings(); 1 -> chooseTrack(C.TRACK_TYPE_TEXT); 2 -> chooseTrack(C.TRACK_TYPE_AUDIO); 3 -> settingsDialog(); 4 -> cinemaSettings(); 5 -> diagnosticsDialog() }
        }.setNegativeButton("Fermer", null).create()
        dialog.setOnDismissListener { cinema.setMenuOpen(false) }; dialog.show()
    }
    private fun cinemaSettings() {
        session.pause()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        box.addView(text("Masquage des commandes : 0 = jamais. Avec TalkBack, les commandes restent affichées.", 17f))
        addSettingSlider(box, "Délai de masquage", 0, 30, prefs.hideSeconds, " s") { prefs.hideSeconds = it }
        box.addView(CheckBox(this).apply { text = "Activer les gestes (double toucher, volume, luminosité, zoom)"; textSize = 18f; minHeight = dp(56); isChecked = prefs.gestures; setOnCheckedChangeListener { _, b -> prefs.gestures = b } })
        val choices = arrayOf("Suivre le téléphone", "Paysage en plein écran", "Verrouiller l’orientation actuelle")
        choices.forEachIndexed { index, label -> box.addView(button(label) {
            prefs.cinemaOrientation = index
            requestedOrientation = when(index) { 1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; 2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED; else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }) }
        AlertDialog.Builder(this).setTitle("Écran et commandes").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Fermer", null).show()
    }
    private fun addSettingSlider(box: LinearLayout, label: String, from: Int, to: Int, value: Int, unit: String, change: (Int) -> Unit) {
        val caption = text("$label : $value$unit", 17f); box.addView(caption)
        val bar = SeekBar(this).apply { max = to - from; progress = (value - from).coerceIn(0, to - from); minimumHeight = dp(48); contentDescription = caption.text }
        fun applyValue(v: Int) { caption.text = "$label : $v$unit"; bar.contentDescription = caption.text; change(v) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) applyValue(p + from) }
        }); box.addView(bar)
        val row = LinearLayout(this)
        val step = if (to - from > 500) 50 else if (to - from > 50) 5 else 1
        row.addView(button("Moins : $label") { bar.progress = (bar.progress - step).coerceAtLeast(0); applyValue(bar.progress + from) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Plus : $label") { bar.progress = (bar.progress + step).coerceAtMost(bar.max); applyValue(bar.progress + from) }, LinearLayout.LayoutParams(0, -2, 1f)); box.addView(row)
    }
    private fun quickSettings() {
        cinema.setMenuOpen(true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        addSettingSlider(box, "Film", 0, 100, (prefs.movieVolume * 100).toInt(), " %") { prefs.movieVolume = it / 100f; session.updateVolumes() }
        addSettingSlider(box, "Voix (prochain segment)", 10, 100, (prefs.voiceVolume * 100).toInt(), " %") { prefs.voiceVolume = it / 100f }
        addSettingSlider(box, "Film pendant la voix", 0, 100, (prefs.duckVolume * 100).toInt(), " %") { prefs.duckVolume = it / 100f; session.updateVolumes() }
        val brightness = window.attributes.screenBrightness.takeIf { it >= 0 }
            ?: (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        addSettingSlider(box, "Luminosité de cette fenêtre", 2, 100, (brightness * 100).toInt().coerceIn(2, 100), " %") {
            window.attributes = window.attributes.apply { screenBrightness = it / 100f }
        }
        val d = AlertDialog.Builder(this).setTitle("Réglages sans arrêter le film").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Fermer", null).create()
        d.setOnDismissListener { cinema.setMenuOpen(false) }; d.show()
    }
    private fun engineDialog() {
        session.pause()
        val engines = session.speech.engines
        val labels = engines.map { "${it.label} — ${it.name}" } + "Télécharger d’autres moteurs : sources officielles"
        AlertDialog.Builder(this).setTitle("Moteur vocal du lecteur seulement").setItems(labels.toTypedArray()) { _, i ->
            if (i < engines.size) session.speech.switchEngine(engines[i].name) else engineDownloads()
        }.setNegativeButton("Fermer", null).show()
    }
    private fun engineDownloads() {
        val entries = listOf(
            "Sherpa-ONNX : voix française Miro (écoutes et APK arm64-v8a)" to "https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-miro-high.html",
            "Sherpa-ONNX : voix française Tom (écoutes et APK arm64-v8a)" to "https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-tom-medium.html",
            "Acapela TTS Voices (Google Play, voix payantes)" to "https://play.google.com/store/apps/details?id=com.acapelagroup.android.tts",
            "Reconnaissance et synthèse vocales Google (Google Play)" to "https://play.google.com/store/apps/details?id=com.google.android.tts")
        AlertDialog.Builder(this).setTitle("Ouvrir une source officielle dans le navigateur").setItems(entries.map { it.first }.toTypedArray()) { _, i ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entries[i].second))) }
            catch (_: ActivityNotFoundException) { showMessage("Aucun navigateur disponible.", true) }
        }.setNegativeButton("Fermer", null).show()
    }
    private fun diagnosticsDialog() {
        val report = session.metrics.report(session.speech)
        AlertDialog.Builder(this).setTitle("Mesures locales — cette session").setMessage(report)
            .setPositiveButton("Fermer", null).setNeutralButton("Partager ce rapport") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Partager les mesures"))
            }.show()
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
        if (::cinema.isInitialized) cinema.update(session.media?.title ?: "Sous-titres en voix", status)
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
        if (error && fullscreen && !closed) { setFullscreen(false) }
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
        check("Mode adaptatif : accélération limitée, puis pause confort si nécessaire", prefs.adaptive) { prefs.adaptive = it }
        column.addView(text("Le mode adaptatif utilise les durées indexées quand elles sont disponibles ; sinon il conserve le mode confort. Il ne résume et ne supprime aucun dialogue.", 16f))
        addSettingSlider(column, "Accélération maximale par rapport à la vitesse habituelle", 100, 180, prefs.adaptivePercent, " %") { prefs.adaptivePercent = it }
        check("Préparer les prochaines répliques dans un cache vocal local", prefs.cacheEnabled) { prefs.cacheEnabled = it }
        addSettingSlider(column, "Anticipation", 10, 120, prefs.lookAheadSeconds, " s") { prefs.lookAheadSeconds = it }
        addSettingSlider(column, "Limite du cache", 16, 128, prefs.cacheMb, " Mio") { prefs.cacheMb = it }
        column.addView(button("Vider le cache vocal") { session.speech.clearCache(); showMessage("Cache vocal vidé. Aucun fichier vidéo supprimé.", false) })
        column.addView(button("Reprise après une pause : choisir le comportement") {
            val choices = arrayOf("Début de la réplique", "Début du segment interrompu", "Attendre la commande Reprendre la voix")
            AlertDialog.Builder(this).setTitle("Reprise vocale").setSingleChoiceItems(choices, prefs.resumePolicy.ordinal) { d, i -> prefs.resumePolicy = fr.soustitresvoix.core.NarrationMachine.ResumePolicy.values()[i]; d.dismiss() }.setNegativeButton("Fermer", null).show()
        })
        addSettingSlider(column, "Entrée de la baisse du film", 0, 1000, prefs.attackMs, " ms") { prefs.attackMs = it }
        addSettingSlider(column, "Remontée du film", 0, 2000, prefs.releaseMs, " ms") { prefs.releaseMs = it }
        addSettingSlider(column, "Maintien entre deux répliques (anti-pompage)", 0, 2000, prefs.duckHoldMs, " ms") { prefs.duckHoldMs = it }
        check("Ne pas lire les noms explicites en MAJUSCULES suivis de deux-points", prefs.skipSpeakers) { prefs.skipSpeakers = it }
        check("Ne pas lire les lignes de chanson marquées ♪ ou ♫", prefs.skipSongs) { prefs.skipSongs = it }
        check("Ne pas lire les notes explicitement marquées NDT", prefs.skipNotes) { prefs.skipNotes = it }
        column.addView(button("Aperçu des filtres sur la réplique courante") {
            val raw = session.lastCaption.ifBlank { "MARIE: Bonjour.\n[musique]\n♪ Une chanson ♪\nNDT: une note." }
            val filtered = fr.soustitresvoix.core.SpeechPlanning.filter(raw, prefs.filters)
            AlertDialog.Builder(this).setTitle("Aperçu sans modifier le fichier").setMessage("Original :\n$raw\n\nVocalisé :\n${filtered.ifBlank { "(rien)" }}").setPositiveButton("Fermer", null).show()
        })
        column.addView(button("Choisir ou télécharger un moteur vocal") { engineDialog() })
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
        AlertDialog.Builder(this).setTitle("Version Cinéma 0.2 — aide et limites")
            .setMessage("1. Ouvre une vidéo locale, par exemple MKV, MP4 ou WebM.\n\n2. Choisis sa piste de sous-titres, ou ajoute un fichier SRT, VTT, ASS/SSA.\n\n3. Choisis une voix dans la langue des sous-titres. Le lecteur ne traduit pas le texte.\n\n4. Appuie sur Lire. Le mode confort évite de couper les phrases, mais peut interrompre brièvementment la vidéo.\n\nLes sous-titres en images (PGS/VobSub) et ceux incrustés dans l’image ne sont pas vocalisés. Le support des codecs audio et vidéo dépend du téléphone. Les fichiers protégés par DRM et les applications de streaming ne sont pas pris en charge.\n\nL’application demande des voix locales et n’a pas d’autorisation Internet. Le moteur vocal installé reste un composant externe. La lecture s’arrête lorsque l’application passe en arrière-plan.\n\nLes boutons ont des libellés pour TalkBack. Les sous-titres ne sont pas annoncés une seconde fois par l’interface.\n\nCette application vocalise le texte ; elle ne fournit pas d’audiodescription des scènes.")
            .setPositiveButton("Fermer", null).show()
    }
    companion object { private const val OPEN_VIDEO = 10; private const val OPEN_SUBTITLE = 11 }
}
