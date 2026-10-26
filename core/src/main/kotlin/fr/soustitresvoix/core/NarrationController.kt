package fr.soustitresvoix.core

/**
 * Session boundary for narration. Calls and their returned effects are synchronous
 * and ordered; the owner must apply every effect in order on its session thread.
 * The existing machine remains the owner of occurrence identity and resume rules.
 */
interface NarrationController {
    fun setNarrationLanguage(languageTag: String?) = Unit
    fun setJournalContext(contextId: String) = Unit
    fun registerOccurrences(occurrences: List<NarrationLedger.Occurrence>) = Unit
    fun markPrepared(identities: List<String>) = Unit
    fun markFiltered(identities: List<String>) = Unit
    fun journalSnapshot(): NarrationLedger.Snapshot = NarrationLedger().snapshot()
    fun exportJournalMetadata(): String = ""
    fun restoreJournalMetadata(encoded: String): Boolean = false
    fun replayOccurrence(identity: String, text: String): List<NarrationMachine.Effect> = emptyList()
    fun setComprehensionHold(value: Boolean): List<NarrationMachine.Effect> = emptyList()
    fun snapshot(): NarrationMachine.Snapshot
    fun setSpeechReady(ready: Boolean): List<NarrationMachine.Effect>
    fun setMediaReady(ready: Boolean): List<NarrationMachine.Effect>
    fun setResumePolicy(policy: NarrationMachine.ResumePolicy): List<NarrationMachine.Effect>
    fun resumeVoice(): List<NarrationMachine.Effect>
    fun pendingTexts(): List<String>
    fun pendingPlan(): List<NarrationMachine.PlannedReplica>
    fun play(): List<NarrationMachine.Effect>
    fun pause(): List<NarrationMachine.Effect>
    fun reset(keepPlaybackIntent: Boolean = false): List<NarrationMachine.Effect>
    fun setEnabled(value: Boolean): List<NarrationMachine.Effect>
    fun setMode(value: NarrationMachine.Mode): List<NarrationMachine.Effect>
    fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean = false,
        identities: List<String>? = null, deadlinesMs: List<Long?>? = null): List<NarrationMachine.Effect>
    fun advance(positionMs: Long): List<NarrationMachine.Effect>
    fun speechFinished(id: String): List<NarrationMachine.Effect>
    fun speechFailed(id: String, detail: String): List<NarrationMachine.Effect>
    fun fail(detail: String): List<NarrationMachine.Effect>
    fun preview(): List<NarrationMachine.Effect>
    fun preview(text: String): List<NarrationMachine.Effect>
}

/** Pure delegation: no filtering, scheduling, identity conversion or policy change. */
class ExistingNarrationController(
    private val machine: NarrationMachine = NarrationMachine()
) : NarrationController {
    override fun setNarrationLanguage(languageTag: String?) { machine.languageTag = languageTag }
    override fun setJournalContext(contextId: String) = machine.setJournalContext(contextId)
    override fun registerOccurrences(occurrences: List<NarrationLedger.Occurrence>) = machine.registerOccurrences(occurrences)
    override fun markPrepared(identities: List<String>) = machine.markPrepared(identities)
    override fun markFiltered(identities: List<String>) = machine.markFiltered(identities)
    override fun journalSnapshot() = machine.journalSnapshot()
    override fun exportJournalMetadata() = machine.exportJournalMetadata()
    override fun restoreJournalMetadata(encoded: String) = machine.restoreJournalMetadata(encoded)
    override fun replayOccurrence(identity: String, text: String) = machine.replayOccurrence(identity, text)
    override fun setComprehensionHold(value: Boolean) = machine.setComprehensionHold(value)
    override fun snapshot() = machine.snapshot()
    override fun setSpeechReady(ready: Boolean) = machine.setSpeechReady(ready)
    override fun setMediaReady(ready: Boolean) = machine.setMediaReady(ready)
    override fun setResumePolicy(policy: NarrationMachine.ResumePolicy) = machine.setResumePolicy(policy)
    override fun resumeVoice() = machine.resumeVoice()
    override fun pendingTexts() = machine.pendingTexts()
    override fun pendingPlan() = machine.pendingPlan()
    override fun play() = machine.play()
    override fun pause() = machine.pause()
    override fun reset(keepPlaybackIntent: Boolean) = machine.reset(keepPlaybackIntent)
    override fun setEnabled(value: Boolean) = machine.setEnabled(value)
    override fun setMode(value: NarrationMachine.Mode) = machine.setMode(value)
    override fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean,
        identities: List<String>?, deadlinesMs: List<Long?>?) =
        machine.cues(frameTimeUs, texts, hasImages, identities, deadlinesMs)
    override fun advance(positionMs: Long) = machine.advance(positionMs)
    override fun speechFinished(id: String) = machine.speechFinished(id)
    override fun speechFailed(id: String, detail: String) = machine.speechFailed(id, detail)
    override fun fail(detail: String) = machine.fail(detail)
    override fun preview() = machine.preview()
    override fun preview(text: String) = machine.preview(text)
}
