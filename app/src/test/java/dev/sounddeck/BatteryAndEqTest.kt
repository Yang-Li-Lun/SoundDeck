package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Source-derived vectors. The supplied real diagnostic contains no raw battery payload. */
class BatteryAndEqTest {
    @Test fun reportedFirmware61CapabilitiesSelectThresholdRequests() {
        val capabilities = setOf(0x10, 0x23, 0x25, 0x27, 0x29, 0x2a, 0x50, 0x6b, 0xe2, 0xfc)
        val requests = SonyProtocol.batteryRequests(capabilities)
        assertEquals(2, requests.size)
        assertArrayEquals(bytes(0x22, 9), requests[0]); assertArrayEquals(bytes(0x22, 10), requests[1])
        assertTrue(SonyProtocol.batteryRequests(setOf(0x23, 0x25)).isEmpty())
    }
    @Test fun legacyAndThresholdVariantsAreNotBothPolled() {
        val requests = SonyProtocol.batteryRequests(setOf(0x21, 0x22, 0x29, 0x2a))
        assertEquals(2, requests.size)
        assertArrayEquals(bytes(0x22, 1), requests[0]); assertArrayEquals(bytes(0x22, 2), requests[1])
    }
    @Test fun thresholdNotificationsUpdateBothEarsAndCaseIndependently() {
        var s = HeadphoneState().receive(SonyProtocol.parse(bytes(0x25, 9, 72, 0, 61, 1, 20, 25))!!, 10)
        assertEquals(72, s.left?.percent); assertEquals(61, s.right?.percent)
        assertEquals(false, s.left?.charging); assertEquals(true, s.right?.charging)
        assertEquals(20, s.left?.threshold); assertEquals(25, s.right?.threshold)
        s = s.receive(SonyProtocol.parse(bytes(0x23, 10, 100, 3, 30))!!, 20)
        assertEquals(72, s.left?.percent); assertEquals(100, s.case?.percent)
        assertEquals("已充滿", s.case?.statusLabel); assertEquals(20L, s.batteryUpdatedAt)
        s = s.receive(SonyProtocol.parse(bytes(0x25, 9, 255, 255, 0, 2, 20, 30))!!, 30)
        assertNull(s.left); assertEquals(0, s.right?.percent); assertNull(s.right?.charging)
        assertEquals(100, s.case?.percent)
    }
    @Test fun unknownPercentageAndUnknownChargingDoNotEraseEachOtherOrBecomeThreshold() {
        val event = SonyProtocol.parse(bytes(0x23, 9, 255, 1, 82, 255, 15, 255)) as SonyEvent.Battery
        assertNull(event.left?.percent); assertTrue(event.left?.charging == true)
        assertEquals(15, event.left?.threshold)
        assertEquals(82, event.right?.percent); assertNull(event.right?.charging)
        assertNull(event.right?.threshold)
    }
    @Test fun thresholdFramesRequireExactLengthAndKnownSubtype() {
        for (frame in listOf(bytes(0x25, 9, 10, 0, 20, 1, 25, 30), bytes(0x23, 10, 30, 0, 40))) {
            for (n in 0 until frame.size) assertNull(SonyProtocol.parse(frame.copyOf(n)))
            assertNull(SonyProtocol.parse(frame + byteArrayOf(1)))
        }
        assertNull(SonyProtocol.parse(bytes(0x25, 0x0b, 50, 0, 20)))
    }
    @Test fun returnAndNotifyBatteryLayoutsAgree() {
        val packet = bytes(0x23, 9, 20, 0, 50, 1, 15, 25)
        assertEquals(SonyProtocol.parse(packet), SonyProtocol.parse(packet.copyOf().also { it[0] = 0x25 }))
    }
    @Test fun presetLabelsKeepIdsAndOriginalNames() {
        val preset = EqPreset(0x10, "BRIGHT")
        assertEquals("明亮", preset.displayName); assertEquals("BRIGHT", preset.name); assertEquals(0x10, preset.id)
        assertEquals("關閉", EqPresetNames.label(0)); assertEquals("自訂 2", EqPresetNames.label(0xa2))
        assertEquals("未知預設 (0x08)", EqPresetNames.label(8)) // Reserved is not invented.
        assertEquals("New EQ (0x7E)", EqPresetNames.label(0x7e, "New EQ"))
    }
    @Test fun blankNamesStillDisplayKnownIdsAndUnknownFallback() {
        val cap = SonyProtocol.parse(bytes(0x51, 0, 6, 21, 2, 0x14, 0, 0x7e, 0)) as SonyEvent.EqCapability
        assertEquals(listOf(0x14, 0x7e), cap.presets.map { it.id })
        assertEquals(listOf("人聲", "未知預設 (0x7E)"), cap.presets.map { it.displayName })
        assertEquals(listOf("", ""), cap.presets.map { it.name })
    }
}
