package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.SubtitleImport
import fr.soustitresvoix.core.SubtitleText
import fr.soustitresvoix.core.TranslatedSubtitleExport
import fr.soustitresvoix.core.TranslatedTrack
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An explicitly selected subtitle file can be translated without a video or a
 * PlaybackSession. Leaving its panel does not touch playback and does not stop
 * the task; its Activity owner closes it when destroyed. Only a fully validated
 * track can be exported. Model execution and all disk work stay off the UI.
 */
@UnstableApi
class LocalSrtTranslationJob(
    context: Context,
    private val onChanged: (State) -> Unit,
    private val engineFactory: (TranslationProviderConfig) -> TranslationEngine = {
        TranslationEngines.create(it, context = context.applicationContext)
    },
    private val indexerFactory: () -> SubtitleIndexer = { Media3SubtitleSource(context.applicationContext).createIndexer() }
) : AutoCloseable {
    data class State(
        val status: String = "Restauration du dernier fichier de sous-titres…",
        val busy: Boolean = true,
        val sourceName: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val complete: Boolean = false,
        val encodingRequired: Boolean = false,
        val performance: String? = null
    ) {
        val hasSource: Boolean get() = sourceName != null
    }

    private class Source(val file: File, val name: String, val mime: String, val charset: String) {
        override fun toString() = "Source(private subtitle file)"
    }
    private class Task {
        val cancelled = AtomicBoolean(false)
        @Volatile var indexer: SubtitleIndexer? = null
        @Volatile var engine: TranslationEngine? = null
        @Volatile var performance: String? = null
    }
    private class ReplacedModelSelection : IOException()
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sourceDirectory = File(application.filesDir, "local-srt-source-v1")
    private val runner = TranslationRunner(File(application.cacheDir, "local-srt-translations-v1"),
        File(application.filesDir, "local-srt-progress-v1"), File(application.filesDir, "local-srt-results-v1"))
    @Volatile private var closed = false
    @Volatile private var sequence = 0L
    private var source: Source? = null
    private var active: Task? = null
    private var completed: TranslatedTrack? = null
    private var completedExports: Map<SubtitleExportFormat, String> = emptyMap()
    @Volatile var state: State = State()
        private set

    init {
        val ticket = ++sequence
        worker.execute {
            synchronized(WORK_LOCK) {
                if (closed || sequence != ticket) return@execute
                val recordExists = File(sourceDirectory, RECORD).exists() || File(sourceDirectory, "$RECORD.bak").exists()
                val restored = readSource()
                if (restored != null) removeOrphanSources(restored.file)
                main.post {
                    if (!closed && sequence == ticket) {
                        source = restored
                        update(State(status = when {
                            restored != null -> "Fichier conservé prêt. Choisis le modèle et lance ou reprends la traduction."
                            recordExists -> "Le fichier de sous-titres conservé ne peut pas être restauré. Sélectionne-le à nouveau."
                            else -> "Choisis un fichier de sous-titres, puis un modèle local."
                        }, busy = false, sourceName = restored?.name))
                    }
                }
            }
        }
    }

    /** The original is read only; a successful import commits a private UTF-8 copy. */
    fun importSource(uri: Uri, displayName: String, charset: String? = null) = importContent(displayName, charset) { current ->
        require(uri.scheme in setOf("content", "file"))
        application.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive(current)
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= SubtitleImport.MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException()
    }

    /** An accessible, wholly synthetic source, independent of any video or OCR. */
    fun importExample() = importContent("exemple-traduction-en.srt", "UTF-8") { EXAMPLE_SRT.toByteArray(Charsets.UTF_8) }

    private fun importContent(displayName: String, charset: String?, readBytes: (Task) -> ByteArray) {
        if (closed || state.busy) return
        val previous = state
        val current = Task(); active = current
        val ticket = ++sequence
        update(previous.copy(status = "Vérification et copie privée du fichier de sous-titres…",
            busy = true, complete = false, encodingRequired = false, performance = null))
        worker.execute {
            synchronized(WORK_LOCK) {
                try {
                    ensureActive(current)
                    val bytes = readBytes(current)
                    ensureActive(current)
                    val name = displayName.take(512).replace('\u0000', ' ').ifBlank { "Sous-titres.srt" }
                    val decoded = SubtitleImport.decode(bytes, name, charset)
                    val normalized = decoded.text.toByteArray(Charsets.UTF_8)
                    require(normalized.size in 1..SubtitleImport.MAX_BYTES)
                    ensureActive(current)
                    val imported = commitSource(name, decoded.kind, decoded.charset, normalized)
                    // The commit is atomic. A cancellation arriving after this
                    // point cannot truthfully be reported as an undone import.
                    main.post {
                        if (isCurrent(current, ticket)) {
                            active = null; source = imported; completed = null; completedExports = emptyMap()
                            update(State("Fichier importé (${decoded.charset}). Choisis le modèle puis lance la traduction.",
                                busy = false, sourceName = imported.name))
                        }
                    }
                } catch (_: SubtitleImport.EncodingRequired) {
                    finishFailure(current, ticket, previous.copy(status =
                        "L’encodage du fichier doit être choisi avant l’import. Le fichier précédent est conservé.",
                        busy = false, encodingRequired = true))
                } catch (_: TranslationCancelledException) {
                    finishFailure(current, ticket, previous.copy(status = "Import annulé. Le fichier précédent est conservé.", busy = false))
                } catch (_: Exception) {
                    finishFailure(current, ticket, previous.copy(status =
                        "Import impossible : vérifie le fichier, son encodage et la limite de 8 Mio. Le fichier précédent est conservé.", busy = false))
                }
            }
        }
    }

    /** Each explicit start resumes only this source/language/model/options identity. */
    fun start(sourceLanguage: String, config: TranslationProviderConfig) {
        if (closed || state.busy) return
        val selected = source ?: run {
            update(state.copy(status = "Choisis d’abord un fichier de sous-titres.")); return
        }
        if (config.provider != TranslationProvider.LOCAL_GGUF) {
            update(state.copy(status = "Choisis le moteur GGUF local pour cette préparation hors ligne.")); return
        }
        val current = Task(); active = current
        val ticket = ++sequence
        completed = null; completedExports = emptyMap()
        update(State("Indexation complète du fichier de sous-titres…", busy = true, sourceName = selected.name))
        worker.execute {
            synchronized(WORK_LOCK) {
                try {
                    ensureActive(current)
                    // A panel can retain the descriptor of a model that was
                    // replaced through another panel. Do not expose its old
                    // cached translation as if it belonged to the new model.
                    val selectedModel = config.localModel
                    val preparedModel = LocalGgufModelStore(application).current()
                    if (selectedModel != null && preparedModel != null && selectedModel.file == preparedModel.file &&
                        selectedModel.sha256 != preparedModel.sha256) throw ReplacedModelSelection()
                    TranslationEngines.validateForRequest(config, sourceLanguage, "fr")
                    val identity = TranslationEngines.translationIdentity(config)
                    // Media3 remains the timing/parser authority, but it can
                    // skip a malformed SubRip block without marking its index
                    // truncated. Refuse a partial source before any inference.
                    val expectedOccurrences = if (selected.mime == SubtitleImport.Kind.SRT.mime)
                        SrtSourceIntegrity.expectedCount(selected.file) else null
                    val scanner = indexerFactory(); current.indexer = scanner
                    val index = scanner.scan(SavedMedia(Uri.fromFile(selected.file).toString(), "Sous-titres",
                        subtitlePath = selected.file.absolutePath, subtitleMime = selected.mime)) { percent ->
                        publish(current, ticket, state.copy(status = if (percent >= 0)
                            "Indexation du fichier : $percent %" else "Indexation du fichier…"))
                    }
                    ensureActive(current)
                    require(!index.truncated)
                    val cues = index.selected("external-subtitle").mapNotNull { item ->
                        val text = SubtitleText.clean(item.raw)
                        if (text.isBlank()) null else TranslationCue(item.identity, item.startUs, item.endUs, text)
                    }
                    if (expectedOccurrences != null) SrtSourceIntegrity.checkCount(expectedOccurrences, cues.size)
                    val plan = TranslationPlan.create(cues, sourceLanguage, "fr", identity)
                    ensureActive(current)
                    val engine = engineFactory(config); current.engine = engine
                    (engine as? LocalTranslationPerformanceSource)?.setPerformanceListener { performance ->
                        val rendered = performance.render()
                        current.performance = rendered
                        main.post {
                            if (isCurrent(current, ticket) && !current.cancelled.get())
                                update(state.copy(performance = rendered))
                        }
                    }
                    ensureActive(current)
                    val result = runner.run(plan, engine, { current.cancelled.get() || closed }, { done, total ->
                        publish(current, ticket, State("Traduction locale : $done / $total occurrences validées.",
                            busy = true, sourceName = selected.name, done = done, total = total, performance = current.performance))
                    }, TranslationRunPolicy(requestMayConsumeRemoteQuota = false))
                    ensureActive(current)
                    val exports = SubtitleExportFormat.values().associateWith { TranslatedSubtitleExport.encode(result.cues, it) }
                    ensureActive(current)
                    val warning = runner.cacheWarning
                    main.post {
                        if (isCurrent(current, ticket)) {
                            active = null
                            if (current.cancelled.get()) {
                                update(state.copy(status = "Traduction arrêtée. Les occurrences validées sont conservées pour la reprise.", busy = false))
                            } else {
                                completed = result
                                completedExports = exports
                                update(State("Traduction complète : ${result.cues.size} occurrences. Export SRT disponible." +
                                    (warning?.let { "\n$it" } ?: ""), busy = false, sourceName = selected.name,
                                    done = result.cues.size, total = result.cues.size, complete = true, performance = current.performance))
                            }
                        }
                    }
                } catch (_: ReplacedModelSelection) {
                    finishFailure(current, ticket, state.copy(status =
                        "Le modèle local a été remplacé depuis sa sélection. Prépare ou sélectionne le modèle actuel avant de relancer ; les traductions précédentes restent conservées avec leur propre identité.",
                        busy = false, complete = false))
                } catch (_: SrtSourceIntegrity.InvalidSource) {
                    finishFailure(current, ticket, state.copy(status =
                        SrtSourceIntegrity.FAILURE_MESSAGE,
                        busy = false, complete = false))
                } catch (_: TranslationCancelledException) {
                    finishFailure(current, ticket, state.copy(status =
                        "Traduction arrêtée. Les occurrences validées sont conservées ; relance avec le même fichier, modèle et réglages pour reprendre.",
                        busy = false, complete = false))
                } catch (error: TranslationFailure) {
                    finishFailure(current, ticket, state.copy(status = error.reason.message +
                        " Les occurrences déjà validées restent conservées.", busy = false, complete = false))
                } catch (_: Exception) {
                    finishFailure(current, ticket, state.copy(status =
                        if (current.cancelled.get() || closed || Thread.currentThread().isInterrupted)
                            "Préparation arrêtée. Les occurrences déjà validées restent conservées pour la reprise."
                        else "Préparation locale interrompue : vérifie le fichier, la langue et le modèle. Les occurrences validées restent conservées.",
                        busy = false, complete = false))
                } finally {
                    current.indexer = null
                    (current.engine as? LocalTranslationPerformanceSource)?.setPerformanceListener(null)
                    runCatching { current.engine?.close() }; current.engine = null
                }
            }
        }
    }

    /** Cancels computation, never playback. Validated disk checkpoints remain. */
    fun cancel() {
        val current = active ?: return
        current.cancelled.set(true)
        current.indexer?.cancel()
        runCatching { current.engine?.close() }
        update(state.copy(status = "Arrêt demandé ; attente de la fin du calcul en cours…"))
    }

    /** Text comes only from the completed validated track, never a partial prefix. */
    fun completedExport(format: SubtitleExportFormat = SubtitleExportFormat.SRT): String? =
        if (completed != null && state.complete && !state.busy) completedExports[format] else null

    fun exportFileName(format: SubtitleExportFormat = SubtitleExportFormat.SRT): String {
        val name = source?.name?.substringBeforeLast('.')?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")
            ?.take(120)?.ifBlank { null } ?: "sous-titres"
        return "$name.fr.${format.extension}"
    }

    override fun close() {
        if (closed) return
        closed = true; sequence++
        active?.let { task -> task.cancelled.set(true); task.indexer?.cancel(); runCatching { task.engine?.close() } }
        active = null
        worker.shutdownNow()
    }

    private fun ensureActive(task: Task) {
        if (closed || task.cancelled.get() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }
    private fun isCurrent(task: Task, ticket: Long) = !closed && sequence == ticket && active === task
    private fun update(value: State) {
        state = value
        if (Looper.myLooper() == Looper.getMainLooper()) onChanged(value)
        else main.post { if (!closed && state === value) onChanged(value) }
    }
    private fun publish(task: Task, ticket: Long, value: State) = main.post {
        if (isCurrent(task, ticket) && !task.cancelled.get()) update(value)
    }
    private fun finishFailure(task: Task, ticket: Long, value: State) = main.post {
        if (isCurrent(task, ticket)) { active = null; update(value) }
    }

    private fun commitSource(name: String, kind: SubtitleImport.Kind, charset: String, bytes: ByteArray): Source {
        if (!sourceDirectory.isDirectory && !sourceDirectory.mkdirs()) throw IOException()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = File(sourceDirectory, "source-$digest.${kind.extension}")
        writeAtomic(file, bytes)
        val record = JSONObject().put("version", 1).put("file", file.name).put("name", name)
            .put("mime", kind.mime).put("charset", charset).toString().toByteArray(Charsets.UTF_8)
        writeAtomic(File(sourceDirectory, RECORD), record)
        removeOrphanSources(file)
        return Source(file, name, kind.mime, charset)
    }
    private fun readSource(): Source? = try {
        val bytes = AtomicFile(File(sourceDirectory, RECORD)).openRead().use { input ->
            require(File(sourceDirectory, RECORD).length() in 1..8192)
            input.readBytes()
        }
        val record = JSONObject(bytes.toString(Charsets.UTF_8))
        require(record.getInt("version") == 1)
        val name = record.getString("file"); require(SOURCE_FILE.matches(name))
        val file = File(sourceDirectory, name)
        require(file.isFile && file.length() in 1..SubtitleImport.MAX_BYTES.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(name.substringAfter("source-").substringBefore('.') == digest)
        val mime = record.getString("mime"); require(SubtitleImport.Kind.values().any { it.mime == mime })
        val display = record.getString("name"); require(display.isNotBlank() && display.length <= 512 && '\u0000' !in display)
        Source(file, display, mime, record.getString("charset"))
    } catch (_: Exception) { null }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun removeOrphanSources(retained: File) {
        sourceDirectory.listFiles()?.filter { it != retained && SOURCE_FILE.matches(it.name.removeSuffix(".bak").removeSuffix(".new")) }
            ?.forEach { it.delete() }
    }
    companion object {
        // An old Activity worker may still be stopping while the next owner is
        // created. Serialize private source/checkpoint commits across owners.
        private val WORK_LOCK = Any()
        private const val RECORD = "selection.json"
        private val SOURCE_FILE = Regex("source-[0-9a-f]{64}\\.(srt|vtt|ass)")
        private val EXAMPLE_SRT = """
            1
            00:00:00,000 --> 00:00:20,000
            STATION ENTRANCE

            2
            00:00:01,000 --> 00:00:03,000
            Good evening, Élodie.

            3
            00:00:02,500 --> 00:00:04,500
            The train leaves at 7:00 p.m.

            4
            00:00:05,000 --> 00:00:07,000
            Wait for me!

            5
            00:00:08,000 --> 00:00:10,000
            Wait for me!

            6
            00:00:08,000 --> 00:00:10,000
            Wait for me!

            7
            00:00:11,000 --> 00:00:13,000
            Platform 4 — tickets cost €12.50.

            8
            00:00:14,000 --> 00:00:16,000
            [Door closes]

        """.trimIndent()
    }
}
