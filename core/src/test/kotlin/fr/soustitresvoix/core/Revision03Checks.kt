package fr.soustitresvoix.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random

object Revision03Checks {
    private var passed=0
    private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS 0.3 $name") }
    private fun ready()=NarrationMachine().apply { setMode(NarrationMachine.Mode.ADAPTIVE); setSpeechReady(true); setMediaReady(true); play() }
    private fun List<NarrationMachine.Effect>.speak()=filterIsInstance<NarrationMachine.Effect.Speak>().single()
    private fun wav(f: File, seconds: Int=1) {
        val bytes=seconds*32000
        val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36+bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        f.outputStream().use { it.write(h.array()); it.write(ByteArray(bytes)) }
    }
    @JvmStatic fun main(args: Array<String>) { runAll(args.firstOrNull()?.toIntOrNull() ?: 42) }
    fun runAll(seed: Int) {
        passed=0
        test("French abbreviation is not separated from its name") { check(SpeechTools.segments("M. Dupont arrive. Il repart.")==listOf("M. Dupont arrive.","Il repart.")) }
        test("decimal and abbreviation punctuation remains intact") { val s="Le Dr. Martin compte 3.14 unités ; c’est exact."; check(SpeechTools.segments(s)==listOf(s)) }
        test("wrapped subtitle lines join without deleting punctuation") { check(SpeechTools.segments("Oui, vraiment ;\nla suite arrive.")==listOf("Oui, vraiment ; la suite arrive.")) }
        test("explicit speaker lines never merge") { check(SpeechTools.segments("- Salut\n- Pourquoi ?")==listOf("- Salut","- Pourquoi ?")) }
        test("named speaker lines remain separate") { check(SpeechTools.segments("ALICE: Bonjour\nBOB: Salut")==listOf("ALICE: Bonjour","BOB: Salut")) }
        test("ellipsis and closing quotes are preserved") { val s="Pourquoi ? « Vraiment ! » Oui..."; check(SpeechTools.segments(s).joinToString(" ")==s) }
        test("French closing guillemet belongs to its own sentence") {
            check(SpeechTools.segments("Il dit : « Oui ! » Puis il part.") == listOf("Il dit : « Oui ! »", "Puis il part."))
        }
        test("opening guillemet does not attach to the previous question") {
            check(SpeechTools.segments("Pourquoi ? « Vraiment ! » Oui...") == listOf("Pourquoi ?", "« Vraiment ! »", "Oui..."))
        }
        test("closing quote followed by a reporting clause stays fluid") {
            check(SpeechTools.segments("« Oui ! », répond-elle. Elle part.") == listOf("« Oui ! », répond-elle.", "Elle part."))
        }
        test("removing speaker names retains each speaker boundary") {
            val text = SpeechTools.filter("ALICE: Bonjour,\ncomment vas-tu ?\nBOB: Très bien.", SpeechTools.Filters(speakers = true))
            check(SpeechTools.segments(text) == listOf("Bonjour, comment vas-tu ?", "Très bien."))
        }
        test("speaker filtering never merges two unpunctuated speakers") {
            val text = SpeechTools.filter("ALICE: Bonjour\nBOB: Salut", SpeechTools.Filters(speakers = true))
            check(SpeechTools.segments(text) == listOf("Bonjour", "Salut"))
        }
        test("Arabic sentence punctuation remains available to the voice") {
            check(SpeechTools.segments("لماذا؟ نعم.") == listOf("لماذا؟", "نعم."))
        }
        test("paragraph boundaries survive segmentation") { check(SpeechTools.segments("Premier\n\nSecond")==listOf("Premier","Second")) }
        test("Unicode and long words never split surrogate pairs") {
            val s="😀".repeat(1500); val chunks=SpeechTools.segments(s)
            check(chunks.joinToString("")==s); check(chunks.all { it.length<=1000 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        }
        test("punctuation-only text is not silently deleted") { check(SpeechTools.segments("?!…").joinToString("")=="?!…") }
        test("empty text never creates speech") { check(SpeechTools.segments(" \n\t ").isEmpty()) }
        test("adaptive direct estimate can accelerate without cached WAV") { check(AdaptiveTiming.factor(4000,0,2100,1.8f)==1.8f) }
        test("adaptive reserves remaining segments") { check(AdaptiveTiming.factor(1000,2000,2100,2f)==1.5f) }
        test("unknown timing does not invent a deadline") { check(AdaptiveTiming.factor(4000,0,null,2f)==1f) }
        test("expired deadline asks for cap rather than normal speed") { check(AdaptiveTiming.factor(4000,0,-10,2f)==2f) }
        test("fitting sentence uses normal speed") { check(AdaptiveTiming.factor(1000,500,3000,1.8f)==1f) }
        test("maximum is always respected") { check(AdaptiveTiming.factor(Long.MAX_VALUE,Long.MAX_VALUE,1,1.7f)==1.7f) }
        test("invalid maximum is safe") { check(AdaptiveTiming.factor(10000,0,2000,Float.NaN)==1f) }
        test("forecast considers accumulation not just first segment") { check(AdaptiveTiming.forecastFactor(listOf(1000L to 2100L, 4000L to 3100L),2f)>1.6f) }
        test("planner cannot borrow silence before a future subtitle appears") {
            val factor = AdaptiveTiming.planFactor(listOf(
                AdaptiveTiming.Window(3000, releaseMs=10000, deadlineMs=12000)), 2f)
            check(factor in 1.578f..1.580f)
        }
        test("independent later dialogue cannot unnecessarily speed this sentence") {
            check(AdaptiveTiming.planFactor(listOf(AdaptiveTiming.Window(1000, deadlineMs=2000),
                AdaptiveTiming.Window(3000, releaseMs=10000, deadlineMs=12000)), 2f) == 1f)
        }
        test("planner includes unknown-deadline backlog before a known deadline") {
            check(AdaptiveTiming.planFactor(listOf(AdaptiveTiming.Window(1000),
                AdaptiveTiming.Window(3000, deadlineMs=2100)), 2f) == 2f)
        }
        test("planner leaves naturally fitting separated dialogue at normal rate") {
            check(AdaptiveTiming.planFactor(listOf(AdaptiveTiming.Window(1000, deadlineMs=2000),
                AdaptiveTiming.Window(1000, releaseMs=10000, deadlineMs=12000)), 2f) == 1f)
        }
        test("planner cannot exceed the user ceiling for an impossible overlap") {
            check(AdaptiveTiming.planFactor(listOf(AdaptiveTiming.Window(9000, deadlineMs=1000)), 1.4f) == 1.4f)
            check(AdaptiveTiming.planFactor(listOf(AdaptiveTiming.Window(9000, deadlineMs=1000)), Float.NaN) == 1f)
        }
        test("duration calibration learns from completed measurements") { val m=SpeechDurationModel(); val t="Bonjour à tous, voici la phrase."; val before=m.estimate(t,1f); m.observe(t,before*2,1f); check(m.estimate(t,1f)>before); check(m.observations==1) }
        test("calibration rejects empty zero-time observations") { val m=SpeechDurationModel(); m.observe("Bonjour",0,1f); check(m.observations==0) }
        test("explicit deadline avoids pause at cosmetic cue disappearance") { val m=ready(); m.cues(1000,listOf("Bonjour."),identities=listOf("a"),deadlinesMs=listOf(2000)); m.cues(1500000,emptyList()); check(m.snapshot().videoRequested); m.advance(1999); check(m.snapshot().videoRequested); m.advance(2000); check(!m.snapshot().videoRequested) }
        test("deadline never generates speech before a cue event") { val m=ready(); repeat(10) { m.advance(it*1000L) }; check(m.snapshot().speakingId==null) }
        test("remaining sentence text accompanies the current segment") { val s=ready().cues(1,listOf("Bonjour. Au revoir."),deadlinesMs=listOf(5000)).speak(); check(s.text=="Bonjour." && s.remainingText=="Au revoir." && s.deadlineMs==5000L) }
        test("adaptive pending plan keeps overlapping replicas and remaining segments") {
            val m=ready()
            val first=m.cues(1,listOf("Bonjour. Au revoir."), identities=listOf("a"), deadlinesMs=listOf(5000)).speak()
            m.cues(2,listOf("Bonjour. Au revoir.", "Deuxième locuteur."), identities=listOf("a", "b"), deadlinesMs=listOf(5000, 3000))
            check(m.pendingPlan() == listOf(NarrationMachine.PlannedReplica("Au revoir.", 5000),
                NarrationMachine.PlannedReplica("Deuxième locuteur.", 3000)))
            check(m.speechFinished(first.id).speak().text == "Au revoir.")
            check(m.pendingPlan() == listOf(NarrationMachine.PlannedReplica("Deuxième locuteur.", 3000)))
        }
        test("known overlap does not expire the older long cue early") { val m=ready(); m.cues(1,listOf("A"),identities=listOf("a"),deadlinesMs=listOf(5000)); m.cues(2,listOf("A","B"),identities=listOf("a","b"),deadlinesMs=listOf(5000,6000)); m.advance(2000); check(m.snapshot().videoRequested) }
        test("identical simultaneous speakers keep separate deadlines") { val m=ready(); val first=m.cues(1,listOf("Oui","Oui"),identities=listOf("a","b"),deadlinesMs=listOf(1000,3000)).speak(); val next=m.speechFinished(first.id).speak(); check(first.deadlineMs==1000L && next.deadlineMs==3000L) }
        test("pause blocks old completion and timer-based resume") { val m=ready(); val id=m.cues(1,listOf("Bonjour."),deadlinesMs=listOf(1000)).speak().id; m.pause(); m.advance(2000); m.speechFinished(id); check(!m.snapshot().wantsPlayback && !m.snapshot().videoRequested) }
        test("seek removes old deadline state") { val m=ready(); m.cues(1,listOf("Bonjour."),deadlinesMs=listOf(100)); m.reset(true); m.setMediaReady(true); m.advance(1000); check(!m.snapshot().waitingForSpeech && m.snapshot().speakingId==null) }
        test("preview is distinguishable from movie narration") { val m=ready(); check(m.preview("Bonjour.").speak().preview); check(!m.snapshot().wantsPlayback) }
        test("legacy comfort keeps its boundary pause") { val m=ready(); m.setMode(NarrationMachine.Mode.COMFORT); m.cues(1,listOf("Bonjour."),deadlinesMs=listOf(5000)); m.cues(2,emptyList()); check(m.snapshot().waitingForSpeech) }
        test("cache persists across cache object recreation") { val d=Files.createTempDirectory("persist").toFile(); try { val c=SpeechDiskCache(d){16L shl 20}; val f=c.temporary(); wav(f); check(c.put("key",f)!=null); check(SpeechDiskCache(d){16L shl 20}.get("key")?.durationMs==1000L) } finally { d.deleteRecursively() } }
        test("valid-header corrupted PCM is rejected by digest") { val d=Files.createTempDirectory("digest").toFile(); try { val c=SpeechDiskCache(d){16L shl 20}; val f=c.temporary(); wav(f); c.put("key",f); java.io.RandomAccessFile(File(d,"key.wav"),"rw").use{it.seek(100);it.write(1)};check(c.get("key")==null) }finally{d.deleteRecursively()} }
        test("truncated audio falls back as cache miss") { val d=Files.createTempDirectory("truncated").toFile(); try { val c=SpeechDiskCache(d){16L shl 20}; val f=c.temporary();wav(f);c.put("key",f);File(d,"key.wav").writeBytes(byteArrayOf(1));check(c.get("key")==null) }finally{d.deleteRecursively()} }
        test("model namespace changes cache identity") { check(SpeechTools.key("engine:model1:punctuation3","Salut")!=SpeechTools.key("engine:model2:punctuation3","Salut")) }
        test("natural PCM silences are kept unchanged") { val d=Files.createTempDirectory("silence").toFile();try { val c=SpeechDiskCache(d){16L shl 20};val f=c.temporary();wav(f);val expected=f.readBytes();val entry=c.put("key",f)!!;check(entry.file.readBytes().contentEquals(expected)) }finally{d.deleteRecursively()} }
        test("dark frames never justify cropping") { check(BlackBars.inspect(IntArray(100*100){0xff000000.toInt()},100,100)==null) }
        test("unletterboxed light frame proposes no crop") { val m=BlackBars.inspect(IntArray(100*100){-1},100,100)!!;check(!m.nonzero()) }
        test("known horizontal margins are conservatively detected") { val p=IntArray(100*100){ i-> if(i/100 in 10..89)-1 else 0xff000000.toInt() };val m=BlackBars.inspect(p,100,100)!!;check(m.top==.09f && m.bottom==.09f && m.left==0f) }
        test("text touching border prevents removal of that margin") { val p=IntArray(100*100){i->if(i/100 in 10..89)-1 else 0xff000000.toInt()};p[50]=-1;check(BlackBars.inspect(p,100,100)!!.top==0f) }
        test("all sampled frames must agree on usable evidence") { val good=BlackBars.Margins(top=.1f,bottom=.1f);check(BlackBars.consensus(listOf(good,good,good,good,null))==null) }
        test("bar consensus takes smallest candidate on each side") { val samples=List(5){BlackBars.Margins(top=.1f+it*.01f,bottom=.1f)};check(BlackBars.consensus(samples)!!.top==.1f) }
        test("invalid or excessive crop is rejected") { check(!BlackBars.Margins(top=.5f).safe());check(!BlackBars.Margins(left=Float.NaN).safe()) }
        test("percentiles use a bounded sample window") { val w=LatencyWindow(4); (1..5).forEach{w.add(it.toLong())};check(w.summary().contains("n=4, p50=3 ms, p95=5 ms"));w.clear();check(w.summary()=="aucune mesure") }
        test("30000 adaptive randomized events preserve manual pause and voice safety") {
            val random=Random(seed);val m=ready();var position=0L
            repeat(30000) {
                when(random.nextInt(7)) {
                    0 -> { position+=random.nextLong(1,500);m.advance(position) }
                    1 -> m.cues(position*1000, listOf("Phrase ${random.nextInt(50)}. Suite."),deadlinesMs=listOf(position+random.nextLong(1,2000)))
                    2 -> { val stale=m.snapshot().speakingId;m.pause();m.advance(position+10000);if(stale!=null)m.speechFinished(stale);check(!m.snapshot().videoRequested) }
                    3 -> m.play()
                    4 -> m.snapshot().speakingId?.let{m.speechFinished(it)}
                    5 -> { m.reset();m.setMediaReady(true) }
                    else -> m.speechFinished("unknown")
                }
                val s=m.snapshot();check(s.pendingCount>=0);if(!s.wantsPlayback)check(!s.videoRequested)
            }
        }
        println("RESULT $passed checks 0.3 passed; 30000 randomized events; seed=$seed")
    }
}
