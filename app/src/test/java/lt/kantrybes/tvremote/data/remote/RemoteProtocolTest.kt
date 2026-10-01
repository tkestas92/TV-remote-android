package lt.kantrybes.tvremote.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import remote.Remotemessage.RemoteImeBatchEdit
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingRequest
import remote.Remotemessage.RemoteSetActive
import remote.Remotemessage.RemoteStart

class RemoteProtocolTest {
    @Test
    fun pingResponseEchoesVal1IncludingZero() {
        val request = RemoteMessage.newBuilder()
            .setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(0).setVal2(9))
            .build()

        val outcome = RemoteProtocol.handle(request, RemoteProtocol.DEFAULT_FEATURES)

        assertEquals(0, outcome.reply!!.remotePingResponse.val1)
        assertFalse(outcome.reply.hasRemotePingRequest())
    }

    @Test
    fun pingResponseRoundTripsThroughTheLengthPrefix() {
        val request = RemoteMessage.newBuilder()
            .setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(42))
            .build()
        val reply = RemoteProtocol.handle(request, RemoteProtocol.DEFAULT_FEATURES).reply!!
        val decoded = RemoteMessage.parseFrom(ProtobufFraming.readPayload(reply.toByteArray().let {
            java.io.ByteArrayInputStream(ProtobufFraming.frame(it))
        }))

        assertEquals(42, decoded.remotePingResponse.val1)
    }

    @Test
    fun configureKeepsOnlyFeaturesTheTvSupports() {
        val supported = RemoteProtocol.PING or RemoteProtocol.KEY or RemoteProtocol.POWER
        val message = RemoteMessage.newBuilder()
            .setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(supported))
            .build()

        val outcome = RemoteProtocol.handle(message, RemoteProtocol.DEFAULT_FEATURES)

        assertEquals(supported, outcome.features)
        assertEquals(supported, outcome.reply!!.remoteConfigure.code1)
        assertEquals("atvremote", outcome.reply.remoteConfigure.deviceInfo.packageName)
        assertEquals("1", outcome.reply.remoteConfigure.deviceInfo.unknown2)
        assertEquals(1, outcome.reply.remoteConfigure.deviceInfo.unknown1)
    }

    @Test
    fun setActiveRepliesWithTheNegotiatedFeatures() {
        val features = RemoteProtocol.PING or RemoteProtocol.KEY
        val message = RemoteMessage.newBuilder()
            .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(0))
            .build()

        val outcome = RemoteProtocol.handle(message, features)

        assertEquals(features, outcome.reply!!.remoteSetActive.active)
        assertEquals(features, outcome.features)
    }

    @Test
    fun remoteStartMarksTheSessionReadyWithoutAReply() {
        val message = RemoteMessage.newBuilder()
            .setRemoteStart(RemoteStart.newBuilder().setStarted(true))
            .build()

        val outcome = RemoteProtocol.handle(message, RemoteProtocol.DEFAULT_FEATURES)

        assertNull(outcome.reply)
        assertTrue(outcome.sessionReady)
    }

    @Test
    fun keyInjectUsesShortAndLongDirections() {
        val shortPress = RemoteProtocol.keyInject(TvKey.OK, KeyPress.SHORT)
        val hold = RemoteProtocol.keyInject(TvKey.VOLUME_UP, KeyPress.START_LONG)
        val release = RemoteProtocol.keyInject(TvKey.VOLUME_UP, KeyPress.END_LONG)

        assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, shortPress.remoteKeyInject.keyCode)
        assertEquals(RemoteDirection.SHORT, shortPress.remoteKeyInject.direction)
        assertEquals(RemoteKeyCode.KEYCODE_VOLUME_UP, hold.remoteKeyInject.keyCode)
        assertEquals(RemoteDirection.START_LONG, hold.remoteKeyInject.direction)
        assertEquals(RemoteDirection.END_LONG, release.remoteKeyInject.direction)
    }

    @Test
    fun appLinkUsesTheMarketLaunchUrl() {
        val message = RemoteProtocol.appLink("com.google.android.youtube.tv")

        assertEquals(
            "market://launch?id=com.google.android.youtube.tv",
            message.remoteAppLinkLaunchRequest.appLink,
        )
        assertTrue(message.hasRemoteAppLinkLaunchRequest())
    }

    @Test
    fun imeTextMatchesAndroidTvRemote2BatchEdit() {
        val message = RemoteProtocol.imeText("café", imeCounter = 3, fieldCounter = 4)
        val edit = message.remoteImeBatchEdit.getEditInfo(0)

        assertEquals(3, message.remoteImeBatchEdit.imeCounter)
        assertEquals(4, message.remoteImeBatchEdit.fieldCounter)
        assertEquals(1, edit.insert)
        assertEquals(3, edit.textFieldStatus.start)
        assertEquals(3, edit.textFieldStatus.end)
        assertEquals("café", edit.textFieldStatus.value)
    }

    @Test
    fun incomingBatchEditStoresCounters() {
        val message = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder().setImeCounter(2).setFieldCounter(9),
            )
            .build()

        val outcome = RemoteProtocol.handle(message, RemoteProtocol.DEFAULT_FEATURES)

        assertEquals(2, outcome.imeCounters!!.ime)
        assertEquals(9, outcome.imeCounters.field)
        assertTrue(outcome.imeActivity)
        assertNull(outcome.reply)
    }
}
