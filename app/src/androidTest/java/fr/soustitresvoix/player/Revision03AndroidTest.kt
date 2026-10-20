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
import org.junit.Before
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
    @Before fun clearPreferences() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear().commit()
    }
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
    @Test fun framingIsRestoredPerVideoWithoutInheritedZoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.session.prefs.narration = false
                a.session.prefs.putInt("orientation", 0)
                a.session.refreshSettings()
                // Two valid resource URI forms give distinct per-video identities.
                val entire = SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Cadre entier")
                val cropped = SavedMedia("android.resource://${a.packageName}/raw/demonstration", "Recadrage choisi")
                fun open(media: SavedMedia): CinemaView {
                    a.setFullscreen(false)
                    a.session.open(media)
                    a.setFullscreen(true)
                    return a.findViewById(R.id.cinema_root)
                }
                assertTrue(open(entire).setFramingMode(0))
                open(cropped).apply {
                    assertTrue(setFramingMode(1, true))
                    setImageZoom(3f)
                }
                val original = open(entire)
                val originalVideo = original.getChildAt(0) as PlayerView
                assertTrue("The entire-frame video inherited disabled protection", a.session.prefs.protectFrame)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, originalVideo.resizeMode)
                assertEquals(1f, originalVideo.videoSurfaceView!!.scaleX, 0f)
                original.setImageZoom(4f)
                assertEquals(1f, originalVideo.videoSurfaceView!!.scaleX, 0f)

                val deliberateCrop = open(cropped)
                val croppedVideo = deliberateCrop.getChildAt(0) as PlayerView
                assertFalse("The explicitly cropped video lost its choice", a.session.prefs.protectFrame)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, croppedVideo.resizeMode)
                assertEquals("A previous transient zoom must not leak into reopening", 1f, croppedVideo.videoSurfaceView!!.scaleX, 0f)
                deliberateCrop.setFrameProtection(true)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, croppedVideo.resizeMode)
                val protectedAgain = open(cropped)
                assertTrue(a.session.prefs.protectFrame)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, (protectedAgain.getChildAt(0) as PlayerView).resizeMode)
            }
        }
    }
    @Test fun fullImageModesClearPreviouslyApprovedEncodedMargins() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.session.prefs.narration = false
                a.session.prefs.putInt("orientation", 0)
                a.session.refreshSettings()
                a.setFullscreen(true)
                val view = a.findViewById<CinemaView>(R.id.cinema_root)
                for (mode in listOf(2, 0)) {
                    a.session.visual.crop(BlackBars.Margins(top = .1f, bottom = .1f))
                    assertTrue(a.session.visual.margins.nonzero())
                    assertTrue(view.setFramingMode(mode))
                    assertFalse("An entire-image mode kept a previous margin crop", a.session.visual.margins.nonzero())
                    assertTrue(a.session.prefs.protectFrame)
                }
            }
        }
    }
    @Test fun stretchKeepsAllFourRenderedCornersAfterPreviousZoomAndCrop() {
        val frame = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { a ->
                activity = a
                a.session.prefs.narration = false
                a.session.prefs.showSubtitles = false
                a.session.prefs.putInt("orientation", 0)
                a.session.refreshSettings()
                a.session.player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.countDown() }
                })
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Mire des quatre coins"))
                a.setFullscreen(true)
                val view = a.findViewById<CinemaView>(R.id.cinema_root)
                assertTrue(view.setFramingMode(1, true))
                view.setImageZoom(3f)
                a.session.visual.crop(BlackBars.Margins(left = .1f, right = .1f))
                assertTrue(view.setFramingMode(2))
                view.showControls(false)
            }
            assertTrue("The real decoder never rendered the synthetic chart", frame.await(20, TimeUnit.SECONDS))
            waitFor("Fullscreen bars remained visible") {
                val insets = activity!!.window.decorView.rootWindowInsets
                insets != null && !insets.isVisible(android.view.WindowInsets.Type.systemBars())
            }
            instrument.waitForIdleSync()
            val bounds = android.graphics.Rect()
            scenario.onActivity { a ->
                val view = a.findViewById<CinemaView>(R.id.cinema_root)
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
                assertEquals(View.GONE, a.findViewById<View>(R.id.cinema_controls).visibility)
                assertTrue(a.session.prefs.protectFrame)
                assertFalse(a.session.visual.margins.nonzero())
            }
            // Read the composed screen, not merely SurfaceView's source buffer:
            // the latter could still contain pixels cropped off the display.
            val image = requireNotNull(instrument.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width && bounds.bottom <= image.height)
                fun colorAt(x: Float, y: Float) = image.getPixel(
                    bounds.left + (bounds.width() * x).toInt(), bounds.top + (bounds.height() * y).toInt())
                fun red(c: Int) = Color.red(c) > 170 && Color.green(c) < 100 && Color.blue(c) < 100
                fun green(c: Int) = Color.green(c) > 170 && Color.red(c) < 100 && Color.blue(c) < 100
                fun blue(c: Int) = Color.blue(c) > 170 && Color.red(c) < 100 && Color.green(c) < 100
                fun yellow(c: Int) = Color.red(c) > 170 && Color.green(c) > 170 && Color.blue(c) < 100
                assertTrue("Upper-left red marker was cropped or not rendered", red(colorAt(.05f, .05f)))
                assertTrue("Upper-right green marker was cropped or not rendered", green(colorAt(.95f, .05f)))
                assertTrue("Lower-left blue marker was cropped or not rendered", blue(colorAt(.05f, .95f)))
                assertTrue("Lower-right yellow marker was cropped or not rendered", yellow(colorAt(.95f, .95f)))
            } finally {
                image.recycle()
                for (command in listOf("mkdir -p /sdcard/Download/cinema03-tests",
                    "screencap -p /sdcard/Download/cinema03-tests/stretch-four-corners.png")) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                }
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
    @Test fun cancellationAtFinalProgressDoesNotPublishIndex() {
        val context = instrument.targetContext
        val external = java.io.File(context.cacheDir, "cancel-at-completion.srt")
        external.writeText("1\n00:00:01,000 --> 00:00:03,000\nUne phrase complète.\n")
        val media = listOf(
            SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}", "MKV"),
            SavedMedia("file:///not-read", "SRT", subtitlePath = external.path, subtitleMime = "application/x-subrip")
        )
        try {
            for (saved in media) {
                val task = FullSubtitleIndexer(context)
                var reachedCompletion = false
                try {
                    task.scan(saved) { percent ->
                        if (percent == 100) { reachedCompletion = true; task.cancel() }
                    }
                    fail("An index cancelled at publication was returned for ${saved.title}")
                } catch (error: java.io.IOException) {
                    assertTrue("The fixture never reached final publication", reachedCompletion)
                    assertTrue(error.message.orEmpty().contains("annulée"))
                }
            }
        } finally { external.delete() }
    }
    @Test fun wrappedTextMatchesItsRealDeadline() {
        val i=CueAheadIndex();i.add("t",CuesWithTiming(listOf(Cue.Builder().setText("Bonjour,\ncomment vas-tu ?").build()),1000000,3000000))
        assertEquals(4000L,i.windowEnd("t","Bonjour, comment vas-tu ?",1500,SpeechTools.Filters()))
        assertNotNull(i.identities("t",listOf("Bonjour, comment vas-tu ?"),1500000,SpeechTools.Filters()))
    }
    @Test fun deadlineMatchingDoesNotBorrowAnArbitrarySubstring() {
        val index = CueAheadIndex()
        val cue = Cue.Builder().setText("Oui, mais pas maintenant. Ensuite.").build()
        index.add("fr", CuesWithTiming(listOf(cue), 1_000_000, 3_000_000))
        assertNull(index.windowEnd("fr", "Oui", 1500, SpeechTools.Filters()))
        assertEquals(4000L, index.windowEnd("fr", "Ensuite.", 1500, SpeechTools.Filters()))
    }
    @Test fun simultaneousIdenticalTextRetainsEachDeadlineAndOccurrence() {
        val index = CueAheadIndex()
        val sameText = Cue.Builder().setText("Oui.").build()
        index.add("fr", CuesWithTiming(listOf(sameText), 1_000_000, 2_000_000))
        index.add("fr", CuesWithTiming(listOf(sameText), 1_000_000, 5_000_000))
        val occurrences = index.resolved("fr", listOf("Oui.", "Oui."), 1_500_000, SpeechTools.Filters())!!
        assertEquals(2, occurrences.size)
        assertEquals(listOf(3_000_000L, 6_000_000L), occurrences.map { it.endUs })
        assertEquals(2, occurrences.map { it.identity }.distinct().size)

        val duplicates = CueAheadIndex()
        val group = CuesWithTiming(listOf(sameText, sameText), 1_000_000, 2_000_000)
        duplicates.add("fr", group)
        duplicates.add("fr", group) // Re-reading the same packet must not double it.
        val twins = duplicates.resolved("fr", listOf("Oui.", "Oui."), 1_500_000, SpeechTools.Filters())!!
        assertEquals(2, duplicates.count)
        assertEquals(listOf(0, 1), twins.map { it.ordinal })
        assertEquals(2, twins.map { it.identity }.distinct().size)
        assertNull(duplicates.resolved("fr", listOf("Oui.", "Oui.", "Oui."), 1_500_000, SpeechTools.Filters()))
    }
    @Test fun skippedTextOrInvalidTimingMarksAnIndexIncomplete() {
        val normal = Cue.Builder().setText("Texte intact.").build()
        val inputs = listOf(
            CuesWithTiming(listOf(Cue.Builder().setText("x".repeat(24_001)).build()), 1_000_000, 2_000_000),
            CuesWithTiming(listOf(normal), 1_000_000, 0),
            CuesWithTiming(listOf(normal), C.TIME_UNSET, 2_000_000),
            CuesWithTiming(listOf(normal), 1_000_000, C.TIME_UNSET),
            CuesWithTiming(listOf(normal), Long.MAX_VALUE - 10, 100)
        )
        for (input in inputs) {
            val index = CueAheadIndex()
            index.add("fr", input)
            assertTrue("Rejected text must never be reported as a complete index", index.truncated)
            assertEquals(0, index.count)
        }
        val valid = CueAheadIndex()
        valid.add("fr", CuesWithTiming(listOf(normal), 1_000_000, 2_000_000))
        assertFalse(valid.truncated)
        assertEquals(1, valid.count)
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
    @Test fun corruptPreparedAudioFallsBackToDirectSpeechWithTheSameUtterance() {
        val done = CountDownLatch(1)
        val completions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val error = AtomicReference<String?>(null)
        var speech: AndroidSpeech? = null
        val text = "La ponctuation, même après un cache endommagé, reste conservée."
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext)
            prefs.enginePackage = instrument.context.packageName
            prefs.putBool("cache", true)
            speech = AndroidSpeech(instrument.targetContext, prefs, { _, _ -> },
                { id -> completions.add(id); done.countDown() }, { _, message -> error.set(message); done.countDown() })
        }
        try {
            waitFor("Fixture TTS was not ready") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.clearCache(); speech!!.prepareAhead(listOf(text)) }
            waitFor("Fixture did not prepare audio") { speech!!.preparedCount == 1 }
            val files = java.io.File(instrument.targetContext.cacheDir, "speech-v3")
                .listFiles { file -> file.extension == "wav" }.orEmpty()
            assertEquals("The isolated test expected one prepared WAV", 1, files.size)
            files.single().writeBytes(byteArrayOf(0, 1, 2, 3))
            instrument.runOnMainSync { speech!!.speak("fallback-same-utterance", text, 500, true) }
            assertTrue("Direct fallback did not finish", done.await(15, TimeUnit.SECONDS))
            assertNull(error.get())
            assertEquals(listOf("fallback-same-utterance"), completions.toList())
            instrument.runOnMainSync {
                assertEquals(0, speech!!.hits)
                assertEquals(1, speech!!.misses)
                assertTrue(speech!!.timingSource.contains("directe"))
            }
        } finally {
            instrument.runOnMainSync { speech?.close(); PlayerPreferences(instrument.targetContext).enginePackage = "" }
        }
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
    @Test fun hdrAndDolbyFormatsAreExcludedFromSdrEffects() {
        assertFalse(VisualTools.isHdrFormat(null))
        assertFalse(VisualTools.isHdrFormat(androidx.media3.common.Format.Builder().setSampleMimeType("video/avc").build()))
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) {
            val format = androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc")
                .setColorInfo(androidx.media3.common.ColorInfo.Builder().setColorTransfer(transfer).build()).build()
            assertTrue(VisualTools.isHdrFormat(format))
        }
        assertTrue(VisualTools.isHdrFormat(androidx.media3.common.Format.Builder().setSampleMimeType("video/dolby-vision").build()))
    }
    @Test fun allNewCoreRegressionsAlsoExecuteOnAndroidIcu() {
        assertEquals(listOf("M. Dupont arrive.","Il repart."),SpeechTools.segments("M. Dupont arrive. Il repart."))
        assertEquals(listOf("- Bonjour","- Au revoir"),SpeechTools.segments("- Bonjour\n- Au revoir"))
        assertNull(BlackBars.inspect(IntArray(10000){Color.BLACK},100,100))
    }
    @Test fun foregroundPriorityDoesNotLoseAnInFlightPreparationLookup() {
        var speech: AndroidSpeech? = null
        instrument.runOnMainSync {
            val p = PlayerPreferences(instrument.targetContext)
            p.enginePackage = instrument.context.packageName; p.putBool("cache", true)
            speech = AndroidSpeech(instrument.targetContext, p, { _, _ -> }, {}, { _, _ -> })
        }
        try {
            waitFor("TTS fixture not ready") { speech!!.isReady }
            instrument.runOnMainSync {
                speech!!.clearCache()
                speech!!.prepareBatch(listOf("Première préparation conservée.", "Seconde préparation conservée."))
                // The asynchronous cache callback cannot run until this main-loop turn ends.
                speech!!.cancelPreparation(keepPending = true)
            }
            waitFor("A foreground interruption lost a pending preparation item") {
                speech!!.preparedCount == 2 && !speech!!.bulkPreparation
            }
        } finally {
            instrument.runOnMainSync { speech?.close(); PlayerPreferences(instrument.targetContext).enginePackage = "" }
        }
    }
    @Test fun quickMixWindowIsDismissedWhenItsActivityIsDestroyed() {
        var dialog: android.app.AlertDialog? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                dialog = QuickMixDialog.show(a, a.session, a.session.prefs)
                assertTrue(dialog!!.isShowing)
            }
        }
        instrument.runOnMainSync { assertFalse("Quick mix leaked its destroyed Activity", dialog!!.isShowing) }
    }
}
