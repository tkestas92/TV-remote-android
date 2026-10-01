package lt.kantrybes.tvremote.data.adb

import android.util.Log
import lt.kantrybes.tvremote.data.settings.PictureSlider

/**
 * Sets a TCL picture value through ShowWindowService, the voice-assistant path.
 * Both commands run in one shell call; force-stop first so an open dialog
 * does not ignore the extras.
 */
internal class PictureController(
    private val shell: suspend (command: String) -> String?,
) {
    suspend fun set(item: String, value: Int): Boolean {
        val command = command(item, value) ?: return false
        val clamped = value.coerceIn(0, 100)
        val startedAt = System.currentTimeMillis()
        val output = shell(command) ?: return false
        if ("Error" in output) {
            Log.w(TAG, "Picture $item=$clamped failed: ${output.trim()}")
            return false
        }
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(TAG, "Picture $item=$clamped in $elapsed ms")
        return true
    }

    companion object {
        fun command(item: String, value: Int): String? {
            if (!PictureSlider.isItem(item)) return null
            val clamped = value.coerceIn(0, 100)
            return "am force-stop com.tcl.settings; " +
                "am startservice -n com.tcl.settings/.ShowWindowService " +
                "-a com.tcl.settings.SHOW_WINDOW " +
                "--es Type Settings --es ITEM $item --es MOTION SET --es MOTION_VALUE $clamped"
        }

        private const val TAG = "TvRemoteLaunch"
    }
}
