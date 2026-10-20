package dev.sounddeck.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException

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
    private val diagnostic: (String) -> Unit = {},
    private val timeoutMs: Long = 4000,
) : Closeable {
    private val transactions = Mutex()
    private val writes = Mutex()
    @Volatile private var sequence = 0
    @Volatile private var ack: CompletableDeferred<Unit>? = null
    @Volatile private var reply: Pair<(MdrFrame) -> Boolean, CompletableDeferred<ByteArray>>? = null
    private var previous: MdrFrame? = null
    @Volatile private var closed = false

    suspend fun <T> useSession(block: suspend MdrSession.() -> T): T {
        try {
            try { withTimeout(transport.connectionTimeoutMs) { transport.connect() } }
            catch (e: TimeoutCancellationException) { throw IOException("建立控制通道逾時；尚未確認耳機連線。", e) }
            return coroutineScope {
                val reader = launch(Dispatchers.IO) { receive() }
                try { block() } finally { close(); reader.cancelAndJoin() }
            }
        } finally { close() }
    }

    suspend fun request(payload: ByteArray, responseCommand: Int? = null): ByteArray? = transactions.withLock {
        check(!closed) { "控制連線已關閉。" }
        val acknowledgment = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ByteArray>()
        ack = acknowledgment
        reply = responseCommand?.let { command ->
            Pair({ f: MdrFrame -> f.type == MdrCodec.DATA && f.payload.size >= 2 &&
                f.payload[0].u() == command && f.payload[1] == payload[1] }, response)
        }
        try {
            withTimeout(timeoutMs) {
                send(MdrFrame(MdrCodec.DATA, sequence, payload))
                diagnostic("TX ${payload[0].u().toString(16)} / ${payload[1].u().toString(16)}")
                acknowledgment.await()
                sequence = 1 - sequence
                if (responseCommand != null) response.await().also {
                    if (SonyProtocol.parse(it) == null) throw IOException("耳機回覆格式不受支援；已停止控制。")
                } else null
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("耳機回覆逾時；控制結果未確認，請重新連線。", e)
        } finally { ack = null; reply = null }
    }

    private suspend fun send(frame: MdrFrame) = writes.withLock { transport.write(MdrCodec.encode(frame)) }

    private suspend fun receive() {
        val decoder = MdrCodec.Decoder()
        while (currentCoroutineContext().isActive) {
            val chunk = try { transport.read() } catch (e: Exception) { if (closed) return else throw e }
            if (chunk.isEmpty()) { if (closed) return else throw IOException("耳機已中斷控制連線。") }
            for (frame in decoder.feed(chunk)) {
                if (frame.type == MdrCodec.ACK) {
                    if (frame.sequence == 1 - sequence) ack?.complete(Unit)
                    continue
                }
                if (frame.type != MdrCodec.DATA && frame.type != 0x0e) continue
                send(MdrFrame(MdrCodec.ACK, 1 - frame.sequence, byteArrayOf()))
                val old = previous
                if (old != null && old.sequence == frame.sequence && old.type == frame.type &&
                    old.payload.contentEquals(frame.payload)) continue
                previous = frame
                if (frame.type == MdrCodec.DATA) {
                    val event = SonyProtocol.parse(frame.payload)
                    event?.let(onEvent)
                    val command = frame.payload.firstOrNull()?.u()
                    if (command == 0x23 || command == 0x25) {
                        // Only bounded battery packets are recorded. No arbitrary payload, identity, or address logging.
                        val hex = frame.payload.take(16).joinToString(" ") { it.u().toString(16).padStart(2, '0') }
                        diagnostic("RX ${command.toString(16)} battery len=${frame.payload.size} parsed=${event is SonyEvent.Battery} [$hex]")
                    } else diagnostic("RX ${command?.toString(16) ?: "empty"}")
                    reply?.let { (matches, deferred) -> if (matches(frame)) deferred.complete(frame.payload) }
                }
            }
            if (decoder.rejected > 8) throw IOException("收到過多無效封包；已停止控制連線。")
        }
    }

    override fun close() { closed = true; runCatching { transport.close() } }
}


