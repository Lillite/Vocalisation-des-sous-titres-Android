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
import java.util.concurrent.atomic.AtomicReference

/**
 * An explicitly selected subtitle file can be translated without a video or a
 * PlaybackSession. Leaving its panel does not touch playback and does not stop
 * the task; a retained owner survives UI recreation and an explicit owner close stops it. Only a fully validated
 * track can be exported. Model execution and all disk work stay off the UI.
 */
@UnstableApi
class LocalSrtTranslationJob(
    context: Context,
    private val onChanged: (State) -> Unit,
    private val engineFactory: (TranslationProviderConfig) -> TranslationEngine = {
        TranslationEngines.create(it, context = context.applicationContext)
    },
    private val indexerFactory: () -> SubtitleIndexer = { Media3SubtitleSource(context.applicationContext).createIndexer() },
    private val governorFactory: () -> LocalPreparationGovernor = { LocalPreparationGovernor.android(context.applicationContext) }
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
        val acceptMeasurements = AtomicBoolean(true)
        val startedAtMs = System.currentTimeMillis()
        val performanceSession = LocalTranslationSessionMetrics()
        val primaryCause = AtomicReference("")
        val requestedStop = AtomicReference("")
        var engineGeneration = 0L
        fun requestStop(cause: String) {
            requestedStop.compareAndSet("", cause)
            primaryCause.compareAndSet("", cause)
            cancelled.set(true)
        }
        @Volatile var indexer: SubtitleIndexer? = null
        @Volatile var engine: TranslationEngine? = null
        @Volatile var performance: String? = null
        var reportHeader: String = ""
        var pendingReportHeader: Boolean = true
        var journalEntry: LocalSrtAttemptJournal.Entry? = null
    }
    private class ReplacedModelSelection : IOException()
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sourceDirectory = File(application.filesDir, "local-srt-source-v1")
    private val attemptJournal = LocalSrtAttemptJournal(File(application.filesDir, "local-srt-attempt-v1"))
    private val runner = TranslationRunner(File(application.cacheDir, "local-srt-translations-v1"),
        File(application.filesDir, "local-srt-progress-v1"), File(application.filesDir, "local-srt-results-v1"))
    @Volatile private var closed = false
    @Volatile private var sequence = 0L
    private var source: Source? = null
    private var active: Task? = null
    private var completed: TranslatedTrack? = null
    private var completedExports: Map<SubtitleExportFormat, String> = emptyMap()
    private var completedReviewReport: String? = null
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
                val attempt = restored?.let { LocalSrtAttemptJournal.sourceKey(it.file) }?.let(attemptJournal::read)
                val recoveredReport = attempt?.render(application)
                main.post {
                    if (!closed && sequence == ticket) {
                        source = restored
                        update(State(status = when {
                            restored != null && attempt?.state == "RUNNING" ->
                                "La dernière préparation a été interrompue (${attempt.done} / ${attempt.total} occurrences validées au dernier enregistrement). Consulte le diagnostic, puis relance explicitement pour reprendre."
                            restored != null && attempt?.state == "WAITING" ->
                                "La préparation a été suspendue pour préserver les ressources. Son diagnostic et ses occurrences validées sont conservés ; relance explicitement après amélioration des conditions."
                            restored != null && attempt?.state == "CANCELLED" && attempt.cause != "USER_CANCEL" ->
                                "La dernière préparation a été interrompue. Consulte le diagnostic et les motifs Android éventuels, puis relance explicitement pour reprendre."
                            restored != null && attempt?.state == "FAILED" ->
                                "La dernière préparation s’est arrêtée sur une erreur. Son diagnostic est conservé ; relance explicitement avec les mêmes réglages pour reprendre."
                            restored != null -> "Fichier conservé prêt. Choisis le modèle et lance ou reprends la traduction."
                            recordExists -> "Le fichier de sous-titres conservé ne peut pas être restauré. Sélectionne-le à nouveau."
                            else -> "Choisis un fichier de sous-titres, puis un modèle local."
                        }, busy = false, sourceName = restored?.name,
                            done = attempt?.done ?: 0, total = attempt?.total ?: 0, performance = recoveredReport))
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

    /** Independent held-out sentences for reviewing fidelity, not automatic scoring. */
    fun importQualityExample() = importContent("qualite-traduction-en.srt", "UTF-8") {
        LocalGgufQualityCorpus.SOURCE_SRT.toByteArray(Charsets.UTF_8)
    }

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
                    attemptJournal.clear()
                    // The commit is atomic. A cancellation arriving after this
                    // point cannot truthfully be reported as an undone import.
                    main.post {
                        if (isCurrent(current, ticket)) {
                            active = null; source = imported; completed = null; completedExports = emptyMap(); completedReviewReport = null
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
        current.reportHeader = LocalGgufAttemptReport.initial(config, current.startedAtMs)
        current.performance = current.reportHeader
        val ticket = ++sequence
        completed = null; completedExports = emptyMap(); completedReviewReport = null
        update(State("Indexation complète du fichier de sous-titres…", busy = true, sourceName = selected.name,
            performance = current.performance))
        worker.execute {
            synchronized(WORK_LOCK) {
                var terminal: State? = null
                var translated: TranslatedTrack? = null
                var reviewPlan: TranslationPlan? = null
                var exports: Map<SubtitleExportFormat, String> = emptyMap()
                var cleanupWarning: String? = null
                var resourceWaitExpired = false
                try {
                    ensureActive(current)
                    attemptJournal.beginAttempt()
                    LocalSrtAttemptJournal.sourceKey(selected.file)?.let { key ->
                        current.journalEntry = LocalSrtAttemptJournal.begin(key, config, current.startedAtMs,
                            sourceLanguage = sourceLanguage)
                        saveAttempt(current, force = true)
                    }
                    // A panel can retain the descriptor of a model that was
                    // replaced through another panel. Do not expose its old
                    // cached translation as if it belonged to the new model.
                    val selectedModel = config.localModel
                    val preparedModel = LocalGgufModelStore(application).current()
                    if (selectedModel != null && preparedModel != null && selectedModel.file == preparedModel.file &&
                        selectedModel.sha256 != preparedModel.sha256) throw ReplacedModelSelection()
                    TranslationEngines.validateForRequest(config, sourceLanguage, "fr")
                    val identity = TranslationEngines.translationIdentity(config)
                    current.journalEntry = current.journalEntry?.copy(configurationId = identity)
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
                    reviewPlan = plan
                    current.journalEntry = current.journalEntry?.copy(total = plan.cues.size,
                        updatedAtMs = System.currentTimeMillis())
                    saveAttempt(current, force = true)
                    ensureActive(current)
                    fun createEngine(): TranslationEngine {
                    val engine = engineFactory(config); current.engine = engine
                    (engine as? LocalGgufTranslationEngine)?.usePerformanceSession(current.performanceSession)
                    val generation = ++current.engineGeneration
                    (engine as? LocalTranslationPerformanceSource)?.setPerformanceListener { performance ->
                        if (!current.acceptMeasurements.get() || current.cancelled.get() || closed ||
                            current.engine !== engine || current.engineGeneration != generation)
                            return@setPerformanceListener
                        val previousStage = current.journalEntry?.progress?.stage
                        current.journalEntry = current.journalEntry?.measured(performance, System.currentTimeMillis())
                        if (performance.phase != "generation" && performance.phase != "occurrence" && performance.phase != "cache") {
                            val criticalStage = performance.progress?.stage in JOURNAL_CRITICAL_STAGES
                            saveAttempt(current, force = criticalStage && performance.progress?.stage != previousStage ||
                                performance.phase == "load")
                        }
                        // The initial pending sentence is obsolete as soon as a
                        // stage or measurement exists, even if loading still fails.
                        if (current.pendingReportHeader) {
                            current.reportHeader = LocalGgufAttemptReport.initial(config, current.startedAtMs, pendingStatus = false)
                            current.pendingReportHeader = false
                        }
                        val rendered = current.reportHeader + "\n\n" + performance.render()
                        current.performance = rendered
                        main.post {
                            if (isCurrent(current, ticket) && !current.cancelled.get())
                                update(state.copy(performance = rendered))
                        }
                    }
                    return engine
                    }
                    val governor = governorFactory()
                    val restartedOccurrences = HashSet<Int>()
                    var restarts = 0
                    var profileRestarts = 0
                    var resourceRestarts = 0
                    fun releaseOwned(): Boolean {
                        val owned = current.engine ?: return true
                        (owned as? LocalTranslationPerformanceSource)?.setPerformanceListener(null)
                        current.engine = null
                        val warning = closeTranslationEngineOnWorker(owned)
                        if (warning != null) cleanupWarning = warning
                        return warning == null
                    }
                    fun noteFailure(error: TranslationFailure) {
                        current.primaryCause.compareAndSet("", if (error.reason == TranslationFailure.Reason.LOCAL_PROCESS_DIED) "PROCESS_LOSS" else "ERROR")
                        current.journalEntry = current.journalEntry?.let { entry ->
                            if (entry.failureCode.isNotEmpty() || current.primaryCause.get() in setOf("USER_CANCEL", "HOST_DESTROYED")) entry else entry.copy(failureCode = error.reason.name,
                                failedProcessId = entry.processId, failedRequestStartedAtMs = entry.progress?.startedAtMs ?: entry.startedAtMs,
                                failedAtMs = System.currentTimeMillis())
                        }
                        saveAttempt(current, force = true)
                    }
                    fun runWithRecovery(): TranslatedTrack {
                    while (true) {
                    ensureActive(current)
                    val engine = createEngine()
                    try {
                    return runner.runGuarded(plan, engine, { current.cancelled.get() || closed }, { done, total ->
                        current.journalEntry = current.journalEntry?.copy(done = done, total = total,
                            updatedAtMs = System.currentTimeMillis())
                        // The runner has already committed this prefix. Record it
                        // before the next inference, off the UI thread.
                        saveAttempt(current, force = true)
                        publish(current, ticket, State("Traduction locale : $done / $total occurrences validées.",
                            busy = true, sourceName = selected.name, done = done, total = total, performance = current.performance))
                    }, TranslationRunPolicy(requestMayConsumeRemoteQuota = false), beforeRequest = {
                        governor.beforeRequest { current.cancelled.get() || closed }
                    })
                    } catch (pause: LocalPreparationGovernor.Pause) {
                        current.primaryCause.compareAndSet("", if (pause.kind == LocalPreparationGovernor.Kind.MEMORY) "MEMORY_PRESSURE" else "THERMAL_PRESSURE")
                        if (!releaseOwned() || resourceRestarts++ >= MAX_RESOURCE_RESTARTS)
                            throw LocalPreparationGovernor.Expired(pause.kind)
                        current.journalEntry = current.journalEntry?.copy(phase = pause.kind.phase,
                            updatedAtMs = System.currentTimeMillis())
                        saveAttempt(current, force = true)
                        governor.awaitReady(pause.kind, { current.cancelled.get() || closed }) { kind ->
                            publish(current, ticket, state.copy(status = kind.message, busy = true,
                                done = current.journalEntry?.done ?: 0, total = current.journalEntry?.total ?: 0))
                        }
                        ensureActive(current)
                    } catch (error: TranslationFailure) {
                        if (error.localDiagnostic?.reason == LocalGgufDiagnostic.Reason.PROFILE_REQUIRES_RESTART &&
                            engine is LocalGgufTranslationEngine && profileRestarts == 0 && !current.cancelled.get() && !closed) {
                            if (!releaseOwned()) throw error
                            ensureActive(current)
                            try { LocalGgufRuntime.restartIdleProcess(application) { current.cancelled.get() || closed } }
                            catch (failure: LocalGgufRuntime.Failure) {
                                ensureActive(current)
                                throw TranslationFailure(TranslationFailure.Reason.LOCAL_BACKEND, failure.diagnostic, failure.attemptReport)
                            }
                            ensureActive(current)
                            profileRestarts++
                            current.journalEntry = current.journalEntry?.copy(phase = "recovering", profileRestarts = profileRestarts,
                                updatedAtMs = System.currentTimeMillis())
                            saveAttempt(current, force = true)
                            publish(current, ticket, state.copy(status = "Processus de calcul réinitialisé après libération confirmée pour appliquer le profil explicite. Reprise des résultats validés.", busy = true,
                                done = current.journalEntry?.done ?: 0, total = current.journalEntry?.total ?: 0))
                            continue
                        }
                        noteFailure(error)
                        val occurrence = current.journalEntry?.done ?: 0
                        // Only the native local engine has the release protocol needed
                        // to safely construct a replacement owner. Never retry a factory
                        // failure or a remote/unknown engine as local process recovery.
                        val mayRetry = error.reason == TranslationFailure.Reason.LOCAL_PROCESS_DIED &&
                            engine is LocalGgufTranslationEngine && !current.cancelled.get() && !closed &&
                            restarts < MAX_PROCESS_RESTARTS && restartedOccurrences.add(occurrence)
                        if (!mayRetry || !releaseOwned()) throw error
                        ensureActive(current)
                        restarts++
                        current.journalEntry = current.journalEntry?.copy(phase = "recovering", recoveries = restarts,
                            updatedAtMs = System.currentTimeMillis())
                        saveAttempt(current, force = true)
                        publish(current, ticket, state.copy(status =
                            "Le processus de calcul a été perdu. Libération confirmée ; reprise bornée des occurrences restantes avec la même configuration ($restarts / $MAX_PROCESS_RESTARTS).", busy = true,
                            done = current.journalEntry?.done ?: 0, total = current.journalEntry?.total ?: 0))
                    }
                    }
                    }
                    val result = runWithRecovery()
                    ensureActive(current)
                    exports = SubtitleExportFormat.values().associateWith { TranslatedSubtitleExport.encode(result.cues, it) }
                    ensureActive(current)
                    val warning = runner.cacheWarning
                    translated = result
                    terminal = State("Traduction complète : ${result.cues.size} occurrences. Export SRT disponible." +
                        (warning?.let { "\n$it" } ?: ""), busy = false, sourceName = selected.name,
                        done = result.cues.size, total = result.cues.size, complete = true, performance = current.performance)
                } catch (pause: LocalPreparationGovernor.Expired) {
                    resourceWaitExpired = true
                    terminal = state.copy(status = pause.kind.message +
                        " L’attente automatique est terminée ; relance explicitement après amélioration des conditions.",
                        busy = false, complete = false)
                } catch (_: ReplacedModelSelection) {
                    terminal = state.copy(status =
                        "Le modèle local a été remplacé depuis sa sélection. Prépare ou sélectionne le modèle actuel avant de relancer ; les traductions précédentes restent conservées avec leur propre identité.",
                        busy = false, complete = false)
                } catch (_: SrtSourceIntegrity.InvalidSource) {
                    terminal = state.copy(status =
                        SrtSourceIntegrity.FAILURE_MESSAGE,
                        busy = false, complete = false)
                } catch (_: TranslationCancelledException) {
                    terminal = state.copy(status =
                        "Traduction arrêtée. Les occurrences validées sont conservées ; relance avec le même fichier, modèle et réglages pour reprendre.",
                        busy = false, complete = false)
                } catch (error: TranslationFailure) {
                    current.primaryCause.compareAndSet("", if (error.reason == TranslationFailure.Reason.LOCAL_PROCESS_DIED) "PROCESS_LOSS" else "ERROR")
                    current.journalEntry = current.journalEntry?.let { entry ->
                        if (entry.failureCode.isNotEmpty() || current.primaryCause.get() in setOf("USER_CANCEL", "HOST_DESTROYED")) entry else entry.copy(failureCode = error.reason.name,
                            failedProcessId = entry.processId, failedRequestStartedAtMs = entry.progress?.startedAtMs ?: entry.startedAtMs,
                            failedAtMs = System.currentTimeMillis())
                    }
                    val diagnostic = error.localDiagnostic?.render()
                    val details = listOfNotNull(diagnostic, error.localAttemptReport)
                    current.performance = (listOfNotNull(current.performance) +
                        listOf("Fin de la préparation : " + error.reason.message) + details).joinToString("\n\n")
                    terminal = state.copy(status = error.reason.message +
                        " Les occurrences déjà validées restent conservées.", busy = false, complete = false,
                        performance = current.performance)
                } catch (_: Exception) {
                    terminal = state.copy(status =
                        if (current.cancelled.get() || closed || Thread.currentThread().isInterrupted)
                            "Préparation arrêtée. Les occurrences déjà validées restent conservées pour la reprise."
                        else "Préparation locale interrompue : vérifie le fichier, la langue et le modèle. Les occurrences validées restent conservées.",
                        busy = false, complete = false)
                } finally {
                    current.acceptMeasurements.set(false)
                    current.indexer = null
                    (current.engine as? LocalTranslationPerformanceSource)?.setPerformanceListener(null)
                    current.engine?.let { cleanupWarning = closeTranslationEngineOnWorker(it) }
                    current.engine = null
                }
                // Only the worker can confirm that the old native owner is no
                // longer pending. Publish availability after its bounded ACK,
                // including a visible warning when it could not be established.
                val finished = (terminal ?: return@execute).let { value ->
                    // UI progress may still be queued when the next call fails.
                    // The worker's checkpoint counter is the authoritative one.
                    current.journalEntry?.let { value.copy(done = it.done, total = it.total) } ?: value
                }
                current.journalEntry = current.journalEntry?.let { entry ->
                    val cause = current.primaryCause.get().ifEmpty {
                        if (finished.complete) "" else if (current.requestedStop.get().isNotEmpty()) current.requestedStop.get() else "ERROR"
                    }
                    entry.copy(state = when {
                        finished.complete && !current.cancelled.get() -> "COMPLETED"
                        resourceWaitExpired && !current.cancelled.get() -> "WAITING"
                        entry.failureCode.isNotEmpty() -> "FAILED"
                        current.cancelled.get() || closed -> "CANCELLED"
                        else -> "FAILED"
                    }, cause = cause, stopCause = current.requestedStop.get(), updatedAtMs = System.currentTimeMillis())
                }
                saveAttempt(current, force = true)
                // Review is fallible and optional. Release model resources first;
                // a failed review must never discard a completed durable result.
                val review = if (finished.complete && !current.cancelled.get() && !closed) {
                    publish(current, ticket, state.copy(status = "Vérification des indices linguistiques…"))
                    runCatching { LocalTranslationReviewReport.render(requireNotNull(reviewPlan), requireNotNull(translated)) }
                        .getOrDefault(LocalTranslationReviewReport.UNAVAILABLE)
                } else null
                main.post {
                    if (isCurrent(current, ticket)) {
                        active = null
                        var result = if (current.cancelled.get() && current.journalEntry?.failureCode.isNullOrEmpty()) finished.copy(
                            status = "Traduction arrêtée. Les occurrences validées sont conservées pour la reprise.",
                            complete = false) else finished
                        if (result.complete) {
                            completed = translated
                            completedExports = exports
                            completedReviewReport = review
                        }
                        cleanupWarning?.let { result = result.copy(status = result.status + "\n$it") }
                        if (attemptJournal.failedWrite)
                            result = result.copy(status = result.status + "\n" + LocalSrtAttemptJournal.WRITE_WARNING)
                        update(result)
                    }
                }
            }
        }
    }

    /** Cancels computation, never playback. Validated disk checkpoints remain. */
    fun cancel() {
        val current = active ?: return
        current.requestStop("USER_CANCEL")
        current.indexer?.cancel()
        runCatching { current.engine?.close() }
        update(state.copy(status = "Arrêt demandé ; attente de la fin du calcul en cours…"))
    }

    /** Text comes only from the completed validated track, never a partial prefix. */
    fun completedExport(format: SubtitleExportFormat = SubtitleExportFormat.SRT): String? =
        if (completed != null && state.complete && !state.busy) completedExports[format] else null

    fun completedReview(): String? = if (completed != null && state.complete && !state.busy) completedReviewReport else null

    fun exportFileName(format: SubtitleExportFormat = SubtitleExportFormat.SRT): String {
        val name = source?.name?.substringBeforeLast('.')?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")
            ?.take(120)?.ifBlank { null } ?: "sous-titres"
        return "$name.fr.${format.extension}"
    }

    override fun close() {
        if (closed) return
        closed = true; sequence++
        active?.let { task -> task.requestStop("HOST_DESTROYED"); task.indexer?.cancel(); runCatching { task.engine?.close() } }
        active = null
        worker.shutdownNow()
    }

    private fun ensureActive(task: Task) {
        if (closed || task.cancelled.get() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }
    private fun saveAttempt(task: Task, force: Boolean) {
        task.journalEntry?.let { entry ->
            val value = entry.copy(cause = task.primaryCause.get().ifEmpty { entry.cause }, stopCause = task.requestedStop.get())
            task.journalEntry = value
            attemptJournal.save(value, force)
        }
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
        private const val MAX_PROCESS_RESTARTS = 2
        private const val MAX_RESOURCE_RESTARTS = 2
        // An old Activity worker may still be stopping while the next owner is
        // created. Serialize private source/checkpoint commits across owners.
        private val WORK_LOCK = Any()
        private val JOURNAL_CRITICAL_STAGES = setOf("SERVICE", "MODEL_LOAD", "CONTEXT_INIT", "READY", "PREFILL", "DECODE")
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
