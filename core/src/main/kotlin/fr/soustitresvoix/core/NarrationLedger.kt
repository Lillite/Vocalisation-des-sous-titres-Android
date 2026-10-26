package fr.soustitresvoix.core

import java.security.MessageDigest

/**
 * Bounded, text-free account of source occurrences. Keys are hashes even when an
 * extractor's source identity contains subtitle text. Owned by the session thread.
 * A completed occurrence is counted once; replay attempts remain separately visible.
 */
class NarrationLedger(
    private val capacity: Int = 100_000,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    enum class State { DETECTED, PREPARED, QUEUED, STARTED, COMPLETED, INTERRUPTED, ERROR, FILTERED }
    data class Occurrence(val identity: String, val startUs: Long? = null, val endUs: Long? = null)
    data class Entry(
        val identityHash: String, val startUs: Long?, val endUs: Long?, val state: State,
        val attempts: Int = 0, val completions: Int = 0, val interruptions: Int = 0,
        val errors: Int = 0, val generation: Long = 0, val updatedAtMillis: Long = 0
    )
    data class Transition(val identityHash: String, val state: State, val generation: Long, val atMillis: Long)
    data class Snapshot(
        val contextHash: String, val generation: Long, val entries: List<Entry>,
        val transitions: List<Transition>, val capacityExceeded: Boolean,
        val revision: Long = 0,
        val lastCompletedHash: String? = null, val lastInterruptedHash: String? = null
    ) {
        val detectedCount get() = entries.size
        val completedCount get() = entries.count { it.completions > 0 }
        val interruptedCount get() = entries.count { it.state == State.INTERRUPTED }
        val errorCount get() = entries.count { it.state == State.ERROR }
        val pendingCount get() = entries.count { it.state in setOf(State.QUEUED, State.STARTED) }
        val filteredCount get() = entries.count { it.state == State.FILTERED }
        val lastCompleted get() = entries.firstOrNull { it.identityHash == lastCompletedHash }
        val lastInterrupted get() = entries.firstOrNull { it.identityHash == lastInterruptedHash }
        val current get() = entries.lastOrNull { it.state == State.STARTED }
    }

    private var contextHash = hashIdentity("unassigned")
    private var generation = 0L
    private var revision = 0L
    private var capacityExceeded = false
    private var lastCompletedHash: String? = null
    private var lastInterruptedHash: String? = null
    private val entries = LinkedHashMap<String, Entry>()
    private val transitions = ArrayDeque<Transition>()
    init { require(capacity in 1..100_000) }
    val currentGeneration get() = generation
    val currentContextHash get() = contextHash

    fun setContext(identity: String) {
        val hash = hashIdentity(identity)
        if (hash == contextHash) return
        contextHash = hash; generation = 0; capacityExceeded = false
        entries.clear(); transitions.clear(); lastCompletedHash = null; lastInterruptedHash = null
        advanceRevision()
    }
    fun nextGeneration() { if (generation < Long.MAX_VALUE) generation++; advanceRevision() }
    fun snapshot() = Snapshot(contextHash, generation, entries.values.toList(), transitions.toList(), capacityExceeded, revision,
        lastCompletedHash, lastInterruptedHash)
    fun entry(identity: String): Entry? = entries[hashIdentity(identity)]
    fun register(occurrence: Occurrence): Boolean {
        require(occurrence.identity.isNotBlank())
        require(occurrence.startUs == null || occurrence.endUs == null || occurrence.endUs >= occurrence.startUs)
        val key = hashIdentity(occurrence.identity)
        val previous = entries[key]
        if (previous != null) {
            val updated = previous.copy(startUs = previous.startUs ?: occurrence.startUs,
                endUs = previous.endUs ?: occurrence.endUs)
            if (updated != previous) { entries[key] = updated; advanceRevision() }
            return true
        }
        if (entries.size >= capacity) {
            if (!capacityExceeded) { capacityExceeded = true; advanceRevision() }
            return false
        }
        val now = clockMillis().coerceAtLeast(0)
        entries[key] = Entry(key, occurrence.startUs, occurrence.endUs, State.DETECTED,
            generation = generation, updatedAtMillis = now)
        advanceRevision()
        trace(key, State.DETECTED, now)
        return true
    }
    fun prepared(identity: String) = update(identity, State.PREPARED, onlyBeforeQueue = true)
    fun queued(identity: String) = update(identity, State.QUEUED)
    fun started(identity: String) = update(identity, State.STARTED)
    fun completed(identity: String) = update(identity, State.COMPLETED)
    fun interrupted(identity: String) = update(identity, State.INTERRUPTED)
    fun error(identity: String) = update(identity, State.ERROR)
    fun filtered(identity: String) = update(identity, State.FILTERED, onlyBeforeQueue = true)

    private fun update(identity: String, state: State, onlyBeforeQueue: Boolean = false) {
        if (!register(Occurrence(identity))) return
        val key = hashIdentity(identity); val previous = requireNotNull(entries[key])
        if (onlyBeforeQueue && previous.state !in setOf(State.DETECTED, State.PREPARED)) return
        if (previous.state == state) return
        val now = clockMillis().coerceAtLeast(0)
        // Keep event order for diagnostics; replay targets have their own chronology.
        if (state == State.COMPLETED) {
            lastCompletedHash = key
            if (lastInterruptedHash == key) lastInterruptedHash = null
        } else if (state in setOf(State.INTERRUPTED, State.ERROR) && previous.state == State.STARTED) {
            // Dropping an unstarted queued occurrence is visible in the ledger,
            // but must never replace the phrase that was actually speaking.
            lastInterruptedHash = key
        }
        entries.remove(key)
        entries[key] = previous.copy(state = state,
            attempts = increment(previous.attempts, state == State.STARTED),
            completions = increment(previous.completions, state == State.COMPLETED),
            interruptions = increment(previous.interruptions, state == State.INTERRUPTED),
            errors = increment(previous.errors, state == State.ERROR),
            generation = generation, updatedAtMillis = now)
        advanceRevision()
        trace(key, state, now)
    }
    private fun increment(value: Int, condition: Boolean) = if (condition && value < Int.MAX_VALUE) value + 1 else value
    private fun advanceRevision() { if (revision < Long.MAX_VALUE) revision++ }
    private fun trace(key: String, state: State, now: Long) {
        if (transitions.size == 4096) transitions.removeFirst()
        transitions.addLast(Transition(key, state, generation, now))
    }

    /** Versioned metadata only: source IDs and source/translated text never leave this ledger. */
    fun exportMetadata(): String = buildString {
        append("NARRATION-LEDGER-2\n").append(contextHash).append('\n')
        append(generation).append('\t').append(if (capacityExceeded) 1 else 0)
            .append('\t').append(lastCompletedHash ?: "-")
            .append('\t').append(lastInterruptedHash ?: "-").append('\n')
        for (entry in entries.values) with(entry) {
            append(identityHash).append('\t').append(startUs?.toString() ?: "-")
                .append('\t').append(endUs?.toString() ?: "-")
                .append('\t').append(state.name).append('\t').append(attempts).append('\t').append(completions)
                .append('\t').append(interruptions).append('\t').append(errors).append('\t').append(generation)
                .append('\t').append(updatedAtMillis).append('\n')
        }
    }

    /** Atomic import; running/pending speech is marked interrupted, never auto-resumed. */
    fun restoreMetadata(encoded: String): Boolean {
        if (encoded.length > 24_000_000) return false
        return try {
            val lines = encoded.lineSequence().iterator()
            require(lines.hasNext())
            val version = lines.next()
            require(version in setOf("NARRATION-LEDGER-1", "NARRATION-LEDGER-2"))
            require(lines.hasNext() && lines.next() == contextHash)
            val header = lines.next().split('\t')
            require(header.size == if (version == "NARRATION-LEDGER-2") 4 else 2)
            val restoredGeneration = header[0].toLong().also { require(it >= 0) }
            val exceeded = header[1].toInt().also { require(it in 0..1) } == 1
            fun pointer(index: Int) = header[index].takeUnless { it == "-" }?.also {
                require(it.matches(Regex("[0-9a-f]{64}")))
            }
            var completedPointer = if (header.size == 4) pointer(2) else null
            var interruptedPointer = if (header.size == 4) pointer(3) else null
            val restored = LinkedHashMap<String, Entry>()
            var runningPointer: String? = null
            var legacyCompletionAmbiguous = false
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.isEmpty()) continue
                require(restored.size < capacity)
                val fields = line.split('\t'); require(fields.size == 10)
                val key = fields[0]; require(key.matches(Regex("[0-9a-f]{64}")) && key !in restored)
                fun time(index: Int) = fields[index].takeUnless { it == "-" }?.toLong()
                fun count(index: Int) = fields[index].toInt().also { require(it >= 0) }
                val start = time(1); val end = time(2)
                require(start == null || end == null || end >= start)
                val state = State.valueOf(fields[3])
                val entryGeneration = fields[8].toLong().also { require(it in 0..restoredGeneration) }
                val updated = fields[9].toLong().also { require(it >= 0) }
                if (state == State.STARTED) runningPointer = key
                val completions = count(5)
                if (header.size == 2 && completions > 0) {
                    if (state != State.COMPLETED) legacyCompletionAmbiguous = true
                    else completedPointer = key
                }
                val pending = state in setOf(State.QUEUED, State.STARTED)
                val interruptions = count(6)
                restored[key] = Entry(key, start, end, if (pending) State.INTERRUPTED else state,
                    count(4), completions, increment(interruptions, pending), count(7), entryGeneration, updated)
            }
            if (header.size == 4) {
                completedPointer?.let { require((restored[it]?.completions ?: 0) > 0) }
                interruptedPointer?.let {
                    val entry = requireNotNull(restored[it])
                    require(entry.attempts > 0 && (entry.interruptions > 0 || entry.errors > 0))
                    require(entry.state != State.COMPLETED)
                }
            } else if (legacyCompletionAmbiguous) completedPointer = null
            // v1 did not distinguish queue abandonment from spoken interruption.
            // Preserve its counts but do not invent a replay target. A STARTED
            // record, in either version, is explicit evidence of process death.
            if (runningPointer != null) interruptedPointer = runningPointer
            entries.clear(); entries.putAll(restored); transitions.clear()
            lastCompletedHash = completedPointer; lastInterruptedHash = interruptedPointer
            generation = restoredGeneration; capacityExceeded = exceeded
            advanceRevision()
            true
        } catch (_: IllegalArgumentException) { false }
          catch (_: NoSuchElementException) { false }
    }

    companion object {
        fun hashIdentity(identity: String): String = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
