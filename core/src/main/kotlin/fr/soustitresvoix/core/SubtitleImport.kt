package fr.soustitresvoix.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

object SubtitleImport {
    const val MAX_BYTES = 8 * 1024 * 1024
    enum class Kind(val extension: String, val mime: String) {
        SRT("srt", "application/x-subrip"),
        VTT("vtt", "text/vtt"),
        ASS("ass", "text/x-ssa")
    }
    class EncodingRequired : IllegalArgumentException("Le texte n’est pas un UTF-8 valide. Choisis son encodage.")
    data class Result(val kind: Kind, val text: String, val charset: String)

    fun decode(bytes: ByteArray, filename: String, explicitCharset: String? = null): Result {
        require(bytes.isNotEmpty()) { "Le fichier de sous-titres est vide." }
        require(bytes.size <= MAX_BYTES) { "Le fichier de sous-titres dépasse 8 Mio." }
        val charset = explicitCharset ?: when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> "UTF-8"
            bytes.startsWith(0xFF, 0xFE) -> "UTF-16LE"
            bytes.startsWith(0xFE, 0xFF) -> "UTF-16BE"
            else -> "UTF-8"
        }
        val text = try {
            Charset.forName(charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        } catch (e: CharacterCodingException) {
            if (explicitCharset == null) throw EncodingRequired()
            throw IllegalArgumentException("Le fichier ne correspond pas à l’encodage $charset.", e)
        }
        require('\u0000' !in text) { "Ce fichier semble binaire, ou son encodage est incorrect." }
        val lower = filename.lowercase(Locale.ROOT)
        val kind = when {
            text.trimStart().startsWith("WEBVTT") -> Kind.VTT
            Regex("(?im)^\\s*\\[Events]\\s*$").containsMatchIn(text) -> Kind.ASS
            lower.endsWith(".vtt") -> Kind.VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> Kind.ASS
            else -> Kind.SRT
        }
        when (kind) {
            Kind.SRT, Kind.VTT -> require(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?[,.]\\d{1,3}\\s*-->\\s*\\d{1,2}:").containsMatchIn(text)) {
                "Aucun minutage de sous-titres reconnu dans le fichier."
            }
            Kind.ASS -> require(Regex("(?im)^Dialogue\\s*:").containsMatchIn(text)) {
                "Le fichier ASS/SSA ne contient aucune ligne Dialogue."
            }
        }
        return Result(kind, text, charset)
    }

    private fun ByteArray.startsWith(vararg prefix: Int) = size >= prefix.size &&
        prefix.indices.all { (this[it].toInt() and 255) == prefix[it] }
}
