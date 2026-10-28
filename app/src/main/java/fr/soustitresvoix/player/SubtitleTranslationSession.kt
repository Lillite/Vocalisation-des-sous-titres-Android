package fr.soustitresvoix.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SubtitleText
import fr.soustitresvoix.core.SpeechTools
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import fr.soustitresvoix.core.TranslatedTrack
import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.TranslatedSubtitleExport
import fr.soustitresvoix.core.TranslationReview
import fr.soustitresvoix.core.TranslationReviewHint
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TranslationDisplayMode { ORIGINAL, TRANSLATED, BILINGUAL, HIDDEN }

/** Complete by default; an explicit progressive mode exposes only validated contiguous prefixes. */
@UnstableApi
class SubtitleTranslationSession(
    private val owner: PlaybackSession,
    context: Context,
    private val source: SubtitleSource,
    private val changed: () -> Unit,
    private val message: (String, Boolean) -> Unit
) {
    class Preview internal constructor(
        val plan: TranslationPlan,
        internal val generation: Long,
        internal val track: String,
        internal val index: CueAheadIndex,
        internal val ticket: Long,
        val progressive: Boolean = false,
        val maximumNewCharacters: Long = Long.MAX_VALUE,
        val resumedOccurrences: Int = 0,
        val uncertainRequest: Boolean = false,
        val cachedComplete: Boolean = false,
        val bufferSeconds: Int = 30
    ) {
        internal val cueOrdinals = plan.cues.withIndex().associate { it.value.id to it.index }
        /** One suffix scan per plan; no repeated full scan for each single-text response. */
        internal val missingTimesUs = LongArray(plan.cues.size + 1) { Long.MAX_VALUE }.also { times ->
            for (i in plan.cues.indices.reversed()) times[i] = minOf(plan.cues[i].startUs, times[i + 1])
        }
        val estimatedNewCharacters: Long get() = if (cachedComplete) 0 else plan.cues.drop(resumedOccurrences).sumOf { it.text.length.toLong() }
    }
    private class Job(val cancelled: AtomicBoolean = AtomicBoolean(false)) {
        @Volatile var scanner: SubtitleIndexer? = null
        @Volatile var service: TranslationEngine? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val runner = TranslationRunner(File(context.cacheDir, "translated-subtitles-v1"),
        File(context.filesDir, "translation-progress-v1"), File(context.filesDir, "translations-retained-v1"))
    private val displayPreferences = context.getSharedPreferences("translation-display-v1", Context.MODE_PRIVATE)
    var displayMode: TranslationDisplayMode = runCatching { TranslationDisplayMode.valueOf(displayPreferences.getString("mode", "ORIGINAL")!!) }.getOrDefault(TranslationDisplayMode.ORIGINAL)
        private set
    private var activePlan: TranslationPlan? = null
    private var progressive = false
    private var bufferSeconds = 30
    private var resumeAfterBuffer = false
    val waitingForTranslation: Boolean get() = resumeAfterBuffer
    private var partialTexts: List<String> = emptyList()
    private var partialFingerprint: String? = null
    private var cueOrdinals: Map<String, Int> = emptyMap()
    val progressiveEnabled: Boolean get() = progressive
    val googleTranslationActive: Boolean get() = required && activePlan?.providerId?.startsWith("google-") == true
    val googleAttributionRequired: Boolean get() = googleTranslationActive &&
        displayMode in setOf(TranslationDisplayMode.TRANSLATED, TranslationDisplayMode.BILINGUAL)
    val nextMissingSourceUs: Long get() = nextUntranslatedUs
    private var nextUntranslatedUs: Long = Long.MAX_VALUE
    private var observedSourcePositionUs: Long? = null
    private val currentSourcePositionUs: Long get() = observedSourcePositionUs ?: (owner.player.currentPosition * 1000)
    var complete = false; private set
    val sourceCues: List<TranslationCue> get() = activePlan?.cues ?: emptyList()
    val reviewHints: List<TranslationReviewHint> get() = activePlan?.let { plan -> active?.let { TranslationReview.inspect(plan.cues, it.cues) } } ?: emptyList()
    private var sequence = 0L
    private var restoreCompletion: Pair<Long, (Boolean) -> Unit>? = null
    private fun finishRestore(success: Boolean) {
        val completion = restoreCompletion ?: return
        restoreCompletion = null
        // Defer until the state mutation finishes, and invalidate a success cancelled before delivery.
        main.post {
            val current = !closed && sequence == completion.first && complete &&
                activeGeneration == owner.generation && activeTrack == owner.selectedTextId()
            completion.second(success && current)
        }
    }
    private var retentionTicket = 0L
    private val pendingRetentions = linkedMapOf<Long, (Boolean) -> Unit>()
    private fun invalidateRetentions() {
        retentionTicket++
        val callbacks = pendingRetentions.values.toList(); pendingRetentions.clear()
        callbacks.forEach { it(false) }
    }
    private var job: Job? = null
    private var preview: Preview? = null
    private var active: TranslatedTrack? = null
    private var activeGeneration = -1L
    private var activeTrack: String? = null
    private var requestedTrack: String? = null
    private var closed = false
    var required = false; private set
    var status = "Traduction désactivée : vocalisation de la piste originale"; private set
    val busy: Boolean get() = job != null
    /** Result identity for reports/offline work; foreground TTS keys use voice settings and exact text. */
    val fingerprint: String get() = active?.fingerprint ?: partialFingerprint ?: if (required) "translation-required" else "original"
    val ready: Boolean get() = (active != null || partialTexts.isNotEmpty()) && activeGeneration == owner.generation && activeTrack == owner.selectedTextId() &&
        (complete || progressive && currentSourcePositionUs < nextUntranslatedUs)

    /** No network or API client is constructed at this stage. */
    fun prepare(sourceLanguage: String, providerId: String, prepared: (Preview) -> Unit) =
        prepareWithOptions(sourceLanguage, providerId, prepared = prepared)

    fun prepareWithOptions(sourceLanguage: String, providerId: String, targetLanguage: String = "fr",
        progressive: Boolean = false, maximumNewCharacters: Long = Long.MAX_VALUE, bufferSeconds: Int = 30, prepared: (Preview) -> Unit) {
        if (closed) return
        require(bufferSeconds in 10..120) { "Le tampon doit être compris entre 10 et 120 secondes." }
        val media = owner.media ?: run { message("Ouvre d’abord une vidéo.", true); return }
        val track = owner.selectedTextId() ?: run { message("Choisis une piste de sous-titres textuelle chargée avant de traduire.", true); return }
        finishRestore(false); invalidateRetentions()
        owner.pause(); owner.cancelIndexing(); cancelJob()
        required = true; active = null; clearPartial(); activePlan = null; complete = false; observedSourcePositionUs = null; this.progressive = progressive; this.bufferSeconds = bufferSeconds; preview = null; requestedTrack = track
        owner.resetNarrationForTranslation()
        val generation = owner.generation
        val ticket = ++sequence
        val current = Job(); job = current
        status = "Indexation complète de la piste avant traduction…"; changed()
        val mediaSource = if (track == "external-subtitle") media else media.copy(subtitlePath = null, subtitleMime = null)
        worker.execute {
            var scanning = true
            try {
                val scanner = source.createIndexer(); current.scanner = scanner
                if (current.cancelled.get()) { scanner.cancel(); return@execute }
                val index = scanner.scan(mediaSource) { percent -> main.post {
                    if (isCurrent(current, generation, track, ticket)) {
                        status = if (percent >= 0) "Indexation avant traduction : $percent %" else "Indexation avant traduction…"
                        changed()
                    }
                } }
                scanning = false
                require(!index.truncated) { "Index incomplet : traduction refusée pour éviter de perdre des sous-titres." }
                val cues = index.selected(track).mapNotNull { item ->
                    val text = SubtitleText.clean(item.raw)
                    if (text.isBlank()) null else TranslationCue(item.identity, item.startUs, item.endUs, text)
                }
                if (mediaSource.subtitleMime == "application/x-subrip" && mediaSource.subtitlePath != null)
                    SrtSourceIntegrity.check(File(mediaSource.subtitlePath), cues.size)
                val plan = TranslationPlan.create(cues, sourceLanguage, targetLanguage, providerId = providerId)
                // Exact transport limits are checked by the runner before its first request.
                val checkpoint = runner.checkpointSummary(plan)
                val result = Preview(plan, generation, track, index, ticket, progressive, maximumNewCharacters, checkpoint.first, checkpoint.second, runner.readExisting(plan) != null, bufferSeconds)
                main.post {
                    if (isCurrent(current, generation, track, ticket)) {
                        job = null; preview = result
                        status = "Piste prête à traduire : ${cues.size} occurrences, ${plan.estimatedCharacters} unités UTF-16 estimées. Confirmation du traitement requise."
                        changed(); prepared(result)
                    }
                }
            } catch (error: Exception) { main.post {
                if (isCurrent(current, generation, track, ticket)) {
                    job = null
                    status = if (error is SrtSourceIntegrity.InvalidSource) SrtSourceIntegrity.FAILURE_MESSAGE
                        else if (!scanning && error is IllegalArgumentException) error.message ?: "La piste ne peut pas être traduite."
                        else "Indexation impossible. Vérifie le fichier et sa piste textuelle ; aucun texte n’a été envoyé."
                    message(status, true); changed(); finishRestore(false)
                }
            } }
        }
    }

    /** Invoked only by the user's confirmation of provider and estimated volume. */
    fun start(value: Preview, engine: () -> TranslationEngine) {
        if (closed || preview !== value || value.ticket != sequence || value.generation != owner.generation || value.track != owner.selectedTextId()) {
            message("La vidéo ou la piste a changé. Prépare à nouveau la traduction.", true); return
        }
        owner.pause(); owner.resetNarrationForTranslation()
        val current = Job(); job = current; preview = null; activePlan = value.plan; progressive = value.progressive; bufferSeconds = value.bufferSeconds; cueOrdinals = value.cueOrdinals
        status = if (progressive) "Traduction progressive : la lecture sera disponible après $bufferSeconds secondes de tampon validé." else "Traduction en cours. La lecture française attend la piste complète."; changed()
        worker.execute {
            try {
                if (current.cancelled.get()) return@execute
                data class Prefix(val texts: List<String>, val fingerprint: String, val nextUs: Long)
                val pendingPrefix = AtomicReference<Prefix?>(null)
                val prefixPosted = AtomicBoolean(false)
                val pendingProgress = AtomicReference<Pair<Int, Int>?>(null)
                val progressPosted = AtomicBoolean(false)
                var hashedCount = 0
                var prefixHash = SpeechTools.key("translation-progressive-v2", value.plan.fingerprint)
                val translated = engine().use { service ->
                    current.service = service
                    runner.run(value.plan, service, { current.cancelled.get() || Thread.currentThread().isInterrupted }, { done, total ->
                        pendingProgress.set(done to total)
                        if (progressPosted.compareAndSet(false, true)) main.post {
                            progressPosted.set(false)
                            val progress = pendingProgress.getAndSet(null)
                            if (progress != null && isCurrent(current, value.generation, value.track, value.ticket)) {
                                status = "Traduction : ${progress.first} / ${progress.second} occurrences validées" + when {
                                    resumeAfterBuffer -> ". Lecture en attente du tampon ; Pause annule la reprise automatique."
                                    owner.machine.snapshot().wantsPlayback -> ". La lecture continue pendant la traduction."
                                    progressive && ready -> ". Appuie sur Lire dès que le tampon est suffisant."
                                    else -> ""
                                }
                                changed()
                            }
                        }
                    }, TranslationRunPolicy(value.maximumNewCharacters,
                        requestMayConsumeRemoteQuota = !value.plan.providerId.startsWith("local-gguf-") &&
                            !value.plan.providerId.startsWith("google-local-"))) { texts ->
                        if (value.progressive && texts.size < value.plan.cues.size) {
                            // The runner already validates every returned occurrence. Hash only
                            // additions, then coalesce fast local/cache responses on the UI loop.
                            for (i in hashedCount until texts.size) prefixHash = SpeechTools.key(prefixHash, texts[i])
                            hashedCount = texts.size
                            pendingPrefix.set(Prefix(texts, prefixHash, value.missingTimesUs[texts.size]))
                            if (prefixPosted.compareAndSet(false, true)) main.post {
                                prefixPosted.set(false)
                                val prefix = pendingPrefix.getAndSet(null)
                                if (prefix != null && isCurrent(current, value.generation, value.track, value.ticket)) {
                                    val firstPrefix = partialTexts.isEmpty()
                                    partialTexts = prefix.texts; partialFingerprint = prefix.fingerprint
                                    activeGeneration = value.generation; activeTrack = value.track; nextUntranslatedUs = prefix.nextUs
                                    if (firstPrefix) owner.installTranslationIndex(value.index)
                                    else if (!owner.machine.snapshot().wantsPlayback && owner.machine.snapshot().speakingId == null) owner.rearmCurrentSubtitles()
                                    resumeWhenBuffered()
                                    changed()
                                }
                            }
                        }
                    }
                }
                main.post {
                    if (isCurrent(current, value.generation, value.track, value.ticket)) {
                        val hadPrefix = partialTexts.isNotEmpty()
                        job = null; active = translated; partialTexts = emptyList(); partialFingerprint = null; activePlan = value.plan; complete = true; nextUntranslatedUs = Long.MAX_VALUE; activeGeneration = value.generation; activeTrack = value.track
                        if (!hadPrefix) owner.installTranslationIndex(value.index)
                        else if (!owner.machine.snapshot().wantsPlayback && owner.machine.snapshot().speakingId == null) owner.rearmCurrentSubtitles()
                        status = "Traduction française prête : ${translated.cues.size} occurrences. Seul le français sera vocalisé." +
                            if (resumeAfterBuffer || owner.machine.snapshot().wantsPlayback) "" else " Appuie sur Lire."
                        runner.cacheWarning?.let { status += "\n$it" }
                        val hints = reviewHints.size
                        if (hints > 0) status += "\n$hints occurrences à vérifier : indices linguistiques, sans preuve de contresens."
                        resumeWhenBuffered()
                        message(status, false); changed()
                    }
                }
            } catch (error: Exception) { main.post {
                if (isCurrent(current, value.generation, value.track, value.ticket)) {
                    job = null
                    // A terminal error keeps the validated prefix usable, but cannot
                    // trigger a later automatic resume without a new user action.
                    resumeAfterBuffer = false
                    status = when (error) {
                        is TranslationFailure -> error.message ?: "La traduction a échoué."
                        is IllegalArgumentException -> "Traduction incomplète : activation refusée."
                        else -> "Traduction interrompue ou cache inaccessible. Aucune piste incomplète n’a été activée."
                    }
                    message(status, true); changed()
                }
            } }
        }
    }

    private fun isCurrent(value: Job, generation: Long, track: String, ticket: Long): Boolean =
        !closed && job === value && !value.cancelled.get() && generation == owner.generation &&
            track == owner.selectedTextId() && sequence == ticket

    fun allowPlayback(): Boolean {
        if (!required) return true
        if (!ready) { message("La traduction française n’est pas prête pour cette piste. Termine-la ou choisis explicitement Revenir à la piste originale.", true); return false }
        if (!complete && nextUntranslatedUs - currentSourcePositionUs < bufferSeconds * 1_000_000L) {
            message("Le tampon traduit contient moins de $bufferSeconds secondes. Attends d’autres lots puis appuie sur Lire.", true); return false
        }
        return allowNarrationVoice()
    }

    /** Pending validated speech may outlast the picture or the progressive buffer. */
    fun allowNarrationVoice(): Boolean {
        if (!required) return true
        if (owner.prefs.narration && (!owner.speech.isReady || owner.speech.language != (activePlan?.targetLanguage ?: "fr").substringBefore('-'))) {
            message("Choisis une voix française prête dans Voix et confort avant de lire la traduction.", true); return false
        }
        return true
    }

    /** Lookup is independent of the playback gate: a pending known occurrence remains readable when the frontier is reached. */
    fun textFor(item: CueAheadIndex.Item): String? {
        if (!required) return item.raw
        if (activeGeneration != owner.generation || activeTrack != owner.selectedTextId()) return null
        active?.let { return it.textFor(item.identity, SubtitleText.clean(item.raw)) }
        val ordinal = cueOrdinals[item.identity] ?: return null
        val sourceCue = activePlan?.cues?.getOrNull(ordinal) ?: return null
        if (sourceCue.text != SubtitleText.clean(item.raw)) return null
        return partialTexts.getOrNull(ordinal)
    }

    fun rejectUnmapped() {
        owner.pause()
        status = "Une occurrence ne correspond pas à la piste traduite. Lecture arrêtée : aucune substitution par le texte original."
        message(status, true); changed()
    }

    fun cancel() {
        if (closed) return
        finishRestore(false); invalidateRetentions(); resumeAfterBuffer = false; cancelJob(); preview = null; sequence++
        status = if (ready) "Traduction française conservée" else "Traduction annulée. Relance-la ou reviens explicitement à la piste originale."
        changed()
    }
    private fun cancelJob() {
        job?.let { it.cancelled.set(true); it.scanner?.cancel(); runCatching { it.service?.close() } }; job = null
    }
    fun useOriginal() {
        if (closed) return
        owner.pause(); reset(); owner.resetNarrationForTranslation(); owner.rearmCurrentSubtitles(); changed()
    }
    fun trackChanged() {
        if (!required) return
        finishRestore(false); invalidateRetentions(); cancelJob(); sequence++; preview = null; active = null; clearPartial(); activePlan = null; complete = false
        status = "Piste modifiée : une nouvelle traduction est nécessaire avant la lecture française."
        changed()
    }
    fun observeTrack() {
        if (required && requestedTrack != null && requestedTrack != owner.selectedTextId()) {
            owner.pause(); trackChanged(); requestedTrack = null
        }
    }
    fun reset() {
        finishRestore(false); invalidateRetentions(); cancelJob(); sequence++; preview = null; active = null; clearPartial(); activePlan = null; complete = false; progressive = false; nextUntranslatedUs = Long.MAX_VALUE; observedSourcePositionUs = null; required = false; requestedTrack = null
        status = "Traduction désactivée : vocalisation de la piste originale"
    }
    fun setDisplayMode(mode: TranslationDisplayMode) {
        displayMode = mode; displayPreferences.edit().putString("mode", mode.name).apply()
        owner.rearmCurrentSubtitles(); changed()
    }
    fun displayTextFor(item: CueAheadIndex.Item): String? {
        if (!required) return if (displayMode == TranslationDisplayMode.HIDDEN) null else item.raw
        return when (displayMode) {
        TranslationDisplayMode.ORIGINAL -> item.raw
        TranslationDisplayMode.TRANSLATED -> textFor(item)
        TranslationDisplayMode.BILINGUAL -> textFor(item)?.let { item.raw + "\n" + it }
        TranslationDisplayMode.HIDDEN -> null
        }
    }
    /** Explicit Pause, seek, focus loss, stop and settings changes invalidate automatic recovery. */
    internal fun playbackInterrupted() { resumeAfterBuffer = false }

    private fun clearPartial() {
        partialTexts = emptyList(); partialFingerprint = null; cueOrdinals = emptyMap(); resumeAfterBuffer = false
    }

    private fun resumeWhenBuffered() {
        if (!resumeAfterBuffer || !ready || (!complete && nextUntranslatedUs - currentSourcePositionUs < bufferSeconds * 1_000_000L)) return
        // Consume the intent before play(): a rejected voice/focus request cannot
        // turn a future unrelated callback into a playback command.
        resumeAfterBuffer = false
        owner.play()
    }

    /** Hold at the first missing source occurrence, including cues shorter than a player tick. */
    fun onPlaybackPosition(positionUs: Long): Boolean {
        observedSourcePositionUs = positionUs
        if (!required || complete) return true
        if (!ready || positionUs >= nextUntranslatedUs) {
            val snapshot = owner.machine.snapshot()
            if (snapshot.wantsPlayback && progressive && partialTexts.isNotEmpty()) {
                resumeAfterBuffer = busy
                owner.pauseForTranslationBuffer(nextUntranslatedUs)
                // The rewind is internal, not a user seek. Keep the exact frontier
                // for readiness even if the renderer hasn't delivered its callback.
                observedSourcePositionUs = minOf(positionUs, nextUntranslatedUs)
            } else if (snapshot.wantsPlayback || snapshot.speakingId != null) owner.pause()
            val nextStatus = if (resumeAfterBuffer) "Lecture en attente de traduction. Reprise après $bufferSeconds secondes de tampon ; Pause annule cette reprise."
                else "Pause : la traduction n’est pas encore prête à cette position. Attends la progression puis appuie sur Lire."
            if (status != nextStatus) { status = nextStatus; changed() }
            return false
        }
        return true
    }
    fun editableCues(): List<TranslationCue> = active?.cues ?: activePlan?.cues?.take(partialTexts.size)
        ?.mapIndexed { i, cue -> cue.copy(text = partialTexts[i]) } ?: emptyList()
    fun editCue(id: String, text: String): Boolean {
        val plan = activePlan ?: return false
        val track = active ?: return false
        if (!complete || busy || activeGeneration != owner.generation || activeTrack != owner.selectedTextId() || track.cues.none { it.id == id }) return false
        return try {
            val replacement = plan.complete(track.cues.map { if (it.id == id) text else it.text })
            owner.pause(); owner.resetNarrationForTranslation(); active = replacement
            owner.rearmCurrentSubtitles(); status = "Réplique corrigée. Le cache vocal dépend du nouveau texte ; appuie sur Lire."
            changed(); true
        } catch (_: IllegalArgumentException) { message("Texte vide, invalide ou trop long ; correction refusée.", true); false }
    }
    /** Explicit preparation of this occurrence only; existing audio for other cues stays reusable. */
    fun editCueAndPrepareVoice(id: String, text: String): Boolean {
        if (!editCue(id, text)) return false
        val cue = active?.cues?.firstOrNull { it.id == id } ?: return false
        val target = activePlan?.targetLanguage?.substringBefore('-')
        val voiceLanguage = owner.speech.language?.substringBefore('-')
        val unavailable = when {
            !owner.prefs.cacheEnabled -> "active le cache vocal"
            !owner.speech.isReady -> "choisis une voix disponible"
            target == null || !target.equals(voiceLanguage, ignoreCase = true) -> "choisis une voix dans la langue de traduction"
            else -> null
        }
        if (unavailable != null) {
            status = "Réplique corrigée ; pour préparer uniquement sa voix, $unavailable. La lecture reste en pause."
            message(status, true); changed(); return true
        }
        // Use exactly the same filtering, segmentation and language as foreground narration.
        // The text forms part of each cache key, so other occurrences need no invalidation.
        val segments = SpeechTools.segments(SpeechTools.filter(cue.text, owner.prefs.filters), owner.speech.language)
        if (segments.isEmpty()) {
            status = "Réplique corrigée ; les exclusions choisies ne laissent aucun segment à préparer. La lecture reste en pause."
            message(status, true); changed(); return true
        }
        owner.speech.prepareBatch(segments)
        status = "Réplique corrigée ; préparation de ses ${segments.size} segments vocaux demandée. Les autres répliques ne sont pas régénérées. La lecture reste en pause."
        changed(); return true
    }
    fun exportCurrent(format: SubtitleExportFormat): String? = if (complete && ready) active?.let { TranslatedSubtitleExport.encode(it.cues, format) } else null
    fun saveCurrentTranslation(): Boolean {
        val plan = activePlan ?: return false
        val track = active ?: return false
        if (!complete || !ready) return false
        val saved = runner.retain(plan, track)
        status = if (saved) "Traduction conservée durablement dans l’application. Un export SRT/VTT permet aussi une copie indépendante." else "La conservation a échoué. La piste reste disponible dans cette session ; exporte-la si possible."
        message(status, !saved); changed(); return saved
    }
    fun saveCurrentTranslationAsync() = retainCurrentForOffline { }

    /**
     * Explicit offline retention prerequisite. Persists the complete exact track on the worker,
     * then reports once on the main thread. A changed generation, track, text or reset reports false.
     * It never prepares audio or starts playback by itself; partial progressive tracks are refused.
     */
    fun retainCurrentForOffline(onDone: (Boolean) -> Unit) = retainCurrentForOffline(null, onDone)
    fun retainCurrentForOffline(workKey: String?, onDone: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { retainCurrentForOffline(workKey, onDone) }; return }
        if (closed) { onDone(false); return }
        if (!required) { onDone(true); return }
        val plan = activePlan
        val track = active
        if (plan == null || track == null || !complete || activeGeneration != owner.generation ||
            activeTrack != owner.selectedTextId() || track.cues.size != plan.cues.size) {
            message("Termine d’abord la traduction complète avant de conserver toute la narration hors ligne.", true)
            onDone(false); return
        }
        invalidateRetentions()
        val ticket = ++retentionTicket
        val generation = owner.generation
        val selectedTrack = owner.selectedTextId()
        val translationSequence = sequence
        pendingRetentions[ticket] = onDone
        status = "Conservation durable de la traduction avant préparation hors ligne…"; changed()
        try {
            worker.execute {
                val saved = runCatching {
                    if (workKey == null) runner.retain(plan, track) else runner.retainForWork(workKey, plan, track)
                }.getOrDefault(false)
                main.post {
                    val callback = pendingRetentions.remove(ticket) ?: return@post
                    val current = !closed && ticket == retentionTicket && generation == owner.generation &&
                        translationSequence == sequence && selectedTrack == owner.selectedTextId() &&
                        active?.fingerprint == track.fingerprint && complete
                    if (current) {
                        status = if (saved) "Traduction complète conservée durablement pour la narration hors ligne." else "Conservation de la traduction impossible : préparation hors ligne arrêtée, aucune lecture lancée."
                        message(status, !saved); changed()
                    }
                    callback(saved && current)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pendingRetentions.remove(ticket)?.invoke(false)
        }
    }
    fun storedTranslationSummary(): String {
        val (count, bytes) = runner.retainedStorageSummary()
        return "$count traduction(s) conservée(s), $bytes octets. Stockage privé de l’application, textes non annoncés comme chiffrés. Les exports externes sont indépendants."
    }
    fun forgetCurrentSavedTranslation(): Boolean {
        val plan = activePlan ?: return false
        return runner.removeRetained(plan).also {
            status = if (it) "Conservation durable retirée pour cette piste. La session et le cache peuvent encore contenir sa traduction." else "La conservation n’a pas pu être retirée."
            message(status, !it); changed()
        }
    }
    /** Deletes one offline work's durable translation; video, source and exports are untouched. */
    fun forgetWorkSavedTranslation(workKey: String, onDone: (Boolean) -> Unit = {}) {
        try {
            worker.execute {
                val removed = runCatching { runner.removeRetainedForWork(workKey) }.getOrDefault(false)
                main.post {
                    if (!closed) {
                        message(if (removed) "Traduction conservée retirée pour cette œuvre. Les autres œuvres, exports et copies conservées séparément restent disponibles."
                            else "La traduction de cette œuvre n’a pas pu être retirée.", !removed)
                        changed()
                    }
                    onDone(removed)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { onDone(false) }
    }
    /** No credentials or subtitle text in the session selection metadata. */
    fun selectionMetadata(): String? {
        val plan = activePlan ?: return null
        if (!complete || !ready) return null
        return JSONObject().put("version", 1).put("source", plan.sourceLanguage).put("target", plan.targetLanguage)
            .put("provider", plan.providerId).put("sourceFingerprint", plan.fingerprint).put("resultFingerprint", active?.fingerprint).toString()
    }
    /** Resume must remember that French was requested even while a progressive
     * plan is incomplete. No text, credentials or automatic provider call. */
    fun checkpointSelectionMetadata(): String? {
        selectionMetadata()?.let { return it }
        if (!required) return null
        val plan = activePlan ?: return JSONObject().put("version", 0).put("unavailable", true).toString()
        return JSONObject().put("version", 1).put("source", plan.sourceLanguage).put("target", plan.targetLanguage)
            .put("provider", plan.providerId).put("sourceFingerprint", plan.fingerprint).toString()
    }
    /** Re-indexes asynchronously, restores only an exact existing local complete result, never contacts a provider. */
    fun restoreSelection(metadata: String, onDone: (Boolean) -> Unit = {}): Boolean = try {
        require(!closed && owner.media != null && owner.selectedTextId() != null)
        require(metadata.length <= 2048)
        val json = JSONObject(metadata); require(json.getInt("version") == 1)
        val expected = json.getString("sourceFingerprint"); require(expected.matches(Regex("[a-f0-9]{64}")))
        val expectedResult = if (json.has("resultFingerprint")) json.getString("resultFingerprint").also {
            require(it.matches(Regex("[a-f0-9]{64}")))
        } else null
        prepareWithOptions(json.getString("source"), json.getString("provider"), json.getString("target")) { value ->
            if (value.plan.fingerprint != expected) { status = "La piste a changé ; traduction conservée non réutilisée."; changed(); finishRestore(false) }
            else {
                activePlan = value.plan // Preserve the requested language even when only a partial checkpoint exists.
                val current = Job(); job = current
                worker.execute {
                    val restored = runner.readExisting(value.plan, expectedResult)
                    main.post {
                        if (isCurrent(current, value.generation, value.track, value.ticket)) {
                            job = null
                            if (restored == null) { status = "Traduction locale absente. Relance explicitement la traduction ou reviens à l’original."; message(status, true) }
                            else { activePlan = value.plan; active = restored; complete = true; activeGeneration = value.generation; activeTrack = value.track; nextUntranslatedUs = Long.MAX_VALUE; owner.installTranslationIndex(value.index); status = "Traduction locale restaurée sans réseau. Appuie sur Lire." }
                            changed(); finishRestore(restored != null)
                        }
                    }
                }
            }
        }
        restoreCompletion = sequence to onDone
        true
    } catch (_: Exception) {
        if (!closed) {
            owner.pause(); finishRestore(false); invalidateRetentions(); cancelJob(); sequence++
            preview = null; active = null; clearPartial(); activePlan = null; complete = false; required = true
            requestedTrack = owner.selectedTextId()
            status = "Sélection de traduction indisponible. Choisis sa réparation ou Revenir à la piste originale."
            changed()
        }
        message("Sélection de traduction invalide ; la lecture reste en pause, sans substitution par l’original ni appel réseau.", true)
        onDone(false); false
    }
    fun close() { if (!closed) { reset(); closed = true; worker.shutdownNow() } }
}
