package fr.soustitresvoix.player

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.media3.ui.SubtitleView
import androidx.media3.common.text.Cue
import fr.soustitresvoix.core.SubtitleImport
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Accessible library controls and an independent immersive video presentation. */
@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var translationControls: SubtitleTranslationControls
    private lateinit var localSrtTranslationControls: LocalSrtTranslationControls
    private lateinit var localGgufBenchmarkControls: LocalGgufBenchmarkControls
    private lateinit var preparationDiagnostics: PreparationDiagnosticsController
    private lateinit var prefs: PlayerPreferences
    internal lateinit var session: PlaybackSession; private set
    private lateinit var playerView: PlayerView
    private lateinit var captionOverlay: SubtitleView
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
    private var buildingUi = false
    private var uiRebuildPending = false
    private val rebuildUiAfterCurrentPass = Runnable { buildUi() }
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var scrubbing = false
    private var message = "Ouvre une vidéo ou lance l’essai intégré. La traduction en ligne envoie seulement le texte, après confirmation."
    private var messageIsError = false
    private var lastSaveMs = 0L
    private var pendingRepairUri: String? = null
    private var repairDialog: AlertDialog? = null
    private var resumeDialog: AlertDialog? = null
    private var pendingResumeOffer = false
    private var resumeOfferUri: String? = null
    private var pendingSubtitleExport: String? = null
    private val diagnosticModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::preparationDiagnostics.isInitialized && !closed) preparationDiagnostics.selectedModel(uri)
    }
    private val diagnosticVideoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::preparationDiagnostics.isInitialized && !closed) preparationDiagnostics.selectedVideo(uri)
    }
    private val translationModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::translationControls.isInitialized && !closed) translationControls.selectedLocalModel(uri)
    }
    private val localSrtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::localSrtTranslationControls.isInitialized && !closed) localSrtTranslationControls.selectedSubtitle(uri)
    }
    private val localSrtModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::localSrtTranslationControls.isInitialized && !closed) localSrtTranslationControls.selectedModel(uri)
    }
    private val subtitleExportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val content = pendingSubtitleExport
        pendingSubtitleExport = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && content == null && !closed) {
            showMessage("L’export a été interrompu pendant le choix du fichier. Relance l’export depuis la traduction.", true)
        }
        if (result.resultCode == RESULT_OK && uri != null && content != null) {
            io.execute {
                val succeeded = runCatching {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Sortie indisponible" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }.isSuccess
                main.post {
                    if (!closed) showMessage(if (succeeded) "Sous-titres exportés en UTF-8." else "Impossible d’écrire l’export dans le fichier choisi. Vérifie l’emplacement et réessaie.", !succeeded)
                }
            }
        }
    }
    // Append new launchers after the existing ones so their Activity Result
    // registration keys keep matching an export already open before an update.
    private val localBenchmarkModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (::localGgufBenchmarkControls.isInitialized && !closed) localGgufBenchmarkControls.selectedModel(uri)
    }
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
        session.onMediaAccessFailure = { saved ->
            if (!closed && repairDialog?.isShowing != true) {
                val record = session.savedSessionRecord() ?: MediaSessionSnapshot(saved.uri, saved.title,
                    positionMs = saved.positionMs, subtitlePath = saved.subtitlePath, subtitleMime = saved.subtitleMime)
                repairDialog = SessionRepairControls.show(this, record,
                    onChooseReplacement = { pendingRepairUri = saved.uri; pickDocument(OPEN_VIDEO) },
                    onRetry = { session.open(saved, restoreSession = true) },
                    onForgetSession = { pendingRepairUri = null; session.forgetSession() },
                    alive = { !closed && session.media?.uri == saved.uri })
            }
        }
        session.onSessionRestored = {
            if (resumeOfferUri != null && resumeOfferUri == session.media?.uri) {
                resumeOfferUri = null; pendingResumeOffer = true
                main.post { offerSessionResume() }
            }
        }
        translationControls = SubtitleTranslationControls(this, session,
            launchLocalModelPicker = { translationModelPicker.launch(arrayOf("*/*")) },
            otherLocalPreparationBusy = {
                (::localSrtTranslationControls.isInitialized && localSrtTranslationControls.busy) ||
                    (::localGgufBenchmarkControls.isInitialized && localGgufBenchmarkControls.busy)
            },
            onExport = ::requestSubtitleExport)
        localSrtTranslationControls = LocalSrtTranslationControls(this,
            otherPreparationBusy = { session.isPreparing || (::localGgufBenchmarkControls.isInitialized && localGgufBenchmarkControls.busy) },
            launchSubtitlePicker = { localSrtPicker.launch(arrayOf("*/*")) },
            launchModelPicker = { localSrtModelPicker.launch(arrayOf("*/*")) },
            onExport = ::requestSubtitleExport, onChanged = { refreshUi() })
        localGgufBenchmarkControls = LocalGgufBenchmarkControls(this,
            otherPreparationBusy = { session.isPreparing || localSrtTranslationControls.busy ||
                (::preparationDiagnostics.isInitialized && preparationDiagnostics.isBusy) },
            launchModelPicker = { localBenchmarkModelPicker.launch(arrayOf("*/*")) }, onChanged = { refreshUi() })
        preparationDiagnostics = PreparationDiagnosticsController(this,
            playbackIdle = {
                val state = session.machine.snapshot()
                !session.player.isPlaying && !state.wantsPlayback && state.speakingId == null && !session.isPreparing &&
                    !localSrtTranslationControls.busy && !localGgufBenchmarkControls.busy
            },
            launchModelPicker = { diagnosticModelPicker.launch(arrayOf("*/*")) },
            launchVideoPicker = { diagnosticVideoPicker.launch(arrayOf("video/*", "application/octet-stream")) })
        buildUi()
        if (!handleViewIntent(intent)) prefs.lastMedia()?.let { saved ->
            val scheme = Uri.parse(saved.uri).scheme
            if (scheme == "content" || scheme == "android.resource") {
                resumeOfferUri = saved.uri; session.open(saved, restoreSession = true)
            } else prefs.clearMedia()
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
        offerSessionResume()
    }
    override fun onPause() {
        stopped = true; main.removeCallbacks(ticker)
        if (::preparationDiagnostics.isInitialized) preparationDiagnostics.onHostPaused()
        if (::session.isInitialized) session.pause()
        super.onPause()
    }
    override fun onDestroy() {
        closed = true; uiRebuildPending = false; requestToken++
        cinema?.close(); repairDialog?.dismiss(); resumeDialog?.dismiss(); resumeDialog = null
        if (::translationControls.isInitialized) translationControls.close()
        if (::localSrtTranslationControls.isInitialized) localSrtTranslationControls.close()
        if (::localGgufBenchmarkControls.isInitialized) localGgufBenchmarkControls.close()
        if (::preparationDiagnostics.isInitialized) preparationDiagnostics.close()
        main.removeCallbacksAndMessages(null); io.shutdownNow()
        if (::playerView.isInitialized) playerView.player = null
        if (::session.isInitialized) {
            session.captionSink = null; session.onMediaAccessFailure = null; session.onSessionRestored = null
        }
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); buildUi() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fullscreen", fullscreen); outState.putInt("savedOrientation", savedOrientation)
        super.onSaveInstanceState(outState)
    }
    private fun offerSessionResume() {
        if (!pendingResumeOffer || closed || stopped || isFinishing || isDestroyed) return
        pendingResumeOffer = false
        resumeDialog?.dismiss()
        resumeDialog = session.showResumeSession(this)
    }
    private fun handleBack() {
        if (cinema?.back() == true) return
        if (fullscreen) setFullscreen(false) else finish()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyWindowMode() }
    internal fun setFullscreen(value: Boolean) {
        if (closed || fullscreen == value) return
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
        if (closed) return
        if (buildingUi) {
            // Detaching a SurfaceView or applying window/configuration changes
            // may synchronously request another layout. Never let that nested
            // pass adopt the shared PlayerView while this pass still owns it.
            uiRebuildPending = true
            return
        }
        main.removeCallbacks(rebuildUiAfterCurrentPass)
        uiRebuildPending = false
        buildingUi = true
        try {
            buildUiContent()
        } finally {
            buildingUi = false
            if (uiRebuildPending && !closed) {
                uiRebuildPending = false
                main.post(rebuildUiAfterCurrentPass)
            }
        }
    }
    private fun buildUiContent() {
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
        if (!::captionOverlay.isInitialized) {
            captionOverlay = SubtitleView(this).apply {
                tag = "narration-subtitle-overlay"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false; isClickable = false
            }
            playerView.addView(captionOverlay, FrameLayout.LayoutParams(-1, -1))
            playerView.addView(GoogleTranslationAttribution.badge(this).apply {
                tag = GoogleTranslationAttribution.VIDEO_TAG; visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(176), dp(24), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                setMargins(dp(8), 0, dp(8), dp(4))
            })
        }
        session.captionSink = { cues, useOverride -> updateCaptionOverride(cues, useOverride) }
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
        GoogleTranslationAttribution.positionVideoBadge(playerView, captionOverlay)
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
        column.addView(button("Traduire les sous-titres en français") { translationControls.show() })
        column.addView(button("Traduire un fichier SRT hors ligne") { localSrtTranslationControls.show() })
        column.addView(button("Comparer CPU et GPU — essai local intégré") { localGgufBenchmarkControls.show() })
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
        listOfNotNull(playerView.subtitleView, if (::captionOverlay.isInitialized) captionOverlay else null).forEach { view -> view.apply {
            setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.subtitleSp)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.BLACK, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } }
        updateCaptionOverride(session.displayedCues, session.usesCaptionOverride)
    }
    private fun updateCaptionOverride(cues: List<Cue>?, useOverride: Boolean) {
        if (closed || !::captionOverlay.isInitialized || !::playerView.isInitialized) return
        playerView.subtitleView?.visibility = if (prefs.showSubtitles && !useOverride) View.VISIBLE else View.INVISIBLE
        captionOverlay.visibility = if (prefs.showSubtitles && useOverride) View.VISIBLE else View.GONE
        captionOverlay.setCues(if (useOverride) cues.orEmpty() else emptyList())
        playerView.findViewWithTag<View>(GoogleTranslationAttribution.VIDEO_TAG)?.visibility =
            if (prefs.showSubtitles && useOverride && !cues.isNullOrEmpty() && session.translation.googleAttributionRequired) View.VISIBLE else View.GONE
        if (fullscreen) cinema?.updateCaptionPosition()
        else GoogleTranslationAttribution.positionVideoBadge(playerView, captionOverlay)
    }
    private fun refreshUi() {
        if (closed || !::session.isInitialized) return
        val state = session.machine.snapshot()
        if (state.wantsPlayback || state.speakingId != null || session.isPreparing ||
            (::localSrtTranslationControls.isInitialized && localSrtTranslationControls.busy) ||
            (::localGgufBenchmarkControls.isInitialized && localGgufBenchmarkControls.busy)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session.prepareTick()
        if (::translationControls.isInitialized) translationControls.refresh()
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
    private fun requestSubtitleExport(mime: String, name: String, content: String) {
        if (pendingSubtitleExport != null) {
            showMessage("Un export attend déjà le choix de son fichier.", true)
        } else {
            pendingSubtitleExport = content
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name)
            try { subtitleExportLauncher.launch(intent) }
            catch (_: ActivityNotFoundException) {
                pendingSubtitleExport = null
                showMessage("Aucun sélecteur Android ne permet de créer le fichier d’export.", true)
            }
        }
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
        if (resultCode != RESULT_OK) { if (requestCode == OPEN_VIDEO) pendingRepairUri = null; return }
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
        val repairUri = pendingRepairUri
        pendingRepairUri = null
        io.execute {
            try {
                val name = displayName(uri)
                main.post {
                    if (closed || token != requestToken) return@post
                    resumeOfferUri = uri.toString()
                    if (repairUri != null && session.media?.uri == repairUri) {
                        session.relocateSessionTo(uri.toString(), name)
                    } else {
                        val previous = prefs.lastMedia()?.takeIf { it.uri == uri.toString() }
                        session.open(previous?.copy(title = name) ?: SavedMedia(uri.toString(), name), restoreSession = true)
                        showMessage("Vidéo ouverte. Choisis une piste de sous-titres, puis appuie sur Lire.", false)
                    }
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
                    // These private sidecars may be referenced by another saved
                    // session. They are not an evictable cache; importing a new
                    // file must never remove an older session's subtitle source.
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
        val originalSettings = prefs.settingsSnapshot()
        val originalDisplayMode = session.translation.displayMode
        val audioGeneration = session.audioCommands.snapshot().context.generation
        val panel = SettingsPanel(this)
        val audioResult = text("Les deux volumes du film utilisent les réglages audio validés.", 16f).apply {
            id = R.id.audio_settings_general_result
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun changeFilmAudio(command: AudioCommand) {
            if (closed || isFinishing || isDestroyed) return
            val result = session.audioCommands.submit(AudioCommandRequest(audioGeneration, command))
            audioResult.text = when (result) {
                is AudioCommandDecision.Rejected -> AudioCommandText.rejection(result.reason)
                is AudioCommandDecision.Accepted -> if (result.changed) "Niveaux du film enregistrés ; cible appliquée au lecteur."
                    else "Valeur déjà demandée : aucun changement."
            }
            if (result is AudioCommandDecision.Rejected) {
                panel.select(SettingsSection.SOUND)
                audioResult.post {
                    if (audioResult.isAttachedToWindow && !closed && !isFinishing && !isDestroyed)
                        audioResult.requestRectangleOnScreen(android.graphics.Rect(0, 0, audioResult.width, audioResult.height), true)
                }
            }
        }
        var pendingBenchmarkSeconds: Int? = null
        var pendingImageSettings = false
        var pendingLocalDiagnostic = false
        lateinit var dialog: AlertDialog
        fun add(section: SettingsSection, label: String, aliases: String = "", view: View) = panel.add(section, label, view, aliases)
        fun action(section: SettingsSection, label: String, aliases: String = "", change: () -> Unit) {
            add(section, label, aliases, button(label, change))
        }
        fun check(section: SettingsSection, label: String, value: Boolean, aliases: String = "", readCurrent: (() -> Boolean)? = null, enabled: () -> Boolean = { true }, change: (Boolean) -> Unit) {
            val view = CheckBox(this).apply {
                text = label; textSize = 19f; minHeight = dp(56); isChecked = value; isEnabled = enabled()
                setOnCheckedChangeListener { _, checked -> change(checked); session.refreshSettings(); applyCaptionStyle(); panel.refresh() }
            }
            add(section, label, aliases, view)
            panel.onRefresh {
                view.isEnabled = enabled()
                val desired = readCurrent?.invoke() ?: when (label) {
                    "Vocaliser les sous-titres" -> prefs.narration
                    "Afficher aussi les sous-titres à l’écran" -> prefs.showSubtitles
                    else -> prefs.skipDescriptions
                }
                // No preference write or callback from a visual refresh.
                if (view.isChecked != desired) {
                    view.setOnCheckedChangeListener(null); view.isChecked = desired
                    view.setOnCheckedChangeListener { _, checked -> change(checked); session.refreshSettings(); applyCaptionStyle(); panel.refresh() }
                }
            }
        }
        fun numeric(section: SettingsSection, label: String, from: Int, to: Int, unit: String,
            read: () -> Int, presets: List<Int> = emptyList(), aliases: String = "", step: Int = 5, change: (Int) -> Unit) {
            val control = SettingsNumberControl(this, label, from, to, step, unit, read, change, presets)
            add(section, label, aliases, control); panel.onRefresh(control::refresh)
        }
        check(SettingsSection.VOICE, "Vocaliser les sous-titres", prefs.narration, "activer désactiver narration synthèse") {
            session.beforeSettingsChange(); prefs.narration = it
        }
        fun modeLabel() = when (prefs.mode) {
            NarrationMachine.Mode.COMFORT -> "Confort"
            NarrationMachine.Mode.CONTINUOUS -> "Continu"
            NarrationMachine.Mode.ADAPTIVE -> "Adaptatif"
        }
        val modeButton = button("Mode vocal : ${modeLabel()}") { }
        modeButton.setOnClickListener {
            fun choose(mode: NarrationMachine.Mode, integral: Boolean) {
                session.beforeSettingsChange(); prefs.putBool("integralReading", integral)
                prefs.setMode(mode); session.refreshSettings(); panel.refresh()
            }
            AlertDialog.Builder(this).setTitle("Synchronisation de la voix")
                .setItems(arrayOf("Confort : phrases complètes", "Adaptatif : accélération plafonnée puis pause", "Continu : autoriser la coupure de phrases")) { _, index ->
                    when (index) {
                        0 -> choose(NarrationMachine.Mode.COMFORT, true)
                        1 -> choose(NarrationMachine.Mode.ADAPTIVE, true)
                        2 -> AlertDialog.Builder(this).setTitle("Autoriser les phrases coupées ?")
                            .setMessage("Le mode Continu peut interrompre une phrase pour suivre la vidéo. Il désactive la garantie de lecture intégrale. Confort et Adaptatif conservent les sous-titres complets et peuvent mettre la vidéo en pause.")
                            .setPositiveButton("Autoriser le mode Continu") { _, _ -> choose(NarrationMachine.Mode.CONTINUOUS, false) }
                            .setNegativeButton("Conserver la lecture intégrale", null).show()
                    }
                }.setNegativeButton("Annuler", null).show()
        }
        panel.onRefresh { modeButton.text = "Mode vocal : ${modeLabel()}" }
        add(SettingsSection.SYNCHRONIZATION, "Mode vocal : Confort, Continu, Adaptatif", "synchronisation pause accélération", modeButton)
        check(SettingsSection.SYNCHRONIZATION, "Lecture intégrale : conserver tous les sous-titres", prefs.bool("integralReading", true),
            "panneaux dialogues répétitions aucune phrase perdue", readCurrent = { prefs.bool("integralReading", true) }) {
            session.beforeSettingsChange(); prefs.putBool("integralReading", it)
            if (it && prefs.mode == NarrationMachine.Mode.CONTINUOUS) prefs.setMode(NarrationMachine.Mode.COMFORT)
        }
        add(SettingsSection.SYNCHRONIZATION, "Confort et Adaptatif : phrases complètes", view = text("Confort et Adaptatif conservent les phrases complètes. Le mode Continu peut couper les phrases trop longues.", 16f))
        action(SettingsSection.SYNCHRONIZATION, "Préréglage : lecture intégrale", "ne rien perdre panneaux répétitions filtres désactivés") {
            session.beforeSettingsChange(); prefs.narration = true; prefs.putBool("integralReading", true); prefs.setMode(NarrationMachine.Mode.COMFORT)
            prefs.skipDescriptions = false
            listOf("skipSpeakers", "skipSongs", "skipNotes").forEach { prefs.putBool(it, false) }
            prefs.speechRate = 1f; session.refreshSettings(); panel.refresh()
        }
        fun comprehensionPreset(label: String, profile: Int, maximumPercent: Int, pauseMs: Int, aliases: String) {
            action(SettingsSection.SYNCHRONIZATION, label, aliases) {
                session.beforeSettingsChange(); prefs.narration = true; prefs.putBool("integralReading", true)
                prefs.setMode(NarrationMachine.Mode.ADAPTIVE); prefs.speechRate = 1f
                prefs.putInt("adaptiveMax", maximumPercent); prefs.putInt("comprehensionPauseMs", pauseMs)
                prefs.putInt("comprehensionProfile", profile); prefs.putInt("allowedLagMs", 600)
                prefs.skipDescriptions = false
                listOf("skipSpeakers", "skipSongs", "skipNotes").forEach { prefs.putBool(it, false) }
                session.refreshSettings(); panel.refresh()
            }
        }
        comprehensionPreset("Préréglage : compréhension confortable", 0, 140, 350, "débit normal accélération modérée pause 350 ms phrases complètes")
        comprehensionPreset("Préréglage : compréhension équilibrée", 1, 180, 0, "débit normal compromis accélération 180 pour cent phrases complètes")
        comprehensionPreset("Préréglage : pauses rares", 2, 220, 0, "moins de pauses accélération 220 pour cent phrases complètes")
        add(SettingsSection.SYNCHRONIZATION, "Comparaison des profils de compréhension", view = text("Les trois profils utilisent Adaptatif, 100 % de vitesse de base et la lecture intégrale : confortable plafonne à 140 % avec 350 ms de pause après une phrase ; équilibré plafonne à 180 % sans pause ajoutée ; pauses rares plafonne à 220 % sans pause ajoutée. Une pause de la vidéo reste nécessaire si une phrase ne tient pas. Aucun profil ne résume ni ne supprime les sous-titres.", 16f))
        numeric(SettingsSection.SYNCHRONIZATION, "Pause de compréhension après une phrase", 0, 3000, "ms",
            { prefs.int("comprehensionPauseMs", 0, 0, 3000) }, listOf(0, 350, 600),
            "pause confort ponctuation délai fin de phrase", step = 50) {
            session.beforeSettingsChange(); prefs.putInt("comprehensionPauseMs", it); session.refreshSettings()
        }
        numeric(SettingsSection.VOICE, "Vitesse de la voix", 60, 200, "%", { (prefs.speechRate * 100).roundToInt() },
            listOf(80, 100, 125), "débit vitesse synthèse pourcentage") {
            session.beforeSettingsChange(); prefs.speechRate = it / 100f; session.refreshSettings()
        }
        numeric(SettingsSection.VOICE, "Volume de la voix", 10, 100, "%", { (prefs.voiceVolume * 100).roundToInt() },
            listOf(50, 75, 100), "gain narration synthèse") { prefs.voiceVolume = it / 100f; session.refreshSettings() }
        action(SettingsSection.VOICE, "Choisir une voix hors ligne", "langue naturel moteur voix locale") { voiceDialog() }
        action(SettingsSection.VOICE, "Écouter un essai de la voix", "tester écouter aperçu") { session.refreshSettings(); session.previewVoice() }
        action(SettingsSection.VOICE, "Ouvrir les réglages vocaux Android", "système TTS") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        add(SettingsSection.SOUND, "Volume habituel du film : aide", view = text(AudioSettingsDescriptions.MOVIE_VOLUME, 16f))
        numeric(SettingsSection.SOUND, "Volume habituel du film", 0, 100, "%", { (prefs.movieVolume * 100).roundToInt() },
            listOf(25, 50, 100), "son film volume normal") {
            changeFilmAudio(AudioCommand.SetLevels(it / 100f, session.audioCommands.snapshot().context.requested.duckVolume))
        }
        add(SettingsSection.SOUND, "Son du film conservé pendant la voix : aide", view = text(AudioSettingsDescriptions.DUCK_VOLUME, 16f))
        numeric(SettingsSection.SOUND, "Son du film conservé pendant la voix", 0, 100, "%", { (prefs.duckVolume * 100).roundToInt() },
            listOf(0, 25, 50), "atténuation ducking baisse mixage") {
            changeFilmAudio(AudioCommand.SetLevels(session.audioCommands.snapshot().context.requested.movieVolume, it / 100f))
        }
        add(SettingsSection.SOUND, "Résultat des commandes audio", "erreur refus validation volume", audioResult)
        check(SettingsSection.SUBTITLES, "Afficher aussi les sous-titres à l’écran", prefs.showSubtitles, "affichage texte visible") { prefs.showSubtitles = it }
        add(SettingsSection.SUBTITLES, "Exclusions désactivées en Lecture intégrale", "filtres lecture intégrale indications", view = text("En Lecture intégrale, tous les textes sont conservés : les exclusions ci-dessous sont désactivées. Pour exclure volontairement certaines indications, décoche Lecture intégrale dans Synchronisation, puis choisis les exclusions souhaitées.", 16f))
        check(SettingsSection.SUBTITLES, "Ne pas lire les indications entre crochets, par exemple [musique]", prefs.skipDescriptions,
            "filtre descriptions crochets bruit musique", enabled = { !prefs.bool("integralReading", true) }) { session.beforeSettingsChange(); prefs.skipDescriptions = it }
        numeric(SettingsSection.SUBTITLES, "Taille des sous-titres", 22, 60, "sp", { prefs.subtitleSp.roundToInt() },
            listOf(24, 32, 48), "police texte grossissement") { prefs.subtitleSp = it.toFloat(); applyCaptionStyle() }
        action(SettingsSection.SUBTITLES, "Choisir la piste de sous-titres", "langue intégré externe SRT ASS VTT") { chooseTrack(C.TRACK_TYPE_TEXT) }
        action(SettingsSection.SUBTITLES, "Retirer le fichier de sous-titres ajouté", "externe supprimer détacher") {
            session.attachSubtitle(null, null); showMessage("Fichier ajouté retiré. Les pistes intégrées restent disponibles.", false)
        }
        action(SettingsSection.TRANSLATION, "Traduire les sous-titres en français", "traduction fournisseur export SRT VTT quota cache DeepL Azure LibreTranslate GGUF local") { translationControls.show() }
        action(SettingsSection.TRANSLATION, "Traduire un fichier SRT hors ligne", "GGUF modèle local sans vidéo export reprise") { localSrtTranslationControls.show() }
        action(SettingsSection.TRANSLATION, "Comparer CPU et GPU — essai local intégré", "GGUF OpenCL CPU benchmark performances modèle thermique") { localGgufBenchmarkControls.show() }
        action(SettingsSection.IMAGE, "Image, cadrage, zoom et corrections", "étirement bandes noires marges contraste luminosité Lanczos proportions") {
            pendingImageSettings = true; dialog.dismiss()
        }
        action(SettingsSection.DIAGNOSTIC, "Diagnostic local : appareil, vidéo et modèle", "IA RAM mémoire CPU processeur GPU NPU GGUF décodage codecs") {
            pendingLocalDiagnostic = true; dialog.dismiss()
        }
        AdvancedSettings(this, session, prefs) { seconds ->
            pendingBenchmarkSeconds = seconds; dialog.dismiss()
        }.populate(panel)
        dialog = object : AlertDialog(this) {
            override fun getButton(whichButton: Int): Button? = panel.actionButton(whichButton) ?: super.getButton(whichButton)
        }.apply { setTitle("Voix et confort"); setView(panel) }
        panel.installActions(close = { dialog.dismiss() }, cancel = {
            if (!closed) {
                session.restoreSettings(originalSettings)
                if (session.translation.displayMode != originalDisplayMode) session.translation.setDisplayMode(originalDisplayMode)
            }
            dialog.dismiss()
        })
        dialog.setOnDismissListener {
            if (!closed) {
                if (!session.isPreparing) session.refreshSettings()
                applyCaptionStyle(); applyWindowMode()
                pendingBenchmarkSeconds?.let { session.startPlaybackBenchmark(it) }
                if (pendingImageSettings) { setFullscreen(true); cinema?.showImageSettings() }
                if (pendingLocalDiagnostic) preparationDiagnostics.show()
            }
        }
        dialog.show()
        // Let the floating window honor its usable display frame and resize
        // around the keyboard instead of imposing a height from full metrics.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        AlertDialog.Builder(this).setTitle("Sous-titres en voix 0.5.2 : mode d’emploi et limites")
            .setMessage("Ouvre une vidéo locale, choisis une piste textuelle et une voix installée dans la même langue. Les réglages sont répartis en huit rubriques : Voix, Synchronisation, Traduction, Sous-titres, Image, Son, Stockage et Diagnostic. La recherche ignore les accents et montre les commandes correspondantes. Les valeurs précises acceptent une saisie numérique bornée, des boutons et des préréglages. Les changements prennent effet immédiatement : Fermer les conserve ; Annuler modifications restaure les réglages présents à l’ouverture du panneau, sans annuler un envoi déjà confirmé ni rétablir des fichiers de préparation supprimés.\n\nLecture intégrale : dialogues, panneaux, chansons, indications et répétitions distinctes de la piste choisie restent éligibles à la vocalisation. Les répétitions artificielles d’une même occurrence ne doivent pas être relues. Confort attend la fin de la phrase ; Adaptatif accélère dans la limite réglée et met en pause si nécessaire. Le mode Continu reste un choix explicite pouvant couper les phrases et désactive la lecture intégrale. Aucun profil de compréhension ne choisit Continu : confortable utilise 100 % de base, un plafond de 140 % et 350 ms de pause ; équilibré, 100 % et 180 % sans pause ajoutée ; pauses rares, 100 % et 220 % sans pause ajoutée. La pause après une phrase se règle de 0 à 3 000 ms. Aucun dialogue n’est résumé.\n\nTraduction : dans la rubrique Traduction, choisis Google en ligne sans clé, Google local (ML Kit), Azure, DeepL ou un serveur LibreTranslate HTTPS et vérifie la langue source. L’indexation et l’estimation seules n’envoient pas le texte. Une confirmation autorise les appels au fournisseur ; des caractères déjà traités peuvent être facturés même après annulation. La préparation complète reste le choix initial. Le mode progressif peut démarrer après le tampon choisi (30 secondes par défaut, réglable de 10 à 120). Il attend au premier sous-titre indisponible et reprend seulement une lecture que tu avais demandée ; une pause manuelle annule cette reprise. Les lots validés peuvent être repris après interruption. Le plafond de nouveaux caractères est local, sans connaître le quota réel du compte.\n\nLa narration utilise la traduction validée. L’affichage peut conserver l’original, montrer le français, présenter les deux dans une même zone ou masquer le texte, selon le choix proposé. Le rendu natif et le rendu traduit ne sont pas affichés en double. L’export SRT ou VTT demande un emplacement dans le sélecteur Android et écrit en UTF-8 ; il n’altère pas le fichier original. Les options DeepL de contexte, glossaire existant, modèle et registre dépendent du compte et de la langue : elles ne garantissent pas la fidélité linguistique. Les comparaisons de fournisseurs nécessitent leurs accès et une confirmation. Google ML Kit traduit sur le téléphone après téléchargement des modèles compatibles, en Wi-Fi par défaut. Le texte est traité localement ; le SDK peut transmettre des données techniques et des statistiques à Google. Attribution et liens Google sont disponibles dans les réglages Traduction.\n\nSéance et synchronisation : les choix de pistes, la position et les réglages d’une vidéo peuvent être mémorisés. Reprendre une séance reste une action explicite après vérification de son accès et de ses pistes ; une traduction ou une préparation manquante n’est pas déclarée prête. Si l’autorisation du fichier a expiré ou si la vidéo a été déplacée, le parcours de réparation permet de la sélectionner à nouveau sans effacer les originaux. Les réglages de décalage, dérive et calibration se trouvent dans Synchronisation. Le bilan de vocalisation de Diagnostic distingue les occurrences et les erreurs connues ; il ne prouve pas la qualité acoustique d’une voix ni l’absence universelle de défaut.\n\nStockage : le cache vocal ordinaire reste limité et évictable. Les préparations explicitement conservées pour une écoute hors ligne utilisent un stockage distinct, avec un état à vérifier et une gestion des œuvres conservées. Une préparation peut être reprise ou annulée ; une œuvre n’est prête hors ligne que si toutes ses données nécessaires ont été vérifiées et si la vidéo reste accessible. Préparer toute la vidéo dans le cache ordinaire ne garantit pas de conserver tous ses fichiers. Reste dans l’application pendant les traitements : une lecture ou une préparation durable en arrière-plan n’est pas garantie. Les profils modifient anticipation et mémoire, pas le processeur interne d’un moteur tiers.\n\nImage et son : étirement intégral conserve toute l’image avec proportions modifiées. La protection du cadre empêche les zooms et déplacements accidentels ; retirer des marges exige un aperçu et une confirmation. Les corrections d’image restent protégées pour le HDR/Dolby Vision. Les traitements audio experts du film restent séparés de la voix et limités au PCM16 mono/stéréo compatible ; ils n’annoncent pas de passthrough ou de normalisation du volume perçu.\n\nLimites : les pistes PGS/VobSub et les sous-titres incrustés dans les pixels ne sont pas vocalisés sans OCR, qui reste absent. Pas d’accès aux DRM d’autres applications, d’audiodescription ni de lecture durable en arrière-plan. Les décodeurs dépendent du téléphone ; choisir le secours logiciel n’installe pas un codec absent. Les voix restent fournies par des moteurs Android externes ; aucun runtime vocal neuronal GPU/NPU propre au lecteur n’est embarqué.\n\nConfidentialité : les moteurs en ligne envoient le texte et ses options au fournisseur choisi après confirmation, jamais la vidéo ou son audio. Google local exécute la traduction sur le téléphone ; les téléchargements et la télémétrie technique du SDK restent possibles. Une clé peut être mémorisée chiffrée avec Android Keystore ; ne la publie pas dans un diagnostic. Les données locales de préparation et de traduction peuvent contenir du texte ou de l’audio vocalisé. Aucun fichier original n’est modifié. La 0.5.2 conserve l’identité de l’édition Traduction ; la stable 0.3.2 reste une application distincte.")
            .setPositiveButton("Fermer", null).show()
    }
    companion object { private const val OPEN_VIDEO = 10; private const val OPEN_SUBTITLE = 11 }
}
