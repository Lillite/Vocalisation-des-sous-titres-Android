package fr.soustitresvoix.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.TranslationPlan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSrtTranslationOwnerRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        ShadowProcess.setPid(12345)
        val files = temporary.newFolder(); val cache = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Retained SRT state did not arrive")
    }

    @Test fun replacingTheUiObserverNeverClosesTheRetainedComputationOrNotifiesTheOldUi() {
        val app = context()
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val modelFile = temporary.newFile().apply { writeBytes(ByteArray(24)) }
        val model = LocalGgufModel(modelFile, "a".repeat(64), 24, modelFile.lastModified())
        val owner = LocalSrtTranslationOwner(app) { context, changed ->
            LocalSrtTranslationJob(context, changed, { config -> object : ContextualTranslationEngine {
                override val id = TranslationEngines.translationIdentity(config)
                override val maxBatchItems = 1
                override val maxBatchCharacters = TranslationPlan.MAX_CUE_CHARACTERS
                override val requiresDurableResult = true
                override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String, cancelled: () -> Boolean) = error("Context required")
                override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                    previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
                    entered.countDown()
                    assertTrue(proceed.await(10, TimeUnit.SECONDS))
                    if (cancelled()) throw TranslationCancelledException()
                    return texts.map { "FR $it" }
                }
                override fun close() { proceed.countDown() }
            } })
        }
        var oldEvents = 0; var newEvents = 0
        val old = owner.attach { oldEvents++ }
        try {
            await { !owner.job.state.busy }
            val source = temporary.newFile().apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nHello\n\n") }
            owner.job.importSource(Uri.fromFile(source), "fixture.srt")
            await { !owner.job.state.busy }
            owner.job.start("en", TranslationProviderConfig(TranslationProvider.LOCAL_GGUF, localModel = model))
            await { entered.count == 0L }
            val replacement = owner.attach { newEvents++ }
            old.close() // A late detach of the old screen must not detach its replacement.
            val before = oldEvents
            assertTrue(owner.job.state.busy)
            proceed.countDown()
            await { !owner.job.state.busy }
            assertTrue(owner.job.state.complete)
            assertEquals(before, oldEvents)
            assertTrue(newEvents > 0)
            assertNotNull(owner.job.completedExport())
            replacement.close()
        } finally { proceed.countDown(); owner.close() }
    }

    @Test fun viewModelStoreReturnsTheSameOwnerUntilItsExplicitFinalClear() {
        val app = context()
        val store = ViewModelStore()
        val first = ViewModelProvider(store, LocalSrtTranslationOwner.Factory(app))[LocalSrtTranslationOwner::class.java]
        val second = ViewModelProvider(store, LocalSrtTranslationOwner.Factory(app))[LocalSrtTranslationOwner::class.java]
        assertSame(first, second)
        await { !first.job.state.busy }
        first.job.importExample()
        await { !first.job.state.busy }
        store.clear()
        val retained = first.job.state
        first.job.importQualityExample()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame("Final owner clearing invalidates any future command", retained, first.job.state)
    }
    @Test fun actualMainActivityRecreationKeepsTheSameUsableSrtJob() {
        ShadowProcess.setPid(12345)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val firstActivity = controller.get()
            val first = ViewModelProvider(firstActivity, LocalSrtTranslationOwner.Factory(firstActivity.applicationContext))
                .get(LocalSrtTranslationOwner::class.java)
            await { !first.job.state.busy }
            first.job.importExample()
            await { !first.job.state.busy }
            controller.recreate()
            val nextActivity = controller.get()
            assertNotSame(firstActivity, nextActivity)
            val retained = ViewModelProvider(nextActivity, LocalSrtTranslationOwner.Factory(nextActivity.applicationContext))
                .get(LocalSrtTranslationOwner::class.java)
            assertSame(first, retained)
            retained.job.importQualityExample()
            await { !retained.job.state.busy && retained.job.state.sourceName == "qualite-traduction-en.srt" }
        } finally { controller.pause().stop().destroy() }
    }

}
