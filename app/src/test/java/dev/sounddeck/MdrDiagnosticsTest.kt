package dev.sounddeck

import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MdrDiagnosticsTest {
    private class Wire : MdrTransport {
        val input = Channel<ByteArray>(16)
        val writes = mutableListOf<ByteArray>()
        private fun frame(type: Int, seq: Int, vararg p: Int) = MdrCodec.encode(MdrFrame(type, seq, bytes(*p)))
        override suspend fun connect() {}
        override suspend fun read() = input.receive()
        override suspend fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
            val f = MdrCodec.Decoder().feed(bytes).single()
            if (f.type != MdrCodec.DATA) return
            val reply = frame(12, 0, 0x67, 0x17, 1, 1, 1, 0, 12)
            // Reply precedes its ACK. A notification, duplicate, and table 2 frame interleave.
            input.send(frame(1, f.sequence) + reply + reply +
                frame(12, 1, 0x69, 0x17, 0, 1, 1, 0, 12) +
                frame(14, 0, 0x37, 4, 0xaa, 0xbb) + frame(1, 1 - f.sequence))
        }
        override fun close() { input.close() }
    }

    @Test fun passiveLoggingPreservesBytesEventsAndResponseEvenWhenSinkThrows() = runBlocking {
        val results = mutableListOf<List<List<Int>>>()
        val observed = mutableListOf<List<SonyEvent>>()
        val logs = mutableListOf<String>()
        for (mode in 0..2) {
            val wire = Wire()
            val events = mutableListOf<SonyEvent>()
            MdrSession(wire, events::add, diagnostic = {
                if (mode == 1) logs += it
                if (mode == 2) error("diagnostic sink unavailable")
            }).useSession {
                diagnosticOperation("Noise") {
                    assertArrayEquals(bytes(0x67, 0x17, 1, 1, 1, 0, 12), request(SonyProtocol.noise, 0x67))
                }
            }
            results += wire.writes.map { it.map(Byte::u) }
            observed += events
        }
        assertEquals(results[0], results[1]); assertEquals(results[0], results[2])
        assertEquals(observed[0], observed[1]); assertEquals(observed[0], observed[2])
        assertEquals(2, observed[0].size)
        assertTrue(logs.any { "TX" in it && "payload=[66 17]" in it })
        assertTrue(logs.any { "payload=[67 17 01 01 01 00 0c]" in it })
        assertTrue(logs.any { "ACK expected=1 received=0" in it && "completed=false" in it })
        assertTrue(logs.any { "ACK expected=1 received=1" in it && "completed=true" in it })
        assertTrue(logs.any { "duplicate=true" in it && "eventApplied=false" in it })
        assertTrue(logs.any { "replyForReq=1 replyMatched=true replyCompleted=true" in it })
        assertTrue(logs.any { "settled=false" in it && "replyMatched=false" in it })
        assertTrue(logs.any { "ignored=table2" in it })
        assertTrue(logs.any { "type=0e" in it && "payload=[redacted]" in it })
        assertTrue(logs.all { "sid=" in it && "op=" in it && "elapsedMs=" in it })
        val ids = logs.map { Regex("event=(\\d+)").find(it)!!.groupValues[1].toLong() }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
    }

    @Test fun onlyFixedLengthApprovedSettingsExposePayload() {
        val allowed = listOf(bytes(0x68, 0x17, 1, 1, 0, 1, 20), bytes(0xe3, 1, 0), bytes(0xe8, 1, 1),
            bytes(0xe7, 1, 0), bytes(0xe9, 1, 1), bytes(0xf8, 12, 0, 1), bytes(0xf7, 12, 1, 0),
            bytes(0xf9, 12, 0, 0), bytes(0xfb, 12, 2, 3), bytes(0x13, 3, 2, 1), bytes(0x99, 0, 8, 0))
        for (p in allowed) assertFalse(MdrDiagnostics.frame(MdrFrame(12, 0, p)).contains("redacted"))
        val privateBytes = "PRIVATE_DEVICE_AA:BB:CC:DD:EE:FF".toByteArray()
        for (p in listOf(bytes(5, 1, privateBytes.size) + privateBytes, bytes(0x67, 0x17) + privateBytes,
            bytes(0x67, 0x18, 1, 1, 1, 1, 1), bytes(0x37, 4) + privateBytes, bytes(0x67, 0x17, 1))) {
            val output = MdrDiagnostics.frame(MdrFrame(12, 0, p))
            assertTrue(output.endsWith("payload=[redacted]"))
            assertFalse(output.contains("PRIVATE"))
        }
        assertEquals("Identity", MdrDiagnostics.parsed(SonyEvent.Identity(1, "PRIVATE_DEVICE")))
    }

    @Test fun timeoutAndCorruptFrameAreRecordedWithoutRetry() = runBlocking {
        val input = Channel<ByteArray>(4)
        val commands = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val wire = object : MdrTransport {
            override suspend fun connect() {}
            override suspend fun read() = input.receive()
            override suspend fun write(bytes: ByteArray) {
                commands += bytes
                val corrupt = MdrCodec.encode(MdrFrame(1, 1, byteArrayOf())).also { it[it.lastIndex - 1] = 99 }
                input.send(corrupt)
            }
            override fun close() { input.close() }
        }
        val error = runCatching { MdrSession(wire, {}, logs::add, timeoutMs = 100).useSession {
            request(SonyProtocol.dsee, 0xe7)
        } }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(1, commands.size)
        assertTrue(logs.any { "DECODE rejected=1" in it })
        assertTrue(logs.any { "outcome=timeout ack=false reply=false" in it })
    }
}
