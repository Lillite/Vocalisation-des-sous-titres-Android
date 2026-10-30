package fr.soustitresvoix.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.TranslationPlan
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSrtTranslationJobRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var application: Context
    private lateinit var model: LocalGgufModel
    private val jobs = mutableListOf<LocalSrtTranslationJob>()
    private val srt = """
        1
        00:00:00,000 --> 00:01:30,000
        EXIT

        2
        00:00:01,234 --> 00:00:04,567
        Hello Élodie 😀

        3
        00:00:01,234 --> 00:00:04,567
        Hello Élodie 😀

        4
        00:00:08,000 --> 00:00:10,000
        Hello Élodie 😀

    """.trimIndent()

    @Before fun setUp() {
        val files = temporary.newFolder("private-files")
        val cache = temporary.newFolder("private-cache")
        application = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        val file = temporary.newFile("synthetic-model.gguf").apply { writeBytes(ByteArray(24)) }
        model = LocalGgufModel(file, "a".repeat(64), file.length(), file.lastModified())
    }

    @After fun closeJobs() {
        jobs.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun configuration(context: String = "") = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
        localModel = model, localContext = context)

    private open class Engine(config: TranslationProviderConfig,
        private val action: (Int, List<String>) -> List<String> = { _, texts -> texts.map { "FR $it" } }
    ) : ContextualTranslationEngine {
        override val id = TranslationEngines.translationIdentity(config)
        override val maxBatchItems = 1
        override val maxBatchCharacters = TranslationPlan.MAX_CUE_CHARACTERS
        override val requiresDurableResult = true
        val inputs = mutableListOf<List<String>>()
        val contexts = mutableListOf<Pair<List<String>, List<String>>>()
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> = throw AssertionError("Journal context must be supplied")
        override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
            inputs += texts.toList(); contexts += previousSources.toList() to previousTranslations.toList()
            return action(inputs.size, texts)
        }
        override fun close() = Unit
    }

    private fun job(factory: (TranslationProviderConfig) -> TranslationEngine,
        changed: (LocalSrtTranslationJob.State) -> Unit = {}): LocalSrtTranslationJob =
        LocalSrtTranslationJob(application, {
            assertSame("UI callbacks must run on the main looper", Looper.getMainLooper(), Looper.myLooper())
            changed(it)
        }, factory).also { jobs += it; await { !it.state.busy } }

    private fun import(job: LocalSrtTranslationJob, text: String = srt, name: String = "fixture.srt"): File {
        val original = temporary.newFile().apply { writeText(text, Charsets.UTF_8) }
        job.importSource(Uri.fromFile(original), name)
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.hasSource)
        return original
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("The expected asynchronous job state was not reached")
    }

    private fun prepareNativeModel(): LocalGgufModelStore = LocalGgufModelStore(application).also { store ->
        model = store.importModel(ByteArrayInputStream(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0) + ByteArray(16)))
    }

    @Test fun completedLocalTrackWaitsForReleaseBeforeOfferingExportOrStartingAnotherTask() {
        val store = prepareNativeModel()
        val backend = DeferredTranslationCleanupBackend()
        var constructions = 0
        val job = job({ config ->
            constructions++
            LocalGgufTranslationEngine(requireNotNull(config.localModel), store, { backend })
        })
        import(job)
        try {
            job.start("en", configuration())
            await { backend.cleanupEntered.count == 0L }
            assertTrue("A missing release ACK must keep the task busy", job.state.busy)
            assertFalse(job.state.complete)
            assertNull(job.completedExport())
            assertNull(job.completedReview())
            job.start("en", configuration())
            assertEquals("A second owner cannot be constructed during release", 1, constructions)
            backend.acknowledgeRelease.countDown()
            await { !job.state.busy }
            assertTrue(job.state.status, job.state.complete)
            assertNotNull(job.completedReview())
            val exported = requireNotNull(job.completedExport())
            assertEquals(4, Regex(" --> ").findAll(exported).count())
            job.start("en", configuration())
            await { !job.state.busy }
            assertTrue(job.state.status, job.state.complete)
            assertEquals(exported, job.completedExport())
            assertEquals(2, constructions)
            assertEquals("The retained result must avoid a second native open", 1, backend.openCalls.get())
        } finally { backend.acknowledgeRelease.countDown() }
    }

    @Test fun cancelledLocalTaskRemainsBusyUntilReleaseAndResumesOnlyItsValidatedPrefix() {
        val store = prepareNativeModel()
        val blockedGeneration = CountDownLatch(1)
        val backend = DeferredTranslationCleanupBackend().apply {
            beforeGeneration = { call, _ ->
                if (call == 2) {
                    blockedGeneration.countDown()
                    assertTrue(nonBlockingClose.await(10, TimeUnit.SECONDS))
                }
            }
        }
        var constructions = 0
        val job = job({ config ->
            constructions++
            LocalGgufTranslationEngine(requireNotNull(config.localModel), store, { backend })
        })
        import(job)
        try {
            job.start("en", configuration())
            await { blockedGeneration.count == 0L && job.state.done == 1 }
            job.cancel()
            await { backend.cleanupEntered.count == 0L }
            assertTrue("UI cancellation must return while native release is pending", job.state.busy)
            job.start("en", configuration())
            assertEquals(1, constructions)
            assertNull(job.completedExport())
            backend.acknowledgeRelease.countDown()
            await { !job.state.busy }
            assertFalse(job.state.complete)
            job.start("en", configuration())
            await { !job.state.busy }
            assertTrue(job.state.status, job.state.complete)
            assertEquals(2, constructions)
            assertEquals("One completed occurrence must be restored, not translated again", 5, backend.generateCalls.get())
            assertEquals(4, Regex(" --> ").findAll(requireNotNull(job.completedExport())).count())
        } finally { backend.nonBlockingClose.countDown(); backend.acknowledgeRelease.countDown() }
    }

    @Test fun localReleaseFailureRemainsVisibleAndCannotDiscardACompletedExport() {
        val store = prepareNativeModel()
        val backend = DeferredTranslationCleanupBackend(LocalGgufRuntime.Code.TIMEOUT)
        val job = job({ config -> LocalGgufTranslationEngine(requireNotNull(config.localModel), store, { backend }) })
        import(job)
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertTrue(job.state.status, job.state.status.contains("libération du modèle local n’a pas été confirmée"))
        val output = requireNotNull(job.completedExport())
        assertEquals(4, Regex(" --> ").findAll(output).count())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Late progress cannot overwrite the cleanup warning",
            job.state.status.contains("libération du modèle local n’a pas été confirmée"))
        assertEquals(output, job.completedExport())
    }

    @Test fun translatesAndExportsAnSrtWithoutAnyVideoWhilePreservingEveryOccurrence() {
        lateinit var engine: Engine
        val job = job({ Engine(it).also { value -> engine = value } })
        val original = import(job)
        val sourceBytes = original.readBytes()
        SrtSourceIntegrity.check(original, 4)
        try { SrtSourceIntegrity.check(original, 3); fail("The shared guard must reject a missing occurrence") }
        catch (_: SrtSourceIntegrity.InvalidSource) { }
        assertNull(job.completedExport())
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertEquals(4, job.state.done)
        assertEquals(4, engine.inputs.size)
        assertEquals(3, engine.inputs.flatten().count { it == "Hello Élodie 😀" })
        val result = requireNotNull(job.completedExport())
        assertEquals(4, Regex(" --> ").findAll(result).count())
        assertEquals(2, Regex("00:00:01,234 --> 00:00:04,567").findAll(result).count())
        assertTrue(result.contains("00:00:00,000 --> 00:01:30,000"))
        assertEquals(3, Regex("FR Hello Élodie 😀").findAll(result).count())
        assertEquals("fixture.fr.srt", job.exportFileName())
        assertTrue(requireNotNull(job.completedExport(SubtitleExportFormat.VTT)).startsWith("WEBVTT\n\n"))
        assertArrayEquals(sourceBytes, original.readBytes())
    }

    @Test fun integratedEnglishExampleImportsPassivelyAndPreservesItsEightDistinctOccurrences() {
        var constructions = 0
        val job = job({ Engine(it).also { constructions++ } })
        job.importExample()
        await { !job.state.busy }
        assertEquals("exemple-traduction-en.srt", job.state.sourceName)
        assertEquals(0, constructions)
        assertFalse(job.state.complete)
        assertNull(job.completedExport())
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertEquals(8, job.state.done)
        val output = requireNotNull(job.completedExport())
        assertEquals(8, Regex(" --> ").findAll(output).count())
        assertEquals(3, Regex("FR Wait for me!").findAll(output).count())
        assertEquals(2, Regex("00:00:08,000 --> 00:00:10,000").findAll(output).count())
        assertTrue(output.contains("00:00:00,000 --> 00:00:20,000"))
        assertTrue(output.contains("FR Good evening, Élodie."))
        assertTrue(output.contains("FR Platform 4 — tickets cost €12.50."))
        assertEquals("exemple-traduction-en.fr.srt", job.exportFileName())
    }

    @Test fun completedReviewUsesTheSourceAndTargetLanguagesAndCannotLeakIntoANewSourceSelection() {
        val source = """
            1
            00:00:00,000 --> 00:00:03,000
            twelve minutes

            2
            00:00:04,000 --> 00:00:07,000
            1,024 records

            3
            00:00:08,000 --> 00:00:11,000
            at 07:30

            4
            00:00:12,000 --> 00:00:15,000
            twelve minutes

        """.trimIndent()
        val translations = listOf("12 minutes", "1 024 documents", "à 7h30", "onze minutes")
        val job = job({ Engine(it) { call, _ -> listOf(translations[call - 1]) } })
        import(job, source)
        assertNull(job.completedReview())
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        val report = requireNotNull(job.completedReview())
        assertTrue(report.contains("en vers fr"))
        assertTrue(report.contains("Occurrence 4"))
        for (correct in 1..3) assertFalse(report, report.contains("Occurrence $correct\n"))
        assertTrue(report.contains("Source : twelve minutes"))
        assertTrue(report.contains("Traduction : onze minutes"))
        assertTrue(report.contains("ne prouve pas la fidélité"))
        assertFalse(report.contains(model.file.absolutePath))
        assertFalse(report.contains(model.sha256))
        assertEquals(4, Regex(" --> ").findAll(requireNotNull(job.completedExport())).count())
        job.importExample()
        assertNull(job.completedReview())
        await { !job.state.busy }
        assertNull(job.completedReview())
        assertNull(job.completedExport())
    }

    @Test fun aNewOwnerRestoresTheSourceAndCompleteDurableResultAfterCacheEviction() {
        val first = job({ Engine(it) })
        import(first)
        first.start("en", configuration())
        await { !first.state.busy }
        val output = first.completedExport()
        assertNotNull(output)
        first.close()
        File(application.cacheDir, "local-srt-translations-v1").deleteRecursively()
        var computations = 0
        val restored = job({ Engine(it) { _, _ -> computations++; throw AssertionError("Do not rerun the model") } })
        assertEquals("fixture.srt", restored.state.sourceName)
        restored.start("en", configuration())
        await { !restored.state.busy }
        assertTrue(restored.state.status, restored.state.complete)
        assertEquals(output, restored.completedExport())
        assertEquals(0, computations)
    }

    @Test fun nativePerformanceIsPublishedOnMainAndLateMeasurementsCannotReplaceANewerSourceState() {
        val measured = LocalTranslationPerformance(requestedMode = "AUTO", backend = "CPU", device = "CPU",
            promptTokens = 40, cachedPromptTokens = 30, promptEvaluatedTokens = 10,
            generatedTokens = 9, generationMs = 1500, endToEndMs = 2000)
        var lateListener: ((LocalTranslationPerformance) -> Unit)? = null
        val job = job({ config -> object : Engine(config), LocalTranslationPerformanceSource {
            private var listener: ((LocalTranslationPerformance) -> Unit)? = null
            override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) {
                this.listener = listener
                if (listener != null) lateListener = listener
            }
            override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
                listener?.invoke(measured)
                return super.translateWithContext(texts, sourceLanguage, targetLanguage, previousSources, previousTranslations, cancelled)
            }
        } })
        import(job)
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        val completedReport = requireNotNull(job.state.performance)
        assertTrue(completedReport.contains("Préparation locale commencée"))
        assertTrue(completedReport.endsWith(measured.render()))
        assertNotNull(job.completedExport())
        lateListener?.invoke(measured.copy(backend = "VULKAN", gpuObserved = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A completed task cannot be changed by a late native reply", completedReport, job.state.performance)
        job.importExample()
        assertNull(job.state.performance)
        await { !job.state.busy }
        assertNull(job.state.performance)
        assertFalse(job.state.complete)
    }


    @Test fun failedBeforeFirstOccurrenceStillHasAnAttemptReportAndPreservesTheNativeDiagnostic() {
        val nativeDiagnostic = requireNotNull(LocalGgufDiagnostic.parse(org.json.JSONObject()
            .put("stage", "MODEL_LOAD").put("reason", "ALLOCATION_FAILED").put("elapsedMs", 15)
            .put("openClStatus", org.json.JSONObject.NULL).put("directPlatforms", -1).put("icdPlatforms", -1)
            .put("gpuDevices", -1).put("probeCached", false)))
        val failureReport = LocalGgufAttemptReport.failure(LocalGgufRuntime.Code.DIED, null, 30)
        val job = job({ throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROCESS_DIED,
            nativeDiagnostic, failureReport) })
        import(job)
        job.start("en", configuration())
        assertNotNull("A report must exist before engine construction or any successful load", job.state.performance)
        assertTrue(requireNotNull(job.state.performance).contains("Aucun chargement ou calcul réussi"))
        await { !job.state.busy }
        assertEquals(0, job.state.done)
        assertFalse(job.state.complete)
        assertNull(job.completedExport())
        val report = requireNotNull(job.state.performance)
        assertTrue(report.contains("ALLOCATION_FAILED"))
        assertTrue(report.contains("[DIED]"))
        assertTrue(report.contains("cause reste indéterminée"))
        assertFalse(report.contains(model.file.absolutePath))
        assertFalse(report.contains("Hello Élodie"))
        job.importExample()
        await { !job.state.busy }
        assertNull("A different source must not inherit the previous failed attempt", job.state.performance)
    }

    @Test fun anOldModelDescriptorCannotSilentlyRestoreItsCachedResultAfterAnotherModelWasImported() {
        fun fixture(tag: Byte) = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0) + ByteArray(16) + byteArrayOf(tag)
        val store = LocalGgufModelStore(application)
        model = store.importModel(ByteArrayInputStream(fixture(1)))
        val previousConfig = configuration()
        var constructions = 0
        val job = job({ Engine(it).also { constructions++ } })
        import(job)
        job.start("en", previousConfig)
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertEquals(1, constructions)
        val replacement = store.importModel(ByteArrayInputStream(fixture(2)))
        assertNotEquals(previousConfig.localModel?.sha256, replacement.sha256)
        job.start("en", previousConfig)
        await { !job.state.busy }
        assertFalse(job.state.complete)
        assertTrue(job.state.status, job.state.status.contains("remplacé depuis sa sélection"))
        assertNull(job.completedExport())
        assertEquals(1, constructions)
        model = replacement
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertEquals(2, constructions)
    }

    @Test fun cancellationKeepsOnlyValidatedOccurrencesAndARecreatedOwnerResumesWithTheirContext() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val first = job({ config -> object : Engine(config, { call, texts ->
            if (call == 2) { entered.countDown(); released.await(10, TimeUnit.SECONDS) }
            texts.map { "FR $it" }
        }) {
            override fun close() { released.countDown() }
        } })
        import(first)
        first.start("en", configuration())
        await { entered.count == 0L && first.state.done == 1 }
        assertNull(first.completedExport())
        first.cancel()
        await { !first.state.busy }
        assertFalse(first.state.complete)
        assertNull(first.completedExport())
        first.close()
        lateinit var resumedEngine: Engine
        val resumed = job({ Engine(it).also { value -> resumedEngine = value } })
        resumed.start("en", configuration())
        await { !resumed.state.busy }
        assertTrue(resumed.state.status, resumed.state.complete)
        assertEquals(3, resumedEngine.inputs.size)
        assertEquals(listOf("EXIT") to listOf("FR EXIT"), resumedEngine.contexts.first())
        assertEquals(4, Regex(" --> ").findAll(requireNotNull(resumed.completedExport())).count())
    }

    @Test fun invalidUtf8RequiresAnExplicitEncodingAndKeepsThePreviousSourceAndExport() {
        val job = job({ Engine(it) })
        import(job)
        job.start("en", configuration())
        await { !job.state.busy }
        val previousExport = job.completedExport()
        val encoded = temporary.newFile().apply {
            writeBytes("1\n00:00:00,000 --> 00:00:01,000\nCafé\n\n".toByteArray(Charsets.ISO_8859_1))
        }
        job.importSource(Uri.fromFile(encoded), "ancien.srt")
        await { !job.state.busy }
        assertTrue(job.state.encodingRequired)
        assertEquals("fixture.srt", job.state.sourceName)
        assertEquals(previousExport, job.completedExport())
        job.importSource(Uri.fromFile(encoded), "ancien.srt", "windows-1252")
        await { !job.state.busy }
        assertFalse(job.state.encodingRequired)
        assertEquals("ancien.srt", job.state.sourceName)
        assertFalse(job.state.complete)
        assertNull(job.completedExport())
        job.start("en", configuration())
        await { !job.state.busy }
        assertTrue(job.state.status, job.state.complete)
        assertTrue(requireNotNull(job.completedExport()).contains("FR Café"))
    }

    @Test fun standalonePreparationRejectsRemoteProvidersWithoutConstructingAnyEngine() {
        var constructed = 0
        val job = job({ Engine(it).also { constructed++ } })
        import(job)
        job.start("en", TranslationProviderConfig(TranslationProvider.AZURE, apiKey = "not-a-real-key"))
        assertFalse(job.state.busy)
        assertEquals(0, constructed)
        assertTrue(job.state.status.contains("GGUF local"))
        assertNull(job.completedExport())
    }

    @Test fun aMalformedSrtBlockBetweenValidOccurrencesIsRefusedBeforeAnyModelCall() {
        var constructed = 0
        val job = job({ Engine(it).also { constructed++ } })
        for (invalidTiming in listOf("not a timing", "00:00:02,000 --> invalid")) {
            val malformed = """
                1
                00:00:00,000 --> 00:00:01,000
                First valid occurrence.

                2
                $invalidTiming
                This occurrence must never disappear silently.

                3
                00:00:04,000 --> 00:00:05,000
                Last valid occurrence.

            """.trimIndent()
            import(job, malformed, "malformed.srt")
            job.start("en", configuration())
            await { !job.state.busy }
            assertFalse(job.state.complete)
            assertTrue(job.state.status, job.state.status.contains("bloc incomplet ou illisible"))
            assertEquals(0, constructed)
            assertNull(job.completedExport())
        }
    }

    @Test fun corruptPrivateSourceRecordIsRejectedAndCannotReadAnArbitraryPath() {
        val first = job({ Engine(it) })
        import(first)
        first.close()
        val record = File(application.filesDir, "local-srt-source-v1/selection.json")
        record.writeText("""{"version":1,"file":"../outside.srt","name":"fixture.srt","mime":"application/x-subrip","charset":"UTF-8"}""")
        val restored = job({ Engine(it) })
        assertFalse(restored.state.hasSource)
        assertTrue(restored.state.status.contains("restauré"))
        assertNull(restored.completedExport())
    }
}
