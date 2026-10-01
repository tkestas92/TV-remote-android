package lt.kantrybes.tvremote.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.MainActivity
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.NowPlaying
import lt.kantrybes.tvremote.data.adb.NowPlayingDump
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.TvKey

/**
 * Lock-screen and notification-shade card for whatever the TV is playing.
 * The session tag is the device name "TCL TV". Hardware volume keys are
 * delivered through a relative [VolumeProviderCompat] while the card is active.
 */
internal class TvMediaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private var session: MediaSessionCompat? = null
    private var shown: NowPlaying? = null
    private var destroyReason: String? = null
    private val artwork = HashMap<String, Bitmap>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = mediaSession()
        updates = scope.launch {
            app.media.nowPlaying.collect { update(it) }
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            Log.i(TAG, "Action dismiss")
            app.media.stop("dismissed")
            return START_NOT_STICKY
        }
        promote(shown)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updates?.cancel()
        session?.release()
        session = null
        val reason = destroyReason ?: "dismissed"
        destroyReason = null
        app.media.onServiceGone(reason)
        super.onDestroy()
    }

    private fun mediaSession(): MediaSessionCompat {
        val created = MediaSessionCompat(this, getString(R.string.media_device))
        created.setSessionActivity(openApp())
        created.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = send(TvKey.PLAY_PAUSE, "play")
            override fun onPause() = send(TvKey.PLAY_PAUSE, "pause")
            override fun onSkipToNext() = send(TvKey.NEXT, "next")
            override fun onSkipToPrevious() = send(TvKey.PREVIOUS, "previous")
            override fun onFastForward() = send(TvKey.FAST_FORWARD, "fast forward")
            override fun onRewind() = send(TvKey.REWIND, "rewind")
        })
        created.setPlaybackToRemote(object : VolumeProviderCompat(
            VolumeProviderCompat.VOLUME_CONTROL_RELATIVE,
            VOLUME_STEPS,
            VOLUME_STEPS / 2,
        ) {
            override fun onAdjustVolume(direction: Int) {
                when {
                    direction > 0 -> send(TvKey.VOLUME_UP, "volume up")
                    direction < 0 -> send(TvKey.VOLUME_DOWN, "volume down")
                }
            }
        })
        created.isActive = true
        return created
    }

    private fun update(item: NowPlaying?) {
        val active = session ?: return
        if (item != null) shown = item
        val current = shown
        if (current == null) {
            promote(null)
            return
        }
        val state = if (item == null) NowPlayingDump.STATE_PAUSED else current.state
        val speed = if (item == null) 0f else current.speed
        active.setMetadata(metadata(current))
        active.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(TRANSPORT_ACTIONS)
                .setState(playbackState(state), current.positionMs, speed, SystemClock.elapsedRealtime())
                .build(),
        )
        promote(current)
    }

    private fun metadata(item: NowPlaying): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item.appName)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork(item.packageName))
        item.artist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
        return builder.build()
    }

    private fun artwork(packageName: String): Bitmap {
        artwork[packageName]?.let { return it }
        val res = when (packageName) {
            "com.google.android.youtube.tv" -> R.drawable.ic_art_youtube
            "lt.go3.android.tv" -> R.drawable.ic_art_go3
            "com.stremio.one" -> R.drawable.ic_art_stremio
            "com.netflix.ninja" -> R.drawable.ic_art_netflix
            else -> R.drawable.ic_art_tv
        }
        val bitmap = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888)
        val drawable = ContextCompat.getDrawable(this, res)
        if (drawable != null) {
            drawable.setBounds(0, 0, ART_PX, ART_PX)
            drawable.draw(Canvas(bitmap))
        }
        artwork[packageName] = bitmap
        return bitmap
    }

    private fun promote(item: NowPlaying?) {
        val active = session ?: return
        val text = item?.artist?.takeIf { it.isNotBlank() } ?: item?.appName ?: getString(R.string.media_device)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle(item?.title ?: getString(R.string.media_device))
            .setContentText(text)
            .setSubText(getString(R.string.media_device))
            .setContentIntent(openApp())
            .setDeleteIntent(dismiss())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(active.sessionToken)
                    .setRemotePlaybackInfo(getString(R.string.media_device), R.drawable.ic_stat_tv, openApp()),
            )
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (error: SecurityException) {
            Log.w(TAG, "Foreground media card was not allowed", error)
            destroyReason = "not allowed"
            stopSelf()
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, IMMUTABLE_UPDATE)
    }

    private fun dismiss(): PendingIntent {
        val intent = Intent(this, TvMediaService::class.java).setAction(ACTION_DISMISS)
        return PendingIntent.getService(this, 1, intent, IMMUTABLE_UPDATE)
    }

    private fun send(key: TvKey, action: String) {
        Log.i(TAG, "Action $action")
        app.remote.sendKey(key, KeyPress.SHORT)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private val app: TvRemoteApplication
        get() = application as TvRemoteApplication

    private companion object {
        const val TAG = "TvRemoteMedia"
        const val CHANNEL_ID = "tv_playback"
        const val NOTIFICATION_ID = 42
        const val ACTION_DISMISS = "lt.kantrybes.tvremote.media.DISMISS"
        const val ART_PX = 256
        const val VOLUME_STEPS = 100
        const val IMMUTABLE_UPDATE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val TRANSPORT_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND

        fun playbackState(state: Int): Int = when (state) {
            NowPlayingDump.STATE_PLAYING -> PlaybackStateCompat.STATE_PLAYING
            NowPlayingDump.STATE_PAUSED -> PlaybackStateCompat.STATE_PAUSED
            NowPlayingDump.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_NONE
        }
    }
}
