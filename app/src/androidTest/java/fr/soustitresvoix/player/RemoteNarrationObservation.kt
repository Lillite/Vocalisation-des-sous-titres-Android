package fr.soustitresvoix.player

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationLedger
import fr.soustitresvoix.core.NarrationMachine
import org.junit.Assert.*
import java.io.File

/** Observes the real default components without changing commands or callbacks.
 * Requests, interrupted attempts and completed playback are separate evidence.
 * This helper is used exclusively with the versioned synthetic demonstration. */
@UnstableApi
internal class RemoteNarrationObservation {
    private data class Attempt(
        val id: String, val text: String, val remaining: String, val occurrence: String,
        val generation: Long, val started: Int, val interruptionsBefore: Int,
        var stopped: Int? = null, var interruptionsAfter: Int? = null, var done: Int? = null
    ) {
        // Remaining text identifies the position inside a multi-segment source
        // occurrence, including two identical segments in that same occurrence.
        val job get() = Triple(occurrence, text, remaining)
    }
    private val events = mutableListOf<String>()
    private val attempts = linkedMapOf<String, Attempt>()
    private val completed = mutableListOf<Attempt>()
    private val failures = mutableListOf<String>()
    private val violations = mutableListOf<String>()
    private var runningId: String? = null
    private var session: PlaybackSession? = null
    private var narration: NarrationController? = null

    fun attach(value: PlaybackSession) { session = value; event("session.attached") }
    fun detach() { session = null }

    fun event(label: String): Int {
        val current = session
        val state = current?.machine?.snapshot()
        val player = current?.player
        val ledger = narration?.journalSnapshot()
        val detail = buildString {
            append(events.size).append(' ').append(SystemClock.elapsedRealtime()).append(' ').append(label)
            if (player != null) append(" player=").append(player.playbackState)
                .append(" positionMs=").append(player.currentPosition)
                .append(" playWhenReady=").append(player.playWhenReady)
                .append(" bufferedMs=").append(player.totalBufferedDuration)
            if (state != null) append(" machine=").append(state)
            if (ledger != null) {
                append(" generation=").append(ledger.generation).append(" revision=").append(ledger.revision)
                append(" ledger=").append(ledger.entries.joinToString(";") {
                    "${it.identityHash}:${it.state}:a${it.attempts}:c${it.completions}:i${it.interruptions}:e${it.errors}"
                })
            }
        }
        events += detail
        return events.lastIndex
    }

    val components: PlaybackComponents = object : PlaybackComponents by DefaultPlaybackComponents {
        override fun createNarration(): NarrationController {
            val delegate = ExistingNarrationController()
            narration = delegate
            return object : NarrationController by delegate {
                private fun observe(label: String, call: () -> List<NarrationMachine.Effect>): List<NarrationMachine.Effect> {
                    val effects = call()
                    event("controller.$label effects=$effects")
                    return effects
                }
                override fun setMediaReady(ready: Boolean) = observe("mediaReady($ready)") { delegate.setMediaReady(ready) }
                override fun setSpeechReady(ready: Boolean) = observe("speechReady($ready)") { delegate.setSpeechReady(ready) }
                override fun play() = observe("play") { delegate.play() }
                override fun pause() = observe("pause") { delegate.pause() }
                override fun reset(keepPlaybackIntent: Boolean) = observe("reset($keepPlaybackIntent)") { delegate.reset(keepPlaybackIntent) }
                override fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean,
                    identities: List<String>?, deadlinesMs: List<Long?>?) = observe(
                    "cues($frameTimeUs,identities=${identities?.map(NarrationLedger::hashIdentity)})") {
                    delegate.cues(frameTimeUs, texts, hasImages, identities, deadlinesMs)
                }
                override fun speechFinished(id: String) = observe("speechFinished($id)") { delegate.speechFinished(id) }
                override fun speechFailed(id: String, detail: String) = observe("speechFailed($id,$detail)") { delegate.speechFailed(id, detail) }
                override fun fail(detail: String) = observe("fail($detail)") { delegate.fail(detail) }
            }
        }

        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService {
            val delegate = DefaultPlaybackComponents.createSpeech(context, preferences,
                onAvailability = { ready, message -> event("speech.availability($ready)"); onAvailability(ready, message) },
                onDone = { id ->
                    val order = event("speech.done($id)")
                    val attempt = attempts[id]
                    if (attempt == null) violations += "Completion without an observed request: $id"
                    else {
                        if (attempt.done != null) violations += "The same utterance completed twice: $id"
                        if (attempt.stopped != null) violations += "An explicitly stopped attempt completed: $id"
                        attempt.done = order; completed += attempt
                    }
                    if (runningId == id) runningId = null
                    onDone(id)
                },
                onFailure = { id, message ->
                    event("speech.failure($id,$message)"); failures += "$id:$message"
                    if (runningId == id) runningId = null
                    onFailure(id, message)
                })
            return object : SpeechService by delegate {
                override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
                    remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) {
                    if (runningId != null) violations += "A new request replaced unfinished attempt $runningId"
                    val ledger = requireNotNull(narration).journalSnapshot()
                    val entry = ledger.current
                    if (entry == null) violations += "Speech request without a started source occurrence: $id"
                    val order = event("speech.speak($id,text=$text,remaining=$remaining)")
                    if (attempts.containsKey(id)) violations += "An utterance ID was reused: $id"
                    attempts[id] = Attempt(id, text, remaining, entry?.identityHash.orEmpty(), ledger.generation, order, entry?.interruptions ?: -1)
                    runningId = id
                    delegate.speak(id, text, availableMs, adaptive, remaining, forecast, budget, planning)
                }
                override fun stop() {
                    val order = event("speech.stop($runningId)")
                    runningId?.let { id ->
                        val attempt = requireNotNull(attempts[id])
                        attempt.stopped = order
                        attempt.interruptionsAfter = narration?.journalSnapshot()?.entries
                            ?.firstOrNull { it.identityHash == attempt.occurrence }?.interruptions
                    }
                    runningId = null
                    delegate.stop()
                }
            }
        }
    }

    fun observePlayer(value: PlaybackSession) {
        value.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { event("player.state($state)") }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { event("player.playWhenReady($playWhenReady,reason=$reason)") }
            override fun onIsPlayingChanged(isPlaying: Boolean) { event("player.isPlaying($isPlaying)") }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                event("player.discontinuity(reason=$reason,oldMs=${oldPosition.positionMs},newMs=${newPosition.positionMs})")
            }
        })
        value.playback.addAnalyticsListener(object : AnalyticsListener {
            override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) { event("analytics.loading($isLoading)") }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) { event("analytics.state($state)") }
            override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) { event("analytics.discontinuity(reason=$reason,oldMs=${oldPosition.positionMs},newMs=${newPosition.positionMs})") }
        })
    }

    fun assertComplete(expectedTexts: List<String>, sourceIndices: List<Int>, sourceHashes: List<String>, rawRequests: List<String>) {
        assertTrue("Invalid speech event ordering: $violations", violations.isEmpty())
        assertTrue("Playback callbacks failed: $failures", failures.isEmpty())
        assertEquals("Every completed segment must retain its source occurrence and order",
            expectedTexts.indices.map { sourceHashes[sourceIndices[it]] to expectedTexts[it] },
            completed.map { it.occurrence to it.text })
        assertEquals("Every real service request must have an observed live speech attempt",
            attempts.values.map { it.text }, rawRequests)
        assertEquals("Each playback completion must have its own utterance ID", completed.size, completed.map { it.id }.toSet().size)
        assertEquals("A source speech job completed more than once", completed.size, completed.map { it.job }.toSet().size)
        assertNull("An attempt remains active", runningId)
        val all = attempts.values.toList()
        assertTrue("The explicit pause must interrupt the initial Bonjour", all.size >= 2)
        assertEquals(expectedTexts.first(), all[0].text)
        assertEquals(all[0].job, all[1].job)
        assertNotNull("The initial request must actually have been stopped", all[0].stopped)
        for ((index, attempt) in all.withIndex()) {
            if (attempt.stopped != null) {
                assertNull("An interrupted request must not also complete", attempt.done)
                assertTrue("A retry needs a real controller interruption: ${attempt.id}",
                    (attempt.interruptionsAfter ?: -1) > attempt.interruptionsBefore)
                val retry = all.drop(index + 1).firstOrNull { it.job == attempt.job }
                assertNotNull("An interrupted source job was not retried: ${attempt.id}", retry)
                assertTrue("The old attempt must stop before its retry", requireNotNull(attempt.stopped) < requireNotNull(retry).started)
                assertEquals("An internal retry must stay in the same source generation", attempt.generation, retry.generation)
            } else assertNotNull("An attempt neither stopped nor completed: ${attempt.id}", attempt.done)
            val previous = all.take(index).lastOrNull { it.job == attempt.job }
            if (previous != null) {
                assertNull("A completed source job was requested again", previous.done)
                assertNotNull("A repeated request needs an observed stop", previous.stopped)
            }
        }
        val ledger = requireNotNull(narration).journalSnapshot()
        assertEquals("All eight distinct source occurrences must complete once", sourceHashes.toSet(),
            ledger.entries.filter { it.completions > 0 }.map { it.identityHash }.toSet())
        assertTrue("A source occurrence completed more than once", ledger.entries.all { it.completions == 1 && it.errors == 0 })
    }

    /** UTP collects its additional-output directory; the device runner also
     * pulls the existing synthetic screenshot directory on failure or success. */
    fun writeDiagnostics() {
        val text = events.joinToString("\n", postfix = "\n")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (output != null) runCatching { File(output).apply { mkdirs() }.resolve("remote-narration-trace.txt").writeText(text) }
            .onFailure { Log.e("RemoteNarrationTrace", "Could not write UTP trace", it) }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            val descriptors = instrument.uiAutomation.executeShellCommandRw(
                "sh -c 'mkdir -p /sdcard/Download/cinema03-tests && cat > /sdcard/Download/cinema03-tests/remote-narration-trace.txt'")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
        }.onFailure { Log.e("RemoteNarrationTrace", "Could not export synthetic trace", it) }
        // Preserve useful evidence in per-test logcat even if file collection fails.
        events.forEach { line -> line.chunked(3000).forEach { Log.i("RemoteNarrationTrace", it) } }
    }
}
