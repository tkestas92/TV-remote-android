package lt.kantrybes.tvremote.data.remote

import com.google.polo.wire.protobuf.PoloProto
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ProtobufFramingTest {
    @Test
    fun varintMatchesProtobufLengthEncoding() {
        assertArrayEquals(byteArrayOf(0x00), ProtobufFraming.encodeVarint(0))
        assertArrayEquals(byteArrayOf(0x7F), ProtobufFraming.encodeVarint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ProtobufFraming.encodeVarint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), ProtobufFraming.encodeVarint(300))
    }

    @Test
    fun framedOuterMessageRoundTripsWhenBytesArriveOneAtATime() {
        val secret = byteArrayOf(1, 2, 3)
        val message = PoloProto.OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PoloProto.OuterMessage.Status.STATUS_OK)
            .setSecret(
                PoloProto.Secret.newBuilder()
                    .setSecret(ByteString.copyFrom(secret))
                    .build(),
            )
            .build()
        val framed = ProtobufFraming.frame(message.toByteArray())
        val payload = ProtobufFraming.readPayload(OneByteStream(framed))
        val decoded = PoloProto.OuterMessage.parseFrom(payload)

        assertEquals(2, decoded.protocolVersion)
        assertEquals(PoloProto.OuterMessage.Status.STATUS_OK, decoded.status)
        assertArrayEquals(secret, decoded.secret.secret.toByteArray())
        assertEquals(framed.size, ByteArrayInputStream(framed).readBytes().size)
    }

    private class OneByteStream(private val data: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= data.size) return -1
            return data[index++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= data.size) return -1
            buffer[offset] = data[index++]
            return 1
        }
    }
}
