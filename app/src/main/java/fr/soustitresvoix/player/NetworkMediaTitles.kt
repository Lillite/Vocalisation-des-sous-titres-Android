package fr.soustitresvoix.player

import android.net.Uri
import java.net.URLDecoder
import java.util.Locale

/** Display names are separate from the protected address and the playback identity. */
internal object NetworkMediaTitles {
    const val FALLBACK = "Vidéo réseau sans titre"
    const val MAX_LENGTH = 200
    private val extensions = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mpeg", "mpg", "ts", "m2ts",
        "mts", "wmv", "flv", "ogv", "3gp", "3g2", "m3u8", "mpd")
    private val generic = setOf("media", "video", "vidéo", "stream", "download", "file", "index", "master",
        "manifest", "playlist", "hls", "dash")
    private val opaque = Regex("[A-Za-z0-9_-]{28,}|[0-9a-fA-F]{16,}|\\d{8,}")
    // Match complete parameters, so semicolons inside a quoted filename are data.
    private const val TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
    private val parameter = Regex("\\s*;\\s*($TOKEN)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|($TOKEN))\\s*")
    private val quotedEscape = Regex("\\\\([\"\\\\])")

    fun userTitle(value: String): String? = checked(value)

    fun metadata(value: CharSequence?): String? = value?.toString()?.let(::meaningful)

    fun urlPath(rawUrl: String): String? {
        val leaf = Uri.parse(rawUrl).lastPathSegment ?: return null
        if (leaf.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions) return null
        return filename(leaf)
    }

    fun contentDisposition(header: String?): String? {
        if (header == null || header.length > 4096 || header.any { it == '\r' || it == '\n' }) return null
        var cursor = header.indexOf(';').takeIf { it >= 0 } ?: return null
        var ordinary: String? = null
        var extended: String? = null
        val names = HashSet<String>()
        while (cursor < header.length) {
            val match = parameter.matchAt(header, cursor) ?: return null
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            if (!names.add(name)) return null
            val quoted = match.groups[2]?.value
            val value = quoted?.let { quotedEscape.replace(it) { escaped -> escaped.groupValues[1] } }
                ?: match.groupValues[3]
            when (name) {
                "filename" -> ordinary = value
                "filename*" -> if (quoted == null) {
                    val parts = value.split('\'', limit = 3)
                    if (parts.size == 3 && parts[0].equals("UTF-8", true)) {
                        extended = runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), "UTF-8") }.getOrNull()
                    }
                }
            }
            cursor = match.range.last + 1
        }
        return extended?.let(::filename) ?: ordinary?.let(::filename)
    }

    private fun filename(value: String): String? {
        val safe = checked(value) ?: return null
        val extension = safe.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return meaningful(if (extension in extensions) safe.substringBeforeLast('.') else safe)
    }

    private fun meaningful(value: String): String? = checked(value)?.takeIf { title ->
        title.lowercase(Locale.ROOT) !in generic && !opaque.matches(title) && title.any(Char::isLetter)
    }

    private fun checked(value: String): String? = value.trim().takeIf { title ->
        title.length in 1..MAX_LENGTH && title.none { character ->
            character.isISOControl() || character in "/\\@=" || character == '\uFFFD' ||
                character in '\u202A'..'\u202E' || character in '\u2066'..'\u2069'
        }
    }
}
