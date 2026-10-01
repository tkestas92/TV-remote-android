package lt.kantrybes.tvremote

import android.app.Application
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.data.adb.AdbClient
import lt.kantrybes.tvremote.data.adb.AppLauncher
import lt.kantrybes.tvremote.data.remote.RemoteSessionController
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import lt.kantrybes.tvremote.media.MediaCoordinator
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TvRemoteApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal lateinit var remote: RemoteSessionController
        private set

    internal lateinit var adb: AdbClient
        private set

    internal lateinit var media: MediaCoordinator
        private set

    private val warmGeneration = AtomicInteger(0)
    private var warmJob: Job? = null
    private var prefetchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        adb = AdbClient(File(filesDir, "adb-key"))
        remote = RemoteSessionController(
            application = this,
            scope = appScope,
            onSessionReady = ::warmAdb,
            onForegroundStop = ::closeAdb,
        )
        media = MediaCoordinator(this)
        media.start()
    }

    private fun warmAdb(host: String) {
        if (host.isBlank()) return
        val token = warmGeneration.get()
        warmJob = appScope.launch(adb.dispatcher) {
            val previous = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (token != warmGeneration.get()) return@launch
                val startedAt = System.nanoTime()
                if (!adb.isReady(host)) adb.connection(host)
                if (token != warmGeneration.get()) {
                    adb.drop()
                    return@launch
                }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                Log.i(LAUNCH_TAG, "adb warm in $elapsed ms")
                prefetchLaunchers(host, token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(LAUNCH_TAG, "adb warm failed: ${error.message}")
            } finally {
                Process.setThreadPriority(previous)
            }
        }
    }

    private fun prefetchLaunchers(host: String, token: Int) {
        prefetchJob?.cancel()
        prefetchJob = appScope.launch(Dispatchers.IO) {
            if (token != warmGeneration.get()) return@launch
            val settings = SettingsRepository(this@TvRemoteApplication)
            val packages = settings.shortcuts().first().map { it.packageName }
            AppLauncher(adb, settings).prefetch(host, packages)
        }
    }

    private fun closeAdb() {
        warmGeneration.incrementAndGet()
        warmJob?.cancel()
        warmJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        appScope.launch(adb.dispatcher) { adb.drop() }
    }

    private companion object {
        const val LAUNCH_TAG = "TvRemoteLaunch"
    }
}
