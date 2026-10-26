package fr.soustitresvoix.core

import java.util.ArrayDeque

/** Single-threaded deterministic controller. Apply returned effects in order. */
class NarrationMachine(private val maxPendingChars: Int = 24_000) {
    var languageTag: String? = null
    private val ledger = NarrationLedger()
    private val seenOccurrenceIds = mutableSetOf<String>()
    enum class Mode { COMFORT, CONTINUOUS, ADAPTIVE }
    enum class ResumePolicy { REPLICA, SEGMENT, EXPLICIT }
    sealed class Effect {
        data class Speak(val id: String, val text: String, val remainingText: String = "", val deadlineMs: Long? = null, val preview: Boolean = false) : Effect()
        object StopSpeech : Effect()
        data class Video(val playing: Boolean) : Effect()
        data class Duck(val enabled: Boolean) : Effect()
        data class Message(val text: String, val error: Boolean = false) : Effect()
    }
    data class Snapshot(
        val wantsPlayback: Boolean, val videoRequested: Boolean,
        val speakingId: String?, val pendingCount: Int, val waitingForSpeech: Boolean,
        val enabled: Boolean, val mode: Mode, val voiceSuspended: Boolean = false,
        val recoverableCount: Int = 0
    )
    private data class Job(val text: String, var expired: Boolean = false,
        val group: Long = 0, val original: String = text, var deadlineMs: Long? = null,
        val occurrenceId: String? = null)
    private data class Running(val id: String, val job: Job, val preview: Boolean = false)
    private val queue = ArrayDeque<Job>()
    /** Overflow and player errors retain engine-safe chunks for explicit recovery.
     * This bound matches the supported full subtitle index, independently of the
     * small hot queue. No eviction is used for source narration. */
    private val recoverable = ArrayDeque<Job>()
    private var recoverableChars = 0
    private var recoveryHold = false
    private var comprehensionHold = false
    private var current: Running? = null
    private var sequence = 0L
    private var groupSequence = 0L
    private var resumePolicy = ResumePolicy.REPLICA
    private var voiceSuspended = false
    private var activeIdentities: List<String>? = null
    private var wantsPlayback = false
    private var mediaReady = false
    private var speechReady = false
    private var enabled = true
    private var mode = Mode.COMFORT
    private var requestedVideo = false
    private var requestedDuck = false
    private var lastFrameKey: Pair<Long, List<String>>? = null
    private var activeTexts: List<String> = emptyList()
    private var activeDeadlines: List<Long?> = emptyList()
    private var lastSpokenText = ""
    private var lastSpokenGroup = -1L
    private var lastSpokenOccurrence: String? = null
    private var warnedImages = false
    private val effects = mutableListOf<Effect>()

    fun setJournalContext(contextId: String) {
        if (ledger.currentContextHash != NarrationLedger.hashIdentity(contextId)) seenOccurrenceIds.clear()
        ledger.setContext(contextId)
    }
    fun registerOccurrences(occurrences: List<NarrationLedger.Occurrence>) { occurrences.forEach(ledger::register) }
    fun markPrepared(identities: List<String>) { identities.forEach(ledger::prepared) }
    fun markFiltered(identities: List<String>) { identities.forEach(ledger::filtered) }
    fun journalSnapshot() = ledger.snapshot()
    fun exportJournalMetadata() = ledger.exportMetadata()
    fun restoreJournalMetadata(encoded: String) = ledger.restoreMetadata(encoded)
    /** The owner releases this hold only from its generation-checked callback. */
    fun setComprehensionHold(value: Boolean): List<Effect> = event {
        comprehensionHold = value && wantsPlayback && current?.preview != true
    }
    /** Explicit recovery uses text re-resolved by the owner from the selected source track. */
    fun replayOccurrence(identity: String, text: String): List<Effect> = event {
        comprehensionHold = false
        wantsPlayback = false
        if (!speechReady || identity.isBlank() || text.isBlank() || text.length > 24_000) {
            interrupt(keep = true)
            effects += Effect.Message("La réplique ne peut pas être relue : voix indisponible ou texte hors limite.", true)
            return@event
        }
        interrupt(keep = true)
        voiceSuspended = false
        // An interrupted occurrence can still own queued segments in this session.
        queue.removeIf { it.occurrenceId == identity }
        removeRecoverable { it.occurrenceId == identity }
        val segments = SpeechTools.segments(text, languageTag)
        if (segments.isEmpty()) return@event
        val group = ++groupSequence
        ledger.register(NarrationLedger.Occurrence(identity)); ledger.queued(identity)
        val jobs = segments.map { Job(it, group = group, original = text, occurrenceId = identity) }
        // Recovery is paused video with explicit voice playback, like Repeat last.
        // Keep the group as a preview queue so long occurrences remain engine-safe.
        replayQueue.addAll(jobs.drop(1))
        val running = Running(nextId(), jobs.first(), preview = true)
        current = running; lastSpokenText = text; lastSpokenGroup = group; lastSpokenOccurrence = identity
        ledger.started(identity)
        effects += Effect.Speak(running.id, running.job.text,
            jobs.drop(1).joinToString(" ") { it.text }, preview = true)
    }
    private val replayQueue = ArrayDeque<Job>()

    fun snapshot() = Snapshot(wantsPlayback, requestedVideo, current?.id,
        queue.size + replayQueue.size, wantsPlayback && mustHold(), enabled, mode, voiceSuspended,
        recoverable.size)
    fun setSpeechReady(ready: Boolean): List<Effect> = event {
        speechReady = ready
        if (!ready) {
            comprehensionHold = false
            interrupt(keep = true)
            if (enabled) wantsPlayback = false
        }
    }
    fun setMediaReady(ready: Boolean): List<Effect> = event {
        mediaReady = ready
        if (!ready && current?.preview != true) { comprehensionHold = false; interrupt(keep = true) }
    }
    fun setResumePolicy(policy: ResumePolicy): List<Effect> = event {
        resumePolicy = policy
        if (policy != ResumePolicy.EXPLICIT) voiceSuspended = false
    }
    fun resumeVoice(): List<Effect> { voiceSuspended = false; return play() }
    fun pendingTexts(): List<String> = queue.map { it.text }
    data class PlannedReplica(val text: String, val deadlineMs: Long?)
    /** Pending groups include both this replica's remaining segments and overlaps. */
    fun pendingPlan(): List<PlannedReplica> = queue.groupBy { it.group }.values.map { jobs ->
        PlannedReplica(jobs.joinToString(" ") { it.text }, jobs.first().deadlineMs)
    }
    fun play(): List<Effect> = event {
        if (voiceSuspended && enabled) {
            wantsPlayback = false
            effects += Effect.Message("Appuie sur Reprendre la voix pour reprendre la phrase et la vidéo.")
            return@event
        }
        if (current?.preview == true) interrupt(keep = false)
        if (enabled && !speechReady) {
            wantsPlayback = false
            effects += Effect.Message("La voix n’est pas prête. Choisis une voix installée ou désactive la vocalisation.", true)
        } else wantsPlayback = true
    }
    fun pause(): List<Effect> = event {
        comprehensionHold = false
        wantsPlayback = false
        val running = current
        // Buffering or voice unavailability may already have put the interrupted
        // segment back at the head. Its group must have actually started: a new
        // queued replica must not inherit the previous one's resume policy.
        val interrupted = when {
            running?.preview == true -> null
            running != null -> running.job
            else -> queue.peekFirst()?.takeIf { it.group == lastSpokenGroup }
        }
        if (interrupted != null && resumePolicy == ResumePolicy.REPLICA) {
            interrupt(keep = false)
            queue.removeIf { it.group == interrupted.group }
            removeRecoverable { it.group == interrupted.group }
            SpeechTools.segments(interrupted.original, languageTag).asReversed().forEach {
                queue.addFirst(Job(it, interrupted.expired, interrupted.group, interrupted.original, interrupted.deadlineMs, interrupted.occurrenceId))
            }
        } else interrupt(keep = current?.preview != true)
        if (interrupted != null && resumePolicy == ResumePolicy.EXPLICIT) voiceSuspended = true
    }
    /** Call before seek, media replacement or subtitle track change. */
    fun reset(keepPlaybackIntent: Boolean = false): List<Effect> = event {
        comprehensionHold = false
        val oldIntent = wantsPlayback
        interrupt(keep = false); abandonQueue(); seenOccurrenceIds.clear(); ledger.nextGeneration()
        activeTexts = emptyList(); activeDeadlines = emptyList(); activeIdentities = null; voiceSuspended = false
        lastFrameKey = null; lastSpokenText = ""; lastSpokenGroup = -1L; lastSpokenOccurrence = null
        warnedImages = false; mediaReady = false
        wantsPlayback = keepPlaybackIntent && oldIntent
    }
    fun setEnabled(value: Boolean): List<Effect> = event {
        if (enabled != value) {
            comprehensionHold = false
            enabled = value; voiceSuspended = false
            interrupt(keep = false); abandonQueue()
            if (value) {
                activeTexts.forEachIndexed { index, text ->
                    enqueue(text, if (mode == Mode.ADAPTIVE) activeDeadlines.getOrNull(index) else null, activeIdentities?.getOrNull(index))
                }
                if (!speechReady) wantsPlayback = false
            }
        }
    }
    fun setMode(value: Mode): List<Effect> = event {
        if (mode != value) {
            comprehensionHold = false
            mode = value
            if (value != Mode.ADAPTIVE) {
                current?.job?.deadlineMs = null; queue.forEach { it.deadlineMs = null }
                recoverable.forEach { it.deadlineMs = null }
            }
            if (value == Mode.CONTINUOUS) {
                interrupt(keep = false); abandonQueue()
                activeTexts.forEachIndexed { index, text -> enqueue(text, occurrenceId = activeIdentities?.getOrNull(index)) }
            }
        }
    }
    /** Stable occurrence IDs distinguish restyling from a later identical replica. */
    fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean = false,
        identities: List<String>? = null, deadlinesMs: List<Long?>? = null): List<Effect> = event {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val validIdentities = identities?.takeIf {
            it.size == clean.size && it.all(String::isNotBlank) && it.distinct().size == it.size
        }
        clean.forEachIndexed { index, _ -> validIdentities?.get(index)?.let {
            ledger.register(NarrationLedger.Occurrence(it))
        } }
        val key = frameTimeUs to clean
        if (key == lastFrameKey && !hasImages && validIdentities == activeIdentities) return@event
        val oldTexts = activeTexts
        val oldIdentities = activeIdentities
        if (!hasImages && validIdentities != null && validIdentities == oldIdentities && clean == oldTexts) return@event
        activeIdentities = validIdentities; lastFrameKey = key; activeTexts = clean
        activeDeadlines = deadlinesMs.orEmpty()
        if (!enabled) return@event
        if (hasImages && !warnedImages) {
            warnedImages = true; wantsPlayback = false
            interrupt(keep = true)
            effects += Effect.Message("Cette piste contient des sous-titres en images. Leur lecture vocale n’est pas disponible. Choisis une piste texte ou ajoute un fichier SRT, VTT ou ASS.", true)
        }
        current?.takeUnless { it.preview || it.job.deadlineMs != null }?.job?.expired = true
        queue.forEach { if (it.deadlineMs == null) it.expired = true }
        if (mode == Mode.CONTINUOUS) { interrupt(keep = false); abandonQueue() }
        val incomingIndices = if (validIdentities != null) {
            // A source identity remains authoritative across ASS restyling, overlap
            // intervals and translated wording. A seek explicitly starts a new pass.
            clean.indices.filter { i -> validIdentities[i] !in seenOccurrenceIds }
        } else if (clean == oldTexts) clean.indices.toList() else {
            val remaining = oldTexts.toMutableList()
            clean.indices.filter { !remaining.remove(clean[it]) }
        }
        if (validIdentities != null && seenOccurrenceIds.size + incomingIndices.size > 100_000) {
            wantsPlayback = false; interrupt(keep = true)
            incomingIndices.forEach { ledger.error(validIdentities[it]) }
            effects += Effect.Message("Limite de suivi de 100 000 occurrences atteinte. La lecture est arrêtée pour garder le suivi explicite ; un déplacement ouvre un nouveau passage.", true)
            return@event
        }
        incomingIndices.forEach { i ->
            val identity = validIdentities?.get(i)
            val admitted = enqueue(clean[i], if (mode == Mode.ADAPTIVE) deadlinesMs?.getOrNull(i) else null, identity)
            if (admitted && identity != null) seenOccurrenceIds.add(identity)
        }

    }
    fun advance(positionMs: Long): List<Effect> = event {
        current?.takeUnless { it.preview }?.job?.let { job ->
            job.deadlineMs?.let { if (positionMs >= it) job.expired = true }
        }
        queue.forEach { job -> job.deadlineMs?.let { if (positionMs >= it) job.expired = true } }
        recoverable.forEach { job -> job.deadlineMs?.let { if (positionMs >= it) job.expired = true } }
    }
    fun speechFinished(id: String): List<Effect> = event {
        val running = current?.takeIf { it.id == id } ?: return@event
        current = null
        val remaining = if (running.preview) replayQueue else queue
        if (remaining.none { it.group == running.job.group } &&
            (running.preview || recoverable.none { it.group == running.job.group })) {
            running.job.occurrenceId?.let(ledger::completed)
        }
        if (running.preview && replayQueue.isNotEmpty()) {
            val job = replayQueue.removeFirst(); val next = Running(nextId(), job, preview = true)
            current = next
            effects += Effect.Speak(next.id, job.text,
                replayQueue.joinToString(" ") { it.text }, preview = true)
        }
    }
    fun speechFailed(id: String, detail: String): List<Effect> = event {
        if (current?.id != id) return@event
        val occurrence = current?.job?.occurrenceId
        comprehensionHold = false
        wantsPlayback = false; interrupt(keep = true)
        occurrence?.let(ledger::error)
        effects += Effect.Message("Lecture vocale interrompue : $detail", true)
    }
    fun fail(detail: String): List<Effect> = event {
        comprehensionHold = false
        val running = current
        val retained = (if (running != null && (!running.preview || running.job.occurrenceId != null))
            listOf(running.job) + if (running.preview) replayQueue.toList() else emptyList() else emptyList()) + queue.toList()
        val affected = (retained.mapNotNull { it.occurrenceId } + recoverable.mapNotNull { it.occurrenceId }).distinct()
        wantsPlayback = false; interrupt(keep = false); queue.clear()
        // Keep the visible hot queue empty on a generic player error, but retain
        // every unfinished chunk until the user explicitly retries playback.
        retained.asReversed().forEach { recoverable.addFirst(it); recoverableChars += it.text.length }
        recoveryHold = recoverable.isNotEmpty()
        affected.forEach(ledger::error)
        effects += Effect.Message(detail, true)
    }
    /** Repeating a whole replica consumes its pending segments, not other replicas. */
    fun preview(text: String = lastSpokenText): List<Effect> = event {
        comprehensionHold = false
        wantsPlayback = false
        val refusal = when {
            !speechReady -> Effect.Message("Aucune voix disponible. Ouvre les réglages vocaux Android.", true)
            text.isBlank() -> Effect.Message("Aucune phrase à répéter.")
            text.length > 3_000 -> Effect.Message("Cette phrase est trop longue pour un essai vocal.", true)
            else -> null
        }
        if (refusal != null) {
            // Refusing a preview must not consume the interrupted film replica.
            interrupt(keep = true); effects += refusal; return@event
        }
        val repeatGroup = if (text == lastSpokenText) lastSpokenGroup else -1L
        interrupt(keep = current?.job?.group != repeatGroup)
        if (repeatGroup >= 0) {
            queue.removeIf { it.group == repeatGroup }
            removeRecoverable { it.group == repeatGroup }
        }
        voiceSuspended = false
        val occurrence = if (repeatGroup >= 0) lastSpokenOccurrence else null
        val running = Running(nextId(), Job(text, occurrenceId = occurrence), preview = true)
        occurrence?.let(ledger::started)
        current = running; effects += Effect.Speak(running.id, text, preview = true)
    }
    private fun enqueue(text: String, deadlineMs: Long? = null, occurrenceId: String? = null): Boolean {
        if (text.isBlank()) return false
        val group = ++groupSequence
        val identity = occurrenceId ?: "session:${ledger.currentGeneration}:local:$group"
        ledger.register(NarrationLedger.Occurrence(identity))
        val total = queue.sumOf { it.text.length } + (current?.job?.text?.length ?: 0) + text.length
        if (text.length > MAX_RECOVERABLE_CHARS || recoverableChars.toLong() + text.length > MAX_RECOVERABLE_CHARS) {
            wantsPlayback = false; interrupt(keep = true); ledger.error(identity)
            effects += Effect.Message("La piste dépasse la limite de récupération de 12 millions de caractères. La lecture est arrêtée ; les phrases déjà retenues sont conservées. Cette occurrence doit être retrouvée dans la piste source.", true)
            return false
        }
        val jobs = SpeechTools.segments(text, languageTag).map {
            Job(it, group = group, original = text, deadlineMs = deadlineMs, occurrenceId = identity)
        }
        if (total > maxPendingChars || recoverable.isNotEmpty()) {
            wantsPlayback = false; interrupt(keep = true)
            jobs.forEach { recoverable.addLast(it); recoverableChars += it.text.length }
            recoveryHold = true; ledger.error(identity)
            effects += Effect.Message("Trop de texte reçu à la fois. Les phrases sont conservées. Appuie sur Lire pour terminer leur vocalisation avec la vidéo en pause.", true)
        } else {
            ledger.queued(identity); jobs.forEach(queue::addLast)
        }
        return true
    }
    private fun interrupt(keep: Boolean) {
        val running = current ?: return
        current = null
        running.job.occurrenceId?.let(ledger::interrupted)
        replayQueue.clear()
        if (keep && !running.preview) queue.addFirst(running.job)
        effects += Effect.StopSpeech
    }
    private fun abandonQueue() {
        (queue.mapNotNull { it.occurrenceId } + recoverable.mapNotNull { it.occurrenceId }).distinct().forEach(ledger::interrupted)
        queue.clear(); replayQueue.clear(); recoverable.clear(); recoverableChars = 0; recoveryHold = false
    }
    private fun removeRecoverable(predicate: (Job) -> Boolean) {
        val iterator = recoverable.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (predicate(job)) { recoverableChars -= job.text.length; iterator.remove() }
        }
    }
    private fun refillRecovery() {
        if (!enabled || !speechReady || !mediaReady || !wantsPlayback || voiceSuspended || current?.preview == true) return
        var hotChars = queue.sumOf { it.text.length } + (current?.job?.text?.length ?: 0)
        while (recoverable.isNotEmpty()) {
            val next = recoverable.peekFirst()
            // Even with an artificially tiny queue limit, one engine-safe chunk
            // can make progress. An individual source is never truncated to fit.
            if (hotChars > 0 && hotChars.toLong() + next.text.length > maxPendingChars) break
            recoverable.removeFirst(); recoverableChars -= next.text.length
            hotChars += next.text.length
            next.occurrenceId?.let { identity ->
                if (ledger.entry(identity)?.state !in setOf(NarrationLedger.State.QUEUED, NarrationLedger.State.STARTED)) ledger.queued(identity)
            }
            queue.addLast(next)
        }
    }
    private fun mustHold(): Boolean = comprehensionHold || (enabled && mode != Mode.CONTINUOUS &&
        ((recoveryHold && (current != null || queue.isNotEmpty() || recoverable.isNotEmpty())) ||
            current?.job?.expired == true || queue.any { it.expired }))
    private fun settle() {
        refillRecovery()
        if (current == null && !comprehensionHold && !voiceSuspended && enabled && speechReady && mediaReady && wantsPlayback && queue.isNotEmpty()) {
            val job = queue.removeFirst(); val running = Running(nextId(), job)
            current = running; lastSpokenText = job.original; lastSpokenGroup = job.group; lastSpokenOccurrence = job.occurrenceId
            job.occurrenceId?.let(ledger::started)
            val rest = (queue.asSequence() + recoverable.asSequence())
                .filter { it.group == job.group }.joinToString(" ") { it.text }
            effects += Effect.Speak(running.id, job.text, rest, job.deadlineMs)
        }
        if (current == null && queue.isEmpty() && recoverable.isEmpty()) recoveryHold = false
        val video = wantsPlayback && !mustHold() && current?.preview != true
        val duck = current != null
        if (video != requestedVideo) { requestedVideo = video; effects += Effect.Video(video) }
        if (duck != requestedDuck) { requestedDuck = duck; effects += Effect.Duck(duck) }
    }
    private fun nextId(): String = "subtitle-${++sequence}"
    private companion object { const val MAX_RECOVERABLE_CHARS = 12_000_000 }
    private inline fun event(block: () -> Unit): List<Effect> {
        check(effects.isEmpty()) { "State machine must not be called reentrantly" }
        block(); settle()
        return effects.toList().also { effects.clear() }
    }
}
