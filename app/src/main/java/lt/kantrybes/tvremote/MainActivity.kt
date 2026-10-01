package lt.kantrybes.tvremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import lt.kantrybes.tvremote.ui.TvRemoteApp
import lt.kantrybes.tvremote.ui.theme.TVRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            TVRemoteTheme {
                TvRemoteApp()
            }
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
