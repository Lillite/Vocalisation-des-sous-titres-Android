package fr.soustitresvoix.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SubtitleText
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import fr.soustitresvoix.core.TranslatedTrack
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A complete translation is an explicit, session-scoped narration source. */
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
        internal val ticket: Long
    )
    private class Job(val cancelled: AtomicBoolean = AtomicBoolean(false)) {
        @Volatile var scanner: SubtitleIndexer? = null
        @Volatile var service: TranslationEngine? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val runner = TranslationRunner(File(context.cacheDir, "translated-subtitles-v1"))
    private var sequence = 0L
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
    val fingerprint: String get() = active?.fingerprint ?: if (required) "translation-required" else "original"
    val ready: Boolean get() = active != null && activeGeneration == owner.generation && activeTrack == owner.selectedTextId()

    /** No network or API client is constructed at this stage. */
    fun prepare(sourceLanguage: String, providerId: String, prepared: (Preview) -> Unit) {
        if (closed) return
        val media = owner.media ?: run { message("Ouvre d’abord une vidéo.", true); return }
        val track = owner.selectedTextId() ?: run { message("Choisis une piste de sous-titres textuelle chargée avant de traduire.", true); return }
        owner.pause(); owner.cancelIndexing(); cancelJob()
        required = true; active = null; preview = null; requestedTrack = track
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
                val plan = TranslationPlan.create(cues, sourceLanguage, providerId = providerId)
                // Validate every batch before asking for an API call, including very long signs.
                plan.batches(4000, 20)
                val result = Preview(plan, generation, track, index, ticket)
                main.post {
                    if (isCurrent(current, generation, track, ticket)) {
                        job = null; preview = result
                        status = "Piste prête à traduire : ${cues.size} occurrences, ${plan.estimatedCharacters} unités UTF-16 estimées. Confirmation d’envoi requise."
                        changed(); prepared(result)
                    }
                }
            } catch (error: Exception) { main.post {
                if (isCurrent(current, generation, track, ticket)) {
                    job = null
                    status = if (!scanning && error is IllegalArgumentException) error.message ?: "La piste ne peut pas être traduite."
                        else "Indexation impossible. Vérifie le fichier et sa piste textuelle ; aucun texte n’a été envoyé."
                    message(status, true); changed()
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
        val current = Job(); job = current; preview = null
        status = "Traduction en cours. La lecture française attend la piste complète."; changed()
        worker.execute {
            try {
                if (current.cancelled.get()) return@execute
                val translated = engine().use { service ->
                    current.service = service
                    runner.run(value.plan, service, { current.cancelled.get() || Thread.currentThread().isInterrupted }) { done, total -> main.post {
                        if (isCurrent(current, value.generation, value.track, value.ticket)) {
                            status = "Traduction : $done / $total occurrences validées"; changed()
                        }
                    } }
                }
                main.post {
                    if (isCurrent(current, value.generation, value.track, value.ticket)) {
                        job = null; active = translated; activeGeneration = value.generation; activeTrack = value.track
                        owner.installTranslationIndex(value.index)
                        status = "Traduction française prête : ${translated.cues.size} occurrences. Seul le français sera vocalisé. Appuie sur Lire."
                        runner.cacheWarning?.let { status += "\n$it" }
                        message(status, false); changed()
                    }
                }
            } catch (error: Exception) { main.post {
                if (isCurrent(current, value.generation, value.track, value.ticket)) {
                    job = null
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
        if (owner.prefs.narration && (!owner.speech.isReady || owner.speech.language != "fr")) {
            message("Choisis une voix française prête dans Voix et confort avant de lire la traduction.", true); return false
        }
        return true
    }

    fun textFor(item: CueAheadIndex.Item): String? = if (!required) item.raw else if (ready)
        active?.textFor(item.identity, SubtitleText.clean(item.raw)) else null

    fun rejectUnmapped() {
        owner.pause()
        status = "Une occurrence ne correspond pas à la piste traduite. Lecture arrêtée : aucune substitution par le texte original."
        message(status, true); changed()
    }

    fun cancel() {
        if (closed) return
        cancelJob(); preview = null; sequence++
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
        cancelJob(); sequence++; preview = null; active = null
        status = "Piste modifiée : une nouvelle traduction est nécessaire avant la lecture française."
        changed()
    }
    fun observeTrack() {
        if (required && requestedTrack != null && requestedTrack != owner.selectedTextId()) {
            owner.pause(); trackChanged(); requestedTrack = null
        }
    }
    fun reset() {
        cancelJob(); sequence++; preview = null; active = null; required = false; requestedTrack = null
        status = "Traduction désactivée : vocalisation de la piste originale"
    }
    fun close() { if (!closed) { reset(); closed = true; worker.shutdownNow() } }
}
