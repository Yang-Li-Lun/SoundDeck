package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Synthetic replies exercise the real request/readback path without Bluetooth or device changes. */
class ControllerDiagnosticsTest {
    private class Wire : MdrTransport {
        val input = Channel<ByteArray>(32)
        val commands = mutableListOf<ByteArray>()
        var noise = NoiseState(NoiseMode.AMBIENT, 17, true, 1)
        var dsee = false
        var speak = false
        var preview = 1
        var settings = SpeakSettings(SpeakSensitivity.AUTO, 3)
        var eq = 0x10
        var bands = List(6) { 10 }
        var accept = true
        var unsettledAfterWrite = false
        var omitReadback = false
        var changePreservedValues = false
        private var wrote = false
        private var sequence = 0

        override suspend fun connect() {}
        override suspend fun read() = input.receive()
        override suspend fun write(bytes: ByteArray) {
            val frame = MdrCodec.Decoder().feed(bytes).single()
            if (frame.type != MdrCodec.DATA) return
            val p = frame.payload
            commands += p
            input.send(MdrCodec.encode(MdrFrame(MdrCodec.ACK, 1 - frame.sequence, byteArrayOf())))
            val response = when (p[0].u()) {
                0x66 -> SonyProtocol.setNoise(noise).also {
                    it[0] = 0x67
                    it[2] = if (wrote && unsettledAfterWrite) 0 else 1
                }
                0xe2 -> bytes(0xe3, 1, 0)
                0xe6 -> bytes(0xe7, 1, if (dsee) 1 else 0)
                0xf6 -> bytes(0xf7, 12, if (speak) 0 else 1, preview)
                0xfa -> bytes(0xfb, 12, settings.sensitivity.code, settings.modeOutTime)
                0x52 -> bytes(0x53, 0, 0)
                0x56 -> bytes(0x57, 0, eq, bands.size) + bands.map { it.toByte() }.toByteArray()
                0x68, 0xe8, 0xf8, 0xfc, 0x58 -> {
                    wrote = true
                    if (accept) when (p[0].u()) {
                        0x68 -> {
                            noise = (SonyProtocol.parse(p.copyOf().also { it[0] = 0x67 }) as SonyEvent.Noise).value
                            if (changePreservedValues) noise = noise.copy(level = 8, voice = false)
                        }
                        0xe8 -> dsee = p[2].u() == 1
                        0xf8 -> { speak = p[2].u() == 0; preview = if (changePreservedValues) 0 else p[3].u() }
                        0xfc -> settings = SpeakSettings(SpeakSensitivity.entries.single { it.code == p[2].u() }, p[3].u())
                        0x58 -> { eq = p[2].u(); if (p[3].u() > 0) bands = p.drop(4).map { it.u() } }
                    }
                    null
                }
                else -> error("Unexpected command")
            }
            if (response != null && !(wrote && omitReadback)) {
                input.send(MdrCodec.encode(MdrFrame(MdrCodec.DATA, sequence, response)))
                sequence = 1 - sequence
            }
        }
        override fun close() { input.close() }
    }

    private val privateText = "PRIVATE_HEADSET_AA:BB:CC:DD:EE:FF"
    private fun state() = HeadphoneState(
        connection = ConnectionState.READY, functions = setOf(0x6b, 0xe2, 0xfc, 0x50),
        model = privateText, firmware = privateText, endpointLabel = privateText,
        noise = NoiseState(NoiseMode.AMBIENT, 2, false, 1), dsee = false, dseeAvailable = true,
        speak = false, speakPreview = 0, speakSettings = SpeakSettings(SpeakSensitivity.LOW, 0),
        eq = SonyEvent.Equalizer(0, List(6) { 0 }), eqAvailable = true,
        eqCapability = SonyEvent.EqCapability(6, 21, listOf(EqPreset(0x10, privateText), EqPreset(0xa1, privateText))),
    )

    private data class Case(val action: ControlAction, val name: String, val commands: List<Int>, val target: String, val rejected: String)
    private val cases = listOf(
        Case(ControlAction.Noise(NoiseMode.ANC), "Noise", listOf(0x66, 0x68, 0x66), "mode=ANC,level=17,voice=true,wireMode=0", "mode=AMBIENT,level=17,voice=true,wireMode=1,settled=true"),
        Case(ControlAction.VoiceFocus(), "VoiceFocus", listOf(0x66, 0x68, 0x66), "mode=AMBIENT,level=17,voice=false,wireMode=1", "mode=AMBIENT,level=17,voice=true,wireMode=1,settled=true"),
        Case(ControlAction.Dsee(true), "Dsee", listOf(0xe2, 0xe8, 0xe6), "enabled=true", "enabled=false"),
        Case(ControlAction.Speak(true), "Speak", listOf(0xf6, 0xf8, 0xf6), "enabled=true,preview=1", "enabled=false,preview=1"),
        Case(ControlAction.SpeakDetection(SpeakSensitivity.HIGH), "SpeakDetection", listOf(0xfa, 0xfc, 0xfa), "sensitivity=HIGH,modeOutTime=3", "sensitivity=AUTO,modeOutTime=3"),
        Case(ControlAction.Equalizer(0xa1, listOf(0, 1, 10, 12, 15, 20)), "Equalizer", listOf(0x52, 0x58, 0x56), "preset=161,bands=[0, 1, 10, 12, 15, 20]", "preset=16,bands=[10, 10, 10, 10, 10, 10]"),
    )

    @Test fun everySetterReportsResolvedTargetAndActualReadbackWithoutChangingRequestSequence() = runBlocking {
        for (case in cases) for (accept in listOf(false, true)) {
            var current = state()
            val wire = Wire().apply { this.accept = accept }
            val diagnostics = mutableListOf<String>()
            val messages = mutableListOf<String>()
            var applied = 0
            val controller = SonyController({ current }, messages::add, { applied++ }, diagnostics::add)
            MdrSession(wire, { current = current.receive(it, 1) }).useSession { controller.execute(this, case.action) }

            assertEquals(case.commands, wire.commands.map { it[0].u() })
            assertEquals(1, wire.commands.count { it[0].u() in listOf(0x68, 0xe8, 0xf8, 0xfc, 0x58) })
            assertEquals(if (accept) 1 else 0, applied)
            assertEquals(listOf("CONTROL ${case.name} target(${case.target})"), diagnostics.filter { " target(" in it })
            assertEquals(2, diagnostics.size)
            val expectedReadback = if (!accept) case.rejected else case.target + if (case.name in listOf("Noise", "VoiceFocus")) ",settled=true" else ""
            assertEquals("CONTROL ${case.name} readback($expectedReadback) matches=$accept", diagnostics.last())
            assertTrue(diagnostics.all { it.length <= 256 && !it.contains(privateText) && !it.contains("AA:BB") })
            if (!accept) assertTrue(messages.single().contains("回報與要求不同"))
            else assertTrue(messages.isEmpty())
        }
    }

    @Test fun unsettledNoiseAndVoiceReadbacksAreUnconfirmedWithoutResendingOrSuccess() = runBlocking {
        for (case in cases.take(2)) {
            var current = state()
            val wire = Wire().apply { unsettledAfterWrite = true }
            val diagnostics = mutableListOf<String>()
            val messages = mutableListOf<String>()
            var applied = 0
            MdrSession(wire, { current = current.receive(it, 1) }).useSession {
                SonyController({ current }, messages::add, { applied++ }, diagnostics::add).execute(this, case.action)
            }
            assertEquals(0, applied)
            assertTrue(messages.single().startsWith("設定尚未確認"))
            assertFalse(messages.single().contains("回報與要求不同"))
            assertTrue(diagnostics.last().contains("settled=false) matches=false"))
            assertEquals(case.commands, wire.commands.map { it[0].u() })
        }
    }

    @Test fun missingReadbackKeepsResolvedTargetButDoesNotClaimSuccessOrResend() = runBlocking {
        for (case in cases) {
            var current = state()
            val wire = Wire().apply { omitReadback = true }
            val diagnostics = mutableListOf<String>()
            var applied = 0
            var failure: Throwable? = null
            MdrSession(wire, { current = current.receive(it, 1) }, timeoutMs = 300).useSession {
                failure = runCatching {
                    SonyController({ current }, {}, { applied++ }, diagnostics::add).execute(this, case.action)
                }.exceptionOrNull()
            }
            assertTrue(failure is IOException)
            assertEquals(0, applied)
            assertEquals(case.commands, wire.commands.map { it[0].u() })
            assertEquals(listOf("CONTROL ${case.name} target(${case.target})"), diagnostics)
        }
    }

    @Test fun diagnosticsPreserveExistingComparisonRulesForUnrequestedNoiseAndSpeakFields() = runBlocking {
        for (case in listOf(cases[0], cases[3])) {
            var current = state()
            val wire = Wire().apply { changePreservedValues = true }
            val diagnostics = mutableListOf<String>()
            var applied = 0
            MdrSession(wire, { current = current.receive(it, 1) }).useSession {
                SonyController({ current }, {}, { applied++ }, diagnostics::add).execute(this, case.action)
            }
            assertEquals(1, applied)
            assertTrue(diagnostics.last().endsWith("matches=true"))
            assertTrue(diagnostics.last().contains(if (case.name == "Noise") "level=8,voice=false" else "preview=0"))
        }
    }

    @Test fun boundedEqReadbackAndFailingDiagnosticSinkCannotAffectControl() = runBlocking {
        for (throwFromSink in listOf(false, true)) {
            var current = state()
            val wire = Wire().apply { bands = List(16) { 255 } }
            val diagnostics = mutableListOf<String>()
            var applied = 0
            MdrSession(wire, { current = current.receive(it, 1) }).useSession {
                SonyController({ current }, {}, { applied++ }, {
                    diagnostics.add(it)
                    if (throwFromSink) error(privateText)
                }).execute(this, ControlAction.Equalizer(0xa1))
            }
            assertEquals(1, applied)
            assertEquals(listOf(0x52, 0x58, 0x56), wire.commands.map { it[0].u() })
            assertTrue(diagnostics.all { it.length <= 256 && !it.contains(privateText) })
            assertTrue(diagnostics.last().contains("bands=[255, 255"))
        }
    }

    @Test fun noiseDseeAndSpeakPassiveCaptureIncludesEveryRequestAndReadbackWithoutExtraWrites() = runBlocking {
        for (case in listOf(cases[0], cases[2], cases[3])) for (accept in listOf(false, true)) {
            var current = state()
            val wire = Wire().apply { this.accept = accept }
            val logs = mutableListOf<String>()
            MdrSession(wire, { current = current.receive(it, 1) }, logs::add).useSession {
                SonyController({ current }, {}).execute(this, case.action)
            }
            assertEquals(case.commands, wire.commands.map { it[0].u() })
            assertEquals(3, logs.count { "REQUEST_BEGIN" in it })
            assertTrue(logs.any { "CONTROL_BEGIN name=${case.name}" in it && "op=1" in it })
            assertTrue(logs.any { "CONTROL ${case.name} readback(" in it && "matches=$accept" in it })
            for (p in wire.commands) {
                val hex = p.joinToString(" ") { it.u().toString(16).padStart(2, '0') }
                assertTrue("Missing TX $hex", logs.any { "TX" in it && "payload=[$hex]" in it })
            }
            if (case.name == "Dsee") assertTrue(logs.any { "payload=[e3 01 00]" in it && "RX" in it })
            if (case.name == "Speak") assertTrue(logs.any { "preview=1" in it })
            assertTrue(logs.none { privateText in it })
        }
    }
}
