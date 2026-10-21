package lt.kantrybes.tvremote.ui.share

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.YouTubeShare
import lt.kantrybes.tvremote.data.settings.SettingsRepository

/** No UI. YouTube links open on the TV; anything else is a toast, then the activity finishes. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The share sheet uses startActivity, so getCallingActivity() is null for a normal
        // share. Ignoring a null caller would drop those requests.
        if (!reserveOpen()) {
            finish()
            return
        }
        val startedAt = System.currentTimeMillis()
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = YouTubeShare.find(shared)
        if (url == null) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Toast.makeText(this, R.string.share_opening, Toast.LENGTH_SHORT).show()
        val app = application as TvRemoteApplication
        app.appScope.launch {
            val host = SettingsRepository(app).read().pairedHost
            val opened = app.adb.openYouTube(host, url, startedAt)
            if (!opened) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, R.string.adb_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }

    private companion object {
        const val OPEN_INTERVAL_MS = 2_000L
        private var lastOpenAt = 0L

        fun reserveOpen(): Boolean {
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (now - lastOpenAt < OPEN_INTERVAL_MS) return false
                lastOpenAt = now
                return true
            }
        }
    }
}
