package fr.soustitresvoix.player

import android.app.ActivityManager
import android.os.Debug
import android.os.Process
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivityManager

/** Test-only JVM precondition for the diagnostic-copy regression.
 * Robolectric 4.17 does not implement getProcessMemoryInfo: the unrelated PSS
 * lookup would return null before the diagnostic reaches the clipboard.
 * Supply one zero-valued synthetic metric for the local PID. This is neither
 * a measured Android PSS value nor a replacement for any filesystem/app path.
 * All other ActivityManager behavior remains in the official parent shadow.
 */
@Implements(ActivityManager::class)
class DiagnosticProcessMemoryShadow : ShadowActivityManager() {
    @Implementation
    protected fun getProcessMemoryInfo(pids: IntArray): Array<Debug.MemoryInfo> {
        require(pids.contentEquals(intArrayOf(Process.myPid()))) {
            "This JVM regression supplies a memory metric only for its own process"
        }
        return arrayOf(Debug.MemoryInfo())
    }
}
