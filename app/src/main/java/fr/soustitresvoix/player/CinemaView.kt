package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandRequest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Video fills the window; safe-area padding belongs only to overlays. */
@UnstableApi
class CinemaView(
    private val activity: Activity, private val session: PlaybackSession, private val prefs: PlayerPreferences,
    private val video: PlayerView, private val exit: () -> Unit, private val settings: () -> Unit,
    private val subtitles: () -> Unit, private val audioTracks: () -> Unit
) : FrameLayout(activity) {
    private val main = Handler(Looper.getMainLooper())
    private val accessibility = activity.getSystemService(AccessibilityManager::class.java)
    private val top = LinearLayout(activity)
    private val bottom = LinearLayout(activity)
    private val title = label(session.media?.title ?: "Vidéo")
    private val status = label("")
    private val time = label("")
    private val feedback = label("")
    private val seek = SeekBar(activity)
    private val play = button("Lire") { togglePlay() }
    private val surface = View(activity)
    private val unlock = button("Déverrouiller") { setLocked(false) }
    private var visibleControls = true
    private var locked = false
    private var scrubbing = false
    private var disposed = false
    private val dialogs = mutableSetOf<AlertDialog>()
    private val imageRefreshers = mutableSetOf<() -> Unit>()
    private var zoom = 1f
    private var pendingPan: Pair<Float, Float>? = null
    private var framingMediaUri: String? = null
    private var framingRestored = false
    private var previousPlaybackIntent = false
    private var dx = 0f; private var dy = 0f
    private var startX = 0f; private var startY = 0f
    private var startVolume = 1f; private var startBrightness = 0.5f
    private var audioGestureGeneration = 0L
    private var startPosition = 0L
    private var dragSeek: Long? = null
    private var scaling = false
    private var moved = false
    private var touchActive = false
    private val hide = Runnable {
        if (!touchActive && !scrubbing && !accessibility.isTouchExplorationEnabled &&
            !hasAccessibleFocus(this) && session.machine.snapshot().wantsPlayback && !locked) showControls(false)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    fun showImageSettings() = imageDialog()
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 16f; isAllCaps = false; minHeight = dp(52)
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(45, 45, 45))
        setOnClickListener { if (!disposed) { action(); scheduleHide() } }
    }
    private fun showDialog(builder: AlertDialog.Builder, dismissed: () -> Unit = {}): AlertDialog? {
        if (disposed || activity.isFinishing || activity.isDestroyed) return null
        val dialog = builder.create()
        dialogs += dialog
        dialog.setOnDismissListener { dialogs -= dialog; dismissed() }
        dialog.show()
        return dialog
    }
    init {
        id = R.id.cinema_root; setBackgroundColor(Color.BLACK); clipChildren = true
        (video.parent as? ViewGroup)?.removeView(video)
        video.setPadding(0, 0, 0, 0)
        addView(video, LayoutParams(-1, -1))
        surface.contentDescription = "Afficher ou masquer les commandes du lecteur"
        surface.isFocusable = true; surface.setOnClickListener { if (!locked) showControls(!visibleControls) }
        addView(surface, LayoutParams(-1, -1))
        top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(0xC9000000.toInt()); top.id = R.id.cinema_top
        top.addView(button("Retour") { exit() })
        title.maxLines = 2; top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("Verrouiller") { setLocked(true) })
        addView(top, LayoutParams(-1, -2, Gravity.TOP))
        bottom.orientation = LinearLayout.VERTICAL; bottom.setBackgroundColor(0xD9000000.toInt())
        bottom.id = R.id.cinema_controls
        time.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        status.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bottom.addView(status); bottom.addView(time)
        seek.max = 1000; seek.minimumHeight = dp(48); seek.contentDescription = "Position dans la vidéo"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; main.removeCallbacks(hide) }
            override fun onStopTrackingTouch(bar: SeekBar) { scrubbing = false; seekTo(bar.progress); scheduleHide() }
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user && !scrubbing) seekTo(value) }
        })
        bottom.addView(seek)
        val transport = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        transport.addView(button("−10 s") { session.seek(session.player.currentPosition - 10_000) }, LinearLayout.LayoutParams(0, -2, 1f))
        play.id = R.id.cinema_play
        play.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(168, 74, 0))
        transport.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        transport.addView(button("+10 s") { session.seek(session.player.currentPosition + 10_000) }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(transport)
        val tools = LinearLayout(activity)
        tools.addView(button("Répéter") { session.repeatLast(); showControls(true) })
        tools.addView(button("Reprendre la voix") { session.resumeVoice() })
        tools.addView(button("Sous-titres") { subtitles() })
        tools.addView(button("Audio") { audioTracks() })
        tools.addView(button("Étirement intégral") { setFramingMode(2); tell("Image entière étirée — recadrage accidentel bloqué") })
        tools.addView(button("Image / zoom") { imageDialog() })
        tools.addView(button("Volumes rapides") { QuickMixDialog.show(activity, session, prefs) })
        tools.addView(button("Voix / écran") { settings() })
        tools.addView(button("Masquer") { showControls(false) })
        bottom.addView(HorizontalScrollView(activity).apply { addView(tools) })
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        feedback.gravity = Gravity.CENTER; feedback.setBackgroundColor(0xC9000000.toInt())
        feedback.visibility = View.GONE; feedback.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(feedback, LayoutParams(-2, -2, Gravity.CENTER))
        unlock.visibility = View.GONE; unlock.id = R.id.cinema_unlock
        addView(unlock, LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setOnApplyWindowInsetsListener { _, insets ->
            val left: Int; val right: Int; val up: Int; val down: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = safe.left; right = safe.right; up = safe.top; down = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                val safe = intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetRight,
                    insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
                left = safe[0]; right = safe[1]; up = safe[2]; down = safe[3]
            }
            top.setPadding(left, up, right, 0); bottom.setPadding(left, 0, right, down)
            (unlock.layoutParams as LayoutParams).apply { topMargin = up; rightMargin = right }
            video.subtitleView?.setPadding(left + dp(8), 0, right + dp(8), down)
            video.findViewWithTag<androidx.media3.ui.SubtitleView>("narration-subtitle-overlay")
                ?.setPadding(left + dp(8), 0, right + dp(8), down)
            insets
        }
        val scale = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true; dragSeek = null
                if (prefs.protectFrame) { tell("Recadrage protégé : zoom bloqué"); return false }
                return !locked && prefs.bool("gestureZoom", true) && !accessibility.isTouchExplorationEnabled
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setImageZoom(zoom * detector.scaleFactor); tell("Zoom ${"%.1f".format(zoom)}×"); return true
            }
        })
        val gesture = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                audioGestureGeneration = session.audioCommands.snapshot().context.generation
                startX = event.x; startY = event.y; startVolume = prefs.movieVolume
                startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                startPosition = session.player.currentPosition; moved = false; dragSeek = null; return true
            }
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { if (!moved && !scaling) surface.performClick(); return true }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (prefs.bool("gestureSeek", true)) {
                    val delta = if (event.x < width / 2) -10_000 else 10_000
                    session.seek(session.player.currentPosition + delta); tell(if (delta < 0) "Reculer 10 s" else "Avancer 10 s")
                }
                return true
            }
            override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaling || first == null) return false
                moved = true
                if (zoom > 1.01f) { dx -= distanceX; dy -= distanceY; applyImage(); return true }
                val horizontal = abs(event.x - startX) > abs(event.y - startY)
                if (horizontal && prefs.bool("gestureSeek", true)) {
                    val duration = session.player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    dragSeek = (startPosition + ((event.x - startX) / width.coerceAtLeast(1) * 90_000).toLong()).coerceIn(0, duration)
                    tell("Aller à ${clock(dragSeek!!)}")
                } else if (!horizontal && prefs.bool("gestureLevels", true)) {
                    val change = (startY - event.y) / height.coerceAtLeast(1)
                    if (startX > width / 2) {
                        val current = session.audioCommands.snapshot().context.requested
                        val result = session.audioCommands.submit(AudioCommandRequest(audioGestureGeneration,
                            AudioCommand.SetLevels((startVolume + change).coerceIn(0f, 1f), current.duckVolume)))
                        if (result is AudioCommandDecision.Rejected) tell(AudioCommandText.rejection(result.reason))
                        else tell("Volume film ${(prefs.movieVolume * 100).roundToInt()} %")
                    } else {
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = (startBrightness + change).coerceIn(0.01f, 1f) }
                        tell("Luminosité ${(activity.window.attributes.screenBrightness * 100).roundToInt()} %")
                    }
                }
                return true
            }
        })
        surface.setOnTouchListener { _, event ->
            if (accessibility.isTouchExplorationEnabled) return@setOnTouchListener false
            if (locked) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchActive = true; scaling = false; main.removeCallbacks(hide) }
                MotionEvent.ACTION_UP -> {
                    if (!scaling) dragSeek?.let { session.seek(it) }
                    dragSeek = null; touchActive = false; scheduleHide()
                }
                MotionEvent.ACTION_CANCEL -> { dragSeek = null; touchActive = false; scheduleHide() }
            }
            scale.onTouchEvent(event); gesture.onTouchEvent(event); true
        }
        restoreFraming()
        applyImage(); refresh(); scheduleHide()
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCaptionPosition() }
    }
    private fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
    private fun togglePlay() {
        val state = session.machine.snapshot()
        if (state.wantsPlayback || state.speakingId != null) session.pause() else session.play()
        showControls(true)
    }
    private fun seekTo(value: Int) {
        val d = session.player.duration
        if (d > 0 && d != C.TIME_UNSET) session.seek((d.toDouble() * value / 1000).toLong())
    }
    fun refresh() {
        if (disposed) return
        if (!framingRestored || framingMediaUri != session.media?.uri) { restoreFraming(); applyImage() }
        val state = session.machine.snapshot()
        val playLabel = when { state.wantsPlayback -> "Pause"; state.speakingId != null -> "Arrêter la voix"; else -> "Lire" }
        if (play.text.toString() != playLabel) play.text = playLabel
        val statusLabel = when { state.voiceSuspended -> "Voix en attente : Reprendre la voix"; state.waitingForSpeech -> "La voix termine la phrase"; state.wantsPlayback -> ""; else -> "En pause" }
        if (status.text.toString() != statusLabel) status.text = statusLabel
        status.visibility = if (statusLabel.isBlank()) View.GONE else View.VISIBLE
        val d = session.player.duration; val pos = session.player.currentPosition
        time.text = "${clock(pos)} / ${if (d > 0 && d != C.TIME_UNSET) clock(d) else "—"}"
        seek.isEnabled = d > 0 && d != C.TIME_UNSET && session.player.isCurrentMediaItemSeekable
        if (!scrubbing && !seek.isAccessibilityFocused && seek.isEnabled) seek.progress = (pos.toDouble() / d * 1000).toInt().coerceIn(0, 1000)
        if (!seek.isAccessibilityFocused) seek.contentDescription = "Position ${clock(pos)} sur ${if (d > 0) clock(d) else "durée inconnue"}"
        val titleLabel = session.media?.title ?: "Vidéo"
        if (title.text.toString() != titleLabel) title.text = titleLabel
        if (previousPlaybackIntent && !state.wantsPlayback && state.speakingId == null && !locked && !visibleControls) showControls(true)
        previousPlaybackIntent = state.wantsPlayback
        imageRefreshers.toList().forEach { it() }
    }
    private fun hasAccessibleFocus(view: View): Boolean = view.isAccessibilityFocused || (view.isFocused && !view.isInTouchMode) ||
        (view is ViewGroup && (0 until view.childCount).any { hasAccessibleFocus(view.getChildAt(it)) })
    private fun scheduleHide() {
        main.removeCallbacks(hide)
        val seconds = prefs.int("controlsTimeout", 5, 0, 30)
        if (seconds > 0 && visibleControls && !accessibility.isTouchExplorationEnabled) {
            val timeout = if (Build.VERSION.SDK_INT >= 29) accessibility.getRecommendedTimeoutMillis(
                seconds * 1000, AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT)
            else seconds * 1000
            main.postDelayed(hide, timeout.toLong())
        }
    }
    fun showControls(show: Boolean) {
        if (disposed) return
        visibleControls = show; top.visibility = if (show) View.VISIBLE else View.GONE
        bottom.visibility = if (show) View.VISIBLE else View.GONE
        main.removeCallbacks(hide); updateCaptionPosition()
        if (show) scheduleHide()
    }
    private fun updateCaptionPosition() {
        if (disposed) return
        val space = if (visibleControls && height > 0) (bottom.height.toFloat() / height + 0.02f).coerceIn(0.08f, 0.60f) else 0.08f
        video.subtitleView?.setBottomPaddingFraction(space)
        video.findViewWithTag<androidx.media3.ui.SubtitleView>("narration-subtitle-overlay")?.setBottomPaddingFraction(space)
    }
    private fun setLocked(value: Boolean) {
        locked = value; showControls(!value); unlock.visibility = if (value) View.VISIBLE else View.GONE
        surface.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (value) unlock.requestFocus()
    }
    fun back(): Boolean { if (locked) { setLocked(false); return true }; return false }
    private fun tell(value: String) {
        feedback.text = value; feedback.visibility = View.VISIBLE
        main.removeCallbacks(clearFeedback); main.postDelayed(clearFeedback, 1000)
    }
    private val clearFeedback = Runnable { feedback.visibility = View.GONE }
    private fun rememberFraming(value: Int) {
        prefs.putInt("imageMode", value)
        // A deliberate crop belongs to this video, never to the default for unseen files.
        if (value != 1) prefs.putInt("imageDefaultMode", value)
        session.media?.let { prefs.putInt("frame-${fr.soustitresvoix.core.SpeechTools.key("framing", it.uri)}", value) }
    }
    private fun restoreFraming() {
        framingMediaUri = session.media?.uri; framingRestored = true
        val previousSafe = prefs.int("imageMode", 0, 0, 2).takeIf { it != 1 } ?: 0
        val default = prefs.int("imageDefaultMode", previousSafe, 0, 2).takeIf { it != 1 } ?: 0
        val rememberedMode = (session.imageViewport["frameMode"] as? Number)?.toInt()?.takeIf { it in 0..2 }
        val mode = rememberedMode ?: framingMediaUri?.let { uri ->
            prefs.int("frame-${fr.soustitresvoix.core.SpeechTools.key("framing", uri)}", default, 0, 2)
        } ?: default
        prefs.putInt("imageMode", mode)
        prefs.putBool("protectFrame", session.imageViewport["protectFrame"] as? Boolean ?: (mode != 1))
        zoom = (session.imageViewport["zoom"] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 1f
        fun pan(key: String) = (session.imageViewport[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(-1.5f, 1.5f) ?: 0f
        pendingPan = pan("panX") to pan("panY")
        dx = 0f; dy = 0f
    }
    internal fun setFrameProtection(enabled: Boolean) {
        if (disposed) return
        if (enabled && prefs.int("imageMode", 0, 0, 2) == 1) {
            // Use the same entire-image transition as the explicit mode button.
            setFramingMode(0)
            return
        }
        prefs.putBool("protectFrame", enabled)
        if (enabled) {
            zoom = 1f; dx = 0f; dy = 0f
        }
        applyImage()
    }
    internal fun setFramingMode(value: Int, authorizeCrop: Boolean = false): Boolean {
        if (disposed) return false
        if (value !in 0..2) return false
        if (value == 1 && prefs.protectFrame && !authorizeCrop) return false
        prefs.putBool("protectFrame", value != 1)
        rememberFraming(value); zoom=1f; dx=0f; dy=0f
        if (value != 1 && session.visual.margins.nonzero()) session.visual.crop(fr.soustitresvoix.core.BlackBars.Margins())
        applyImage(); return true
    }
    internal fun setImageZoom(value: Float) {
        if (disposed) return
        if (prefs.protectFrame) { zoom=1f; dx=0f; dy=0f; tell("Protection active : aucune partie du cadre ne doit sortir de l’écran") }
        else zoom=if (value.isFinite()) value.coerceIn(1f,4f) else 1f
        applyImage()
    }
    private fun applyImage() {
        if (disposed) return
        if (width > 0 && height > 0) pendingPan?.let { (x, y) -> dx = x * width; dy = y * height; pendingPan = null }
        session.finishPlaybackBenchmark("Cadrage ou zoom modifié : essai interrompu")
        if (prefs.protectFrame) {
            zoom=1f; dx=0f; dy=0f
            if (prefs.int("imageMode",0,0,2)==1) rememberFraming(0)
        }
        video.resizeMode = when (prefs.int("imageMode", 0, 0, 2)) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        session.visual.setFramingMode(prefs.int("imageMode", 0, 0, 2))
        dx = dx.coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2)
        dy = dy.coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2)
        video.videoSurfaceView?.apply { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy }
        session.imageViewport = mapOf("zoom" to zoom, "panX" to (pendingPan?.first ?: if (width > 0) dx / width else 0f),
            "panY" to (pendingPan?.second ?: if (height > 0) dy / height else 0f),
            "frameMode" to prefs.int("imageMode", 0, 0, 2), "protectFrame" to prefs.protectFrame)
    }
    fun imageDialog() {
        if (disposed) return
        val generation = session.imageCommands.snapshot().context.generation
        var refreshImageUi: () -> Unit = {}
        var restoring = false
        fun current(): Boolean {
            if (disposed) return false
            val context = session.imageCommands.snapshot().context
            val reason = when {
                context.closed -> "Lecture fermée : réglage indisponible"
                context.generation != generation -> "La vidéo a changé : rouvre les réglages d’image"
                else -> return true
            }
            showDialog(AlertDialog.Builder(activity).setTitle("Réglage d’image refusé")
                .setMessage(reason).setPositiveButton("Fermer", null))
            refreshImageUi()
            return false
        }
        fun imageButton(value: String, action: () -> Unit) = button(value) {
            if (current()) { action(); refreshImageUi() }
        }.apply { setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null }
        fun heading(value: String) = label(value).apply {
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val expert = ExpertImageControls(activity, session.imageCommands,
            showDialog = { showDialog(it) }, alive = { !disposed && !activity.isFinishing && !activity.isDestroyed })
        column.addView(expert.view)
        column.addView(heading("Cadrage et protection"))
        val framingState = label("").apply { id = R.id.image_framing_state }
        column.addView(framingState)
        val protection = CheckBox(activity).apply {
            text="Protéger le cadre : bloquer zoom et déplacements accidentels"; textSize=17f; minHeight=dp(56); isChecked=prefs.protectFrame
            setOnCheckedChangeListener { _, checked ->
                if (!restoring && current()) { setFrameProtection(checked); refreshImageUi() }
            }
        }
        fun mode(title: String, value: Int, description: String) {
            column.addView(label(description))
            column.addView(imageButton(title) {
                if (!setFramingMode(value)) showDialog(AlertDialog.Builder(activity).setTitle("Autoriser la perte d’une partie du cadre ?")
                    .setMessage("Le mode recadré remplit l’écran en coupant une partie de l’image. Cela désactive la protection contre le recadrage accidentel.")
                    .setPositiveButton("Autoriser le recadrage") { _, _ ->
                        if (current()) { setFramingMode(value, true); refreshImageUi() }
                    }
                    .setNegativeButton("Garder l’image entière",null))
            })
        }
        mode("Image entière, proportions originales", 0, ImageSettingsDescriptions.FRAMING_FIT)
        mode("Tout l’écran — image entière étirée", 2, ImageSettingsDescriptions.FRAMING_STRETCH)
        mode("Tout l’écran, image recadrée", 1, ImageSettingsDescriptions.FRAMING_CROP)
        column.addView(label(ImageSettingsDescriptions.FRAME_PROTECTION))
        column.addView(protection)
        column.addView(label(ImageSettingsDescriptions.FRAME_DIAGNOSTIC))
        column.addView(imageButton("Diagnostic du cadrage et des bandes noires") {
            val format=session.video.format; val size=session.player.videoSize
            // videoSize describes the processed output when effects are active.
            // Keep source metadata separate so stretching cannot disguise its ratio.
            val sourceWidth = format?.width ?: 0
            val sourceHeight = format?.height ?: 0
            val pixelRatio = format?.pixelWidthHeightRatio ?: 1f
            val rotation = format?.rotationDegrees ?: 0
            val sourceRatio = if (sourceWidth > 0 && sourceHeight > 0 && pixelRatio > 0f) {
                val unrotated = sourceWidth * pixelRatio / sourceHeight
                if (rotation == 90 || rotation == 270) 1f / unrotated else unrotated
            } else null
            val ratio = sourceRatio?.let { String.format(Locale.FRANCE,"%.3f",it) } ?: "inconnu"
            val framing=arrayOf("Image entière, proportions originales","Tout l’écran, image recadrée","Tout l’écran — image entière étirée")[prefs.int("imageMode",0,0,2)]
            val message="Vidéo codée : $sourceWidth × $sourceHeight pixels\n"+
                "Proportions déclarées : rapport des pixels $pixelRatio ; rotation $rotation° ; rapport d’affichage $ratio\n"+
                "Sortie du traitement vidéo : ${size.width} × ${size.height} pixels\n"+
                "Surface disponible : ${width} × ${height} pixels\nMode : $framing ; zoom ${zoom}×\n"+
                "Protection : ${prefs.protectFrame} ; marges validées : ${session.visual.margins}\n"+
                "Les bandes encodées font partie des images. Le poinçon et les coins physiques peuvent masquer de petites zones. Analyse facultative séparée, jamais automatique."
            showDialog(AlertDialog.Builder(activity).setTitle("Diagnostic de l’image").setMessage(message).setPositiveButton("Fermer",null))
        })
        column.addView(heading("Marges et redimensionnement"))
        column.addView(label(ImageSettingsDescriptions.MARGIN_ANALYSIS))
        column.addView(imageButton("Analyser les marges encodées — aperçu obligatoire") {
            session.visual.showAnalysis(activity) { applyImage(); refreshImageUi() }
        })
        column.addView(label(ImageSettingsDescriptions.MARGIN_RESET))
        column.addView(imageButton("Annuler toute suppression de marges") {
            session.visual.crop(fr.soustitresvoix.core.BlackBars.Margins()); applyImage()
        })
        column.addView(label(ImageSettingsDescriptions.DETAILED_SCALING))
        val scaling = CheckBox(activity).apply {
            text = "Mise à l’échelle détaillée Lanczos — SDR seulement"
            textSize = 17f; minHeight = dp(56); isChecked = session.visual.detailedScaling
            setOnCheckedChangeListener { _, checked ->
                if (!restoring && current()) {
                    session.visual.setDetailedScaling(checked); refreshImageUi()
                }
            }
        }
        column.addView(scaling)
        val scalingState = label("")
        column.addView(scalingState)
        column.addView(heading("Zoom et déplacements"))
        column.addView(label(ImageSettingsDescriptions.ZOOM))
        column.addView(imageButton("Zoom +") { setImageZoom(zoom + .25f) })
        column.addView(imageButton("Zoom −") { setImageZoom(zoom - .25f) })
        column.addView(imageButton("Recentrer / réinitialiser") { zoom = 1f; dx = 0f; dy = 0f; applyImage() })
        column.addView(label(ImageSettingsDescriptions.PAN))
        column.addView(imageButton("Déplacer l’image à gauche") { dx -= dp(40); applyImage() })
        column.addView(imageButton("Déplacer l’image à droite") { dx += dp(40); applyImage() })
        column.addView(imageButton("Déplacer l’image vers le haut") { dy -= dp(40); applyImage() })
        column.addView(imageButton("Déplacer l’image vers le bas") { dy += dp(40); applyImage() })
        column.addView(heading("Luminosité de l’écran"))
        val screenState = label("").apply { id = R.id.image_screen_state }
        column.addView(screenState)
        column.addView(label(ImageSettingsDescriptions.SCREEN_BRIGHTNESS))
        column.addView(imageButton("Écran : luminosité +10 points") {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = ((screenBrightness.takeIf { it >= 0 } ?: 0.5f) + 0.1f).coerceIn(0.01f, 1f) }
        })
        column.addView(imageButton("Écran : luminosité −10 points") {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = ((screenBrightness.takeIf { it >= 0 } ?: 0.5f) - 0.1f).coerceIn(0.01f, 1f) }
        })
        column.addView(label(ImageSettingsDescriptions.SCREEN_SYSTEM))
        column.addView(imageButton("Écran : reprendre le réglage système") {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
        })
        fun update(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
        refreshImageUi = {
            if (!disposed) {
                expert.refresh()
                val context = session.imageCommands.snapshot().context
                val framing = arrayOf("image entière, proportions originales", "image recadrée", "image entière étirée")[prefs.int("imageMode", 0, 0, 2)]
                update(framingState, "Cadrage demandé : $framing. Protection ${if (prefs.protectFrame) "activée" else "désactivée"}. " +
                    String.format(Locale.FRANCE, "Zoom : %.2f×. Déplacement horizontal : %.0f dp ; vertical : %.0f dp.",
                        zoom, dx / resources.displayMetrics.density, dy / resources.displayMetrics.density))
                update(scalingState, "Lanczos ${if (session.visual.detailedScaling) "demandé" else "désactivé"}. " + when {
                    context.closed -> "Lecture fermée."
                    context.generation != generation -> "La vidéo a changé : rouvre les réglages d’image."
                    context.hdrProtected -> "Traitement neutralisé par la protection HDR."
                    context.requested.originalComparison -> "Traitement masqué par la comparaison."
                    else -> "Cible : ${session.visual.scalingTarget}. La demande ne mesure pas le rendu affiché."
                })
                val screen = activity.window.attributes.screenBrightness
                update(screenState, if (screen < 0f) "Consigne de l’écran : réglage système, manuel ou adaptatif selon Android."
                    else "Consigne de l’écran : ${(screen * 100).roundToInt()} %. La lumière réellement émise n’est pas mesurée.")
                restoring = true
                try {
                    protection.isChecked = prefs.protectFrame
                    scaling.isChecked = session.visual.detailedScaling
                    scaling.isEnabled = !context.closed && context.generation == generation && !context.hdrProtected
                } finally { restoring = false }
            }
        }
        refreshImageUi()
        showDialog(AlertDialog.Builder(activity).setTitle("Image et cadrage").setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton("Fermer", null), dismissed = { imageRefreshers -= refreshImageUi })
            ?.let { imageRefreshers += refreshImageUi }
    }
    fun close() {
        prefs.putInt("zoomPercent", (zoom * 100).toInt())
        disposed = true; main.removeCallbacksAndMessages(null)
        imageRefreshers.clear()
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        video.videoSurfaceView?.apply { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f }
        video.subtitleView?.setBottomPaddingFraction(0.08f)
        video.findViewWithTag<androidx.media3.ui.SubtitleView>("narration-subtitle-overlay")?.setBottomPaddingFraction(0.08f)
    }
}
