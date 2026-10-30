package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
        var onClose: (() -> Unit)? = null
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
        override fun close() { closeCount++; onClose?.invoke() }
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
    @Test fun mtuCallbacksCannotCompletePendingOperationsOrChangeFragmentSize() = runTest {
        val driver = Driver().apply { connectImmediately = false }
        val diagnostics = mutableListOf<String>()
        val transport = GattMdrTransport(driver, diagnostics::add)
        val connecting = launch { transport.connect() }
        runCurrent()
        driver.events.mtuChanged(517, 0)
        driver.events.mtuChanged(23, 257)
        runCurrent()
        assertTrue(connecting.isActive)
        assertEquals(0, driver.subscribeCount)
        assertTrue(driver.writes.isEmpty())
        driver.events.connected(0, true)
        runCurrent()
        assertTrue(connecting.isCompleted)
        assertEquals(1, driver.subscribeCount)
        assertTrue(diagnostics.any { it == "GATT_CONFIG txWrite=with_response rx=notification frameChunkLimit=20 mtuRequest=none" })

        driver.wrongWriteCallback = true
        val writing = launch { transport.write(ByteArray(45) { it.toByte() }) }
        runCurrent()
        assertEquals(1, driver.writes.size)
        driver.events.mtuChanged(517, 0)
        driver.events.mtuChanged(23, 257)
        runCurrent()
        assertTrue(writing.isActive)
        assertEquals(1, driver.writes.size)
        driver.wrongWriteCallback = false
        driver.events.characteristicWritten(0, SonyGattProfile.SERVICE, SonyGattProfile.TX)
        runCurrent()
        assertTrue(writing.isCompleted)
        assertEquals(listOf(20, 20, 5), driver.writes.map { it.size })
        assertArrayEquals(ByteArray(45) { it.toByte() }, driver.writes.reduce { a, b -> a + b })
        assertTrue(driver.response)
        assertEquals(2, diagnostics.count { it.startsWith("GATT_MTU_CALLBACK") && "pendingStage=CONNECT" in it })
        assertEquals(2, diagnostics.count { it.startsWith("GATT_MTU_CALLBACK") && "pendingStage=WRITE" in it })

        driver.emit(byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), transport.read())
        transport.close()
        val count = diagnostics.size
        driver.events.mtuChanged(517, 0)
        assertEquals(count, diagnostics.size)
        assertEquals(1, driver.closeCount)
    }
    @Test fun failingPassiveGattDiagnosticsLeaveNoResponseAndIndicationPathUnchanged() = runTest {
        val driver = Driver()
        driver.services = listOf(driver.services.single().copy(characteristics = listOf(
            SonyGattProfile.Characteristic(SonyGattProfile.TX, SonyGattProfile.WRITE_NO_RESPONSE),
            SonyGattProfile.Characteristic(SonyGattProfile.RX, SonyGattProfile.INDICATE, listOf(SonyGattProfile.CCCD)))))
        val observations = mutableListOf<String>()
        val transport = GattMdrTransport(driver, diagnostic = {
            if (it.startsWith("GATT_CONFIG") || it.startsWith("GATT_MTU_CALLBACK")) {
                observations += it
                error("diagnostic sink unavailable")
            }
        })
        transport.connect()
        driver.events.mtuChanged(23, 257)
        transport.write(byteArrayOf(1, 2, 3))
        driver.emit(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), transport.read())
        assertEquals(2, observations.size)
        assertEquals("GATT_CONFIG txWrite=without_response rx=indication frameChunkLimit=20 mtuRequest=none", observations.first())
        assertTrue(driver.indication)
        assertFalse(driver.response)
        assertEquals(1, driver.subscribeCount)
        assertEquals(1, driver.writes.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), driver.writes.single())
        transport.close()
        assertEquals(1, driver.closeCount)
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
    @Test fun onlyRecognizedActualLinkLossIsRecoverable() = runTest {
        for (status in listOf(0, 8, 19, 22, 5, 133, 257)) {
            val driver = Driver(); val transport = GattMdrTransport(driver)
            transport.connect(); driver.emit(byteArrayOf(7))
            driver.events.disconnected(status)
            val failure = runCatching { transport.read() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(status == 0 || status == 8, failure is TransportDisconnected)
            assertEquals(1, driver.closeCount)
        }
    }
    @Test fun serviceInvalidationAndNonDisconnectedErrorsNeverQualifyForRecovery() = runTest {
        for (status in listOf(8, 257)) {
            val driver = Driver(); val transport = GattMdrTransport(driver)
            transport.connect()
            driver.events.connected(status, false)
            val failure = runCatching { transport.read() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure is TransportDisconnected)
        }
    }
    @Test fun writeAuthorizationErrorIsNotAConnectionTimeout() = runTest {
        val driver = Driver().also { it.writeStatus = 8 }
        val transport = GattMdrTransport(driver)
        transport.connect()
        val failure = runCatching { transport.write(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(failure is IOException); assertFalse(failure is TransportDisconnected)
    }
    @Test fun localCloseIgnoresLateDisconnectedCallback() = runTest {
        val driver = Driver(); val transport = GattMdrTransport(driver)
        transport.connect(); transport.close()
        driver.events.disconnected(8)
        val failure = runCatching { transport.read() }.exceptionOrNull()
        assertFalse(failure is TransportDisconnected); assertEquals(1, driver.closeCount)
    }
    @Test fun firstFailureSurvivesReentrantRecoverableDisconnectDuringDiagnostic() = runTest {
        val driver = Driver().also { it.writeStatus = 133 }
        val diagnostics = mutableListOf<String>()
        var injectedDisconnect = false
        val transport = GattMdrTransport(driver, diagnostic = { entry ->
            diagnostics += entry
            if (entry.startsWith("GATT WRITE status=133")) {
                injectedDisconnect = true
                driver.events.disconnected(8)
            }
        })
        transport.connect()
        val writeFailure = runCatching { transport.write(byteArrayOf(1)) }.exceptionOrNull()
        val readFailure = runCatching { transport.read() }.exceptionOrNull()
        assertTrue(injectedDisconnect)
        assertTrue(diagnostics.contains("GATT disconnected status=8"))
        for (failure in listOf(writeFailure, readFailure)) {
            assertTrue(failure is IOException)
            assertFalse(failure is TransportDisconnected)
            assertTrue(failure?.message.orEmpty().contains("WRITE status=133"))
        }
        assertEquals(1, driver.closeCount)
    }
    @Test fun concurrentCloseWaitsUntilDriverReleaseHasCompleted() {
        val driver = Driver()
        val transport = GattMdrTransport(driver)
        runBlocking { transport.connect() }
        val driverCloseEntered = CountDownLatch(1)
        val releaseDriverClose = CountDownLatch(1)
        val driverCloseCompleted = CountDownLatch(1)
        val secondCloseStarted = CountDownLatch(1)
        val driverWaitExpired = AtomicBoolean(false)
        driver.onClose = {
            driverCloseEntered.countDown()
            try {
                if (!releaseDriverClose.await(5, TimeUnit.SECONDS)) driverWaitExpired.set(true)
            } finally { driverCloseCompleted.countDown() }
        }
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "gatt-close-test").apply { isDaemon = true }
        }
        try {
            val firstClose = executor.submit { transport.close() }
            assertTrue(driverCloseEntered.await(2, TimeUnit.SECONDS))
            val secondClose = executor.submit {
                secondCloseStarted.countDown()
                transport.close()
            }
            assertTrue(secondCloseStarted.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { secondClose.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1L, driverCloseCompleted.count)
            releaseDriverClose.countDown()
            firstClose.get(2, TimeUnit.SECONDS)
            secondClose.get(2, TimeUnit.SECONDS)
            assertEquals(0L, driverCloseCompleted.count)
            assertFalse(driverWaitExpired.get())
            assertEquals(1, driver.closeCount)
        } finally {
            releaseDriverClose.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
    @Test fun failedDriverReleaseStopsRecoveryAndKeepsAddressRedacted() = runTest {
        val driver = Driver().apply { onClose = { throw IOException("release AA:BB:CC:DD:EE:FF failed") } }
        val transport = GattMdrTransport(driver)
        var attempts = 0
        val failure = runCatching {
            SessionRecovery({ true }).run({ _, _ -> fail("release not confirmed") }) { _, ready ->
                attempts++
                transport.connect(); ready(ControlTransport.BLE)
                driver.events.disconnected(8)
                transport.read()
            }
        }.exceptionOrNull()
        assertEquals(1, attempts)
        assertTrue(failure is IOException); assertFalse(failure is TransportDisconnected)
        assertTrue(failure?.message.orEmpty().contains("關閉未確認"))
        assertFalse(failure?.message.orEmpty().contains("AA:BB"))
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
        assertTrue(message.contains("耳機回報設定相符"))
        assertEquals(1, driver.closeCount)
    }
}
