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
        assertEquals(measured.render(), job.state.performance)
        assertNotNull(job.completedExport())
        lateListener?.invoke(measured.copy(backend = "VULKAN", gpuObserved = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A completed task cannot be changed by a late native reply", measured.render(), job.state.performance)
        job.importExample()
        assertNull(job.state.performance)
        await { !job.state.busy }
        assertNull(job.state.performance)
        assertFalse(job.state.complete)
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
