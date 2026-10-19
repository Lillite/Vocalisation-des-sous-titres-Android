package fr.soustitresvoix.core

import fr.soustitresvoix.core.NarrationMachine.Effect
import fr.soustitresvoix.core.NarrationMachine.Mode
import java.nio.charset.Charset
import kotlin.random.Random

/** Also runnable without Android/Gradle; the exact production core is compiled. */
object CoreChecks {
    private var passed = 0
    private fun checkCase(name: String, body: () -> Unit) {
        try { body(); passed++; println("PASS $name") }
        catch (t: Throwable) { throw AssertionError("FAIL $name", t) }
    }
    private fun ready(mode: Mode = Mode.COMFORT): NarrationMachine = NarrationMachine().apply {
        setSpeechReady(true); setMediaReady(true); setMode(mode); play()
    }
    private fun List<Effect>.speech() = filterIsInstance<Effect.Speak>()
    private inline fun <reified T : Throwable> expectFailure(body: () -> Unit) {
        try { body() } catch (t: Throwable) { check(t is T) { "Expected ${T::class}, got $t" }; return }
        error("Expected ${T::class.simpleName}")
    }
    @JvmStatic fun main(args: Array<String>) { runAll(args.firstOrNull()?.toInt() ?: 42) }
    fun runAll(seed: Int = 42) {
        passed = 0
        checkCase("initial state is paused") {
            val s = NarrationMachine().snapshot(); check(!s.wantsPlayback && !s.videoRequested && s.speakingId == null)
        }
        checkCase("first subtitle speaks without pausing the film") {
            val m = ready(); val e = m.cues(1000, listOf("Bonjour."))
            check(e.speech().single().text == "Bonjour."); check(m.snapshot().videoRequested)
        }
        checkCase("comfort waits for the end of speech at cue disappearance") {
            val m = ready(); val id = m.cues(1000, listOf("Bonjour.")).speech().single().id
            m.cues(2000, emptyList()); check(m.snapshot().waitingForSpeech); check(!m.snapshot().videoRequested)
            m.speechFinished(id); check(m.snapshot().videoRequested && !m.snapshot().waitingForSpeech)
        }
        checkCase("next subtitle is not lost while an earlier one finishes") {
            val m = ready(); val id = m.cues(1, listOf("Un")).speech().single().id
            m.cues(2, listOf("Deux")); check(!m.snapshot().videoRequested)
            val e = m.speechFinished(id); check(e.speech().single().text == "Deux"); check(m.snapshot().videoRequested)
        }
        checkCase("three queued boundaries remain ordered") {
            val m = ready(); val id = m.cues(1, listOf("Un")).speech().single().id
            m.cues(2, listOf("Deux")); m.cues(3, listOf("Trois"))
            val second = m.speechFinished(id).speech().single(); check(second.text == "Deux")
            check(!m.snapshot().videoRequested)
            val third = m.speechFinished(second.id).speech().single(); check(third.text == "Trois")
            check(m.snapshot().videoRequested)
        }
        checkCase("continuous mode interrupts expired speech") {
            val m = ready(Mode.CONTINUOUS); m.cues(1, listOf("Un"))
            val e = m.cues(2, listOf("Deux")); check(Effect.StopSpeech in e)
            check(e.speech().single().text == "Deux"); check(m.snapshot().videoRequested)
        }
        checkCase("continuous mode stops at a blank subtitle frame") {
            val m = ready(Mode.CONTINUOUS); m.cues(1, listOf("Un"))
            check(Effect.StopSpeech in m.cues(2, emptyList())); check(m.snapshot().speakingId == null)
        }
        checkCase("duplicate callbacks do not speak twice") {
            val m = ready(); m.cues(1, listOf("Un")); check(m.cues(1, listOf("Un")).isEmpty())
            check(m.snapshot().pendingCount == 0)
        }
        checkCase("same text later is a legitimate new occurrence") {
            val m = ready(); val id = m.cues(1, listOf("Oui")).speech().single().id; m.speechFinished(id)
            check(m.cues(2, listOf("Oui")).speech().single().text == "Oui")
        }
        checkCase("unchanged overlapping caption is not repeated") {
            val m = ready(); val id = m.cues(1, listOf("A")).speech().single().id; m.speechFinished(id)
            check(m.cues(2, listOf("A", "B")).speech().single().text == "B")
        }
        checkCase("manual pause invalidates the old utterance") {
            val m = ready(); val id = m.cues(1, listOf("A")).speech().single().id
            check(Effect.StopSpeech in m.pause()); m.speechFinished(id)
            check(!m.snapshot().videoRequested && m.snapshot().speakingId == null)
        }
        checkCase("interrupted sentence restarts only after explicit play") {
            val m = ready(); val old = m.cues(1, listOf("A")).speech().single().id; m.pause()
            val next = m.play().speech().single(); check(next.text == "A" && next.id != old)
        }
        checkCase("seek clears old queue and invalidates callbacks") {
            val m = ready(); val id = m.cues(1, listOf("A")).speech().single().id; m.cues(2, listOf("B"))
            m.reset(true); m.speechFinished(id); check(m.snapshot().pendingCount == 0)
            m.setMediaReady(true); check(m.cues(100, listOf("C")).speech().single().text == "C")
        }
        checkCase("media replacement does not auto play") {
            val m = ready(); m.reset(false); m.setMediaReady(true); m.cues(1, listOf("A"))
            check(!m.snapshot().videoRequested && m.snapshot().speakingId == null)
        }
        checkCase("buffering stops speech but can resume when ready") {
            val m = ready(); val old = m.cues(1, listOf("A")).speech().single().id
            check(Effect.StopSpeech in m.setMediaReady(false)); check(m.snapshot().speakingId == null)
            m.speechFinished(old); val e = m.setMediaReady(true).speech().single(); check(e.text == "A" && e.id != old)
        }
        checkCase("no speech while media not ready") {
            val m = NarrationMachine(); m.setSpeechReady(true); m.play(); m.cues(1, listOf("A"))
            check(m.snapshot().speakingId == null); check(m.setMediaReady(true).speech().single().text == "A")
        }
        checkCase("missing TTS refuses automatic playback") {
            val m = NarrationMachine(); check(m.play().any { it is Effect.Message && it.error })
            check(!m.snapshot().videoRequested)
        }
        checkCase("disabled narration permits ordinary playback") {
            val m = NarrationMachine(); m.setEnabled(false); m.play(); check(m.snapshot().videoRequested)
            check(m.cues(1, listOf("A")).speech().isEmpty())
        }
        checkCase("disabling narration releases a comfort hold") {
            val m = ready(); m.cues(1, listOf("A")); m.cues(2, emptyList()); m.setEnabled(false)
            check(m.snapshot().videoRequested && m.snapshot().speakingId == null)
        }
        checkCase("re-enabling can read the currently displayed subtitle") {
            val m = ready(); m.setEnabled(false); m.cues(1, listOf("A"))
            check(m.setEnabled(true).speech().single().text == "A")
        }
        checkCase("speech errors stop film and restore audio") {
            val m = ready(); val id = m.cues(1, listOf("A")).speech().single().id
            val e = m.speechFailed(id, "test"); check(!m.snapshot().videoRequested)
            check(Effect.Duck(false) in e && e.any { it is Effect.Message && it.error })
        }
        checkCase("stale error cannot stop a new caption") {
            val m = ready(); val id = m.cues(1, listOf("A")).speech().single().id
            m.reset(true); m.setMediaReady(true); val current = m.cues(2, listOf("B")).speech().single().id
            m.speechFailed(id, "old"); check(m.snapshot().speakingId == current && m.snapshot().videoRequested)
        }
        checkCase("preview does not auto resume") {
            val m = ready(); val id = m.preview("Essai").speech().single().id
            check(!m.snapshot().videoRequested); m.speechFinished(id); check(!m.snapshot().videoRequested)
        }
        checkCase("preview preserves an interrupted movie subtitle") {
            val m = ready(); m.cues(1, listOf("Film")); val id = m.preview("Essai").speech().single().id
            m.speechFinished(id); check(m.play().speech().single().text == "Film")
        }
        checkCase("bitmap subtitle warns and pauses rather than remaining silent") {
            val m = ready(); val e = m.cues(1, emptyList(), true)
            check(e.any { it is Effect.Message && it.error }); check(!m.snapshot().wantsPlayback)
        }
        checkCase("same bitmap track warns only once per session") {
            val m = ready(); m.cues(1, emptyList(), true)
            check(m.cues(2, emptyList(), true).none { it is Effect.Message })
        }
        checkCase("bounded queue reports overflow") {
            val m = NarrationMachine(20); m.setSpeechReady(true); m.setMediaReady(true); m.play()
            val e = m.cues(1, listOf("a".repeat(21))); check(!m.snapshot().videoRequested)
            check(e.any { it is Effect.Message && it.error })
        }
        checkCase("long text is chunked without exceeding engine-safe limit") {
            val m = ready(); val e = m.cues(1, listOf("mot ".repeat(1000)))
            check(e.speech().single().text.length <= 1000 && m.snapshot().pendingCount >= 3)
        }
        checkCase("no new speech on redundant play") {
            val m = ready(); m.cues(1, listOf("A")); check(m.play().speech().isEmpty())
        }
        checkCase("generic player error clears queued captions") {
            val m = ready(); m.cues(1, listOf("A")); m.cues(2, listOf("B")); m.fail("error")
            check(m.snapshot().pendingCount == 0 && m.snapshot().speakingId == null && !m.snapshot().videoRequested)
        }
        checkCase("known formatting is removed but comparisons survive") {
            check(SubtitleText.clean("<i>Bonjour</i><br/>a < b") == "Bonjour\na < b")
        }
        checkCase("ASS tags and explicit line breaks are handled") {
            check(SubtitleText.clean("{\\an8}Salut\\Ntoi") == "Salut\ntoi")
        }
        checkCase("descriptive captions are kept by default") {
            check(SubtitleText.clean("[Musique] Bonjour") == "[Musique] Bonjour")
            check(SubtitleText.clean("[Musique] Bonjour", true) == "Bonjour")
        }
        checkCase("Unicode and RTL text are retained") {
            check(SubtitleText.clean("こんにちは\nمرحبا\n😀") == "こんにちは\nمرحبا\n😀")
        }
        checkCase("chunks do not break surrogate pairs") {
            val raw = "😀".repeat(700); val chunks = SubtitleText.splitForSpeech(raw, 999)
            check(chunks.joinToString("") == raw)
            check(chunks.all { it.length <= 999 && !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
        }
        checkCase("chunking preserves words across whitespace") {
            val raw = (1..3000).joinToString(" ") { "mot$it" }; val chunks = SubtitleText.splitForSpeech(raw, 100)
            check(chunks.joinToString(" ") == raw && chunks.all { it.length <= 100 })
        }
        checkCase("empty and whitespace-only input generates no speech") {
            check(SubtitleText.splitForSpeech(" \n\t ", 100).isEmpty())
            check(ready().cues(1, listOf(" ", "\n")).speech().isEmpty())
        }
        val srt = "1\n00:00:01,000 --> 00:00:03,000\nÉté à Paris.\n"
        checkCase("UTF-8 SRT import") {
            val r = SubtitleImport.decode(srt.toByteArray(), "exemple.srt")
            check(r.kind == SubtitleImport.Kind.SRT && r.text == srt)
        }
        checkCase("UTF-8 BOM import") {
            check(SubtitleImport.decode(("\uFEFF" + srt).toByteArray(), "s.srt").text == srt)
        }
        checkCase("UTF-16LE BOM import") {
            check(SubtitleImport.decode(("\uFEFF" + srt).toByteArray(Charsets.UTF_16LE), "s.srt").text == srt)
        }
        checkCase("UTF-16BE BOM import") {
            check(SubtitleImport.decode(("\uFEFF" + srt).toByteArray(Charsets.UTF_16BE), "s.srt").text == srt)
        }
        checkCase("legacy encoding is not silently guessed") {
            val bytes = srt.toByteArray(Charset.forName("windows-1252"))
            expectFailure<SubtitleImport.EncodingRequired> { SubtitleImport.decode(bytes, "s.srt") }
            check(SubtitleImport.decode(bytes, "s.srt", "windows-1252").text == srt)
        }
        checkCase("VTT import") {
            val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\nBonjour.\n"
            check(SubtitleImport.decode(vtt.toByteArray(), "x.txt").kind == SubtitleImport.Kind.VTT)
        }
        checkCase("ASS import") {
            val ass = "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Bonjour\n"
            check(SubtitleImport.decode(ass.toByteArray(), "x.ass").kind == SubtitleImport.Kind.ASS)
        }
        checkCase("empty file is rejected") {
            expectFailure<IllegalArgumentException> { SubtitleImport.decode(byteArrayOf(), "s.srt") }
        }
        checkCase("binary file is rejected") {
            expectFailure<IllegalArgumentException> { SubtitleImport.decode(byteArrayOf(0, 1, 2), "s.srt") }
        }
        checkCase("untimed text is rejected") {
            expectFailure<IllegalArgumentException> { SubtitleImport.decode("Bonjour".toByteArray(), "s.srt") }
        }
        checkCase("oversize subtitle file is rejected") {
            expectFailure<IllegalArgumentException> { SubtitleImport.decode(ByteArray(SubtitleImport.MAX_BYTES + 1), "s.srt") }
        }
        checkCase("voice becoming unavailable pauses the film") {
            val m = ready(); m.cues(1, listOf("A")); m.setSpeechReady(false)
            check(!m.snapshot().wantsPlayback && !m.snapshot().videoRequested)
        }
        checkCase("enabling narration without a ready voice pauses ordinary playback") {
            val m = NarrationMachine(); m.setEnabled(false); m.play(); m.setEnabled(true)
            check(!m.snapshot().videoRequested)
        }
        checkCase("repeating the current sentence does not replay it twice on resume") {
            val m = ready(); m.cues(1, listOf("A")); val id = m.preview().speech().single().id
            m.speechFinished(id); check(m.play().speech().isEmpty())
        }
        checkCase("stale completion cannot finish a newer utterance") {
            val m = ready(); val old = m.cues(1, listOf("A")).speech().single().id
            m.reset(true); m.setMediaReady(true); val latest = m.cues(2, listOf("B")).speech().single().id
            m.speechFinished(old); check(m.snapshot().speakingId == latest)
        }
        checkCase("failed sentence is retained for an explicit retry only") {
            val m = ready(); val old = m.cues(1, listOf("A")).speech().single().id
            m.speechFailed(old, "test"); check(!m.snapshot().wantsPlayback && m.snapshot().speakingId == null)
            val retried = m.play().speech().single(); check(retried.text == "A" && retried.id != old)
        }
        checkCase("10,000 randomized lifecycle transitions preserve state invariants") {
            val m = ready(); val r = Random(seed); val ids = mutableListOf<String>(); var frame = 0L
            repeat(10_000) {
                val e = when (r.nextInt(12)) {
                    0 -> m.play(); 1 -> m.pause(); 2 -> m.reset(r.nextBoolean())
                    3 -> m.setMediaReady(r.nextBoolean()); 4 -> m.setEnabled(r.nextBoolean())
                    5 -> m.setMode(if (r.nextBoolean()) Mode.COMFORT else Mode.CONTINUOUS)
                    6 -> m.cues(++frame, listOf("Texte $frame"))
                    7 -> m.cues(++frame, emptyList())
                    8 -> m.speechFinished(ids.randomOrNull(r) ?: "stale")
                    9 -> m.speechFailed(ids.randomOrNull(r) ?: "stale", "test")
                    10 -> m.preview("Essai")
                    else -> m.setSpeechReady(r.nextBoolean())
                }
                ids += e.speech().map { it.id }
                val s = m.snapshot()
                check(!s.videoRequested || s.wantsPlayback)
                check(!s.waitingForSpeech || (!s.videoRequested && s.enabled && s.mode == Mode.COMFORT))
                check(s.pendingCount in 0..24)
            }
            check(ids.distinct().size == ids.size) { "Utterance IDs were reused" }
        }
        println("RESULT $passed/$passed checks passed; randomized transitions: 10000; seed=$seed")
    }
}
