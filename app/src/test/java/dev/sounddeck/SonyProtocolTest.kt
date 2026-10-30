package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SonyProtocolTest {
    @Test fun capabilitiesArePairsNotIndividualBytes() {
        val e = SonyProtocol.parse(bytes(7, 0, 3, 0x6b, 1, 0x21, 2, 0x22, 3)) as SonyEvent.Functions
        assertEquals(setOf(0x6b, 0x21, 0x22), e.values)
        assertNull(SonyProtocol.parse(bytes(7, 0, 3, 0x6b, 1)))
    }
    @Test fun protocolEnableIsZero() {
        assertTrue((SonyProtocol.parse(bytes(1, 0, 0, 0, 0, 3, 0, 1)) as SonyEvent.Protocol).table1)
        assertFalse((SonyProtocol.parse(bytes(1, 0, 0, 0, 0, 3, 1, 0)) as SonyEvent.Protocol).table1)
        assertNull(SonyProtocol.parse(bytes(1, 0, 1, 5))) // Legacy response must not be mistaken for v2.
    }
    @Test fun noiseNotificationAndResponseHaveSameMeaning() {
        val expected = SonyEvent.Noise(NoiseState(NoiseMode.AMBIENT, 17, true, 1), true)
        assertEquals(expected, SonyProtocol.parse(bytes(0x67, 0x17, 1, 1, 1, 1, 17)))
        assertEquals(expected, SonyProtocol.parse(bytes(0x69, 0x17, 1, 1, 1, 1, 17)))
        assertNull(SonyProtocol.parse(bytes(0x67, 0x17, 1, 1, 1, 1, 21)))
        assertNull(SonyProtocol.parse(bytes(0x67, 0x15, 1, 1, 1, 1, 17)))
    }
    @Test fun offAndAncPreserveReportedAmbientSettings() {
        val current = NoiseState(NoiseMode.AMBIENT, 13, true, 1)
        assertArrayEquals(bytes(0x68, 0x17, 1, 0, 1, 1, 13), SonyProtocol.setNoise(current, NoiseMode.OFF))
        assertArrayEquals(bytes(0x68, 0x17, 1, 1, 0, 1, 13), SonyProtocol.setNoise(current, NoiseMode.ANC))
    }
    @Test fun batteryUnknownNeverBecomesZeroOrPreviousValue() {
        var state = HeadphoneState().receive(SonyProtocol.parse(bytes(0x23, 1, 75, 0, 82, 1))!!, 100)
        assertEquals(75, state.left?.percent); assertEquals(true, state.right?.charging)
        state = state.receive(SonyProtocol.parse(bytes(0x25, 1, 0xff, 0xff, 0, 2))!!, 200)
        assertNull(state.left); assertEquals(0, state.right?.percent); assertNull(state.right?.charging)
        state = state.receive(SonyProtocol.parse(bytes(0x25, 2, 100, 3))!!, 300)
        assertEquals(100, state.case?.percent); assertNull(state.left)
    }
    @Test fun dseeAndSpeakUseDifferentBooleanEncodings() {
        assertArrayEquals(bytes(0xe8, 1, 1), SonyProtocol.setDsee(true))
        assertArrayEquals(bytes(0xf8, 12, 0, 1), SonyProtocol.setSpeak(true, 1))
        assertEquals(SonyEvent.Speak(true, 1), SonyProtocol.parse(bytes(0xf7, 12, 0, 1)))
    }
    @Test fun eqCapabilityNamesAreLengthChecked() {
        val e = SonyProtocol.parse(bytes(0x51, 0, 6, 21, 2, 0, 3) + "Off".toByteArray() + bytes(0xa0, 6) + "Manual".toByteArray()) as SonyEvent.EqCapability
        assertEquals(listOf(EqPreset(0, "Off"), EqPreset(0xa0, "Manual")), e.presets)
        assertEquals(6, e.bands)
        assertNull(SonyProtocol.parse(bytes(0x51, 0, 6, 21, 1, 0xa0, 30)))
    }
    @Test fun eqWritesIncludeCountAndDoNotInventBandsForPresets() {
        assertArrayEquals(bytes(0x58, 0, 0x10, 0), SonyProtocol.setEq(0x10))
        assertArrayEquals(bytes(0x58, 0, 0xa0, 6, 0, 10, 20, 4, 8, 13), SonyProtocol.setEq(0xa0, listOf(0, 10, 20, 4, 8, 13)))
    }
    @Test fun truncatedPacketsCannotCrashOrCreateState() {
        val samples = listOf(bytes(7, 0, 1, 0x6b, 0), bytes(0x67, 0x17, 1, 1, 1, 1, 20),
            bytes(0x23, 1, 90, 0, 90, 0), bytes(0xf7, 12, 0, 1), bytes(0x57, 0, 0xa0, 6, 10, 10, 10, 10, 10, 10))
        for (sample in samples) for (n in 0 until sample.size) assertNull(SonyProtocol.parse(sample.copyOf(n)))
    }
    @Test fun unknownOrDisconnectedStateCannotEnableControls() {
        val known = HeadphoneState(noiseSettled = true, connection = ConnectionState.READY, functions = setOf(0x6b), noise = NoiseState(NoiseMode.ANC, 10, false, 0))
        assertTrue(known.canControlNoise)
        assertFalse(known.copy(connection = ConnectionState.DISCONNECTED).canControlNoise)
        assertFalse(known.copy(pending = "waiting").canControlNoise)
        assertFalse(known.copy(functions = emptySet()).canControlNoise)
        assertFalse(HeadphoneState(connection = ConnectionState.READY).canControlNoise)
        assertNull(DeviceProfiles.find("WH-1000XM5"))
        assertNotNull(DeviceProfiles.find("WF-1000XM5"))
    }
}
