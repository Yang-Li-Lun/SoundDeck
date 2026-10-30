package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SonyControllerTest {
    private class HeadsetWire(var reported: NoiseState, val applyWrites: Boolean = true): MdrTransport {
        val inbound = Channel<ByteArray>(16)
        val commands = mutableListOf<ByteArray>()
        private var sequence = 0
        override suspend fun connect() {}
        override suspend fun read() = inbound.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) {
            val f = MdrCodec.Decoder().feed(bytes).single()
            if (f.type != 12) return
            commands.add(f.payload)
            inbound.send(MdrCodec.encode(MdrFrame(1, 1 - f.sequence, byteArrayOf())))
            when(f.payload[0].u()) {
                0x68 -> if (applyWrites) reported = (SonyProtocol.parse(f.payload.copyOf().also { it[0] = 0x67 }) as SonyEvent.Noise).value
                0x66 -> {
                    val data = SonyProtocol.setNoise(reported).also { it[0] = 0x67 }
                    inbound.send(MdrCodec.encode(MdrFrame(12, sequence, data))); sequence = 1 - sequence
                }
            }
        }
        override fun close() { inbound.close() }
    }
    @Test fun changingModePreservesLatestHeadsetLevelAndVoiceNotStaleUi() = runBlocking {
        var state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.ANC, 5, false, 0))
        val wire = HeadsetWire(NoiseState(NoiseMode.ANC, 18, true, 0))
        var result = ""
        val controller = SonyController({ state }, { result = it })
        MdrSession(wire, { state = state.receive(it, 1) }).useSession {
            controller.execute(this, ControlAction.Noise(NoiseMode.AMBIENT))
        }
        assertEquals(listOf(0x66, 0x68, 0x66), wire.commands.map { it[0].u() })
        assertArrayEquals(bytes(0x68, 0x17, 1, 1, 1, 1, 18), wire.commands[1])
        assertEquals(NoiseMode.AMBIENT, state.noise?.mode)
        assertEquals("耳機回報設定相符。", result)
    }
    @Test fun acknowledgedButRejectedWriteKeepsActualState() = runBlocking {
        val wire = HeadsetWire(NoiseState(NoiseMode.ANC, 10, false, 0), applyWrites = false)
        var state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b))
        var result = ""
        val controller = SonyController({ state }, { result = it })
        MdrSession(wire, { state = state.receive(it, 1) }).useSession {
            controller.execute(this, ControlAction.Noise(NoiseMode.OFF))
        }
        assertEquals(NoiseMode.ANC, state.noise?.mode)
        assertTrue(result.contains("回報與要求不同"))
    }
    @Test fun successEventRequiresMatchingReadbackAndRefreshDoesNotEmitSuccess() = runBlocking {
        for (accept in listOf(true,false)) {
            val wire=HeadsetWire(NoiseState(NoiseMode.ANC,10,false,0),applyWrites=accept)
            var state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b))
            var successes=0
            val messages=mutableListOf<String>()
            val controller=SonyController({state},{messages.add(it)},{successes++})
            MdrSession(wire,{state=state.receive(it,1)}).useSession {
                controller.refresh(this)
                assertEquals(0,successes)
                controller.execute(this,ControlAction.Noise(NoiseMode.OFF))
            }
            assertEquals(if(accept) 1 else 0,successes)
            assertEquals(if(accept) 0 else 1,messages.size)
        }
    }
    @Test fun unsupportedControlSendsNoBytes() = runBlocking {
        val wire = HeadsetWire(NoiseState(NoiseMode.ANC, 10, false, 0))
        val controller = SonyController({ HeadphoneState(connection = ConnectionState.READY, functions = emptySet()) }, {})
        MdrSession(wire, {}).useSession {
            assertTrue(runCatching { controller.execute(this, ControlAction.Noise(NoiseMode.OFF)) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(wire.commands.isEmpty())
    }
}
