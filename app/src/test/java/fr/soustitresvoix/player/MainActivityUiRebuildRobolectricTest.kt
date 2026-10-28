package fr.soustitresvoix.player

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Real Activity/ViewGroup ownership. A synchronous hierarchy callback reproduces
 * an orientation/exit request inside the removal, without timing-dependent waits. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityUiRebuildRobolectricTest {
    @Before fun resetPreferences() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("player", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("narration", false).putBoolean("cache", false).putInt("orientation", 0).commit()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun video(activity: MainActivity) = descendants(activity.findViewById<ViewGroup>(android.R.id.content))
        .filterIsInstance<PlayerView>().single()
    private fun nestedParent(activity: MainActivity, video: PlayerView, removed: () -> Unit): FrameLayout {
        val parent = video.parent as ViewGroup
        val index = parent.indexOfChild(video)
        val parameters = video.layoutParams
        parent.removeView(video)
        val outer = FrameLayout(activity)
        val inner = FrameLayout(activity)
        outer.addView(inner, FrameLayout.LayoutParams(-1, -1))
        inner.addView(video, FrameLayout.LayoutParams(-1, -1))
        parent.addView(outer, index, parameters)
        var invoked = false
        inner.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) = Unit
            override fun onChildViewRemoved(parent: View, child: View) {
                if (child === video && !invoked) { invoked = true; removed() }
            }
        })
        return inner
    }
    private fun assertShared(activity: MainActivity, expectedView: PlayerView, expectedPlayer: Any,
                             expectedSurface: View?, expectedOverlay: SubtitleView) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        assertSame(expectedView, video(activity))
        assertSame(expectedPlayer, activity.session.player)
        assertSame(expectedPlayer, expectedView.player)
        assertSame(expectedSurface, expectedView.videoSurfaceView)
        assertSame(expectedOverlay, descendants(root).filterIsInstance<SubtitleView>()
            .single { it.tag == "narration-subtitle-overlay" })
        assertSame(expectedView, expectedOverlay.parent)
        assertFalse(activity.session.machine.snapshot().wantsPlayback)
        assertFalse(activity.session.player.playWhenReady)
    }

    @Test fun configurationAndOppositeFullscreenDuringNestedDetachmentUseLatestLayout() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = owner.get()
            val video = video(activity)
            val player = activity.session.player
            val surface = video.videoSurfaceView
            val overlay = video.findViewWithTag<SubtitleView>("narration-subtitle-overlay")
            var callbacks = 0
            val detachedParent = nestedParent(activity, video) {
                callbacks++
                activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                })
                activity.setFullscreen(false)
            }
            activity.setFullscreen(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, callbacks)
            assertEquals(0, detachedParent.childCount)
            assertNotNull(activity.findViewById<View>(R.id.open_video))
            assertNull(activity.findViewById<View>(R.id.cinema_root))
            assertShared(activity, video, player, surface, overlay)
            activity.setFullscreen(true)
            assertNotNull(activity.findViewById<View>(R.id.cinema_root))
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
            assertNotNull(activity.findViewById<View>(R.id.open_video))
            assertShared(activity, video, player, surface, overlay)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun configurationCyclesKeepTheSharedVideoSurfaceAndOverlayInTheCurrentRoot() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = owner.get()
            val video = video(activity)
            val player = activity.session.player
            val surface = video.videoSurfaceView
            val overlay = video.findViewWithTag<SubtitleView>("narration-subtitle-overlay")
            repeat(3) { index ->
                activity.setFullscreen(true)
                owner.configurationChange(Configuration(activity.resources.configuration).apply {
                    orientation = if (index % 2 == 0) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 960 else 540
                    screenHeightDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 540 else 960
                })
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(activity, owner.get())
                assertNotNull(activity.findViewById<View>(R.id.cinema_root))
                assertShared(activity, video, player, surface, overlay)
                activity.setFullscreen(false)
                assertNotNull(activity.findViewById<View>(R.id.open_video))
                assertShared(activity, video, player, surface, overlay)
            }
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun destroyingAnOwnerCancelsItsCoalescedRebuild() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = owner.get()
        val video = video(activity)
        var removals = 0
        nestedParent(activity, video) {
            removals++
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
        }
        activity.setFullscreen(true)
        val installedRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        owner.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, removals)
        assertSame(installedRoot, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
        assertNull(video.player)
        assertTrue(activity.isDestroyed)
    }

    @Test fun activityDestroyDismissesTheSessionResumeDialogItOwns() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = owner.get()
        MainActivity::class.java.getDeclaredField("pendingResumeOffer").apply { isAccessible = true }.setBoolean(activity, true)
        MainActivity::class.java.getDeclaredMethod("offerSessionResume").apply { isAccessible = true }.invoke(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        owner.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("The resume dialog must leave the destroyed Activity window", dialog.isShowing)
    }

    @Test fun standaloneSrtEntryWorksWithoutVideoAndPreservesTheReadersPlaybackIntent() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = owner.get()
            assertNull(activity.session.media)
            // A synthetic playWhenReady flag makes an accidental session.pause()
            // observable without claiming real audio/video playback in this JVM test.
            activity.session.player.playWhenReady = true
            val intentBefore = activity.session.machine.snapshot().wantsPlayback
            descendants(activity.findViewById<ViewGroup>(android.R.id.content))
                .filterIsInstance<android.widget.Button>().single { it.text.toString() == "Traduire un fichier SRT hors ligne" }
                .performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertEquals(intentBefore, activity.session.machine.snapshot().wantsPlayback)
            assertTrue(activity.session.player.playWhenReady)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(activity.session.player.playWhenReady)
            assertNull(activity.session.media)
        } finally { owner.pause().stop().destroy() }
    }
}
