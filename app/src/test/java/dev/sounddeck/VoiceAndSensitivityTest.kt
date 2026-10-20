package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Synthetic transport tests; the capture parser test uses a separately identified upstream RX frame. */
class VoiceAndSensitivityTest {
    private class Wire : MdrTransport {
        val input = Channel<ByteArray>(32)
        val commands = mutableListOf<ByteArray>()
        var noise = NoiseState(NoiseMode.AMBIENT, 17, true, 1)
        var settings = SpeakSettings(SpeakSensitivity.AUTO, 3)
        var accept = true
        var settled = true
        var malformed = false
        var noReadback = false
        var changedTimeout = false
        private var wrote = false
        private var seq = 0
        override suspend fun connect() {}
        override suspend fun read() = input.receive()
        override suspend fun write(bytes: ByteArray) {
            val frame = MdrCodec.Decoder().feed(bytes).single()
            if (frame.type != MdrCodec.DATA) return
            val p = frame.payload
            commands += p
            input.send(MdrCodec.encode(MdrFrame(MdrCodec.ACK, 1 - frame.sequence, byteArrayOf())))
            val response = when (p[0].u()) {
                0x66 -> SonyProtocol.setNoise(noise).also { it[0] = 0x67; it[2] = if (settled) 1 else 0 }
                0x68 -> { if (accept) noise = (SonyProtocol.parse(p.copyOf().also { it[0] = 0x67 }) as SonyEvent.Noise).value; wrote = true; null }
                0xf6 -> bytes(0xf7, 12, 1, 1)
                0xfa -> if (malformed) bytes(0xfb, 12, 99, 0) else
                    bytes(0xfb, 12, settings.sensitivity.code, settings.modeOutTime)
                0xfc -> {
                    if (accept) settings = SpeakSettings(SpeakSensitivity.entries.single { it.code == p[2].u() },
                        if (changedTimeout) (p[3].u() + 1) % 4 else p[3].u())
                    wrote = true
                    null
                }
                else -> error("Unexpected command ${p[0].u()}")
            }
            if (response != null && !(noReadback && wrote)) {
                input.send(MdrCodec.encode(MdrFrame(MdrCodec.DATA, seq, response)))
                seq = 1 - seq
            }
        }
        override fun close() { input.close() }
    }
    private fun state() = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b, 0xfc),
        noise = NoiseState(NoiseMode.AMBIENT, 3, false, 1), speak = false, speakPreview = 1,
        speakSettings = SpeakSettings(SpeakSensitivity.LOW, 0), pending = "等待確認")

    @Test fun pinnedXm5CaptureDecodesAutoIndependentlyOfPreview() {
        // SonyHeadphonesClient 6f6101f, tests/WF-1000XM5-6.1.0, RX 000085 SYSTEM_RET_EXT_PARAM.
        val raw = bytes(0x3e, 0x0c, 1, 0, 0, 0, 4, 0xfb, 0x0c, 0, 0, 0x18, 0x3c)
        val payload = MdrCodec.Decoder().feed(raw).single().payload
        val s = state().receive(SonyProtocol.parse(payload)!!, 1)
        assertEquals(SpeakSettings(SpeakSensitivity.AUTO, 0), s.speakSettings)
        assertEquals(1, s.speakPreview)
        assertEquals(false, s.speak)
    }
    @Test fun strictExtendedFormatRejectsOtherTypesLengthsAndUnknownEnums() {
        for (cmd in listOf(0xfb, 0xfd)) {
            for (sensitivity in SpeakSensitivity.entries) for (timeout in 0..3) {
                assertEquals(SonyEvent.SpeakConfiguration(SpeakSettings(sensitivity, timeout)),
                    SonyProtocol.parse(bytes(cmd, 12, sensitivity.code, timeout)))
            }
            for (p in listOf(bytes(cmd, 2, 1, 0), bytes(cmd, 12, 1), bytes(cmd, 12, 1, 0, 0),
                bytes(cmd, 12, 3, 0), bytes(cmd, 12, 255, 0), bytes(cmd, 12, 0, 4))) {
                assertNull(SonyProtocol.parse(p))
            }
        }
    }
    @Test fun toggleUsesFreshVoiceAndPreservesLevelWithMatchingReadback() = runBlocking {
        for (voice in listOf(false, true)) {
            var s = state().copy(noise = NoiseState(NoiseMode.AMBIENT, 2, !voice, 1))
            val wire = Wire().apply { noise = noise.copy(voice = voice) }
            var applied = 0
            MdrSession(wire, { s = s.receive(it, 1) }).useSession {
                SonyController({ s }, {}, { applied++ }).execute(this, ControlAction.VoiceFocus())
            }
            assertEquals(1, applied)
            assertEquals(NoiseState(NoiseMode.AMBIENT, 17, !voice, 1), s.noise)
            assertEquals(listOf(0x66, 0x68, 0x66), wire.commands.map { it[0].u() })
        }
    }
    @Test fun staleModeOrUnsettledResponseCannotChangeVoiceOrSwitchMode() = runBlocking {
        for (mode in NoiseMode.entries) for (settled in listOf(false, true)) {
            if (mode == NoiseMode.AMBIENT && settled) continue
            var s = state()
            val wire = Wire().apply { noise = noise.copy(mode = mode); this.settled = settled }
            MdrSession(wire, { s = s.receive(it, 1) }).useSession {
                assertTrue(runCatching { SonyController({ s }, {}).execute(this, ControlAction.VoiceFocus(true)) }
                    .exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(listOf(0x66), wire.commands.map { it[0].u() })
        }
    }
    @Test fun allSensitivityOptionsPreserveFreshTimeoutAndDoNotWriteEnableOrPreview() = runBlocking {
        for (sensitivity in SpeakSensitivity.entries) for (timeout in 0..3) {
            var s = state()
            val wire = Wire().apply { settings = SpeakSettings(SpeakSensitivity.AUTO, timeout) }
            var applied = 0
            MdrSession(wire, { s = s.receive(it, 1) }).useSession {
                SonyController({ s }, {}, { applied++ }).execute(this, ControlAction.SpeakDetection(sensitivity))
            }
            assertEquals(1, applied)
            assertEquals(SpeakSettings(sensitivity, timeout), s.speakSettings)
            assertEquals(false, s.speak); assertEquals(1, s.speakPreview)
            assertEquals(listOf(0xfa, 0xfc, 0xfa), wire.commands.map { it[0].u() })
            assertArrayEquals(bytes(0xfc, 12, sensitivity.code, timeout), wire.commands[1])
        }
    }
    @Test fun rejectedWritesAndChangedPreservedFieldNeverEmitSuccess() = runBlocking {
        for (action in listOf(ControlAction.VoiceFocus(), ControlAction.SpeakDetection(SpeakSensitivity.HIGH))) {
            var s = state()
            val wire = Wire().apply { accept = false }
            var applied = 0; var message = ""
            MdrSession(wire, { s = s.receive(it, 1) }).useSession {
                SonyController({ s }, { message = it }, { applied++ }).execute(this, action)
            }
            assertEquals(0, applied); assertTrue(message.contains("不同"))
            if (action is ControlAction.VoiceFocus) assertEquals(wire.noise, s.noise)
            else assertEquals(wire.settings, s.speakSettings)
        }
        var s = state()
        val wire = Wire().apply { changedTimeout = true }
        var applied = 0
        MdrSession(wire, { s = s.receive(it, 1) }).useSession {
            SonyController({ s }, {}, { applied++ }).execute(this, ControlAction.SpeakDetection(SpeakSensitivity.HIGH))
        }
        assertEquals(0, applied)
        assertEquals(0, s.speakSettings?.modeOutTime)
    }
    @Test fun malformedFreshReadSendsNoWriteAndAckAloneIsNotSuccess() = runBlocking {
        for (malformed in listOf(true, false)) {
            var s = state()
            val wire = Wire().apply { this.malformed = malformed; noReadback = !malformed }
            var applied = 0
            MdrSession(wire, { s = s.receive(it, 1) }, timeoutMs = 300).useSession {
                assertTrue(runCatching { SonyController({ s }, {}, { applied++ })
                    .execute(this, ControlAction.SpeakDetection(SpeakSensitivity.HIGH)) }.exceptionOrNull() is IOException)
            }
            assertEquals(0, applied)
            assertEquals(if (malformed) 0 else 1, wire.commands.count { it[0].u() == 0xfc })
        }
    }
    @Test fun missingCapabilityAndDisconnectedStateSendNothing() = runBlocking {
        for (ready in listOf(false, true)) for (action in listOf(ControlAction.VoiceFocus(), ControlAction.SpeakDetection(SpeakSensitivity.LOW))) {
            val s = state().copy(connection = if (ready) ConnectionState.READY else ConnectionState.DISCONNECTED, functions = emptySet())
            val wire = Wire()
            MdrSession(wire, {}).useSession {
                assertTrue(runCatching { SonyController({ s }, {}).execute(this, action) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(wire.commands.isEmpty())
        }
    }
    @Test fun refreshAndNotificationsPopulateSettingsWithoutSuccessOrDefault() = runBlocking {
        var s = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0xfc))
        assertNull(s.speakSettings); assertFalse(s.canControlSpeakSensitivity)
        var applied = 0
        val wire = Wire()
        MdrSession(wire, { s = s.receive(it, 1) }).useSession { SonyController({ s }, {}, { applied++ }).refresh(this) }
        assertEquals(listOf(0xf6, 0xfa), wire.commands.map { it[0].u() })
        assertEquals(wire.settings, s.speakSettings); assertTrue(s.canControlSpeakSensitivity)
        s = s.receive(SonyProtocol.parse(bytes(0xfd, 12, 2, 1))!!, 2)
        assertEquals(SpeakSettings(SpeakSensitivity.LOW, 1), s.speakSettings)
        assertEquals(0, applied)
    }
}
