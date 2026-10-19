package fr.soustitresvoix.player

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs

/** Viewing controls only: the ExoPlayer and narration session are never replaced. */
@UnstableApi
class CinemaControls(private val activity: Activity, private val video: PlayerView,
    private val session: PlaybackSession, private val prefs: PlayerPreferences,
    private val exit: () -> Unit, private val options: () -> Unit) : FrameLayout(activity) {
    private val main = Handler(Looper.getMainLooper())
    private val accessibility = activity.getSystemService(AccessibilityManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val touch = View(activity)
    private val panels = FrameLayout(activity)
    private val title = label("", 16f)
    private val state = label("", 14f)
    private val position = label("", 15f)
    private val feedback = label("", 20f)
    private val seek = SeekBar(activity)
    private val play = button("Lire") { togglePlay() }
    private val unlock = button("Déverrouiller") { setLocked(false) }
    private var scrubbing = false
    private var resumeAfterSeek = false
    private var menuOpen = false
    private var controlsVisible = true
    private var wasPlaying = false
    private var startY = 0f
    private var startVolume = 0
    private var startBrightness = 0.5f
    private var startPanX = 0f
    private var startPanY = 0f
    private var suppressScroll = false
    var locked = false; private set
    var zoom = 1f; private set
    var imageMode = 0; private set
    private val hide = Runnable {
        if (!locked && !menuOpen && !scrubbing && session.machine.snapshot().wantsPlayback &&
            accessibility?.isTouchExplorationEnabled != true && !panels.hasFocus()) setVisible(false)
    }
    private fun dp(i: Int) = (resources.displayMetrics.density * i).toInt()
    private fun label(s: String, sp: Float) = TextView(activity).apply {
        text = s; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    private fun button(s: String, action: () -> Unit) = Button(activity).apply {
        text = s; textSize = 16f; isAllCaps = false; minHeight = dp(56); minimumWidth = dp(60)
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(0xD0222222.toInt())
        setPadding(dp(8), dp(2), dp(8), dp(2)); setOnClickListener { action(); scheduleHide() }
        setOnFocusChangeListener { _, focus -> if (focus) main.removeCallbacks(hide) else scheduleHide() }
    }
    init {
        id = R.id.cinema_controls
        addView(touch, LayoutParams(-1, -1))
        touch.isClickable = true; touch.isFocusable = true
        touch.contentDescription = "Afficher ou masquer les commandes de la vidéo"
        touch.setOnClickListener { if (!locked) setVisible(!controlsVisible) }
        addView(panels, LayoutParams(-1, -1))
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xAA000000.toInt()) }
        top.addView(button("Retour") { exit() }, LinearLayout.LayoutParams(-2, dp(56)))
        title.maxLines = 1; top.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        top.addView(button("Image") { imageDialog() }, LinearLayout.LayoutParams(-2, dp(56)))
        top.addView(button("Options") { options() }, LinearLayout.LayoutParams(-2, dp(56)))
        panels.addView(top, LayoutParams(-1, -2, Gravity.TOP))
        val middle = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        middle.addView(button("−10 s") { session.seek(session.player.currentPosition - 10_000); showFeedback("Recul de 10 secondes") }, LinearLayout.LayoutParams(0, dp(64), 1f))
        play.id = R.id.cinema_play
        middle.addView(play, LinearLayout.LayoutParams(0, dp(64), 1f))
        middle.addView(button("+10 s") { session.seek(session.player.currentPosition + 10_000); showFeedback("Avance de 10 secondes") }, LinearLayout.LayoutParams(0, dp(64), 1f))
        panels.addView(middle, LayoutParams(dp(300), dp(64), Gravity.CENTER))
        val bottom = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xAA000000.toInt()) }
        state.maxLines = 1; state.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bottom.addView(state)
        val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        position.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        line.addView(position, LinearLayout.LayoutParams(-2, dp(48)))
        seek.max = 1000; seek.minimumHeight = dp(48)
        seek.contentDescription = "Position de la vidéo"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar) { scrubbing = true; resumeAfterSeek = session.machine.snapshot().wantsPlayback; session.pause(); main.removeCallbacks(hide) }
            override fun onStopTrackingTouch(s: SeekBar) {
                jump(s.progress); scrubbing = false
                if (resumeAfterSeek) session.play()
                scheduleHide()
            }
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user && !scrubbing) jump(p) }
        })
        line.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f)); bottom.addView(line)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Répéter") { session.repeatLast() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button("Reprendre la voix") { session.resumeVoice() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button("Verrouiller") { setLocked(true) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        bottom.addView(actions); panels.addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        feedback.visibility = View.GONE; feedback.setBackgroundColor(0xDD000000.toInt())
        feedback.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(feedback, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(66) })
        unlock.visibility = View.GONE
        addView(unlock, LayoutParams(-2, dp(56), Gravity.END or Gravity.TOP))
        val scale = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { suppressScroll = true; main.removeCallbacks(hide); return !locked && prefs.gestures }
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor); showFeedback("Zoom ${Math.round(zoom * 100)} %"); return true }
        })
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                startY = e.y; startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                    ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                startPanX = video.videoSurfaceView?.translationX ?: 0f; startPanY = video.videoSurfaceView?.translationY ?: 0f
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { touch.performClick(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked && prefs.gestures && !suppressScroll) {
                    val delta = if (e.x < width / 2f) -10_000L else 10_000L
                    session.seek(session.player.currentPosition + delta); showFeedback(if (delta < 0) "Recul de 10 secondes" else "Avance de 10 secondes")
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || !prefs.gestures || suppressScroll || e1 == null) return false
                main.removeCallbacks(hide)
                if (zoom > 1.05f) {
                    val surface = video.videoSurfaceView ?: return true
                    surface.translationX = (startPanX + e2.x - e1.x).coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2)
                    surface.translationY = (startPanY + e2.y - e1.y).coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2)
                } else if (abs(e2.y - e1.y) > abs(e2.x - e1.x)) {
                    val delta = (startY - e2.y) / height.coerceAtLeast(1)
                    if (e1.x >= width / 2f) {
                        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
                        val v = (startVolume + delta * max).toInt().coerceIn(0, max)
                        audio?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showFeedback("Volume média ${100 * v / max.coerceAtLeast(1)} %")
                    } else {
                        val v = (startBrightness + delta).coerceIn(0.02f, 1f)
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = v }
                        showFeedback("Luminosité ${Math.round(v * 100)} %")
                    }
                }
                return true
            }
        })
        @Suppress("ClickableViewAccessibility")
        touch.setOnTouchListener { _, event ->
            if (locked) return@setOnTouchListener true
            if (accessibility?.isTouchExplorationEnabled == true) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressScroll = false
            if (event.pointerCount > 1) suppressScroll = true
            if (prefs.gestures) scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scheduleHide()
            true
        }
        setVisible(true)
    }
    private fun togglePlay() {
        val s = session.machine.snapshot()
        if (s.wantsPlayback || s.speakingId != null) session.pause()
        else if (s.voiceResumeRequired) session.resumeVoice() else session.play()
        setVisible(true)
    }
    private fun jump(progress: Int) {
        val d = session.player.duration
        if (d > 0 && d != C.TIME_UNSET) session.seek((d * (progress / 1000.0)).toLong())
    }
    fun safeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        panels.setPadding(left, top, right, bottom)
        val available = resources.configuration.screenWidthDp
        title.visibility = if (available < 480) View.GONE else View.VISIBLE
        (unlock.layoutParams as LayoutParams).apply { topMargin = top; rightMargin = right }; unlock.requestLayout()
    }
    fun setMenuOpen(value: Boolean) { menuOpen = value; if (value) main.removeCallbacks(hide) else scheduleHide() }
    fun setLocked(value: Boolean) {
        locked = value; unlock.visibility = if (value) View.VISIBLE else View.GONE
        panels.visibility = if (value) View.GONE else View.VISIBLE
        touch.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        controlsVisible = !value; if (!value) { touch.requestFocus(); scheduleHide() }
    }
    fun setVisible(value: Boolean) {
        if (locked) return
        controlsVisible = value; panels.visibility = if (value) View.VISIBLE else View.GONE
        if (value) scheduleHide() else main.removeCallbacks(hide)
    }
    private fun scheduleHide() {
        main.removeCallbacks(hide)
        if (controlsVisible && prefs.hideSeconds > 0 && accessibility?.isTouchExplorationEnabled != true)
            main.postDelayed(hide, prefs.hideSeconds * 1000L)
    }
    private fun showFeedback(text: String) {
        feedback.text = text; feedback.visibility = View.VISIBLE
        main.removeCallbacks(clearFeedback); main.postDelayed(clearFeedback, 1100)
    }
    private val clearFeedback = Runnable { feedback.visibility = View.GONE }
    fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, 3f)
        video.videoSurfaceView?.apply { scaleX = zoom; scaleY = zoom; if (zoom == 1f) { translationX = 0f; translationY = 0f } }
    }
    fun setImageMode(mode: Int) {
        imageMode = mode.coerceIn(0, 2)
        video.resizeMode = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)[imageMode]
        setZoom(1f)
    }
    private fun imageDialog() {
        setMenuOpen(true)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val labels = arrayOf("Adapter — image entière", "Remplir — image recadrée", "Étirer — proportions déformées")
        labels.forEachIndexed { i, s -> box.addView(button(s) { setImageMode(i) }) }
        box.addView(label("Zoom de l’image seulement (sous-titres non agrandis)", 16f))
        val value = label("${Math.round(zoom * 100)} %", 18f); box.addView(value)
        box.addView(SeekBar(activity).apply { max = 200; progress = Math.round((zoom - 1) * 100); contentDescription = "Zoom de 100 à 300 pour cent"; minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) { setZoom(1 + p / 100f); value.text = "${p + 100} %" } }
            }) })
        box.addView(button("Recentrer et annuler le zoom") { setZoom(1f); value.text = "100 %" })
        val dialog = android.app.AlertDialog.Builder(activity).setTitle("Format de l’image").setView(box).setPositiveButton("Fermer", null).create()
        dialog.setOnDismissListener { setMenuOpen(false) }; dialog.show()
    }
    fun update(titleText: String, status: String) {
        title.text = titleText; state.text = status
        val snapshot = session.machine.snapshot()
        play.text = when { snapshot.wantsPlayback -> "Pause"; snapshot.speakingId != null -> "Arrêter"; else -> "Lire" }
        val pos = session.player.currentPosition.coerceAtLeast(0); val d = session.player.duration
        position.text = "${clock(pos)} / ${clock(d.coerceAtLeast(0))}"
        seek.isEnabled = d > 0 && session.player.isCurrentMediaItemSeekable
        if (!scrubbing && d > 0) seek.progress = (pos * 1000.0 / d).toInt().coerceIn(0, 1000)
        seek.contentDescription = "Position ${clock(pos)} sur ${clock(d.coerceAtLeast(0))}"
        if (wasPlaying != snapshot.wantsPlayback) { if (!snapshot.wantsPlayback) setVisible(true) else scheduleHide() }
        wasPlaying = snapshot.wantsPlayback
    }
    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }
    fun dispose() { main.removeCallbacksAndMessages(null) }
}
