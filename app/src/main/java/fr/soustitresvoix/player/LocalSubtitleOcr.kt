package fr.soustitresvoix.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import com.googlecode.tesseract.android.TessBaseAPI
import fr.soustitresvoix.core.SubtitleImport
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale

/** User-provided Tesseract models. No model download or image upload. */
internal class OcrModelStore(private val directory: File) {
    private val data = File(directory, "tessdata")
    private val languagePattern = Regex("[a-z][a-z0-9_]{1,31}")

    fun languages(): List<String> = data.listFiles().orEmpty().filter { file ->
        file.isFile && file.extension == "traineddata" && file.nameWithoutExtension.matches(languagePattern) && file.length() in 1..MAX_MODEL_BYTES
    }.map { it.nameWithoutExtension }.sorted()

    fun importModel(name: String, read: () -> java.io.InputStream): String {
        require(name.endsWith(".traineddata", ignoreCase = true))
        val language = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        require(language.matches(languagePattern))
        data.mkdirs()
        val temporary = File.createTempFile("ocr-import-", ".part", data)
        try {
            read().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Lecture du modèle interrompue.")
                        total += count
                        require(total <= MAX_MODEL_BYTES)
                        output.write(buffer, 0, count)
                    }
                    require(total > 0)
                }
            }
            // Validate in an isolated directory first, preserving an existing
            // working language if this file is incomplete or incompatible.
            val staging = java.nio.file.Files.createTempDirectory(directory.toPath(), "verify-").toFile()
            try {
                val model = File(File(staging, "tessdata").apply { mkdirs() }, "$language.traineddata")
                require(temporary.renameTo(model))
                LocalSubtitleOcr(staging, language).close()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                require(model.renameTo(File(data, "$language.traineddata")))
            } finally { staging.deleteRecursively() }
            return language
        } finally { temporary.delete() }
    }

    fun open(language: String): LocalSubtitleOcr {
        require(language in languages())
        return LocalSubtitleOcr(directory, language)
    }

    private companion object { const val MAX_MODEL_BYTES = 128L * 1024 * 1024 }
}

/** Owned by one worker. Tesseract inference and model storage stay on-device. */
internal class LocalSubtitleOcr(directory: File, language: String) : Closeable {
    private val engine = TessBaseAPI()
    init {
        try { require(engine.init(directory.absolutePath, language)) }
        catch (error: RuntimeException) { engine.recycle(); throw error }
    }

    fun recognize(bitmap: Bitmap, frame: Boolean = false): String {
        require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 16_777_216L)
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
        val image = if (frame || !readable.hasAlpha()) readable else Bitmap.createBitmap(readable.width, readable.height, Bitmap.Config.ARGB_8888).also {
            // Transparent PGS captions frequently carry pale lettering. Keep
            // their contrast when flattening onto the recognizer's white page.
            var luminance = 0L
            var samples = 0
            for (y in 0 until readable.height step maxOf(1, readable.height / 32)) {
                for (x in 0 until readable.width step maxOf(1, readable.width / 32)) {
                    val color = readable.getPixel(x, y)
                    if (Color.alpha(color) < 128) continue
                    luminance += (Color.red(color) * 3 + Color.green(color) * 6 + Color.blue(color)) / 10
                    samples++
                }
            }
            val paint = if (samples > 0 && luminance > 145L * samples) Paint().apply {
                colorFilter = ColorMatrixColorFilter(floatArrayOf(-1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            } else null
            Canvas(it).apply {
                drawColor(Color.WHITE)
                drawBitmap(readable, 0f, 0f, paint)
            }
        }
        try {
            engine.setPageSegMode(if (frame) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            engine.setImage(image)
            return engine.getUTF8Text().orEmpty().trim().also { require(it.length <= 24_000) }
        } finally {
            try { engine.clear() } finally {
                if (image !== readable) image.recycle()
                if (readable !== bitmap) readable.recycle()
            }
        }
    }

    override fun close() = engine.recycle()
}

internal data class ImageSubtitleProposal(val startUs: Long, val endUs: Long, val suggestion: String)

/** Recover replacement boundaries in stream order without retaining the images. */
@UnstableApi
internal class ImageSubtitleDraft(private val recognize: (Bitmap) -> String) {
    private data class Pending(val text: String, val explicitEndUs: Long?)
    private val rows = ArrayList<ImageSubtitleProposal>()
    private val pending = ArrayList<Pending>()
    private var startUs = -1L
    private var characters = 0L

    fun accept(group: CuesWithTiming) {
        require(group.startTimeUs >= startUs && group.startTimeUs >= 0L)
        if (group.startTimeUs > startUs) flush(group.startTimeUs)
        startUs = group.startTimeUs
        val endUs = group.durationUs.takeIf { it > 0 }?.let { Math.addExact(startUs, it) }
        for (cue in group.cues) {
            val image = cue.bitmap ?: throw IOException("Sous-titre image illisible.")
            require(rows.size + pending.size < MAX_OCCURRENCES)
            val suggestion = recognize(image).trim()
            characters += suggestion.length
            require(suggestion.length <= 24_000 && characters <= 4_000_000)
            pending += Pending(suggestion, endUs)
        }
    }

    fun finish(mediaEndUs: Long): List<ImageSubtitleProposal> {
        flush(mediaEndUs.takeIf { it > startUs })
        require(rows.isNotEmpty())
        return rows.toList()
    }

    private fun flush(boundaryUs: Long?) {
        for (item in pending) {
            val end = if (boundaryUs == null) item.explicitEndUs else minOf(item.explicitEndUs ?: boundaryUs, boundaryUs)
            require(end != null && end > startUs)
            rows += ImageSubtitleProposal(startUs, end, item.text)
        }
        pending.clear()
    }

    private companion object { const val MAX_OCCURRENCES = 40_000 }
}

internal object ReviewedImageSubtitles {
    fun write(directory: File, proposals: List<ImageSubtitleProposal>, validated: List<String?>): File {
        require(proposals.size == validated.size && proposals.isNotEmpty() && validated.all { !it.isNullOrBlank() })
        directory.mkdirs()
        val result = File.createTempFile("ocr_revu_", ".srt", directory)
        try {
            result.bufferedWriter(Charsets.UTF_8).use { writer ->
                for ((ordinal, index) in proposals.indices.sortedBy { proposals[it].startUs }.withIndex()) {
                    val entry = proposals[index]
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val startMs = entry.startUs / 1000
                    val endMs = Math.addExact(entry.endUs, 999) / 1000
                    require(entry.startUs >= 0 && entry.endUs > entry.startUs && endMs > startMs)
                    val text = requireNotNull(validated[index]).lines().map(String::trim).filter(String::isNotEmpty)
                        .joinToString("\n").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    require(text.isNotBlank() && text.length <= 24_000)
                    writer.appendLine((ordinal + 1).toString())
                    writer.appendLine("${timestamp(startMs)} --> ${timestamp(endMs)}")
                    writer.appendLine(text)
                    writer.appendLine()
                }
            }
            require(result.length() in 1..SubtitleImport.MAX_BYTES.toLong())
            return result
        } catch (error: Exception) { result.delete(); throw error }
    }

    private fun timestamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

@UnstableApi
internal fun recognizeLocalVideoFrame(context: Context, uri: Uri, positionUs: Long, ocr: LocalSubtitleOcr): String {
    require(uri.scheme in setOf("content", "file", "android.resource") && positionUs >= 0 && positionUs != C.TIME_UNSET)
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        return recognizeFrame(retriever, positionUs, ocr)
    } finally { retriever.release() }
}

/** A timed draft based on samples. Equal text separated by a blank frame is
 * two events; only consecutive equal recognitions extend an existing event. */
internal class SampledImageSubtitleDraft {
    private val rows = ArrayList<ImageSubtitleProposal>()
    private var startUs = 0L
    private var lastSampleUs = -1L
    private var pending = ""
    private var key = ""
    private var characters = 0L
    private val whitespace = Regex("\\s+")

    fun sample(positionUs: Long, text: String) {
        require(positionUs >= 0 && positionUs > lastSampleUs && text.length <= 24_000)
        lastSampleUs = positionUs
        val normalized = whitespace.replace(text.trim(), " ")
        if (normalized == key) return
        flush(positionUs)
        startUs = positionUs
        pending = text.trim()
        key = normalized
    }

    fun finish(endUs: Long): List<ImageSubtitleProposal> {
        require(endUs > lastSampleUs)
        flush(endUs)
        return rows.toList()
    }

    private fun flush(endUs: Long) {
        if (pending.isEmpty()) return
        characters += pending.length
        require(characters <= 4_000_000)
        rows += ImageSubtitleProposal(startUs, endUs, pending)
        pending = ""
    }
}

internal const val MAX_OCR_SAMPLES = 20_000L

internal fun recognizeLocalVideoInterval(context: Context, uri: Uri, startUs: Long, endUs: Long, stepUs: Long,
    ocr: LocalSubtitleOcr, progress: (Int) -> Unit): List<ImageSubtitleProposal> {
    require(uri.scheme in setOf("content", "file", "android.resource") && startUs >= 0 && endUs > startUs && stepUs in 100_000..10_000_000)
    val count = (endUs - startUs - 1) / stepUs + 1
    require(count <= MAX_OCR_SAMPLES)
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val draft = SampledImageSubtitleDraft()
        var lastPercent = -1
        for (index in 0L until count) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val time = startUs + index * stepUs
            draft.sample(time, recognizeFrame(retriever, time, ocr))
            val percent = ((index + 1) * 100 / count).toInt()
            if (percent != lastPercent) { progress(percent); lastPercent = percent }
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return draft.finish(endUs)
    } finally { retriever.release() }
}

private fun recognizeFrame(retriever: MediaMetadataRetriever, positionUs: Long, ocr: LocalSubtitleOcr): String {
    val bitmap = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
        ?: throw IOException("Image vidéo indisponible.")
    return try { ocr.recognize(bitmap, frame = true) } finally { bitmap.recycle() }
}
