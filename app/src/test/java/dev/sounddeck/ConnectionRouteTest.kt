package dev.sounddeck

import dev.sounddeck.data.DeviceProfiles
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConnectionRouteTest {
    @Test fun classicRecordKeepsExistingRoute() {
        assertEquals(listOf(ControlTransport.RFCOMM), ControlRoute.plan(DeviceRadio.CLASSIC, emptySet(), ControlPreference.AUTO))
    }
    @Test fun leRecordUsesGattWithoutChangingAudioSettings() {
        assertEquals(listOf(ControlTransport.BLE), ControlRoute.plan(DeviceRadio.LE, emptySet(), ControlPreference.AUTO))
    }
    @Test fun dualModeUsesEvidenceWithBoundedFallback() {
        assertEquals(listOf(ControlTransport.RFCOMM, ControlTransport.BLE), ControlRoute.plan(DeviceRadio.DUAL, setOf(SonyProtocol.SERVICE_UUID), ControlPreference.AUTO))
        assertEquals(listOf(ControlTransport.BLE, ControlTransport.RFCOMM), ControlRoute.plan(DeviceRadio.DUAL, setOf(SonyGattProfile.SERVICE.uppercase()), ControlPreference.AUTO))
        assertEquals(listOf(ControlTransport.BLE, ControlTransport.RFCOMM), ControlRoute.plan(DeviceRadio.UNKNOWN, emptySet(), ControlPreference.AUTO))
    }
    @Test fun explicitRouteDoesNotSilentlyChooseAnotherTransport() {
        assertEquals(listOf(ControlTransport.BLE), ControlRoute.plan(DeviceRadio.CLASSIC, emptySet(), ControlPreference.BLE))
        assertEquals(listOf(ControlTransport.RFCOMM), ControlRoute.plan(DeviceRadio.LE, emptySet(), ControlPreference.RFCOMM))
    }
    @Test fun advertisedPrefixIsNotAcceptedAsVerifiedWireIdentity() {
        assertNotNull(DeviceProfiles.advertised("LE_WF-1000XM5"))
        assertNull(DeviceProfiles.find("LE_WF-1000XM5"))
        assertNull(DeviceProfiles.advertised("WF-1000XM5 fake"))
        assertNull(DeviceProfiles.advertised("LE_WH-1000XM5"))
    }
    @Test fun initializationFailureCanTryNextTransportOnce() = runTest {
        val tried = mutableListOf<ControlTransport>()
        ConnectionAttempts.run(listOf(ControlTransport.RFCOMM, ControlTransport.BLE), { _, _ -> }) { route, ready ->
            tried += route
            if (route == ControlTransport.RFCOMM) throw IOException("bt socket closed, read return: -1")
            ready()
        }
        assertEquals(listOf(ControlTransport.RFCOMM, ControlTransport.BLE), tried)
    }
    @Test fun readySessionFailureNeverReplaysOnFallback() = runTest {
        val tried = mutableListOf<ControlTransport>()
        try {
            ConnectionAttempts.run(ControlTransport.entries, { _, _ -> }) { route, ready ->
                tried += route; ready(); throw IOException("SET result unknown")
            }
            fail()
        } catch (_: IOException) { }
        assertEquals(listOf(ControlTransport.BLE), tried)
    }
    @Test fun userCancellationNeverFallsBack() = runTest {
        val tried = mutableListOf<ControlTransport>()
        try {
            ConnectionAttempts.run(ControlTransport.entries, { _, _ -> }) { route, _ ->
                tried += route; throw CancellationException("user stopped")
            }
            fail()
        } catch (_: CancellationException) { }
        assertEquals(1, tried.size)
    }
    @Test fun wrongIdentityStopsWithoutTryingAnotherRoute() = runTest {
        val tried = mutableListOf<ControlTransport>()
        try {
            ConnectionAttempts.run(ControlTransport.entries, { _, _ -> }) { route, _ ->
                tried += route; throw IdentityRejected("wrong model")
            }
            fail()
        } catch (_: IOException) { }
        assertEquals(1, tried.size)
    }
    @Test fun diagnosticsPreserveSocketEvidenceWithoutAddresses() {
        val summary = ConnectionDiagnostics.describe(IOException("connection failed", IOException("AA:BB:CC:DD:EE:FF bt socket closed, read return: -1")))
        assertFalse(summary.contains("AA:BB"))
        assertTrue(summary.contains("bt socket closed, read return: -1"))
    }
}
