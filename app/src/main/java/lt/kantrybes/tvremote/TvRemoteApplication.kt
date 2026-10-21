package lt.kantrybes.tvremote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lt.kantrybes.tvremote.backend.TvAdb
import lt.kantrybes.tvremote.backend.TvBackend
import lt.kantrybes.tvremote.backend.TvDiscovery
import lt.kantrybes.tvremote.backend.TvMirror
import lt.kantrybes.tvremote.backend.TvNowPlaying
import lt.kantrybes.tvremote.backend.TvPreview
import lt.kantrybes.tvremote.backend.TvRemote
import lt.kantrybes.tvremote.backend.createTvBackend

class TvRemoteApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal lateinit var backend: TvBackend
        private set

    internal val remote: TvRemote
        get() = backend.remote

    internal val adb: TvAdb
        get() = backend.adb

    internal val mirror: TvMirror
        get() = backend.mirror

    internal val media: TvNowPlaying
        get() = backend.nowPlaying

    internal val discovery: TvDiscovery
        get() = backend.discovery

    internal val preview: TvPreview
        get() = backend.preview

    override fun onCreate() {
        super.onCreate()
        backend = createTvBackend(this)
        media.start()
    }
}
