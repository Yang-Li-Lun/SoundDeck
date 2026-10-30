package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoiseSettledTest {
    private val noise = NoiseState(NoiseMode.AMBIENT, 17, true, 1)
    private fun ready() = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
        noise = noise, noiseSettled = true)

    private class Wire(val settled: (Int) -> Boolean) : MdrTransport {
        val input = Channel<ByteArray>(16)
        val commands = mutableListOf<ByteArray>()
        var reads = 0
        override suspend fun connect() {}
        override suspend fun read() = input.receive()
        override suspend fun write(bytes: ByteArray) {
            val f = MdrCodec.Decoder().feed(bytes).single()
            if (f.type != MdrCodec.DATA) return
            commands += f.payload
            val ack = MdrCodec.encode(MdrFrame(MdrCodec.ACK, 1 - f.sequence, byteArrayOf()))
            if (f.payload[0].u() == 0x66) {
                val n = ++reads
                input.send(MdrCodec.encode(MdrFrame(MdrCodec.DATA, (n - 1) % 2,
                    bytes(0x67, 0x17, if (settled(n)) 1 else 0, 1, 1, 1, 17))) + ack)
            } else input.send(ack)
        }
        override fun close() { input.close() }
    }

    @Test fun unknownAndChangingReportsCannotEnableControlsOrRetainSuccess() {
        assertFalse(ready().copy(noiseSettled = null).canControlNoise)
        var state = ready().copy(appliedSettingId = 7).receive(SonyEvent.Noise(noise, false), 10)
        assertEquals(noise, state.noise)
        assertEquals(false, state.noiseSettled)
        assertNull(state.confirmedNoise)
        assertNull(state.appliedSettingId)
        assertFalse(state.canControlNoise)
        assertEquals("耳機仍在切換中", state.noiseStatusLabel)
        state = state.receive(SonyEvent.Noise(noise, true), 11)
        assertTrue(state.canControlNoise)
        assertEquals(noise, state.confirmedNoise)
        assertNull(state.appliedSettingId) // A notification is not a successful user operation.
        assertNull(HeadphoneState().noiseSettled)
    }

    @Test fun allNoiseSettersRejectFreshUnsettledReadWithoutWriting() = runBlocking {
        val actions = listOf(ControlAction.Noise(NoiseMode.ANC), ControlAction.Noise(NoiseMode.OFF),
            ControlAction.AmbientStep(1), ControlAction.AmbientLevel(5), ControlAction.VoiceFocus(false))
        for (action in actions) {
            var state = ready()
            val wire = Wire { false }
            var successes = 0
            MdrSession(wire, { state = state.receive(it, 1) }).useSession {
                val error = runCatching { SonyController({ state }, {}, { successes++ }).execute(this, action) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
            }
            assertEquals(listOf(0x66), wire.commands.map { it[0].u() })
            assertEquals(0, successes)
            assertEquals(false, state.noiseSettled)
        }
    }

    @Test fun relativeLevelRechecksSettledBeforeWrite() = runBlocking {
        var state = ready()
        val wire = Wire { it == 1 }
        MdrSession(wire, { state = state.receive(it, 1) }).useSession {
            assertTrue(runCatching { SonyController({ state }, {}).execute(this, ControlAction.AmbientStep(1)) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(listOf(0x66, 0x66), wire.commands.map { it[0].u() })
    }

    @Test fun laterNotificationCannotReplaceTheFreshReadUsedForWriting() = runBlocking {
        var state = ready()
        val wire = Wire { true }
        MdrSession(wire, {
            state = state.receive(it, 1).receive(SonyEvent.Noise(noise.copy(mode = NoiseMode.ANC, wireMode = 0), true), 2)
        }).useSession {
            assertTrue(runCatching { SonyController({ state }, {}).execute(this, ControlAction.Noise(NoiseMode.OFF)) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(listOf(0x66), wire.commands.map { it[0].u() })
    }

    @Test fun laterUnsettledNotificationPreventsSuccessFromEarlierMatchingReadback() = runBlocking {
        var state = ready()
        val wire = Wire { true }
        var successes = 0
        val messages = mutableListOf<String>()
        MdrSession(wire, {
            state = state.receive(it, 1)
            if (wire.reads == 2) state = state.receive(SonyEvent.Noise(noise, false), 2)
        }).useSession {
            SonyController({ state }, messages::add, { successes++ }).execute(this, ControlAction.Noise(NoiseMode.AMBIENT))
        }
        assertEquals(listOf(0x66, 0x68, 0x66), wire.commands.map { it[0].u() })
        assertEquals(0, successes)
        assertTrue(messages.single().startsWith("設定尚未確認"))
        assertEquals(false, state.noiseSettled)
    }

    @Test fun diagnosticRetentionIsBoundedAndCountsEviction() {
        var state = ready()
        repeat(HeadphoneState.MAX_DIAGNOSTICS + 5) { state = state.withDiagnostic("line=$it " + "x".repeat(600)) }
        assertEquals(HeadphoneState.MAX_DIAGNOSTICS, state.diagnostics.size)
        assertEquals(5L, state.diagnosticsDropped)
        assertTrue(state.diagnostics.first().startsWith("line=5 "))
        assertTrue(state.diagnostics.all { it.length <= 512 })
        assertEquals(noise, state.noise)
        assertTrue(state.canControlNoise)
    }
}
