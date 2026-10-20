package dev.sounddeck

import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic transport only. These tests are not headset/Bluetooth acceptance. */
class MdrSessionTest {
    private class ScriptedTransport(val script: suspend ScriptedTransport.(MdrFrame) -> Unit): MdrTransport {
        val inbound = Channel<ByteArray>(32)
        val writes = CopyOnWriteArrayList<MdrFrame>()
        var closed = false
        override suspend fun connect() {}
        override suspend fun read() = inbound.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) {
            val frame = MdrCodec.Decoder().feed(bytes).single()
            writes.add(frame)
            if (frame.type == MdrCodec.DATA) script(frame)
        }
        suspend fun ack(frame: MdrFrame) { inbound.send(MdrCodec.encode(MdrFrame(1, 1 - frame.sequence, byteArrayOf()))) }
        suspend fun response(sequence: Int, vararg data: Int) { inbound.send(MdrCodec.encode(MdrFrame(12, sequence, bytes(*data)))) }
        override fun close() { closed = true; inbound.close() }
    }

    @Test fun sequenceAlternatesOnlyAfterAckAndReceiverAcksOppositeSequence() = runBlocking {
        var responseSequence = 0
        val transport = ScriptedTransport { frame -> ack(frame); response(responseSequence, 0x67, 0x17, 1, 1, 0, 0, 12); responseSequence = 1 - responseSequence }
        val events = CopyOnWriteArrayList<SonyEvent>()
        MdrSession(transport, { events.add(it) }).useSession {
            request(SonyProtocol.noise, 0x67)
            request(SonyProtocol.noise, 0x67)
        }
        assertEquals(listOf(0, 1), transport.writes.filter { it.type == 12 }.map { it.sequence })
        assertEquals(listOf(1, 0), transport.writes.filter { it.type == 1 }.map { it.sequence })
        assertEquals(2, events.size); assertTrue(transport.closed)
    }
    @Test fun ackAloneNeverCreatesStateOrConfirmsRead() = runBlocking {
        val transport = ScriptedTransport { frame -> ack(frame) }
        val events = CopyOnWriteArrayList<SonyEvent>()
        val result = runCatching { MdrSession(transport, { events.add(it) }, timeoutMs = 120).useSession { request(SonyProtocol.noise, 0x67) } }
        assertTrue(result.exceptionOrNull() is IOException); assertTrue(events.isEmpty()); assertTrue(transport.closed)
    }
    @Test fun staleAckDoesNotAdvanceTransaction() = runBlocking {
        val transport = ScriptedTransport { frame -> inbound.send(MdrCodec.encode(MdrFrame(1, frame.sequence, byteArrayOf()))) }
        val result = runCatching { MdrSession(transport, {}, timeoutMs = 120).useSession { request(SonyProtocol.setDsee(true)) } }
        assertTrue(result.exceptionOrNull() is IOException); assertEquals(1, transport.writes.size)
    }
    @Test fun unrelatedNotificationUpdatesStateButDoesNotSatisfyRequest() = runBlocking {
        val transport = ScriptedTransport { frame ->
            ack(frame)
            response(0, 0x25, 1, 75, 0, 60, 0)
            response(1, 0x67, 0x17, 1, 1, 1, 1, 9)
        }
        val events = CopyOnWriteArrayList<SonyEvent>()
        val response = MdrSession(transport, { events.add(it) }).useSession { request(SonyProtocol.noise, 0x67) }
        assertEquals(0x67, response!![0].u()); assertEquals(2, events.size)
        assertTrue(events.first() is SonyEvent.Battery)
    }
    @Test fun duplicateNotificationGetsAckButNotAppliedTwice() = runBlocking {
        val transport = ScriptedTransport { frame ->
            ack(frame)
            response(0, 0x25, 2, 55, 0)
            response(0, 0x25, 2, 55, 0)
            response(1, 0x67, 0x17, 1, 1, 0, 0, 10)
        }
        val events = CopyOnWriteArrayList<SonyEvent>()
        MdrSession(transport, { events.add(it) }).useSession { request(SonyProtocol.noise, 0x67) }
        assertEquals(2, events.size); assertEquals(3, transport.writes.count { it.type == 1 })
    }
    @Test fun invalidReplyFailsInsteadOfReusingOldState() = runBlocking {
        val transport = ScriptedTransport { frame -> ack(frame); response(0, 0x67, 0x17, 0xff) }
        val events = CopyOnWriteArrayList<SonyEvent>()
        val result = runCatching { MdrSession(transport, { events.add(it) }).useSession { request(SonyProtocol.noise, 0x67) } }
        assertTrue(result.exceptionOrNull() is IOException); assertTrue(events.isEmpty()); assertTrue(transport.closed)
    }
    @Test fun cancellationClosesSocketAndDoesNotResendCommand() = runBlocking {
        val transport = ScriptedTransport { }
        val job = launch { MdrSession(transport, {}).useSession { request(SonyProtocol.noise, 0x67) } }
        delay(50); job.cancelAndJoin()
        assertTrue(transport.closed); assertEquals(1, transport.writes.count { it.type == 12 })
    }
    @Test fun concurrentCallersAreSerialized() = runBlocking {
        val transport = ScriptedTransport { frame -> delay(10); ack(frame) }
        MdrSession(transport, {}).useSession {
            coroutineScope { repeat(8) { launch { request(SonyProtocol.setDsee(it % 2 == 0)) } } }
        }
        assertEquals(List(8) { it % 2 }, transport.writes.map { it.sequence })
    }
    @Test fun failedConnectAlwaysReleasesTransport() = runBlocking {
        var closed = false
        val transport = object : MdrTransport {
            override suspend fun connect() { throw IOException("connection refused") }
            override suspend fun read() = byteArrayOf()
            override suspend fun write(bytes: ByteArray) = Unit
            override fun close() { closed = true }
        }
        assertTrue(runCatching { MdrSession(transport, {}).useSession { } }.exceptionOrNull() is IOException)
        assertTrue(closed)
    }
    @Test fun connectTimeoutIsReportedAsFailureAndReleasesTransport() = runBlocking {
        var closed = false
        val transport = object : MdrTransport {
            override val connectionTimeoutMs = 30L
            override suspend fun connect() { awaitCancellation() }
            override suspend fun read(): ByteArray = error("reader must not start")
            override suspend fun write(bytes: ByteArray) = error("must not write")
            override fun close() { closed = true }
        }
        val error = runCatching { MdrSession(transport, {}).useSession { fail("not connected") } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.cause is TimeoutCancellationException)
        assertTrue(closed)
    }
    @Test fun cancellationDuringConnectRemainsCancellationAndReleasesTransport() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        var cancelled = false
        val transport = object : MdrTransport {
            override suspend fun connect() { started.complete(Unit); awaitCancellation() }
            override suspend fun read(): ByteArray = error("reader must not start")
            override suspend fun write(bytes: ByteArray) = error("must not write")
            override fun close() { closed = true }
        }
        val connecting = launch {
            try { MdrSession(transport, {}).useSession { fail("not connected") } }
            catch (e: CancellationException) { cancelled = true; throw e }
        }
        started.await(); connecting.cancelAndJoin()
        assertTrue(cancelled); assertTrue(closed)
    }
}

