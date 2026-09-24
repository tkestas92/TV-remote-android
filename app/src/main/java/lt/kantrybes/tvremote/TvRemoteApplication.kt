package lt.kantrybes.tvremote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lt.kantrybes.tvremote.data.adb.AdbClient
import lt.kantrybes.tvremote.data.remote.RemoteSessionController
import java.io.File

class TvRemoteApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal lateinit var remote: RemoteSessionController
        private set

    internal lateinit var adb: AdbClient
        private set

    override fun onCreate() {
        super.onCreate()
        adb = AdbClient(File(filesDir, "adb-key"))
        remote = RemoteSessionController(this, appScope)
    }
}
