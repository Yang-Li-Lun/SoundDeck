package dev.sounddeck.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

interface MdrTransport : Closeable {
    val connectionTimeoutMs: Long get() = 12_000L
    suspend fun connect()
    suspend fun read(): ByteArray
    suspend fun write(bytes: ByteArray)
}

/** One transaction at a time; ACK is delivery evidence only. State comes exclusively from RX. */
class MdrSession(
    private val transport: MdrTransport,
    private val onEvent: (SonyEvent) -> Unit,
    diagnostic: (String) -> Unit = {},
    private val timeoutMs: Long = 4000,
) : Closeable {
    private val transactions = Mutex()
    private val writes = Mutex()
    @Volatile private var sequence = 0
    @Volatile private var ack: CompletableDeferred<Unit>? = null
    private data class PendingReply(val matches: (MdrFrame) -> Boolean,
        val result: CompletableDeferred<ByteArray>, val trace: RequestTrace)
    @Volatile private var reply: PendingReply? = null
    private var previous: MdrFrame? = null
    @Volatile private var closed = false
    private val diagnostics = MdrDiagnostics(diagnostic)
    private val frameIds = AtomicLong()
    private var requestId = 0L
    private var operationId = 0L
    @Volatile private var operation = 0L
    private data class RequestTrace(val id: Long, val operation: Long)
    @Volatile private var requestTrace: RequestTrace? = null

    fun recordDiagnostic(text: String) = diagnostics.record(operation, null, text)

    /** The repository serializes controls; these IDs only annotate the existing calls. */
    suspend fun <T> diagnosticOperation(name: String, block: suspend () -> T): T {
        val parent = operation
        val current = ++operationId
        operation = current
        diagnostics.record(current, null, "CONTROL_BEGIN name=$name")
        try {
            return block().also { diagnostics.record(current, null, "CONTROL_END outcome=completed") }
        } catch (e: Throwable) {
            diagnostics.record(current, null, "CONTROL_END outcome=${e.javaClass.simpleName}")
            throw e
        } finally { operation = parent }
    }

    suspend fun <T> useSession(block: suspend MdrSession.() -> T): T {
        recordDiagnostic("SESSION_BEGIN")
        try {
            try { withTimeout(transport.connectionTimeoutMs) { transport.connect() } }
            catch (e: TimeoutCancellationException) { throw IOException("建立控制通道逾時；尚未確認耳機連線。", e) }
            return coroutineScope {
                val reader = launch(Dispatchers.IO) { receive() }
                try { block() } finally { close(); reader.cancelAndJoin() }
            }
        } finally { close(); recordDiagnostic("SESSION_END") }
    }

    suspend fun request(payload: ByteArray, responseCommand: Int? = null): ByteArray? = transactions.withLock {
        check(!closed) { "控制連線已關閉。" }
        val acknowledgment = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ByteArray>()
        val context = RequestTrace(++requestId, operation)
        requestTrace = context
        ack = acknowledgment
        reply = responseCommand?.let { command ->
            PendingReply({ f: MdrFrame -> f.type == MdrCodec.DATA && f.payload.size >= 2 &&
                f.payload[0].u() == command && f.payload[1] == payload[1] }, response, context)
        }
        try {
            diagnostics.record(context.operation, context.id,
                "REQUEST_BEGIN txSeq=$sequence expectedAck=${1 - sequence} expectedReply=${responseCommand?.let { MdrDiagnostics.hex(it) } ?: "none"}")
            withTimeout(timeoutMs) {
                send(MdrFrame(MdrCodec.DATA, sequence, payload))
                acknowledgment.await()
                sequence = 1 - sequence
                diagnostics.record(context.operation, context.id, "ACK_AWAIT_COMPLETE nextTxSeq=$sequence")
                if (responseCommand != null) response.await().also {
                    if (SonyProtocol.parse(it) == null) throw IOException("耳機回覆格式不受支援；已停止控制。")
                } else null
            }.also { diagnostics.record(context.operation, context.id, "REQUEST_END outcome=completed") }
        } catch (e: TimeoutCancellationException) {
            diagnostics.record(context.operation, context.id, "REQUEST_END outcome=timeout ack=${acknowledgment.isCompleted} reply=${response.isCompleted}")
            throw IOException("耳機回覆逾時；控制結果未確認，請重新連線。", e)
        } catch (e: Throwable) {
            diagnostics.record(context.operation, context.id, "REQUEST_END outcome=${e.javaClass.simpleName}")
            throw e
        } finally { ack = null; reply = null; requestTrace = null }
    }

    private suspend fun send(frame: MdrFrame, responseTo: Long? = null) = writes.withLock {
        val context = requestTrace
        val id = frameIds.incrementAndGet()
        diagnostics.record(context?.operation ?: operation, context?.id,
            "TX frame=$id responseTo=${responseTo ?: "-"} phase=start ${MdrDiagnostics.frame(frame)}")
        transport.write(MdrCodec.encode(frame))
        diagnostics.record(context?.operation ?: operation, context?.id, "TX frame=$id phase=transportComplete")
    }

    private suspend fun receive() {
        val decoder = MdrCodec.Decoder()
        while (currentCoroutineContext().isActive) {
            val chunk = try { transport.read() } catch (e: Exception) { if (closed) return else throw e }
            if (chunk.isEmpty()) { if (closed) return else throw IOException("耳機已中斷控制連線。") }
            val rejectedBefore = decoder.rejected
            for (frame in decoder.feed(chunk)) {
                val context = requestTrace
                val id = frameIds.incrementAndGet()
                fun trace(text: String) = diagnostics.record(context?.operation ?: operation, context?.id, "RX frame=$id $text")
                trace(MdrDiagnostics.frame(frame))
                if (frame.type == MdrCodec.ACK) {
                    val expected = 1 - sequence
                    val waiter = ack
                    val accepted = if (frame.sequence == expected) waiter?.complete(Unit) == true else false
                    trace("ACK expected=$expected received=${frame.sequence} waiting=${waiter != null} completed=$accepted")
                    continue
                }
                if (frame.type != MdrCodec.DATA && frame.type != 0x0e) { trace("ignored=frameType"); continue }
                send(MdrFrame(MdrCodec.ACK, 1 - frame.sequence, byteArrayOf()), responseTo = id)
                val old = previous
                if (old != null && old.sequence == frame.sequence && old.type == frame.type &&
                    old.payload.contentEquals(frame.payload)) { trace("duplicate=true eventApplied=false replyMatched=false"); continue }
                previous = frame
                if (frame.type == MdrCodec.DATA) {
                    val event = SonyProtocol.parse(frame.payload)
                    event?.let(onEvent)
                    val pending = reply
                    val matched = pending?.matches?.invoke(frame) == true
                    val completed = if (matched) pending.result.complete(frame.payload) else false
                    trace("duplicate=false parsed=${MdrDiagnostics.parsed(event)} eventApplied=${event != null} " +
                        "replyForReq=${pending?.trace?.id ?: "-"} replyMatched=$matched replyCompleted=$completed")
                } else trace("duplicate=false ignored=table2")
            }
            if (decoder.rejected != rejectedBefore) recordDiagnostic("DECODE rejected=${decoder.rejected - rejectedBefore} totalRejected=${decoder.rejected}")
            if (decoder.rejected > 8) throw IOException("收到過多無效封包；已停止控制連線。")
        }
    }

    override fun close() { closed = true; runCatching { transport.close() } }
}


