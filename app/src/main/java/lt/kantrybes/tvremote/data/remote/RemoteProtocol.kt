package lt.kantrybes.tvremote.data.remote

import remote.Remotemessage.RemoteAppLinkLaunchRequest
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteEditInfo
import remote.Remotemessage.RemoteImeBatchEdit
import remote.Remotemessage.RemoteImeObject
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteKeyInject
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingResponse
import remote.Remotemessage.RemoteSetActive

internal enum class TvKey(val code: RemoteKeyCode) {
    UP(RemoteKeyCode.KEYCODE_DPAD_UP),
    DOWN(RemoteKeyCode.KEYCODE_DPAD_DOWN),
    LEFT(RemoteKeyCode.KEYCODE_DPAD_LEFT),
    RIGHT(RemoteKeyCode.KEYCODE_DPAD_RIGHT),
    OK(RemoteKeyCode.KEYCODE_DPAD_CENTER),
    BACK(RemoteKeyCode.KEYCODE_BACK),
    HOME(RemoteKeyCode.KEYCODE_HOME),
    VOLUME_UP(RemoteKeyCode.KEYCODE_VOLUME_UP),
    VOLUME_DOWN(RemoteKeyCode.KEYCODE_VOLUME_DOWN),
    MUTE(RemoteKeyCode.KEYCODE_VOLUME_MUTE),
    POWER(RemoteKeyCode.KEYCODE_POWER),
    PLAY_PAUSE(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT(RemoteKeyCode.KEYCODE_MEDIA_NEXT),
    PREVIOUS(RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS),
    FAST_FORWARD(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD),
    REWIND(RemoteKeyCode.KEYCODE_MEDIA_REWIND),
    ENTER(RemoteKeyCode.KEYCODE_ENTER),
    DEL(RemoteKeyCode.KEYCODE_DEL),
}

internal enum class KeyPress {
    SHORT,
    START_LONG,
    END_LONG,
}

/**
 * Answers for the Android TV Remote command channel, matching androidtvremote2.
 * Every ping is echoed: RemotePingResponse.val1 is RemotePingRequest.val1.
 */
internal object RemoteProtocol {
    const val PING = 1 shl 0
    const val KEY = 1 shl 1
    const val IME = 1 shl 2
    const val POWER = 1 shl 5
    const val VOLUME = 1 shl 6
    const val APP_LINK = 1 shl 9
    const val DEFAULT_FEATURES = PING or KEY or IME or POWER or VOLUME or APP_LINK

    data class ImeCounters(val ime: Int, val field: Int)

    data class Outcome(
        val reply: RemoteMessage?,
        val features: Int,
        val sessionReady: Boolean,
        val imeCounters: ImeCounters? = null,
        val imeActivity: Boolean = false,
    )

    fun handle(message: RemoteMessage, features: Int): Outcome {
        if (message.hasRemoteConfigure()) {
            val active = features and message.remoteConfigure.code1
            return Outcome(configureReply(active), active, false)
        }
        if (message.hasRemoteSetActive()) {
            return Outcome(setActiveReply(features), features, false)
        }
        if (message.hasRemotePingRequest()) {
            val reply = RemoteMessage.newBuilder()
                .setRemotePingResponse(
                    RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1),
                )
                .build()
            return Outcome(reply, features, false)
        }
        if (message.hasRemoteStart()) {
            return Outcome(null, features, true)
        }
        if (message.hasRemoteImeBatchEdit()) {
            val batch = message.remoteImeBatchEdit
            return Outcome(
                reply = null,
                features = features,
                sessionReady = false,
                imeCounters = ImeCounters(batch.imeCounter, batch.fieldCounter),
                imeActivity = true,
            )
        }
        return Outcome(null, features, false)
    }

    /**
     * Full-field edit, matching androidtvremote2 `send_text`: insert 1, and both
     * cursor indexes are the text length minus one. Counters come from the TV's
     * last remote_ime_batch_edit.
     */
    fun imeText(text: String, imeCounter: Int, fieldCounter: Int): RemoteMessage {
        val cursor = if (text.isEmpty()) 0 else text.codePointCount(0, text.length) - 1
        val edit = RemoteEditInfo.newBuilder()
            .setInsert(1)
            .setTextFieldStatus(
                RemoteImeObject.newBuilder()
                    .setStart(cursor)
                    .setEnd(cursor)
                    .setValue(text),
            )
        return RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder()
                    .setImeCounter(imeCounter)
                    .setFieldCounter(fieldCounter)
                    .addEditInfo(edit),
            )
            .build()
    }

    /**
     * Same link androidtvremote2 sends when the caller passes a package name:
     * no URL scheme, so it is prefixed with market://launch?id=.
     */
    fun appLink(packageName: String): RemoteMessage {
        return RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder()
                    .setAppLink(marketLaunchLink(packageName)),
            )
            .build()
    }

    fun marketLaunchLink(packageName: String): String = "market://launch?id=$packageName"

    fun keyInject(key: TvKey, press: KeyPress): RemoteMessage {
        val direction = when (press) {
            KeyPress.SHORT -> RemoteDirection.SHORT
            KeyPress.START_LONG -> RemoteDirection.START_LONG
            KeyPress.END_LONG -> RemoteDirection.END_LONG
        }
        return RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(key.code)
                    .setDirection(direction),
            )
            .build()
    }

    private fun configureReply(activeFeatures: Int): RemoteMessage {
        return RemoteMessage.newBuilder()
            .setRemoteConfigure(
                RemoteConfigure.newBuilder()
                    .setCode1(activeFeatures)
                    .setDeviceInfo(
                        RemoteDeviceInfo.newBuilder()
                            .setUnknown1(1)
                            .setUnknown2("1")
                            .setPackageName(SERVICE_NAME)
                            .setAppVersion("1.0.0"),
                    ),
            )
            .build()
    }

    private fun setActiveReply(activeFeatures: Int): RemoteMessage {
        return RemoteMessage.newBuilder()
            .setRemoteSetActive(
                RemoteSetActive.newBuilder().setActive(activeFeatures),
            )
            .build()
    }
}
