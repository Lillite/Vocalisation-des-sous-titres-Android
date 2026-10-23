package fr.soustitresvoix.player

import fr.soustitresvoix.core.AudioDspPolicy
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioEqBand
import org.json.JSONArray
import org.json.JSONObject

/** Versioned film-only preferences. A corrupt group falls back to disabled safe defaults. */
internal object AudioDspPreferences {
    const val KEY = "expertAudio.v1"

    fun encode(settings: AudioDspSettings): String {
        require(AudioDspPolicy.validate(settings) == null)
        return JSONObject().apply {
            put("version", 1)
            put("enabled", settings.enabled)
            put("preampDb", settings.preampDb)
            put("balance", settings.balance)
            put("mono", settings.mono)
            put("limiterEnabled", settings.limiterEnabled)
            put("limiterCeilingDb", settings.limiterCeilingDb)
            put("limiterReleaseMs", settings.limiterReleaseMs)
            put("bands", JSONArray().apply {
                for (index in 0..4) put(JSONObject().apply {
                    val band = settings.band(index)
                    put("enabled", band.enabled)
                    put("frequencyHz", band.frequencyHz)
                    put("gainDb", band.gainDb)
                    put("q", band.q)
                })
            })
        }.toString()
    }

    fun decode(raw: String?): AudioDspSettings {
        if (raw == null || raw.length > 4096) return AudioDspSettings()
        return runCatching {
            val json = JSONObject(raw)
            require(json.number("version").toString().toBigDecimal().intValueExact() == 1)
            val bands = json.getJSONArray("bands")
            require(bands.length() == 5)
            fun band(index: Int): AudioEqBand = bands.getJSONObject(index).let {
                AudioEqBand(it.boolean("enabled"), it.number("frequencyHz").toDouble(),
                    it.number("gainDb").toDouble(), it.number("q").toDouble())
            }
            val settings = AudioDspSettings(
                enabled = json.boolean("enabled"), preampDb = json.number("preampDb").toDouble(),
                balance = json.number("balance").toDouble(), mono = json.boolean("mono"),
                limiterEnabled = json.boolean("limiterEnabled"), limiterCeilingDb = json.number("limiterCeilingDb").toDouble(),
                limiterReleaseMs = json.number("limiterReleaseMs").toString().toBigDecimal().intValueExact(),
                band1 = band(0), band2 = band(1), band3 = band(2), band4 = band(3), band5 = band(4))
            require(AudioDspPolicy.validate(settings) == null)
            AudioDspPolicy.canonicalize(settings)
        }.getOrDefault(AudioDspSettings())
    }

    private fun JSONObject.number(key: String): Number = get(key) as? Number
        ?: throw IllegalArgumentException("Expected a numeric DSP preference")
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean
        ?: throw IllegalArgumentException("Expected a Boolean DSP preference")
}
