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
    val mode get() = if (comfort) NarrationMachine.Mode.COMFORT else NarrationMachine.Mode.CONTINUOUS
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
