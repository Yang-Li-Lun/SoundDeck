package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic replies through the actual session and controller, not a hardware claim. */
class ExtendedControllerTest {
    private class Wire(val accept: Boolean = true) : MdrTransport {
        val input = Channel<ByteArray>(32)
        val commands = mutableListOf<ByteArray>()
        var eq = 0x10
        var bands = listOf(10,10,10,10,10,10)
        var level = 19
        var ambient = true
        private var seq = 0
        override suspend fun connect() {}
        override suspend fun read() = input.receive()
        override suspend fun write(bytes: ByteArray) {
            val f = MdrCodec.Decoder().feed(bytes).single()
            if (f.type != MdrCodec.DATA) return
            commands += f.payload
            input.send(MdrCodec.encode(MdrFrame(MdrCodec.ACK, 1 - f.sequence, byteArrayOf())))
            val reply = when (f.payload[0].u()) {
                0x22 -> if (f.payload[1].u() == 9) bytes(0x23, 9, 70, 0, 80, 1, 20, 25) else bytes(0x23, 10, 90, 0, 30)
                0x52 -> bytes(0x53, 0, 0)
                0x56 -> bytes(0x57, 0, eq, bands.size) + bands.map { it.toByte() }.toByteArray()
                0x58 -> { if (accept) { eq = f.payload[2].u(); if (f.payload[3].u() > 0) bands = f.payload.drop(4).map { it.u() } }; null }
                0x66 -> if (ambient) bytes(0x67, 0x17, 1, 1, 1, 1, level) else bytes(0x67, 0x17, 1, 0, 0, 0, level)
                0x68 -> { if (accept) level = f.payload[6].u(); null }
                else -> error("Unexpected command")
            }
            reply?.let { input.send(MdrCodec.encode(MdrFrame(MdrCodec.DATA, seq, it))); seq = 1 - seq }
        }
        override fun close() { input.close() }
    }
    @Test fun refreshUsesDeclaredThresholdCapabilitiesAndAppliesReplies() = runBlocking {
        var s = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x29, 0x2a))
        val wire = Wire()
        MdrSession(wire, { s = s.receive(it, 1) }).useSession { SonyController({s}, {}).refresh(this) }
        assertEquals(listOf(9, 10), wire.commands.map { it[1].u() })
        assertEquals(70, s.left?.percent); assertEquals(80, s.right?.percent); assertEquals(90, s.case?.percent)
    }
    @Test fun widgetLevelStepReadsLatestAndKeepsVoiceThenConfirms() = runBlocking {
        var s = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b), noise = NoiseState(NoiseMode.AMBIENT, 3, false, 1))
        val wire = Wire(); var message = ""
        MdrSession(wire, { s = s.receive(it, 1) }).useSession { SonyController({s}, {message=it}).execute(this, ControlAction.AmbientStep(1)) }
        assertEquals(20, s.noise?.level); assertEquals(true, s.noise?.voice)
        assertEquals(20, wire.commands.single { it[0].u() == 0x68 }[6].u())
        assertTrue(message.contains("已回報"))
    }
    private fun eqState() = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x50), eqAvailable = true,
        eq = SonyEvent.Equalizer(0, listOf(10,10,10,10,10,10)), eqCapability = SonyEvent.EqCapability(6,21,listOf(EqPreset(0,""), EqPreset(0x10,""), EqPreset(0xa1,""))))
    @Test fun nextEqUsesLatestPresetAndOnlyDeclaredIds() = runBlocking {
        var s = eqState(); val wire = Wire(); var message = ""
        MdrSession(wire, { s = s.receive(it, 1) }).useSession { SonyController({s}, {message=it}).execute(this, ControlAction.NextEqualizer) }
        assertEquals(0xa1, s.eq?.preset); assertTrue(message.contains("已回報"))
        assertArrayEquals(bytes(0x58, 0, 0xa1, 0), wire.commands.single { it[0].u() == 0x58 })
    }
    @Test fun customEqComparesReturnedBandsAndRejectedWriteKeepsActual() = runBlocking {
        for (accepted in listOf(true, false)) {
            var s = eqState(); val wire = Wire(accepted); var message = ""
            val bands = listOf(0,1,10,12,15,20)
            MdrSession(wire, { s = s.receive(it, 1) }).useSession { SonyController({s}, {message=it}).execute(this, ControlAction.Equalizer(0xa1, bands)) }
            assertEquals(if (accepted) bands else List(6){10}, s.eq?.bands)
            assertEquals(accepted, message.contains("已回報"))
        }
    }

    @Test fun pendingWidgetDirectLevelUsesFreshVoiceAndReadbackForAllTwentyTicks() = runBlocking {
        for (target in 1..20) {
            var s = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
                noise = NoiseState(NoiseMode.AMBIENT,3,false,1), pending = "等待確認")
            val wire = Wire(); var message = ""
            MdrSession(wire,{s=s.receive(it,1)}).useSession {
                SonyController({s},{message=it}).execute(this,ControlAction.AmbientLevel(target))
            }
            assertEquals(target,s.noise?.level); assertEquals(true,s.noise?.voice)
            assertTrue(message.contains("已回報"))
            assertEquals(target,wire.commands.single{it[0].u()==0x68}[6].u())
        }
    }
    @Test fun staleAmbientWidgetDoesNotSwitchAncWhileAdjustingLevel() = runBlocking {
        var s = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.AMBIENT,3,false,1), pending = "等待確認")
        val wire = Wire().apply { ambient=false }
        MdrSession(wire,{s=s.receive(it,1)}).useSession {
            try { SonyController({s},{}).execute(this,ControlAction.AmbientLevel(7)); fail("Expected fresh-mode rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(wire.commands.none { it[0].u()==0x68 })
    }
    @Test fun pendingNextEqAndRejectedLevelKeepRealReportedValues() = runBlocking {
        var s = eqState().copy(pending="等待確認"); val eqWire=Wire()
        MdrSession(eqWire,{s=s.receive(it,1)}).useSession { SonyController({s},{}).execute(this,ControlAction.NextEqualizer) }
        assertEquals(0xa1,s.eq?.preset)
        s=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b),noise=NoiseState(NoiseMode.AMBIENT,3,false,1),pending="等待確認")
        val levelWire=Wire(false); var message=""
        MdrSession(levelWire,{s=s.receive(it,1)}).useSession { SonyController({s},{message=it}).execute(this,ControlAction.AmbientLevel(7)) }
        assertEquals(19,s.noise?.level); assertTrue(message.contains("不同"))
    }

    @Test fun bothEqDirectionsCycleOnlyDeclaredPresetsFromFreshRead() = runBlocking {
        val presets = listOf(0,0x10,0xa1)
        for (previous in listOf(false,true)) for (index in presets.indices) {
            var s = eqState().copy(pending="等待確認")
            val wire = Wire().apply { eq=presets[index] }
            MdrSession(wire,{s=s.receive(it,1)}).useSession {
                SonyController({s},{}).execute(this,if(previous) ControlAction.PreviousEqualizer else ControlAction.NextEqualizer)
            }
            val expected=presets[Math.floorMod(index+if(previous) -1 else 1,presets.size)]
            assertEquals(expected,s.eq?.preset)
            assertEquals(listOf(0x56,0x52,0x58,0x56),wire.commands.map{it[0].u()})
        }
    }
    @Test fun unknownAndSingleEqPresetHaveDeterministicDirections() = runBlocking {
        for (previous in listOf(false,true)) for (single in listOf(false,true)) {
            var s=eqState().let { if(single) it.copy(eqCapability=it.eqCapability!!.copy(presets=listOf(EqPreset(0xa1,"")))) else it }
            val wire=Wire().apply { eq=0x77 }
            MdrSession(wire,{s=s.receive(it,1)}).useSession {
                SonyController({s},{}).execute(this,if(previous) ControlAction.PreviousEqualizer else ControlAction.NextEqualizer)
            }
            assertEquals(if(single || previous) 0xa1 else 0,s.eq?.preset)
        }
    }
    @Test fun rejectedPreviousEqKeepsActualReadbackAndReportsMismatch() = runBlocking {
        var s=eqState();val wire=Wire(false);var message=""
        MdrSession(wire,{s=s.receive(it,1)}).useSession { SonyController({s},{message=it}).execute(this,ControlAction.PreviousEqualizer) }
        assertEquals(0x10,s.eq?.preset);assertTrue(message.contains("不同"))
        assertEquals(0,wire.commands.single{it[0].u()==0x58}[2].u())
    }
}
