package fr.soustitresvoix.player

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android activity, demuxer, filesystem failure, index callback, copy button,
 * memory metrics and clipboard. No shadows or replacement data-source exception.
 * The only media is a disposable private copy of the generated demonstration.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class IndexDiagnosticPrivacyAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", 0).edit().clear().commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(80)
        }
        fail(message)
    }

    @Test fun copiedDiagnosticKeepsTheVisibleIndexFailureWithoutItsPrivateFilename() {
        val context = instrument.targetContext
        val marker = "synthetic-private-title-${UUID.randomUUID()}.mkv"
        val fixture = File(context.cacheDir, marker)
        context.resources.openRawResource(R.raw.demonstration).use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        // Select the installed test-APK engine so an unrelated system-engine
        // initialization error cannot overwrite the index error being checked.
        // Narration remains disabled: this regression does not synthesize text.
        PlayerPreferences(context).apply {
            enginePackage = instrument.context.packageName
            voiceName = "fixture-fr"
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var activity: MainActivity? = null
                scenario.onActivity { current ->
                    activity = current
                    current.session.open(SavedMedia(Uri.fromFile(fixture).toString(), "Synthetic diagnostic fixture"))
                }
                waitFor("The actual player, text track and local fixture engine did not become ready") {
                    val session = requireNotNull(activity).session
                    session.player.playbackState == Player.STATE_READY &&
                        session.selectedTextId() != null && session.speech.isReady
                }
                assertTrue("The test could not remove its private synthetic copy", fixture.delete())
                assertFalse(fixture.exists())
                scenario.onActivity { current ->
                    current.session.prepareRange(null)
                    assertTrue("The real prepareRange did not launch its indexer", current.session.isPreparing)
                }
                waitFor("The real index worker did not publish its missing-file outcome") {
                    val session = requireNotNull(activity).session
                    !session.isPreparing && session.indexingStatus != "Indexation complète non demandée" &&
                        !session.indexingStatus.startsWith("Indexation complète en cours")
                }
                scenario.onActivity { current ->
                    val status = current.session.indexingStatus
                    assertFalse("The missing file was unexpectedly indexed successfully",
                        status.startsWith("Indexation terminée"))
                    assertTrue("The index failure lost its visible reason", status.isNotBlank())
                    assertTrue("The activity no longer displays the real index failure",
                        descendants(current.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == status })
                    current.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Diagnostic regression sentinel", "No diagnostic copied yet"))
                }
                onView(withText("Réglages de la voix et du confort")).perform(scrollTo(), click())
                // Target each dialog explicitly: an unscoped Back can select
                // the unfocused activity while the dialog below regains focus.
                // Nested use scopes also preserve an assertion failure if a
                // later dialog cleanup fails, and still attempt both closures.
                Closeable {
                    onView(withText("Appliquer et fermer")).inRoot(isDialog()).perform(click())
                }.use {
                    onView(withText("Copier le diagnostic complet")).inRoot(isDialog()).perform(scrollTo(), click())
                    Closeable {
                        onView(withText("Fermer")).inRoot(isDialog()).perform(click())
                    }.use {
                        onView(withText("Diagnostic copié à ta demande ; aucun dialogue ou nom de fichier inclus."))
                            .inRoot(isDialog()).check(matches(isDisplayed()))
                        scenario.onActivity { current ->
                            val clip = requireNotNull(current.getSystemService(ClipboardManager::class.java).primaryClip)
                            val copied = clip.getItemAt(0).text.toString()
                            assertEquals("Diagnostic local", clip.description.label.toString())
                            assertTrue("The copy lost the actual index failure", copied.contains(current.session.indexingStatus))
                            assertFalse("The copied diagnostic exposes a private filename despite its explicit UI promise: $marker",
                                copied.contains(marker))
                        }
                    }
                }
            }
        } finally {
            PlayerPreferences(context).enginePackage = ""
            fixture.delete()
        }
    }
}
