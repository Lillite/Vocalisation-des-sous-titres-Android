package fr.soustitresvoix.player

import android.content.Context
import androidx.core.content.edit
import fr.soustitresvoix.core.AudioSettings
import fr.soustitresvoix.core.NarrationMachine

class PlayerPreferences(context: Context) {
    private val p = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    var narration: Boolean
        get() = p.getBoolean("narration", true)
        set(v) { p.edit().putBoolean("narration", v).apply() }
    var comfort: Boolean
        get() = p.getBoolean("comfort", true)
        set(v) { p.edit().putBoolean("comfort", v).apply() }
    var speechRate: Float
        get() = p.getFloat("speechRate", 1.0f).coerceIn(0.6f, 2f)
        set(v) { p.edit().putFloat("speechRate", v.coerceIn(0.6f, 2f)).apply() }
    var movieVolume: Float
        get() = p.getFloat("movieVolume", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        set(v) { p.edit().putFloat("movieVolume", v.coerceIn(0f, 1f)).apply() }
    var duckVolume: Float
        get() = p.getFloat("duckVolume", 0.25f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .25f
        set(v) { p.edit().putFloat("duckVolume", v.coerceIn(0f, 1f)).apply() }
    var voiceVolume: Float
        get() = p.getFloat("voiceVolume", 1f).coerceIn(0.1f, 1f)
        set(v) { p.edit().putFloat("voiceVolume", v.coerceIn(0.1f, 1f)).apply() }
    var subtitleSp: Float
        get() = p.getFloat("subtitleSp", 32f).coerceIn(22f, 60f)
        set(v) { p.edit().putFloat("subtitleSp", v.coerceIn(22f, 60f)).apply() }
    var skipDescriptions: Boolean
        get() = p.getBoolean("skipDescriptions", false)
        set(v) { p.edit().putBoolean("skipDescriptions", v).apply() }
    var showSubtitles: Boolean
        get() = p.getBoolean("showSubtitles", true)
        set(v) { p.edit().putBoolean("showSubtitles", v).apply() }
    var voiceName: String
        get() = p.getString("voiceName", "") ?: ""
        set(v) { p.edit().putString("voiceName", v).apply() }
    val mode get(): NarrationMachine.Mode {
        val chosen = runCatching { NarrationMachine.Mode.valueOf(p.getString("mode", "")!!) }.getOrDefault(
            if (comfort) NarrationMachine.Mode.COMFORT else NarrationMachine.Mode.CONTINUOUS)
        return if (bool("integralReading", true) && chosen == NarrationMachine.Mode.CONTINUOUS)
            NarrationMachine.Mode.COMFORT else chosen
    }
    fun setMode(mode: NarrationMachine.Mode) { p.edit().putString("mode", mode.name).apply() }
    var enginePackage: String
        get() = p.getString("engine", "") ?: ""
        set(v) { p.edit().putString("engine", v).remove("voiceName").apply() }
    fun int(key: String, default: Int, min: Int, max: Int) = p.getInt(key, default).coerceIn(min, max)
    fun putInt(key: String, value: Int) { p.edit().putInt(key, value).apply() }
    fun bool(key: String, default: Boolean = false) = p.getBoolean(key, default)
    fun putBool(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    /** Existing preference keys and defaults are preserved across every media opening. */
    internal fun movieAudioSettings() = AudioSettings(movieVolume, duckVolume,
        int("duckAttack", 160, 0, 2000), int("duckRelease", 500, 0, 2000),
        int("duckHold", 350, 0, 2000), bool("spectralDucking"),
        AudioDspPreferences.decode(runCatching { p.getString(AudioDspPreferences.KEY, null) }.getOrNull()))
    /** Called only after command validation; grouped values become visible in one preference edit. */
    internal fun saveMovieAudioSettings(settings: AudioSettings) {
        p.edit {
            putFloat("movieVolume", settings.movieVolume)
            putFloat("duckVolume", settings.duckVolume)
            putInt("duckAttack", settings.attackMs)
            putInt("duckRelease", settings.releaseMs)
            putInt("duckHold", settings.holdMs)
            putBoolean("spectralDucking", settings.spectralDucking)
            putString(AudioDspPreferences.KEY, AudioDspPreferences.encode(settings.dsp))
        }
    }
    val adaptiveMax get() = int("adaptiveMax", 180, 100, 250) / 100f
    val resumePolicy get() = NarrationMachine.ResumePolicy.values()[int("resumePolicy", 0, 0, 2)]
    val cacheEnabled get() = bool("cache", true)
    val cacheBytes get() = int("cacheMiB", 512, 16, 8192).toLong() * 1024 * 1024
    val lookAheadCount get() = int("lookAheadCount", 256, 1, 4096)
    val lookAheadSeconds get() = int("lookAheadSeconds", 120, 5, 7200)
    val allowedLagMs get() = int("allowedLagMs", 600, 0, 2000)
    val protectFrame get() = bool("protectFrame", true)
    val filters get() = if (bool("integralReading", true)) fr.soustitresvoix.core.SpeechTools.Filters()
        else fr.soustitresvoix.core.SpeechTools.Filters(skipDescriptions,
            bool("skipSpeakers"), bool("skipSongs"), bool("skipNotes"))
    /** Only user settings. Media locations, journal data and credentials never enter this snapshot. */
    fun settingsSnapshot(): Map<String, Any> = p.all.filterKeys { it in SETTINGS_KEYS }
        .mapNotNull { (key, value) -> value?.takeIf { it is Boolean || it is Int || it is Long || it is Float || it is String }?.let { key to it } }.toMap()

    fun restoreSettings(snapshot: Map<String, Any>) {
        p.edit {
            SETTINGS_KEYS.forEach { remove(it) }
            snapshot.forEach { (key, value) -> if (key in SETTINGS_KEYS) when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> if (value.isFinite()) putFloat(key, value)
                is String -> if (value.length <= 65_536) putString(key, value)
            } }
        }
    }
    companion object {
        internal val SETTINGS_KEYS = setOf("narration", "comfort", "speechRate", "movieVolume", "duckVolume",
            "voiceVolume", "subtitleSp", "skipDescriptions", "showSubtitles", "voiceName", "mode", "engine",
            "duckAttack", "duckRelease", "duckHold", "spectralDucking", AudioDspPreferences.KEY,
            "adaptiveMax", "resumePolicy", "cache", "cacheMiB", "lookAheadCount", "lookAheadSeconds", "allowedLagMs",
            "protectFrame", "skipSpeakers", "skipSongs", "skipNotes", "matchFrameRate", "preferSoftware",
            "imageMode", "imageDefaultMode", "orientation", "gestures", "gestureSeek", "gestureLevels", "gestureZoom",
            "controlsTimeout", "performanceProfile", "modelRevision", "comprehensionPauseMs",
            "comprehensionProfile", "integralReading", "translationDisplay")
    }
    fun lastMedia(): SavedMedia? {
        val uri = p.getString("lastUri", null) ?: return null
        return SavedMedia(uri, p.getString("lastTitle", "Vidéo") ?: "Vidéo",
            p.getLong("position", 0).coerceAtLeast(0), p.getString("subtitlePath", null), p.getString("subtitleMime", null))
    }
    fun save(media: SavedMedia) {
        p.edit().putString("lastUri", media.uri).putString("lastTitle", media.title)
            .putLong("position", media.positionMs.coerceAtLeast(0)).putString("subtitlePath", media.subtitlePath)
            .putString("subtitleMime", media.subtitleMime).apply()
    }
    fun clearMedia() { p.edit().remove("lastUri").remove("lastTitle").remove("position").remove("subtitlePath").remove("subtitleMime").apply() }
}
data class SavedMedia(val uri: String, val title: String, val positionMs: Long = 0,
    val subtitlePath: String? = null, val subtitleMime: String? = null)
