package lt.kantrybes.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lt.kantrybes.tvremote.ui.TvRemoteApp
import lt.kantrybes.tvremote.ui.theme.TVRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TVRemoteTheme {
                TvRemoteApp()
            }
        }
    }
}
