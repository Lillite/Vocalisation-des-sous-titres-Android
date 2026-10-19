package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
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
import java.util.Locale
import kotlin.math.abs

/** Video fills the window; controls and safe-area padding belong to separate overlays. */
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
    private var zoom = 1f
    private var dx = 0f; private var dy = 0f
    private var startX = 0f; private var startY = 0f
    private var startVolume = 1f; private var startBrightness = 0.5f
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
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 16f; isAllCaps = false; minHeight = dp(52)
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(45, 45, 45))
        setOnClickListener { action(); scheduleHide() }
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
        tools.addView(button("Image / zoom") { imageDialog() })
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
            insets
        }
        val scale = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true; dragSeek = null; return !locked && prefs.bool("gestureZoom", true) && !accessibility.isTouchExplorationEnabled
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f); applyImage(); tell("Zoom ${"%.1f".format(zoom)}×"); return true
            }
        })
        val gesture = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
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
                if (zoom > 1.01f) {
                    dx -= distanceX; dy -= distanceY; applyImage(); return true
                }
                val horizontal = abs(event.x - startX) > abs(event.y - startY)
                if (horizontal && prefs.bool("gestureSeek", true)) {
                    val duration = session.player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    dragSeek = (startPosition + ((event.x - startX) / width.coerceAtLeast(1) * 90_000).toLong()).coerceIn(0, duration)
                    tell("Aller à ${clock(dragSeek!!)}")
                } else if (!horizontal && prefs.bool("gestureLevels", true)) {
                    val change = (startY - event.y) / height.coerceAtLeast(1)
                    if (startX > width / 2) {
                        prefs.movieVolume = (startVolume + change).coerceIn(0f, 1f); session.updateMix()
                        tell("Volume film ${(prefs.movieVolume * 100).toInt()} %")
                    } else {
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = (startBrightness + change).coerceIn(0.01f, 1f) }
                        tell("Luminosité ${(activity.window.attributes.screenBrightness * 100).toInt()} %")
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
        val state = session.machine.snapshot()
        play.text = when { state.wantsPlayback -> "Pause"; state.speakingId != null -> "Arrêter la voix"; else -> "Lire" }
        status.text = when { state.voiceSuspended -> "Voix en attente : Reprendre la voix"; state.waitingForSpeech -> "La voix termine la phrase"; state.wantsPlayback -> ""; else -> "En pause" }
        val d = session.player.duration; val pos = session.player.currentPosition
        time.text = "${clock(pos)} / ${if (d > 0 && d != C.TIME_UNSET) clock(d) else "—"}"
        seek.isEnabled = d > 0 && d != C.TIME_UNSET && session.player.isCurrentMediaItemSeekable
        if (!scrubbing && seek.isEnabled) seek.progress = (pos.toDouble() / d * 1000).toInt().coerceIn(0, 1000)
        seek.contentDescription = "Position ${clock(pos)} sur ${if (d > 0) clock(d) else "durée inconnue"}"
        title.text = session.media?.title ?: "Vidéo"
        if (!state.wantsPlayback && state.speakingId == null && !locked && !visibleControls) showControls(true)
    }
    private fun hasAccessibleFocus(view: View): Boolean = view.isAccessibilityFocused ||
        (view is ViewGroup && (0 until view.childCount).any { hasAccessibleFocus(view.getChildAt(it)) })
    private fun scheduleHide() {
        main.removeCallbacks(hide)
        val seconds = prefs.int("controlsTimeout", 5, 0, 30)
        if (seconds > 0 && visibleControls && !accessibility.isTouchExplorationEnabled) main.postDelayed(hide, seconds * 1000L)
    }
    fun showControls(show: Boolean) {
        visibleControls = show; top.visibility = if (show) View.VISIBLE else View.GONE
        bottom.visibility = if (show) View.VISIBLE else View.GONE
        main.removeCallbacks(hide); updateCaptionPosition()
        if (show) scheduleHide()
    }
    private fun updateCaptionPosition() {
        val space = if (visibleControls && height > 0) (bottom.height.toFloat() / height + 0.02f).coerceIn(0.08f, 0.60f) else 0.08f
        video.subtitleView?.setBottomPaddingFraction(space)
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
    private fun applyImage() {
        video.resizeMode = when (prefs.int("imageMode", 0, 0, 2)) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        dx = dx.coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2)
        dy = dy.coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2)
        video.videoSurfaceView?.apply { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy }
    }
    fun imageDialog() {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun mode(label: String, value: Int) { column.addView(button(label) { prefs.putInt("imageMode", value); zoom = 1f; dx = 0f; dy = 0f; applyImage() }) }
        mode("Adapter : image entière", 0); mode("Remplir : recadrage", 1); mode("Étirer : proportions modifiées", 2)
        column.addView(button("Zoom +") { zoom = (zoom + 0.25f).coerceAtMost(4f); applyImage() })
        column.addView(button("Zoom −") { zoom = (zoom - 0.25f).coerceAtLeast(1f); applyImage() })
        column.addView(button("Recentrer / réinitialiser") { zoom = 1f; dx = 0f; dy = 0f; applyImage() })
        column.addView(button("Luminosité automatique du système") { activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f } })
        AlertDialog.Builder(activity).setTitle("Image et cadrage").setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton("Fermer", null).show()
    }
    fun close() {
        disposed = true; main.removeCallbacksAndMessages(null)
        video.videoSurfaceView?.apply { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f }
        video.subtitleView?.setBottomPaddingFraction(0.08f)
    }
}
