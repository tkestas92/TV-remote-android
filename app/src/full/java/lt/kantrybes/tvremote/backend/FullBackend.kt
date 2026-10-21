package lt.kantrybes.tvremote.backend

import android.app.Application
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.AdbClient
import lt.kantrybes.tvremote.data.adb.AppLauncher
import lt.kantrybes.tvremote.data.adb.PictureController
import lt.kantrybes.tvremote.data.remote.ClientCertificateStore
import lt.kantrybes.tvremote.data.remote.ConnectionTester
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.PairingSession
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.RemoteSessionController
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset
import lt.kantrybes.tvremote.data.scrcpy.MirrorStage
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.scrcpy.ScrcpyMirror
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import lt.kantrybes.tvremote.media.MediaCoordinator

internal fun createTvBackend(app: TvRemoteApplication): TvBackend {
    val client = AdbClient(File(app.filesDir, "adb-key"))
    val session = FullSession(app, client)
    val adb = FullAdb(app, client)
    val mirror = FullMirror(ScrcpyMirror(app, client, app.appScope))
    val nowPlaying = MediaCoordinator(app, client, session)
    val discovery = FullDiscovery(app)
    return TvBackend(
        remote = session,
        adb = adb,
        mirror = mirror,
        nowPlaying = nowPlaying,
        discovery = discovery,
        preview = FullPreview(),
    )
}

private class FullSession(
    app: TvRemoteApplication,
    client: AdbClient,
) : TvRemote {
    private val warmGeneration = AtomicInteger(0)
    private var warmJob: Job? = null
    private var prefetchJob: Job? = null
    private val controller = RemoteSessionController(
        application = app,
        scope = app.appScope,
        onSessionReady = { host -> warmAdb(app, client, host) },
        onForegroundStop = { closeAdb(app, client) },
    )

    override val state: StateFlow<RemoteConnectionState> = controller.state
    override val processInForeground: Boolean
        get() = controller.processInForeground

    override fun ensureRunning() = controller.ensureRunning()
    override fun stop() = controller.stop()
    override fun setMediaKeepAlive(keep: Boolean) = controller.setMediaKeepAlive(keep)
    override fun sendKey(key: TvKey, press: KeyPress) = controller.sendKey(key, press)
    override suspend fun sendKeyNow(key: TvKey, press: KeyPress) = controller.sendKeyNow(key, press)
    override fun imeEpoch(): Int = controller.imeEpoch()
    override suspend fun sendImeText(text: String): Boolean = controller.sendImeText(text)
    override fun imeAvailable(): Boolean = controller.imeAvailable()
    override suspend fun awaitImeSince(token: Int, timeoutMs: Long): Boolean {
        return controller.awaitImeSince(token, timeoutMs)
    }
    override fun sendAppLink(packageName: String): Boolean = controller.sendAppLink(packageName)

    private fun warmAdb(app: TvRemoteApplication, client: AdbClient, host: String) {
        if (host.isBlank()) return
        val token = warmGeneration.get()
        warmJob = app.appScope.launch(client.dispatcher) {
            val previous = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (token != warmGeneration.get()) return@launch
                val startedAt = System.nanoTime()
                if (!client.isReady(host)) client.connection(host)
                if (token != warmGeneration.get()) {
                    client.drop()
                    return@launch
                }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                Log.i(LAUNCH_TAG, "adb warm in $elapsed ms")
                prefetchLaunchers(app, client, host, token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(LAUNCH_TAG, "adb warm failed: ${error.message}")
            } finally {
                Process.setThreadPriority(previous)
            }
        }
    }

    private fun prefetchLaunchers(app: TvRemoteApplication, client: AdbClient, host: String, token: Int) {
        prefetchJob?.cancel()
        prefetchJob = app.appScope.launch(Dispatchers.IO) {
            if (token != warmGeneration.get()) return@launch
            val settings = SettingsRepository(app)
            val packages = settings.shortcuts().first().map { it.packageName }
            AppLauncher(client, settings).prefetch(host, packages)
        }
    }

    private fun closeAdb(app: TvRemoteApplication, client: AdbClient) {
        warmGeneration.incrementAndGet()
        warmJob?.cancel()
        warmJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        app.appScope.launch(client.dispatcher) { client.drop() }
    }

    private companion object {
        const val LAUNCH_TAG = "TvRemoteLaunch"
    }
}

private class FullAdb(
    private val app: Application,
    private val client: AdbClient,
) : TvAdb {
    private val launcher = AppLauncher(client, SettingsRepository(app))
    override val dispatcher: CoroutineDispatcher = client.dispatcher

    override fun isReady(host: String): Boolean = client.isReady(host)

    override suspend fun warm(host: String) {
        if (!client.isReady(host)) client.connection(host)
    }

    override fun drop() = client.drop()

    override suspend fun launch(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        return launcher.launch(shortcut, host, supportsAppLinks, sendAppLink, onWaiting, onClear, onError)
    }

    override suspend fun typeAscii(
        host: String,
        chunk: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean = launcher.typeAscii(host, chunk, onWaiting, onClear, onError)

    override suspend fun openYouTube(host: String, url: String, startedAt: Long): Boolean {
        return launcher.openYouTube(host, url, startedAt)
    }

    override suspend fun openPictureMenu(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) = launcher.openPictureMenu(host, onWaiting, onClear, onError)

    override suspend fun exec(host: String, command: String): String? = launcher.exec(host, command)

    override suspend fun prefetch(host: String, packages: List<String>) {
        launcher.prefetch(host, packages)
    }

    override suspend fun setPicture(item: String, value: Int): Boolean {
        val host = SettingsRepository(app).read().pairedHost
        return PictureController { command -> launcher.exec(host, command) }.set(item, value)
    }
}

private class FullMirror(private val mirror: ScrcpyMirror) : TvMirror {
    override val state: StateFlow<MirrorState> = mirror.state
    override val stage: StateFlow<MirrorStage> = mirror.stage
    override val stalled: StateFlow<Boolean> = mirror.stalled
    override val captureBlocked: StateFlow<Boolean> = mirror.captureBlocked
    override fun sessionRunning(): Boolean = mirror.sessionRunning()
    override fun start(host: String, preset: MirrorPreset, presetChange: Boolean) {
        mirror.start(host, preset, presetChange)
    }
    override fun resume() = mirror.resume()
    override fun leave() = mirror.leave()
    override fun stop() = mirror.stop()
    override fun setSurface(next: Surface?) = mirror.setSurface(next)
    override fun touch(action: Int, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        mirror.touch(action, viewX, viewY, viewWidth, viewHeight)
    }
}

private class FullDiscovery(app: Application) : TvDiscovery {
    private val certificates = ClientCertificateStore(File(app.filesDir, "remote-identity"))
    override val names: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override fun hasIdentity(): Boolean = certificates.exists()
    override fun test(host: String) = ConnectionTester.test(host)
    override fun openPairing(host: String): TvPairing {
        return object : TvPairing {
            private val session = PairingSession(host, certificates.getOrCreate())
            override fun openAndRequestCode() = session.openAndRequestCode()
            override fun submitCode(pairingCode: String) = session.submitCode(pairingCode)
            override fun close() = session.close()
            override fun serverFingerprint(): ByteArray? = session.serverFingerprint()
        }
    }
}

private class FullPreview : TvPreview {
    override val picture: StateFlow<TvPicture> = MutableStateFlow(TvPicture()).asStateFlow()
}
