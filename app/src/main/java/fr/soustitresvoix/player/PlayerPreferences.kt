package fr.soustitresvoix.player

import android.content.Context
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
        get() = p.getFloat("movieVolume", 1f).coerceIn(0f, 1f)
        set(v) { p.edit().putFloat("movieVolume", v.coerceIn(0f, 1f)).apply() }
    var duckVolume: Float
        get() = p.getFloat("duckVolume", 0.25f).coerceIn(0f, 1f)
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
    val mode get() = if (adaptive) NarrationMachine.Mode.ADAPTIVE else if (comfort) NarrationMachine.Mode.COMFORT else NarrationMachine.Mode.CONTINUOUS

    var enginePackage: String
        get() = p.getString("enginePackage", "") ?: ""
        set(v) { p.edit().putString("enginePackage", v).apply() }
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun put(k: String, v: Boolean) { p.edit().putBoolean(k, v).apply() }
    private fun put(k: String, v: Int) { p.edit().putInt(k, v).apply() }
    var adaptive: Boolean
        get() = bool("adaptive", false)
        set(v) { put("adaptive", v) }
    var adaptivePercent: Int
        get() = p.getInt("adaptivePercent", 135).coerceIn(100, 180)
        set(v) { put("adaptivePercent", v.coerceIn(100, 180)) }
    var resumePolicy: NarrationMachine.ResumePolicy
        get() = NarrationMachine.ResumePolicy.values()[p.getInt("resumePolicy", 0).coerceIn(0, 2)]
        set(v) { put("resumePolicy", v.ordinal) }
    var cacheEnabled: Boolean
        get() = bool("cacheEnabled", true)
        set(v) { put("cacheEnabled", v) }
    var cacheMb: Int
        get() = p.getInt("cacheMb", 64).coerceIn(16, 128)
        set(v) { put("cacheMb", v.coerceIn(16, 128)) }
    var lookAheadSeconds: Int
        get() = p.getInt("lookAheadSeconds", 30).coerceIn(10, 120)
        set(v) { put("lookAheadSeconds", v.coerceIn(10, 120)) }
    var attackMs: Int
        get() = p.getInt("attackMs", 160).coerceIn(0, 1000)
        set(v) { put("attackMs", v.coerceIn(0, 1000)) }
    var releaseMs: Int
        get() = p.getInt("releaseMs", 500).coerceIn(0, 2000)
        set(v) { put("releaseMs", v.coerceIn(0, 2000)) }
    var duckHoldMs: Int
        get() = p.getInt("duckHoldMs", 400).coerceIn(0, 2000)
        set(v) { put("duckHoldMs", v.coerceIn(0, 2000)) }
    var skipSpeakers: Boolean
        get() = bool("skipSpeakers", false)
        set(v) { put("skipSpeakers", v) }
    var skipSongs: Boolean
        get() = bool("skipSongs", false)
        set(v) { put("skipSongs", v) }
    var skipNotes: Boolean
        get() = bool("skipNotes", false)
        set(v) { put("skipNotes", v) }
    val filters get() = fr.soustitresvoix.core.SpeechPlanning.Filters(skipDescriptions, skipSpeakers, skipSongs, skipNotes)
    var hideSeconds: Int
        get() = p.getInt("hideSeconds", 5).coerceIn(0, 30)
        set(v) { put("hideSeconds", v.coerceIn(0, 30)) }
    var gestures: Boolean
        get() = bool("gestures", true)
        set(v) { put("gestures", v) }
    var cinemaOrientation: Int
        get() = p.getInt("cinemaOrientation", 1).coerceIn(0, 2)
        set(v) { put("cinemaOrientation", v.coerceIn(0, 2)) }
    fun lastMedia(): SavedMedia? {
        val uri = p.getString("lastUri", null) ?: return null
        return SavedMedia(uri, p.getString("lastTitle", "Vidéo") ?: "Vidéo",
            p.getLong("position", 0).coerceAtLeast(0), p.getString("subtitlePath", null),
            p.getString("subtitleMime", null))
    }
    fun save(media: SavedMedia) {
        p.edit().putString("lastUri", media.uri).putString("lastTitle", media.title)
            .putLong("position", media.positionMs.coerceAtLeast(0))
            .putString("subtitlePath", media.subtitlePath).putString("subtitleMime", media.subtitleMime).apply()
    }
    fun clearMedia() { p.edit().remove("lastUri").remove("lastTitle").remove("position").remove("subtitlePath").remove("subtitleMime").apply() }
}
data class SavedMedia(val uri: String, val title: String, val positionMs: Long = 0,
    val subtitlePath: String? = null, val subtitleMime: String? = null)
