package fr.soustitresvoix.player

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real Android ViewGroup callbacks reproduce the nested fullscreen rebuild.
 * No media, rotation timing, arbitrary delay or substitute view implementation
 * is needed to exercise the reparenting failure. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class UiRebuildInstrumentationTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var originalSettings: Map<String, Any>

    @Before fun isolatePausedLibrary() {
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext)
            originalSettings = prefs.settingsSnapshot()
            prefs.clearMedia()
            prefs.narration = false
            prefs.putInt("orientation", 0)
        }
    }

    @After fun restoreSettings() {
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext)
            if (::originalSettings.isInitialized) prefs.restoreSettings(originalSettings)
            prefs.clearMedia()
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun assertSharedPausedViews(
        activity: MainActivity, player: Player, playerView: PlayerView, surface: View, overlay: View
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val views = descendants(content)
        assertSame("Rebuilding the presentation must retain its player", player, activity.session.player)
        assertSame(player, playerView.player)
        assertEquals("The retained PlayerView must occur exactly once in the attached tree", 1,
            views.count { it === playerView })
        assertNotNull(playerView.parent)
        assertTrue(playerView.isAttachedToWindow)
        assertSame("Rebuilding must retain the video surface view", surface, playerView.videoSurfaceView)
        val overlays = views.filter { it.tag == "narration-subtitle-overlay" }
        assertEquals("The narration overlay must not be duplicated", 1, overlays.size)
        assertSame(overlay, overlays.single())
        assertSame(playerView, overlay.parent)
        assertFalse("A presentation change must preserve explicit pause", activity.session.machine.snapshot().wantsPlayback)
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
    }

    @Test fun configurationCallbackDuringFullscreenReparentingKeepsLatestModeAndPause() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var retainedPlayer: Player
            lateinit var retainedView: PlayerView
            lateinit var retainedSurface: View
            lateinit var retainedOverlay: View
            lateinit var removalParent: FrameLayout
            val reentrantCalls = AtomicInteger()

            scenario.onActivity { activity ->
                activity.session.pause()
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                retainedView = descendants(content).filterIsInstance<PlayerView>().single()
                retainedPlayer = activity.session.player
                retainedSurface = requireNotNull(retainedView.videoSurfaceView)
                retainedOverlay = descendants(retainedView).single { it.tag == "narration-subtitle-overlay" }

                // Two native wrappers put the removal callback between the
                // outer rebuild's detach and its eventual addView. The former
                // unguarded implementation reattached the same child in a
                // nested build, then threw "already has a parent" on return.
                val previousParent = retainedView.parent as ViewGroup
                val previousIndex = previousParent.indexOfChild(retainedView)
                val previousLayout = retainedView.layoutParams
                previousParent.removeView(retainedView)
                val wrapper = FrameLayout(activity)
                removalParent = FrameLayout(activity)
                wrapper.addView(removalParent, FrameLayout.LayoutParams(-1, -1))
                removalParent.addView(retainedView, FrameLayout.LayoutParams(-1, -1))
                previousParent.addView(wrapper, previousIndex, previousLayout)

                removalParent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) = Unit
                    override fun onChildViewRemoved(parent: View?, child: View?) {
                        if (child !== retainedView) return
                        removalParent.setOnHierarchyChangeListener(null)
                        reentrantCalls.incrementAndGet()
                        val changed = Configuration(activity.resources.configuration).apply { screenWidthDp += 1 }
                        activity.onConfigurationChanged(changed)
                        activity.setFullscreen(false)
                    }
                })
                activity.setFullscreen(true)
            }

            // The replacement build is posted to the main queue. Waiting for
            // its actual idle boundary makes no assumption about frame time.
            instrument.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("The native removal callback must be exercised exactly once", 1, reentrantCalls.get())
                assertEquals(0, removalParent.childCount)
                assertNull("The last requested mode was the library", activity.findViewById<View>(R.id.cinema_root))
                assertNotNull(activity.findViewById<View>(R.id.open_video))
                assertSharedPausedViews(activity, retainedPlayer, retainedView, retainedSurface, retainedOverlay)
            }

            repeat(3) {
                scenario.onActivity { it.setFullscreen(true) }
                instrument.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.cinema_root))
                    assertSharedPausedViews(activity, retainedPlayer, retainedView, retainedSurface, retainedOverlay)
                    activity.setFullscreen(false)
                }
                instrument.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertNull(activity.findViewById<View>(R.id.cinema_root))
                    assertNotNull(activity.findViewById<View>(R.id.open_video))
                    assertSharedPausedViews(activity, retainedPlayer, retainedView, retainedSurface, retainedOverlay)
                }
            }
        }
    }
}
