package dev.sounddeck.protocol

import java.io.ByteArrayOutputStream

/** Sony MDR framing. Protocol reference and MIT notice: docs/PROTOCOL.md, THIRD_PARTY_NOTICES. */
data class MdrFrame(val type: Int, val sequence: Int, val payload: ByteArray)

object MdrCodec {
    const val DATA = 0x0c
    const val ACK = 0x01
    const val MAX_PAYLOAD = 4096

    fun encode(frame: MdrFrame): ByteArray {
        require(frame.sequence in 0..1 && frame.type in 0..255)
        require(frame.payload.size <= MAX_PAYLOAD)
        val n = frame.payload.size
        val body = bytes(frame.type, frame.sequence, n ushr 24, n ushr 16, n ushr 8, n) + frame.payload
        val out = ByteArrayOutputStream()
        out.write(0x3e)
        for (b in body + byteArrayOf(body.sumOf { it.u() }.toByte())) {
            if (b.u() in 0x3c..0x3e) { out.write(0x3d); out.write(b.u() xor 0x10) }
            else out.write(b.u())
        }
        out.write(0x3c)
        return out.toByteArray()
    }

    /** Bounded, incremental decoder. A new start resynchronizes corrupt or truncated input. */
    class Decoder {
        private val body = ByteArrayOutputStream()
        private var started = false
        private var escaped = false
        var rejected = 0
            private set
        fun feed(data: ByteArray): List<MdrFrame> = buildList {
            for (raw in data) {
                val v = raw.u()
                if (v == 0x3e) { body.reset(); started = true; escaped = false; continue }
                if (!started) continue
                if (v == 0x3c) {
                    val frame = if (escaped) null else decodeBody(body.toByteArray())
                    if (frame != null) add(frame) else rejected++
                    started = false; escaped = false; body.reset(); continue
                }
                if (escaped) {
                    if (v !in 0x2c..0x2e) { started = false; rejected++; body.reset() }
                    else body.write(v xor 0x10)
                    escaped = false
                } else if (v == 0x3d) escaped = true else body.write(v)
                if (body.size() > MAX_PAYLOAD + 7) { started = false; rejected++; body.reset() }
            }
        }
        private fun decodeBody(b: ByteArray): MdrFrame? {
            if (b.size < 7 || b[1].u() !in 0..1) return null
            val length = (b[2].u().toLong() shl 24) or (b[3].u().toLong() shl 16) or
                (b[4].u().toLong() shl 8) or b[5].u().toLong()
            if (length > MAX_PAYLOAD || length != (b.size - 7).toLong()) return null
            if ((b.dropLast(1).sumOf { it.u() } and 255) != b.last().u()) return null
            if (b[0].u() == ACK && length != 0L) return null
            return MdrFrame(b[0].u(), b[1].u(), b.copyOfRange(6, b.lastIndex))
        }
    }
}

fun Byte.u() = toInt() and 255
fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
