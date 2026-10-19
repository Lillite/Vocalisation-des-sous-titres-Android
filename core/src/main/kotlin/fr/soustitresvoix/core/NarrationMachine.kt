package fr.soustitresvoix.core

import java.util.ArrayDeque

/**
 * Deterministic single-thread state machine. It never calls Android or a TTS engine.
 * The Android adapter must deliver all events on the main thread, and execute
 * returned effects in order. Utterance IDs are never reused, even after a seek.
 */
class NarrationMachine(private val maxPendingChars: Int = 24_000) {
    enum class Mode { COMFORT, CONTINUOUS }
    sealed class Effect {
        data class Speak(val id: String, val text: String) : Effect()
        object StopSpeech : Effect()
        data class Video(val playing: Boolean) : Effect()
        data class Duck(val enabled: Boolean) : Effect()
        data class Message(val text: String, val error: Boolean = false) : Effect()
    }
    data class Snapshot(
        val wantsPlayback: Boolean,
        val videoRequested: Boolean,
        val speakingId: String?,
        val pendingCount: Int,
        val waitingForSpeech: Boolean,
        val enabled: Boolean,
        val mode: Mode
    )
    private data class Job(val text: String, var expired: Boolean = false)
    private data class Running(val id: String, val job: Job, val preview: Boolean = false)
    private val queue = ArrayDeque<Job>()
    private var current: Running? = null
    private var sequence = 0L
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
    private var warnedImages = false
    private val effects = mutableListOf<Effect>()

    fun snapshot() = Snapshot(wantsPlayback, requestedVideo, current?.id,
        queue.size, wantsPlayback && mustHold(), enabled, mode)

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

    fun play(): List<Effect> = event {
        // A preview must never continue as an ordinary film subtitle.
        if (current?.preview == true) interrupt(keep = false)
        if (enabled && !speechReady) {
            wantsPlayback = false
            effects += Effect.Message("La voix n’est pas prête. Choisis une voix installée ou désactive la vocalisation.", true)
        } else {
            wantsPlayback = true
        }
    }

    fun pause(): List<Effect> = event {
        wantsPlayback = false
        interrupt(keep = current?.preview != true)
    }

    /** Called BEFORE a seek, media replacement, or subtitle-track switch. */
    fun reset(keepPlaybackIntent: Boolean = false): List<Effect> = event {
        val oldIntent = wantsPlayback
        interrupt(keep = false)
        queue.clear()
        activeTexts = emptyList()
        lastFrameKey = null
        lastSpokenText = ""
        warnedImages = false
        mediaReady = false
        wantsPlayback = keepPlaybackIntent && oldIntent
    }

    fun setEnabled(value: Boolean): List<Effect> = event {
        if (enabled != value) {
            enabled = value
            interrupt(keep = false)
            queue.clear()
            if (value) {
                enqueue(activeTexts.joinToString("\n"))
                if (!speechReady) wantsPlayback = false
            }
        }
    }

    fun setMode(value: Mode): List<Effect> = event {
        if (mode != value) {
            mode = value
            // Do not retain stale comfort-mode captions in continuous mode.
            if (value == Mode.CONTINUOUS) {
                interrupt(keep = false)
                queue.clear()
                enqueue(activeTexts.joinToString("\n"))
            }
        }
    }

    /**
     * frameTimeUs is the timestamp supplied by Media3's CueGroup, not wall time.
     * Identical callbacks are ignored; the same sentence at a different timestamp
     * remains a different occurrence and may legitimately be spoken again.
     */
    fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean = false): List<Effect> = event {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val key = frameTimeUs to clean
        if (key == lastFrameKey && !hasImages) return@event
        val oldTexts = activeTexts
        lastFrameKey = key
        activeTexts = clean
        if (!enabled) return@event
        if (hasImages && !warnedImages) {
            warnedImages = true
            wantsPlayback = false
            interrupt(keep = true)
            effects += Effect.Message(
                "Cette piste contient des sous-titres en images. Leur lecture vocale n’est pas disponible. Choisis une piste texte ou ajoute un fichier SRT, VTT ou ASS.", true)
        }
        // Every actual frame boundary expires the preceding utterances.
        current?.takeUnless { it.preview }?.job?.expired = true
        queue.forEach { it.expired = true }
        if (mode == Mode.CONTINUOUS) {
            interrupt(keep = false)
            queue.clear()
        }
        // Keep unchanged overlapping cues from being re-read. A frame consisting
        // of exactly the same texts at a NEW timestamp is a new occurrence.
        val incoming = if (clean == oldTexts) clean else clean.filterNot { it in oldTexts }
        enqueue(incoming.joinToString("\n"))
    }

    fun speechFinished(id: String): List<Effect> = event {
        if (current?.id == id) current = null
    }

    fun speechFailed(id: String, detail: String): List<Effect> = event {
        if (current?.id != id) return@event
        wantsPlayback = false
        // Preserve the failed sentence for an EXPLICIT retry after voice settings
        // are fixed. Never loop/retry automatically against a failing engine.
        interrupt(keep = true)
        effects += Effect.Message("Lecture vocale interrompue : $detail", true)
    }

    fun fail(detail: String): List<Effect> = event {
        wantsPlayback = false
        interrupt(keep = false)
        queue.clear()
        effects += Effect.Message(detail, true)
    }

    /** Preview/repeat deliberately leaves the film paused on completion. */
    fun preview(text: String = lastSpokenText): List<Effect> = event {
        wantsPlayback = false
        // Repeating the interrupted film sentence consumes that pending copy;
        // testing a DIFFERENT voice sample preserves it for later playback.
        interrupt(keep = current?.job?.text != text)
        if (!speechReady) {
            effects += Effect.Message("Aucune voix disponible. Ouvre les réglages vocaux Android.", true)
            return@event
        }
        if (text.isBlank()) {
            effects += Effect.Message("Aucune phrase à répéter.")
            return@event
        }
        // A preview is bounded rather than silently truncated.
        if (text.length > 3_000) {
            effects += Effect.Message("Cette phrase est trop longue pour un essai vocal.", true)
            return@event
        }
        val running = Running(nextId(), Job(text), preview = true)
        current = running
        effects += Effect.Speak(running.id, text)
    }

    private fun enqueue(text: String) {
        if (text.isBlank()) return
        val total = queue.sumOf { it.text.length } + (current?.job?.text?.length ?: 0) + text.length
        if (total > maxPendingChars) {
            wantsPlayback = false
            interrupt(keep = false)
            queue.clear()
            effects += Effect.Message("Trop de texte reçu à la fois. La lecture est arrêtée plutôt que de perdre silencieusement des sous-titres.", true)
            return
        }
        SubtitleText.splitForSpeech(text, 1_000).forEach { queue.addLast(Job(it)) }
    }

    private fun interrupt(keep: Boolean) {
        val running = current ?: return
        // Invalidate first: stop() may synchronously provoke an engine callback.
        current = null
        if (keep && !running.preview) queue.addFirst(running.job)
        effects += Effect.StopSpeech
    }

    private fun mustHold(): Boolean = enabled && mode == Mode.COMFORT &&
        (current?.job?.expired == true || queue.any { it.expired })

    private fun settle() {
        if (current == null && enabled && speechReady && mediaReady && wantsPlayback && queue.isNotEmpty()) {
            val job = queue.removeFirst()
            val running = Running(nextId(), job)
            current = running
            lastSpokenText = job.text
            effects += Effect.Speak(running.id, job.text)
        }
        val video = wantsPlayback && !mustHold() && current?.preview != true
        val duck = current != null
        if (video != requestedVideo) {
            requestedVideo = video
            effects += Effect.Video(video)
        }
        if (duck != requestedDuck) {
            requestedDuck = duck
            effects += Effect.Duck(duck)
        }
    }

    private fun nextId(): String = "subtitle-${++sequence}"
    private inline fun event(block: () -> Unit): List<Effect> {
        check(effects.isEmpty()) { "State machine must not be called reentrantly" }
        block()
        settle()
        return effects.toList().also { effects.clear() }
    }
}
