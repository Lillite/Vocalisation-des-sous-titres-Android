package fr.soustitresvoix.core

import java.io.File
import java.io.RandomAccessFile

object UpgradeChecks {
    @JvmStatic fun main(args: Array<String>) { runAll(); args.firstOrNull()?.let { path ->
        RandomAccessFile(path, "r").use { f ->
            val index = MkvTextIndex(f.channel).read()
            check(index.values.sumOf { it.size } == 8) { "Expected eight actual MKV captions, got $index" }
            check(index.values.flatten().first().text.startsWith("Bonjour"))
            println("PASS actual synthetic MKV: 8 timed embedded captions indexed without decoding video")
        }
    } }
    fun runAll() {
        var count = 0
        fun case(label: String, body: () -> Unit) { body(); count++; println("PASS $label") }
        fun ready() = NarrationMachine().apply { setMediaReady(true); setSpeechReady(true); play() }
        case("adaptive factor uses measured duration and never exceeds chosen cap") {
            check(SpeechPlanning.factor(6000, 3000, 1.35f) == 1.35f)
            check(SpeechPlanning.factor(2000, 3000, 1.35f) == 1f)
            check(SpeechPlanning.factor(6000, null, 1.35f) == 1f)
            check(SpeechPlanning.factor(6000, 0, 1.35f) == 1f)
            check(SpeechPlanning.factor(6000, 2000, Float.NaN) == 1f)
        }
        case("adaptive mode still pauses rather than discarding unfinished content") {
            val m = ready(); m.setMode(NarrationMachine.Mode.ADAPTIVE)
            val id = m.cues(1, listOf("Bonjour")).filterIsInstance<NarrationMachine.Effect.Speak>().single().id
            m.cues(2, emptyList()); check(m.snapshot().waitingForSpeech)
            m.speechFinished(id); check(m.snapshot().videoRequested)
        }
        case("resume replica keeps its full text") {
            val m = ready(); val text = "Première phrase. Deuxième phrase."
            val id = m.cues(1, listOf(text)).filterIsInstance<NarrationMachine.Effect.Speak>().single().id
            m.speechProgress(id, 17); m.pause()
            check(m.play().filterIsInstance<NarrationMachine.Effect.Speak>().single().text == text)
        }
        case("resume segment uses a real segment offset, not a guessed word") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.SEGMENT)
            val text = "Première phrase. Deuxième phrase."; val start = text.indexOf("Deuxième")
            val id = m.cues(1, listOf(text)).filterIsInstance<NarrationMachine.Effect.Speak>().single().id
            m.speechProgress(id, start); m.pause()
            check(m.play().filterIsInstance<NarrationMachine.Effect.Speak>().single().text == "Deuxième phrase.")
        }
        case("stale segment position cannot alter a newer utterance") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.SEGMENT)
            m.cues(1, listOf("Première. Deuxième.")); m.speechProgress("stale", 10); m.pause()
            check(m.play().filterIsInstance<NarrationMachine.Effect.Speak>().single().text.startsWith("Première"))
        }
        case("manual resume never restarts on an old completion or ordinary play") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.MANUAL)
            val id = m.cues(1, listOf("Bonjour")).filterIsInstance<NarrationMachine.Effect.Speak>().single().id
            m.pause(); m.speechFinished(id); m.play(); check(!m.snapshot().videoRequested)
            check(m.snapshot().voiceResumeRequired)
            check(m.resumeVoice().filterIsInstance<NarrationMachine.Effect.Speak>().single().text == "Bonjour")
        }
        case("seeking clears manual-resume latch and old content") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.MANUAL)
            m.cues(1, listOf("Ancien")); m.pause(); m.reset(false); check(!m.snapshot().voiceResumeRequired)
        }
        case("all filters preserve dialogue by default") {
            val text = "MARIE: Bonjour.\n[musique]\n♪ chanson ♪\nNDT: explication"
            check(SpeechPlanning.filter(text, SpeechPlanning.Filters()) == text)
        }
        case("description filter is independent") {
            check(SpeechPlanning.filter("[musique] Bonjour", SpeechPlanning.Filters(descriptions = true)) == "Bonjour")
        }
        case("explicit speaker label can be removed without stripping the line") {
            check(SpeechPlanning.filter("MARIE: Bonjour", SpeechPlanning.Filters(speakers = true)) == "Bonjour")
            check(SpeechPlanning.filter("Il dit : Bonjour", SpeechPlanning.Filters(speakers = true)) == "Il dit : Bonjour")
        }
        case("song marker inside a spoken sentence is not treated as a song line") {
            check(SpeechPlanning.filter("Le symbole ♪ est écrit ici", SpeechPlanning.Filters(songs = true)).isNotEmpty())
            check(SpeechPlanning.filter("♪ chanson ♪", SpeechPlanning.Filters(songs = true)).isEmpty())
        }
        case("translator note filter is explicit and opt-in") {
            check(SpeechPlanning.filter("NDT: explication", SpeechPlanning.Filters(notes = true)).isEmpty())
            check(SpeechPlanning.filter("Note importante pour toi", SpeechPlanning.Filters(notes = true)).isNotEmpty())
        }
        case("segment offsets refer to the original string") {
            val text = "  Bonjour.\nDeuxième phrase ! Ensuite 👋"
            SpeechPlanning.segments(text).forEach { check(text.substring(it.start).startsWith(it.text)) }
            check(SpeechPlanning.segments(text).size == 3)
        }
        case("long segments are bounded and preserve surrogate pairs") {
            val text = "👋".repeat(500)
            val parts = SpeechPlanning.segments(text)
            check(parts.all { it.text.length <= 280 && !Character.isHighSurrogate(it.text.last()) })
            check(parts.joinToString("") { it.text } == text)
        }
        case("cache identity changes with voice, engine version, speed, text") {
            val a = SpeechPlanning.cacheKey("engine", 1, "voice", 1f, "bonjour")
            check(a.length == 64)
            check(a != SpeechPlanning.cacheKey("engine", 2, "voice", 1f, "bonjour"))
            check(a != SpeechPlanning.cacheKey("engine", 1, "other", 1f, "bonjour"))
            check(a != SpeechPlanning.cacheKey("engine", 1, "voice", 1.2f, "bonjour"))
            check(a != SpeechPlanning.cacheKey("engine", 1, "voice", 1f, "au revoir"))
        }
        case("SRT timing preserves overlapping cues") {
            val t = SubtitleTimeline.parse("1\n00:00:01,000 --> 00:00:03,000\nUn\n\n2\n00:00:02,000 --> 00:00:04,000\nDeux\n")
            check(t.size == 2 && t[0].endMs > t[1].startMs)
        }
        case("WebVTT short timestamps and settings are parsed") {
            val t = SubtitleTimeline.parse("WEBVTT\n\n00:01.500 --> 00:03.000 align:start\nBonjour\n")
            check(t.single() == TimedCaption(1500, 3000, "Bonjour"))
        }
        case("ASS event format and commas in text survive") {
            val t = SubtitleTimeline.parse("[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Bonjour, ami")
            check(t.single().text == "Bonjour, ami")
        }
        case("invalid times and reverse intervals are excluded") {
            check(SubtitleTimeline.time("00:99:99.0") == null)
            check(SubtitleTimeline.parse("00:00:03,000 --> 00:00:01,000\nNon\n").isEmpty())
        }
        case("unchanged overlapping text is not re-read but a new simultaneous occurrence is retained") {
            val m = ready(); m.cues(1, listOf("A")); val id = m.snapshot().speakingId!!
            m.cues(2, listOf("A", "B")); check(m.speechFinished(id).filterIsInstance<NarrationMachine.Effect.Speak>().single().text == "B")
            val n = ready(); n.cues(1, listOf("A")); val first = n.snapshot().speakingId!!
            n.cues(2, listOf("A", "A")); check(n.speechFinished(first).filterIsInstance<NarrationMachine.Effect.Speak>().single().text == "A")
        }
        case("same sentence later remains a legitimate occurrence") {
            val m = ready(); val a = m.cues(1, listOf("Oui")).filterIsInstance<NarrationMachine.Effect.Speak>().single()
            m.speechFinished(a.id); m.cues(2, emptyList())
            check(m.cues(3, listOf("Oui")).filterIsInstance<NarrationMachine.Effect.Speak>().single().id != a.id)
        }
        case("empty/invalid MKV cannot allocate from an unchecked element size") {
            val f = File.createTempFile("invalid-mkv-", ".bin")
            try {
                f.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F))
                var rejected = false
                try { RandomAccessFile(f, "r").use { MkvTextIndex(it.channel).read() } } catch (_: Exception) { rejected = true }
                check(rejected)
            } finally { f.delete() }
        }
        println("RESULT $count upgrade checks passed")
    }
}
