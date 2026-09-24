package lt.kantrybes.tvremote.data.remote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal object ProtobufFraming {
    const val MAX_MESSAGE_BYTES = 1_048_576

    fun frame(payload: ByteArray): ByteArray {
        val prefix = encodeVarint(payload.size)
        return prefix + payload
    }

    fun readPayload(input: InputStream): ByteArray {
        val length = readVarint(input)
        if (length > MAX_MESSAGE_BYTES) {
            throw IOException("Message too large")
        }
        if (length == 0) return ByteArray(0)
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("Connection closed")
            offset += read
        }
        return payload
    }

    fun encodeVarint(value: Int): ByteArray {
        require(value >= 0) { "Varint length must be non-negative" }
        val buffer = ByteArrayOutputStream(5)
        var current = value
        while (current >= 0x80) {
            buffer.write((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        buffer.write(current)
        return buffer.toByteArray()
    }

    fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Connection closed")
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) {
                if (result < 0) throw IOException("Varint out of range")
                return result
            }
            shift += 7
        }
        throw IOException("Varint too long")
    }
}
