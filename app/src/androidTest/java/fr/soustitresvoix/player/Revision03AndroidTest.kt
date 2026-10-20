package fr.soustitresvoix.player

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.SpeechTools
import fr.soustitresvoix.core.BlackBars
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Revision03AndroidTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(message: String, predicate: ()->Boolean) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {
            var ok=false;instrument.runOnMainSync{ok=predicate()};if(ok)return;Thread.sleep(80)
        };fail(message)
    }
    @Test fun stretchedImageRejectsAccidentalZoomAndCrop() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.session.prefs.narration=false;a.session.refreshSettings();a.session.prefs.putInt("orientation",0)
                a.setFullscreen(true)
                val view=a.findViewById<CinemaView>(R.id.cinema_root)
                assertTrue(view.setFramingMode(2));view.setImageZoom(3f)
                val video=view.getChildAt(0) as PlayerView
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL,video.resizeMode)
                assertEquals(1f,video.videoSurfaceView!!.scaleX,0f);assertEquals(1f,video.videoSurfaceView!!.scaleY,0f)
                assertFalse(view.setFramingMode(1));assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL,video.resizeMode)
                assertTrue(view.setFramingMode(1,true));assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM,video.resizeMode)
                assertTrue(view.setFramingMode(2));assertTrue(a.session.prefs.protectFrame)
            }
        }
    }
    @Test fun completeMkvIndexContainsEveryDemoCueWithoutPlayback() {
        val c=instrument.targetContext
        val index=FullSubtitleIndexer(c).scan(SavedMedia("android.resource://${c.packageName}/${R.raw.demonstration}","demo")){}
        assertEquals("Full sequential demux must see all eight subtitles",8,index.count)
        assertFalse(index.truncated)
    }
    @Test fun externalSrtIndexPreservesPunctuationAndTiming() {
        val c=instrument.targetContext;val f=java.io.File(c.cacheDir,"fixture-03.srt")
        f.writeText("1\n00:00:01,000 --> 00:00:04,000\nM. Dupont arrive ; vraiment !\n\n2\n00:00:05,000 --> 00:00:08,000\nAu revoir.\n")
        try { val i=FullSubtitleIndexer(c).scan(SavedMedia("file:///not-read","SRT",subtitlePath=f.path,subtitleMime="application/x-subrip")){}
            assertEquals(2,i.count);assertEquals(4000L,i.windowEnd("external-subtitle","M. Dupont arrive ; vraiment !",1500,SpeechTools.Filters()))
        } finally { f.delete() }
    }
    @Test fun cancellationPreventsCompleteIndexPublication() {
        val task=FullSubtitleIndexer(instrument.targetContext);task.cancel()
        try {task.scan(SavedMedia("android.resource://${instrument.targetContext.packageName}/${R.raw.demonstration}","demo")){};fail("Cancelled indexing unexpectedly succeeded")}
        catch(e:Exception){assertTrue(e.message.orEmpty().contains("annulée"))}
    }
    @Test fun wrappedTextMatchesItsRealDeadline() {
        val i=CueAheadIndex();i.add("t",CuesWithTiming(listOf(Cue.Builder().setText("Bonjour,\ncomment vas-tu ?").build()),1000000,3000000))
        assertEquals(4000L,i.windowEnd("t","Bonjour, comment vas-tu ?",1500,SpeechTools.Filters()))
        assertNotNull(i.identities("t",listOf("Bonjour, comment vas-tu ?"),1500000,SpeechTools.Filters()))
    }
    @Test fun directTtsActuallyReceivesAcceleratedRateWithoutCache() {
        val done=CountDownLatch(1);val error=AtomicReference<String?>(null);var speech:AndroidSpeech?=null
        instrument.runOnMainSync {
            val p=PlayerPreferences(instrument.targetContext);p.enginePackage=instrument.context.packageName;p.putBool("cache",false);p.putInt("adaptiveMax",180)
            speech=AndroidSpeech(instrument.targetContext,p,{_,_->},{done.countDown()},{_,e->error.set(e);done.countDown()})
        }
        try {
            waitFor("TTS fixture not ready"){speech!!.isReady}
            instrument.runOnMainSync {speech!!.speak("direct-03","Cette longue phrase doit accélérer sans cache.",500,true)
                assertEquals(1.8f,speech!!.lastFactor,.001f);assertTrue(speech!!.timingSource.contains("directe"))}
            assertTrue(done.await(15,TimeUnit.SECONDS));assertNull(error.get())
            // The test-only service returns an error unless this specific request arrives >=175%.
        }finally{instrument.runOnMainSync{speech?.close();PlayerPreferences(instrument.targetContext).enginePackage=""}}
    }
    @Test fun validCacheSurvivesSpeechClientRecreation() {
        val done=CountDownLatch(1);val error=AtomicReference<String?>(null);var speech:AndroidSpeech?=null
        fun create(){val p=PlayerPreferences(instrument.targetContext);p.enginePackage=instrument.context.packageName;p.putBool("cache",true)
            speech=AndroidSpeech(instrument.targetContext,p,{_,_->},{done.countDown()},{_,e->error.set(e);done.countDown()})}
        instrument.runOnMainSync{create()}
        try {
            waitFor("TTS fixture not ready"){speech!!.isReady}
            instrument.runOnMainSync {speech!!.clearCache();speech!!.prepareAhead(listOf("Une phrase persistante, ponctuée."))}
            waitFor("No completed synthesis"){speech!!.preparedCount>0}
            instrument.runOnMainSync{speech!!.close();create()}
            waitFor("Recreated TTS client not ready"){speech!!.isReady}
            instrument.runOnMainSync{speech!!.speak("persist-03","Une phrase persistante, ponctuée.")}
            assertTrue(done.await(15,TimeUnit.SECONDS));assertNull(error.get())
            instrument.runOnMainSync{assertEquals(1,speech!!.hits);assertEquals(0,speech!!.misses)}
        }finally{instrument.runOnMainSync{speech?.close();PlayerPreferences(instrument.targetContext).enginePackage=""}}
    }
    @Test fun speechBandProcessorPreservesBypassSamplesAndBufferLength() {
        val processor=SpeechBandProcessor();processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush()
        val input=ByteBuffer.allocateDirect(400).order(ByteOrder.nativeOrder());repeat(200){input.putShort((it*37-3000).toShort())};input.flip()
        val expected=ByteArray(400);input.duplicate().get(expected)
        processor.queueInput(input);val output=processor.output;val bytes=ByteArray(output.remaining());output.get(bytes)
        assertArrayEquals(expected,bytes);assertEquals(200L,processor.processedSamples)
        processor.enabled=true;processor.speaking=true;input.clear();repeat(200){input.putShort((it*100-10000).toShort())};input.flip()
        processor.queueInput(input);assertEquals(400,processor.output.remaining());processor.reset()
    }
    @Test fun catalogueSeparatesEnginesFromModelFilesAndGateways() {
        val doc=instrument.targetContext.assets.open("voice-catalogue.json").bufferedReader().use{JSONObject(it.readText())}
        val entries=doc.getJSONArray("entries");assertTrue(entries.length()>=20)
        var model=false;var gateway=false
        for(i in 0 until entries.length()){val e=entries.getJSONObject(i);assertTrue(e.getString("url").startsWith("https://"));assertTrue(e.getString("size").isNotBlank());
            model=model||e.getString("kind").startsWith("Modèles");gateway=gateway||e.getString("kind").contains("Passerelle")}
        assertTrue(model);assertTrue(gateway)
    }
    @Test fun gpuEffectsCanBeAppliedAndBypassedOnSdrVideo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var a:MainActivity?=null
            scenario.onActivity{activity->a=activity;activity.session.prefs.narration=false;activity.session.refreshSettings();activity.session.open(SavedMedia("android.resource://${activity.packageName}/${R.raw.demonstration}","SDR"))}
            waitFor("SDR video not ready"){a!!.session.player.playbackState==Player.STATE_READY}
            scenario.onActivity { activity -> assertFalse(activity.session.visual.hdr);activity.session.visual.adjust(.12f,.06f);activity.session.play() }
            waitFor("Video did not advance with effect pipeline"){a!!.session.player.currentPosition>1500}
            scenario.onActivity { activity->activity.session.visual.toggleComparison();assertNull(activity.session.player.playerError) }
        }
    }
    @Test fun allNewCoreRegressionsAlsoExecuteOnAndroidIcu() {
        assertEquals(listOf("M. Dupont arrive.","Il repart."),SpeechTools.segments("M. Dupont arrive. Il repart."))
        assertEquals(listOf("- Bonjour","- Au revoir"),SpeechTools.segments("- Bonjour\n- Au revoir"))
        assertNull(BlackBars.inspect(IntArray(10000){Color.BLACK},100,100))
    }
}
