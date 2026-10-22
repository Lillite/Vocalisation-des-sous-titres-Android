package fr.soustitresvoix.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

object CinemaChecks {
    private var count = 0
    private fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
    private fun ready() = NarrationMachine().apply { setSpeechReady(true); setMediaReady(true); play() }
    private fun List<NarrationMachine.Effect>.spoken() = filterIsInstance<NarrationMachine.Effect.Speak>().single()
    @JvmStatic fun main(args: Array<String>) { runAll() }
    fun runAll() {
        count = 0
        test("default filters preserve descriptions and speaker labels") {
            val s = "ALICE: Bonjour.\n[musique]\n♪ Chanson\nNDT: Une note."
            check(SpeechTools.filter(s, SpeechTools.Filters()) == s)
        }
        test("description filter is explicit and independent") {
            check(SpeechTools.filter("ALICE: Bonjour [musique]", SpeechTools.Filters(descriptions = true)) == "ALICE: Bonjour")
        }
        test("speaker filter retains the spoken dialogue") {
            check(SpeechTools.filter("ÉLODIE: Bonjour.", SpeechTools.Filters(speakers = true)) == "Bonjour.")
        }
        test("ordinary colon is not a speaker name") {
            check(SpeechTools.filter("Il a dit : bonjour", SpeechTools.Filters(speakers = true)) == "Il a dit : bonjour")
        }
        test("song filter only drops explicitly marked lines") {
            check(SpeechTools.filter("♪ Paroles\nBonjour", SpeechTools.Filters(songs = true)) == "Bonjour")
        }
        test("translation note filter keeps unmarked dialogue") {
            check(SpeechTools.filter("NDT: Note.\nJe prends des notes.", SpeechTools.Filters(notes = true)) == "Je prends des notes.")
        }
        test("segments preserve both sentences") { check(SpeechTools.segments("Bonjour. Comment vas-tu ?").joinToString(" ") == "Bonjour. Comment vas-tu ?") }
        test("long segments remain engine-safe") { check(SpeechTools.segments("😀 mot ".repeat(1500)).all { it.length <= 1000 }) }
        test("adaptive has no acceleration when a sentence fits") { check(SpeechTools.adaptiveFactor(2000, 3000, 1.25f) == 1f) }
        test("adaptive caps rather than truncates") { check(SpeechTools.adaptiveFactor(10000, 1000, 1.25f) == 1.25f) }
        test("adaptive uses normal speed when timing is unknown") { check(SpeechTools.adaptiveFactor(null, 1000, 1.25f) == 1f) }
        test("expired cue uses normal speed during comfort hold") { check(SpeechTools.adaptiveFactor(1000, -1, 1.25f) == 1f) }
        test("adaptive computes measured duration ratio") { check(SpeechTools.adaptiveFactor(1200, 1000, 1.5f) == 1.2f) }
        test("cache key changes with voice") { check(SpeechTools.key("voice1", "Bonjour") != SpeechTools.key("voice2", "Bonjour")) }
        test("cache key changes with text and rate") {
            check(SpeechTools.key("rate1", "Bonjour") != SpeechTools.key("rate2", "Bonjour"))
            check(SpeechTools.key("rate1", "Bonjour") != SpeechTools.key("rate1", "Bonsoir"))
        }
        test("identical styling update with stable cue identity is not reread") {
            val m = ready(); val first = m.cues(1000, listOf("Bonjour"), identities = listOf("cue1")).spoken()
            check(m.cues(2000, listOf("Bonjour"), identities = listOf("cue1")).isEmpty())
            check(m.snapshot().speakingId == first.id && m.snapshot().videoRequested)
        }
        test("identical text in a later distinct cue is spoken") {
            val m = ready(); val first = m.cues(1000, listOf("Oui"), identities = listOf("cue1")).spoken(); m.speechFinished(first.id)
            check(m.cues(2000, listOf("Oui"), identities = listOf("cue2")).spoken().text == "Oui")
        }
        test("simultaneous second identical speaker is not erased") {
            val m = ready(); val first = m.cues(1, listOf("Oui"), identities = listOf("a")).spoken(); m.speechFinished(first.id)
            check(m.cues(2, listOf("Oui", "Oui"), identities = listOf("a", "b")).spoken().text == "Oui")
        }
        test("fallback handles duplicate overlapping text as a multiset") {
            val m = ready(); val first = m.cues(1, listOf("Oui")).spoken(); m.speechFinished(first.id)
            check(m.cues(2, listOf("Oui", "Oui")).spoken().text == "Oui")
        }
        test("segment resume repeats only the interrupted sentence") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.SEGMENT)
            val a = m.cues(1, listOf("Bonjour. Au revoir.")).spoken()
            val b = m.speechFinished(a.id).spoken(); check(b.text == "Au revoir.")
            m.pause(); check(m.play().spoken().text == "Au revoir.")
        }
        test("replica resume restarts the complete replica") {
            val m = ready(); val a = m.cues(1, listOf("Bonjour. Au revoir.")).spoken(); m.speechFinished(a.id)
            m.pause(); check(m.play().spoken().text == "Bonjour.")
        }
        test("explicit voice resume does not allow accidental play") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.EXPLICIT); m.cues(1, listOf("Bonjour.")); m.pause()
            m.play(); check(!m.snapshot().videoRequested && m.snapshot().voiceSuspended)
            check(m.resumeVoice().spoken().text == "Bonjour.")
        }
        test("adaptive still holds the video for unfinished speech") {
            val m = ready(); m.setMode(NarrationMachine.Mode.ADAPTIVE); val id = m.cues(1, listOf("Bonjour.")).spoken().id
            m.cues(2, emptyList()); check(m.snapshot().waitingForSpeech)
            m.speechFinished(id); check(m.snapshot().videoRequested)
        }
        test("reset clears explicit voice suspension") {
            val m = ready(); m.setResumePolicy(NarrationMachine.ResumePolicy.EXPLICIT); m.cues(1, listOf("A")); m.pause(); m.reset()
            check(!m.snapshot().voiceSuspended)
        }
        test("repeat complete replica consumes its remaining segments only") {
            val m = ready(); val a = m.cues(1, listOf("Bonjour. Au revoir.")).spoken(); m.speechFinished(a.id)
            val repeated = m.preview().spoken(); check(repeated.text == "Bonjour. Au revoir.")
            m.speechFinished(repeated.id)
            check(m.play().filterIsInstance<NarrationMachine.Effect.Speak>().isEmpty())
        }
        val dir = Files.createTempDirectory("speech-cache-test-").toFile()
        try {
            fun wav(file: File, frames: Int = 16000) {
                val b = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                b.put("RIFF".toByteArray()); b.putInt(36 + frames * 2); b.put("WAVEfmt ".toByteArray()); b.putInt(16)
                b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
                b.put("data".toByteArray()); b.putInt(frames * 2); file.writeBytes(b.array())
            }
            test("WAV duration is parsed from audio, not estimated from text") {
                val file = File(dir, "test.wav"); wav(file); check(WavInfo.read(file)?.durationMs == 1000L)
            }
            test("invalid WAV is rejected") { val f = File(dir, "bad.wav"); f.writeText("not audio"); check(WavInfo.read(f) == null) }
            test("truncated WAV is rejected") {
                val file = File(dir, "cut.wav"); wav(file); file.writeBytes(file.readBytes().copyOf(44)); check(WavInfo.read(file) == null)
            }
            test("cache put/get returns a complete recording") {
                val c = SpeechDiskCache(File(dir, "cache")) { 16L shl 20 }; val f = c.temporary(); wav(f)
                check(c.put("test", f)?.durationMs == 1000L); check(c.get("test")?.file?.isFile == true)
            }
            test("cache clear only clears the dedicated cache") {
                val c = SpeechDiskCache(File(dir, "cache")) { 16L shl 20 }; c.clear(); check(c.get("test") == null)
                check(File(dir, "test.wav").exists())
            }
            test("cache limit evicts complete old recordings") {
                val c = SpeechDiskCache(File(dir, "big-cache")) { 16L shl 20 }
                repeat(3) { val f = c.temporary(); wav(f, 4_000_000); c.put("file$it", f) }
                check(c.size() <= 16L shl 20)
            }
        } finally { dir.deleteRecursively() }
        println("RESULT $count cinema checks passed")
    }
}
