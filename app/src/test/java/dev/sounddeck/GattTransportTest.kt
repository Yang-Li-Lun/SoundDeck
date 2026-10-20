package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GattTransportTest {
    private class Driver : GattDriver {
        lateinit var events: GattDriver.Listener
        var services = listOf(SonyGattProfile.Service(SonyGattProfile.SERVICE, listOf(
            SonyGattProfile.Characteristic(SonyGattProfile.TX, SonyGattProfile.WRITE),
            SonyGattProfile.Characteristic(SonyGattProfile.RX, SonyGattProfile.NOTIFY, listOf(SonyGattProfile.CCCD)))))
        val writes = mutableListOf<ByteArray>()
        var closeCount = 0
        var subscribeCount = 0
        var indication = false
        var response = false
        var connectImmediately = true
        var subscribeStatus = 0
        var writeStatus = 0
        var wrongWriteCallback = false
        var onBytes: ((ByteArray) -> Unit)? = null
        override fun open(listener: GattDriver.Listener): Boolean {
            events = listener
            if (connectImmediately) events.connected(0, true)
            return true
        }
        override fun discover(): Boolean { events.services(0, services); return true }
        override fun enableNotifications() = true
        override fun subscribe(indication: Boolean): Boolean {
            subscribeCount++; this.indication = indication
            events.descriptorWritten(subscribeStatus, SonyGattProfile.SERVICE, SonyGattProfile.RX, SonyGattProfile.CCCD)
            return true
        }
        override fun write(value: ByteArray, withResponse: Boolean): Boolean {
            writes += value.copyOf(); response = withResponse
            events.characteristicWritten(writeStatus, SonyGattProfile.SERVICE, if (wrongWriteCallback) "unrelated" else SonyGattProfile.TX)
            onBytes?.invoke(value)
            return true
        }
        fun emit(value: ByteArray) = events.notification(SonyGattProfile.SERVICE, SonyGattProfile.RX, value)
        override fun close() { closeCount++ }
    }

    @Test fun onlyExactServiceAndNamedCharacteristicsCanBeUsed() = runTest {
        for (inventory in listOf(
            emptyList(),
            listOf(SonyGattProfile.Service("5b833e21-6bc7-4802-8e9a-723ceca4bd8f", Driver().services.single().characteristics)),
            listOf(SonyGattProfile.Service(SonyGattProfile.SERVICE, listOf(
                SonyGattProfile.Characteristic("arbitrary-writable", SonyGattProfile.WRITE),
                Driver().services.single().characteristics.last()))),
            Driver().services + Driver().services,
        )) {
            val driver = Driver().also { it.services = inventory }
            val transport = GattMdrTransport(driver)
            try { transport.connect(); fail("Unverified endpoint accepted") } catch (_: IOException) { }
            assertEquals(0, driver.subscribeCount)
            assertTrue(driver.writes.isEmpty())
            assertEquals(1, driver.closeCount)
        }
    }
    @Test fun requiredPropertiesAndCccdAreValidatedBeforeWriting() {
        val valid = Driver().services.single()
        for (chars in listOf(
            listOf(valid.characteristics[0].copy(properties = 0), valid.characteristics[1]),
            listOf(valid.characteristics[0], valid.characteristics[1].copy(properties = 0)),
            listOf(valid.characteristics[0], valid.characteristics[1].copy(descriptors = emptyList())),
            valid.characteristics + valid.characteristics[0],
        )) {
            assertThrows(IOException::class.java) { SonyGattProfile.select(listOf(valid.copy(characteristics = chars))) }
        }
    }
    @Test fun supportsIndicationAndWriteWithoutResponseOnlyWhenDeclared() = runTest {
        val driver = Driver()
        driver.services = listOf(driver.services.single().copy(characteristics = listOf(
            SonyGattProfile.Characteristic(SonyGattProfile.TX, SonyGattProfile.WRITE_NO_RESPONSE),
            SonyGattProfile.Characteristic(SonyGattProfile.RX, SonyGattProfile.INDICATE, listOf(SonyGattProfile.CCCD)))))
        val transport = GattMdrTransport(driver)
        transport.connect(); transport.write(byteArrayOf(1))
        assertTrue(driver.indication); assertFalse(driver.response)
        transport.close()
    }
    @Test fun encodedFramesAreSplitWithoutChangingAnyByte() = runTest {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        transport.connect()
        val frame = MdrCodec.encode(MdrFrame(MdrCodec.DATA, 0, ByteArray(45) { 0x3e }))
        transport.write(frame)
        assertTrue(driver.writes.size > 1)
        assertTrue(driver.writes.all { it.size in 1..20 })
        assertArrayEquals(frame, driver.writes.reduce { a, b -> a + b })
        assertTrue(driver.response)
        transport.close()
    }
    @Test fun subscriptionFailureClosesWithoutProtocolWrites() = runTest {
        val driver = Driver().also { it.subscribeStatus = 5 }
        val transport = GattMdrTransport(driver)
        try { transport.connect(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("SUBSCRIBE")) }
        assertTrue(driver.writes.isEmpty()); assertEquals(1, driver.closeCount)
    }
    @Test fun writeFailureDoesNotSendRemainingFragments() = runTest {
        val driver = Driver().also { it.writeStatus = 133 }
        val transport = GattMdrTransport(driver)
        transport.connect()
        try { transport.write(ByteArray(45)); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("133")) }
        assertEquals(1, driver.writes.size); assertEquals(1, driver.closeCount)
    }
    @Test fun unrelatedWriteCallbackCannotConfirmAnOperation() = runTest {
        val driver = Driver().also { it.wrongWriteCallback = true }
        val transport = GattMdrTransport(driver, operationTimeoutMs = 30)
        transport.connect()
        try { transport.write(byteArrayOf(1)); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("逾時")) }
        assertEquals(1, driver.closeCount)
    }
    @Test fun cancellingConnectionReleasesOnlyThisDriverOnce() = runTest {
        val driver = Driver().also { it.connectImmediately = false }
        val transport = GattMdrTransport(driver)
        val connecting = launch { transport.connect() }
        runCurrent(); connecting.cancelAndJoin(); transport.close()
        assertEquals(1, driver.closeCount)
        driver.events.connected(0, true) // late callback cannot resurrect transport
        try { transport.write(byteArrayOf(1)); fail() } catch (_: IOException) { }
    }
    @Test fun unrelatedNotificationsAreNotMdrInput() = runTest {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        transport.connect()
        driver.events.notification("unknown-service", SonyGattProfile.RX, byteArrayOf(8))
        driver.events.notification(SonyGattProfile.SERVICE, "unknown-characteristic", byteArrayOf(9))
        driver.emit(byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), transport.read())
        transport.close()
    }
    @Test fun disconnectDiscardsBufferedDataAndFailsReads() = runTest {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        transport.connect(); driver.emit(byteArrayOf(7))
        driver.events.connected(19, false)
        try { transport.read(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("19")) }
        assertEquals(1, driver.closeCount)
    }
    @Test fun receiveOverflowFailsClosed() = runTest {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        transport.connect()
        repeat(65) { driver.emit(byteArrayOf(it.toByte())) }
        try { transport.read(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("超限")) }
        assertEquals(1, driver.closeCount)
    }
    @Test fun realSessionAndControllerUseGattBytesAndReadBackState() = runBlocking {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        val decoder = MdrCodec.Decoder()
        var remoteMode = NoiseMode.ANC
        var remoteSequence = 0
        driver.onBytes = { bytes ->
            for (frame in decoder.feed(bytes)) if (frame.type == MdrCodec.DATA) {
                driver.emit(MdrCodec.encode(MdrFrame(MdrCodec.ACK, 1 - frame.sequence, byteArrayOf())))
                if (frame.payload[0].u() == 0x68) remoteMode = if (frame.payload[4].u() == 1) NoiseMode.AMBIENT else NoiseMode.ANC
                if (frame.payload[0].u() == 0x66) {
                    val response = MdrCodec.encode(MdrFrame(MdrCodec.DATA, remoteSequence, bytes(0x67, 0x17, 1, 1,
                        if (remoteMode == NoiseMode.ANC) 0 else 1, 0, 12)))
                    remoteSequence = 1 - remoteSequence
                    response.asList().chunked(3).forEach { driver.emit(it.toByteArray()) }
                }
            }
        }
        var state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.ANC, 12, false, 0))
        var message = ""
        val controller = SonyController({ state }, { message = it })
        MdrSession(transport, { state = state.receive(it, 1) }).useSession {
            controller.execute(this, ControlAction.Noise(NoiseMode.AMBIENT))
        }
        assertEquals(NoiseMode.AMBIENT, state.noise?.mode)
        assertTrue(message.contains("耳機已回報"))
        assertEquals(1, driver.closeCount)
    }
}
