package fr.soustitresvoix.player

import android.content.Context
import android.util.AtomicFile
import fr.soustitresvoix.core.SubtitleTimeline
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Resume metadata contains identities and times, never subtitle text. A later
 * index lookup resolves the occurrence; a changed source must not be replayed. */
data class OccurrenceResume(val id: String, val startMs: Long, val endMs: Long)

data class MediaSessionSnapshot(
    val uri: String,
    val title: String,
    val positionMs: Long = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val fingerprint: String = "",
    val audioTrackId: String? = null,
    val textTrackId: String? = null,
    val audioLanguage: String? = null,
    val textLanguage: String? = null,
    val subtitlePath: String? = null,
    val subtitleMime: String? = null,
    val translationIdentity: String? = null,
    val translationReady: Boolean = false,
    val enginePackage: String = "",
    val voiceName: String = "",
    val narrationMode: String = "COMFORT",
    val settings: Map<String, Any> = emptyMap(),
    val image: Map<String, Any> = emptyMap(),
    val timeline: SubtitleTimeline = SubtitleTimeline(),
    val lastCompleted: OccurrenceResume? = null,
    val lastInterrupted: OccurrenceResume? = null,
    val timelines: Map<String, SubtitleTimeline> = emptyMap(),
    val narrationCheckpoint: OccurrenceResume? = null,
    val narrationCheckpointUnavailable: Boolean = false,
    val narrationCheckpointTrackId: String? = null
)

/** Durable private, atomic, bounded records. This is separate from an evictable
 * cache. Saving a session does not assert that any translation/audio is ready;
 * those resources have to be verified again by their owner before playback. */
class MediaSessionStore(context: Context) {
    private val directory = File(context.filesDir, "media-sessions-v1")
    // An old Activity writer and a new owner may briefly coexist. AtomicFile
    // replacement/read recovery must share a lock across store instances.
    private val diskLock = synchronized(DIRECTORY_LOCKS) {
        DIRECTORY_LOCKS.getOrPut(directory.absolutePath) { Any() }
    }
    var lastWarning: String? = null
        private set

    fun get(uri: String): MediaSessionSnapshot? = synchronized(diskLock) {
        lastWarning = null
        val result = read(file(uri)) ?: return null
        if (result.uri != uri) {
            lastWarning = "La séance mémorisée ne correspond pas au fichier demandé."
            return null
        }
        return result
    }

    fun save(snapshot: MediaSessionSnapshot) = synchronized(diskLock) {
        validate(snapshot)
        check(directory.exists() || directory.mkdirs()) { "Impossible de créer le dossier des séances." }
        val destination = file(snapshot.uri)
        check(destination.exists() || records().size < MAX_SESSIONS) {
            "La limite de $MAX_SESSIONS séances conservées est atteinte. Supprime une ancienne séance."
        }
        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES) { "La séance dépasse la taille maximale autorisée." }
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun remove(uri: String) = synchronized(diskLock) { AtomicFile(file(uri)).delete() }

    fun list(): List<MediaSessionSnapshot> = synchronized(diskLock) {
        lastWarning = null
        return records().mapNotNull(::read).sortedByDescending { it.updatedAtMs }
    }

    /** Call only after the replacement file has been explicitly selected and
     * its identity checked. Save succeeds before the old record is removed. */
    fun relocate(oldUri: String, newUri: String): MediaSessionSnapshot? = synchronized(diskLock) {
        val previous = get(oldUri) ?: return null
        val changed = previous.copy(uri = newUri, updatedAtMs = System.currentTimeMillis())
        save(changed)
        if (oldUri != newUri) remove(oldUri)
        return changed
    }

    private fun records() = directory.listFiles()?.filter { it.name.matches(FILE_NAME) } ?: emptyList()
    private fun file(uri: String): File {
        require(uri.isNotBlank() && uri.length <= MAX_TEXT && '\u0000' !in uri) { "Identité du fichier invalide." }
        val hash = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    private fun read(file: File): MediaSessionSnapshot? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            val bytes = AtomicFile(file).openRead().use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= MAX_RECORD_BYTES)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            decode(JSONObject(bytes.toString(Charsets.UTF_8))).also(::validate)
        } catch (_: Exception) {
            lastWarning = "Une séance mémorisée est illisible ou incompatible ; les fichiers vidéo sont conservés."
            null
        }
    }

    companion object {
        private val DIRECTORY_LOCKS = HashMap<String, Any>()
        const val MAX_SESSIONS = 256
        const val MAX_RECORD_BYTES = 256 * 1024
        private const val MAX_TEXT = 8192
        private const val MAX_SETTINGS = 256
        private const val SCHEMA = 1
        private val FILE_NAME = Regex("[a-f0-9]{64}\\.json")
        private val SECRET_NAME = Regex("(?i)(secret|password|credential|api.?key|access.?token|refresh.?token)")

        private fun validate(snapshot: MediaSessionSnapshot) {
            require(snapshot.uri.isNotBlank() && snapshot.uri.length <= MAX_TEXT && '\u0000' !in snapshot.uri)
            require(snapshot.title.length <= MAX_TEXT && '\u0000' !in snapshot.title)
            require(snapshot.positionMs in 0..SubtitleTimeline.MAX_TIME_MS && snapshot.updatedAtMs >= 0)
            listOf(snapshot.fingerprint, snapshot.audioTrackId, snapshot.textTrackId, snapshot.audioLanguage,
                snapshot.textLanguage, snapshot.subtitlePath, snapshot.subtitleMime, snapshot.translationIdentity,
                snapshot.enginePackage, snapshot.voiceName, snapshot.narrationMode, snapshot.narrationCheckpointTrackId).filterNotNull().forEach {
                require(it.length <= MAX_TEXT && '\u0000' !in it)
            }
            listOf(snapshot.settings, snapshot.image).forEach { values ->
                require(values.size <= MAX_SETTINGS)
                values.forEach { (key, value) ->
                    require(key.isNotBlank() && key.length <= 128 && !SECRET_NAME.containsMatchIn(key)) {
                        "Un réglage privé ne peut pas être conservé dans une séance."
                    }
                    require(value is String || value is Boolean || value is Int || value is Long || value is Float || value is Double)
                    if (value is String) require(value.length <= MAX_TEXT && '\u0000' !in value)
                    if (value is Float) require(value.isFinite())
                    if (value is Double) require(value.isFinite())
                }
            }
            listOfNotNull(snapshot.lastCompleted, snapshot.lastInterrupted).forEach {
                require(it.id.isNotBlank() && it.id.length <= MAX_TEXT && '\u0000' !in it.id)
                require(it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= SubtitleTimeline.MAX_TIME_MS)
            }
            snapshot.narrationCheckpoint?.let {
                require(it.id.matches(Regex("[0-9a-f]{64}")))
                require(it.startMs in -SubtitleTimeline.MAX_TIME_MS..SubtitleTimeline.MAX_TIME_MS &&
                    it.endMs > it.startMs && it.endMs <= SubtitleTimeline.MAX_TIME_MS)
            }
            require(snapshot.timelines.size <= 128)
            snapshot.timelines.keys.forEach { require(it.isNotBlank() && it.length <= MAX_TEXT && '\u0000' !in it) }
        }

        private fun encode(snapshot: MediaSessionSnapshot) = JSONObject().apply {
            put("schema", SCHEMA); put("uri", snapshot.uri); put("title", snapshot.title)
            put("positionMs", snapshot.positionMs); put("updatedAtMs", snapshot.updatedAtMs)
            put("fingerprint", snapshot.fingerprint)
            put("audioTrackId", snapshot.audioTrackId); put("textTrackId", snapshot.textTrackId)
            put("audioLanguage", snapshot.audioLanguage); put("textLanguage", snapshot.textLanguage)
            put("subtitlePath", snapshot.subtitlePath); put("subtitleMime", snapshot.subtitleMime)
            put("translationIdentity", snapshot.translationIdentity); put("translationReady", snapshot.translationReady)
            put("enginePackage", snapshot.enginePackage); put("voiceName", snapshot.voiceName)
            put("narrationMode", snapshot.narrationMode)
            put("settings", encodeMap(snapshot.settings)); put("image", encodeMap(snapshot.image))
            put("timeline", encodeTimeline(snapshot.timeline))
            put("lastCompleted", encodeOccurrence(snapshot.lastCompleted))
            put("lastInterrupted", encodeOccurrence(snapshot.lastInterrupted))
            put("narrationCheckpoint", encodeOccurrence(snapshot.narrationCheckpoint))
            put("narrationCheckpointUnavailable", snapshot.narrationCheckpointUnavailable)
            put("narrationCheckpointTrackId", snapshot.narrationCheckpointTrackId)
            put("timelines", JSONObject().apply { snapshot.timelines.toSortedMap().forEach { (track, value) -> put(track, encodeTimeline(value)) } })
        }

        private fun decode(json: JSONObject): MediaSessionSnapshot {
            require(json.getInt("schema") == SCHEMA)
            fun optional(name: String): String? = if (json.isNull(name)) null else json.getString(name)
            return MediaSessionSnapshot(json.getString("uri"), json.getString("title"), json.getLong("positionMs"),
                json.getLong("updatedAtMs"), json.getString("fingerprint"), optional("audioTrackId"), optional("textTrackId"),
                optional("audioLanguage"), optional("textLanguage"), optional("subtitlePath"), optional("subtitleMime"),
                optional("translationIdentity"), json.getBoolean("translationReady"), json.getString("enginePackage"),
                json.getString("voiceName"), json.getString("narrationMode"), decodeMap(json.getJSONObject("settings")),
                decodeMap(json.getJSONObject("image")), decodeTimeline(json.getJSONObject("timeline")),
                decodeOccurrence(json.optJSONObject("lastCompleted")), decodeOccurrence(json.optJSONObject("lastInterrupted")),
                json.optJSONObject("timelines")?.let { timelines ->
                    require(timelines.length() <= 128)
                    timelines.keys().asSequence().associateWith { decodeTimeline(timelines.getJSONObject(it)) }
                } ?: emptyMap(), decodeOccurrence(json.optJSONObject("narrationCheckpoint")),
                json.optBoolean("narrationCheckpointUnavailable", false), optional("narrationCheckpointTrackId"))
        }

        internal fun encodeTimeline(value: SubtitleTimeline) = JSONObject().apply {
            put("offsetMs", value.globalOffsetMs); put("narrationOffsetMs", value.narrationOffsetMs)
            put("anchors", JSONArray().apply { value.anchors.forEach { anchor ->
                put(JSONObject().put("sourceMs", anchor.sourceMs).put("videoMs", anchor.videoMs))
            } })
            put("portions", JSONArray().apply { value.portions.forEach { portion ->
                put(JSONObject().put("startMs", portion.startMs).put("endMs", portion.endMs).put("offsetMs", portion.offsetMs))
            } })
        }

        internal fun decodeTimeline(json: JSONObject): SubtitleTimeline {
            val array = json.getJSONArray("anchors")
            require(array.length() <= SubtitleTimeline.MAX_ANCHORS)
            val portions = if (json.has("portions")) json.getJSONArray("portions") else null
            require(portions == null || portions.length() <= SubtitleTimeline.MAX_PORTIONS)
            return SubtitleTimeline(json.getLong("offsetMs"), json.getLong("narrationOffsetMs"),
                (0 until array.length()).map { index -> array.getJSONObject(index).let {
                    SubtitleTimeline.Anchor(it.getLong("sourceMs"), it.getLong("videoMs"))
                } }, (0 until (portions?.length() ?: 0)).map { index -> portions!!.getJSONObject(index).let {
                    SubtitleTimeline.Portion(it.getLong("startMs"), it.getLong("endMs"), it.getLong("offsetMs"))
                } })
        }

        private fun encodeOccurrence(value: OccurrenceResume?): JSONObject? = value?.let {
            JSONObject().put("id", it.id).put("startMs", it.startMs).put("endMs", it.endMs)
        }
        private fun decodeOccurrence(json: JSONObject?): OccurrenceResume? = json?.let {
            OccurrenceResume(it.getString("id"), it.getLong("startMs"), it.getLong("endMs"))
        }
        private fun encodeMap(values: Map<String, Any>) = JSONObject().apply {
            values.toSortedMap().forEach { (key, value) ->
                val type = when (value) {
                    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Double -> "double"
                    else -> "string"
                }
                put(key, JSONObject().put("type", type).put("value", value))
            }
        }
        private fun decodeMap(json: JSONObject): Map<String, Any> {
            require(json.length() <= MAX_SETTINGS)
            return json.keys().asSequence().associateWith { key ->
                val item = json.getJSONObject(key)
                when (item.getString("type")) {
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat()
                    "double" -> item.getDouble("value")
                    "string" -> item.getString("value")
                    else -> throw IllegalArgumentException("Type de réglage inconnu.")
                }
            }
        }
    }
}
