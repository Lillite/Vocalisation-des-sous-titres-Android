package fr.soustitresvoix.core

import java.util.ArrayDeque

/** Single-threaded deterministic controller. Apply returned effects in order. */
class NarrationMachine(private val maxPendingChars: Int = 24_000) {
    enum class Mode { COMFORT, CONTINUOUS, ADAPTIVE }
    enum class ResumePolicy { REPLICA, SEGMENT, EXPLICIT }
    sealed class Effect {
        data class Speak(val id: String, val text: String) : Effect()
        object StopSpeech : Effect()
        data class Video(val playing: Boolean) : Effect()
        data class Duck(val enabled: Boolean) : Effect()
        data class Message(val text: String, val error: Boolean = false) : Effect()
    }
    data class Snapshot(
        val wantsPlayback: Boolean, val videoRequested: Boolean,
        val speakingId: String?, val pendingCount: Int, val waitingForSpeech: Boolean,
        val enabled: Boolean, val mode: Mode, val voiceSuspended: Boolean = false
    )
    private data class Job(val text: String, var expired: Boolean = false,
        val group: Long = 0, val original: String = text)
    private data class Running(val id: String, val job: Job, val preview: Boolean = false)
    private val queue = ArrayDeque<Job>()
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
    private var lastSpokenText = ""
    private var lastSpokenGroup = -1L
    private var warnedImages = false
    private val effects = mutableListOf<Effect>()

    fun snapshot() = Snapshot(wantsPlayback, requestedVideo, current?.id,
        queue.size, wantsPlayback && mustHold(), enabled, mode, voiceSuspended)
    fun setSpeechReady(ready: Boolean): List<Effect> = event {
        speechReady = ready
        if (!ready) {
            interrupt(keep = true)
            if (enabled) wantsPlayback = false
        }
    }
    fun setMediaReady(ready: Boolean): List<Effect> = event {
        mediaReady = ready
        if (!ready && current?.preview != true) interrupt(keep = true)
    }
    fun setResumePolicy(policy: ResumePolicy): List<Effect> = event {
        resumePolicy = policy
        if (policy != ResumePolicy.EXPLICIT) voiceSuspended = false
    }
    fun resumeVoice(): List<Effect> { voiceSuspended = false; return play() }
    fun pendingTexts(): List<String> = queue.map { it.text }
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
        wantsPlayback = false
        val running = current
        if (running != null && !running.preview && resumePolicy == ResumePolicy.REPLICA) {
            interrupt(keep = false)
            queue.removeIf { it.group == running.job.group }
            SpeechTools.segments(running.job.original).asReversed().forEach {
                queue.addFirst(Job(it, running.job.expired, running.job.group, running.job.original))
            }
        } else interrupt(keep = current?.preview != true)
        if (running != null && !running.preview && resumePolicy == ResumePolicy.EXPLICIT) voiceSuspended = true
    }
    /** Call before seek, media replacement or subtitle track change. */
    fun reset(keepPlaybackIntent: Boolean = false): List<Effect> = event {
        val oldIntent = wantsPlayback
        interrupt(keep = false); queue.clear()
        activeTexts = emptyList(); activeIdentities = null; voiceSuspended = false
        lastFrameKey = null; lastSpokenText = ""; lastSpokenGroup = -1L
        warnedImages = false; mediaReady = false
        wantsPlayback = keepPlaybackIntent && oldIntent
    }
    fun setEnabled(value: Boolean): List<Effect> = event {
        if (enabled != value) {
            enabled = value; voiceSuspended = false
            interrupt(keep = false); queue.clear()
            if (value) {
                enqueue(activeTexts.joinToString("\n"))
                if (!speechReady) wantsPlayback = false
            }
        }
    }
    fun setMode(value: Mode): List<Effect> = event {
        if (mode != value) {
            mode = value
            if (value == Mode.CONTINUOUS) {
                interrupt(keep = false); queue.clear()
                enqueue(activeTexts.joinToString("\n"))
            }
        }
    }
    /** Stable occurrence IDs distinguish restyling from a later identical replica. */
    fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean = false,
        identities: List<String>? = null): List<Effect> = event {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val key = frameTimeUs to clean
        if (key == lastFrameKey && !hasImages) return@event
        val oldTexts = activeTexts
        val oldIdentities = activeIdentities
        if (!hasImages && identities != null && identities == oldIdentities && clean == oldTexts) return@event
        activeIdentities = identities; lastFrameKey = key; activeTexts = clean
        if (!enabled) return@event
        if (hasImages && !warnedImages) {
            warnedImages = true; wantsPlayback = false
            interrupt(keep = true)
            effects += Effect.Message("Cette piste contient des sous-titres en images. Leur lecture vocale n’est pas disponible. Choisis une piste texte ou ajoute un fichier SRT, VTT ou ASS.", true)
        }
        current?.takeUnless { it.preview }?.job?.expired = true
        queue.forEach { it.expired = true }
        if (mode == Mode.CONTINUOUS) { interrupt(keep = false); queue.clear() }
        val incoming = if (identities != null && oldIdentities != null && identities.size == clean.size) {
            clean.filterIndexed { i, t -> identities[i] !in oldIdentities || t !in oldTexts }
        } else if (clean == oldTexts) clean else {
            val remaining = oldTexts.toMutableList()
            clean.filter { !remaining.remove(it) }
        }
        enqueue(incoming.joinToString("\n"))
    }
    fun speechFinished(id: String): List<Effect> = event { if (current?.id == id) current = null }
    fun speechFailed(id: String, detail: String): List<Effect> = event {
        if (current?.id != id) return@event
        wantsPlayback = false; interrupt(keep = true)
        effects += Effect.Message("Lecture vocale interrompue : $detail", true)
    }
    fun fail(detail: String): List<Effect> = event {
        wantsPlayback = false; interrupt(keep = false); queue.clear()
        effects += Effect.Message(detail, true)
    }
    /** Repeating a whole replica consumes its pending segments, not other replicas. */
    fun preview(text: String = lastSpokenText): List<Effect> = event {
        wantsPlayback = false
        val repeatGroup = if (text == lastSpokenText) lastSpokenGroup else -1L
        interrupt(keep = current?.job?.group != repeatGroup)
        if (repeatGroup >= 0) queue.removeIf { it.group == repeatGroup }
        voiceSuspended = false
        if (!speechReady) {
            effects += Effect.Message("Aucune voix disponible. Ouvre les réglages vocaux Android.", true); return@event
        }
        if (text.isBlank()) { effects += Effect.Message("Aucune phrase à répéter."); return@event }
        if (text.length > 3_000) {
            effects += Effect.Message("Cette phrase est trop longue pour un essai vocal.", true); return@event
        }
        val running = Running(nextId(), Job(text), preview = true)
        current = running; effects += Effect.Speak(running.id, text)
    }
    private fun enqueue(text: String) {
        if (text.isBlank()) return
        val total = queue.sumOf { it.text.length } + (current?.job?.text?.length ?: 0) + text.length
        if (total > maxPendingChars) {
            wantsPlayback = false; interrupt(keep = false); queue.clear()
            effects += Effect.Message("Trop de texte reçu à la fois. La lecture est arrêtée plutôt que de perdre silencieusement des sous-titres.", true)
            return
        }
        val group = ++groupSequence
        SpeechTools.segments(text).forEach { queue.addLast(Job(it, group = group, original = text)) }
    }
    private fun interrupt(keep: Boolean) {
        val running = current ?: return
        current = null
        if (keep && !running.preview) queue.addFirst(running.job)
        effects += Effect.StopSpeech
    }
    private fun mustHold(): Boolean = enabled && mode != Mode.CONTINUOUS &&
        (current?.job?.expired == true || queue.any { it.expired })
    private fun settle() {
        if (current == null && !voiceSuspended && enabled && speechReady && mediaReady && wantsPlayback && queue.isNotEmpty()) {
            val job = queue.removeFirst(); val running = Running(nextId(), job)
            current = running; lastSpokenText = job.original; lastSpokenGroup = job.group
            effects += Effect.Speak(running.id, job.text)
        }
        val video = wantsPlayback && !mustHold() && current?.preview != true
        val duck = current != null
        if (video != requestedVideo) { requestedVideo = video; effects += Effect.Video(video) }
        if (duck != requestedDuck) { requestedDuck = duck; effects += Effect.Duck(duck) }
    }
    private fun nextId(): String = "subtitle-${++sequence}"
    private inline fun event(block: () -> Unit): List<Effect> {
        check(effects.isEmpty()) { "State machine must not be called reentrantly" }
        block(); settle()
        return effects.toList().also { effects.clear() }
    }
}
