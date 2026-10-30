package dev.sounddeck.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException

/** Android adapter and deterministic tests implement this same callback contract. */
interface GattDriver : Closeable {
    interface Listener {
        fun connected(status: Int, connected: Boolean)
        fun disconnected(status: Int) = connected(status, false)
        fun services(status: Int, services: List<SonyGattProfile.Service>)
        fun descriptorWritten(status: Int, service: String, characteristic: String, descriptor: String)
        fun characteristicWritten(status: Int, service: String, characteristic: String)
        fun notification(service: String, characteristic: String, value: ByteArray)
        fun mtuChanged(mtu: Int, status: Int) {}
    }
    fun open(listener: Listener): Boolean
    fun discover(): Boolean
    fun enableNotifications(): Boolean
    fun subscribe(indication: Boolean): Boolean
    fun write(value: ByteArray, withResponse: Boolean): Boolean
}

/** One outstanding GATT operation; transport completion is never headphone state confirmation. */
class GattMdrTransport(
    private val driver: GattDriver,
    private val diagnostic: (String) -> Unit = {},
    private val operationTimeoutMs: Long = 6000,
) : MdrTransport, GattDriver.Listener {
    override val connectionTimeoutMs = 25_000L
    private enum class Operation { CONNECT, DISCOVER, SUBSCRIBE, WRITE }
    private val lock = Any()
    private val operationMutex = Mutex()
    private val frameMutex = Mutex()
    private var pending: Pair<Operation, CompletableDeferred<Unit>>? = null
    private val incoming = Channel<ByteArray>(64)
    private var discovered: List<SonyGattProfile.Service> = emptyList()
    @Volatile private var closed = false
    @Volatile private var selection: SonyGattProfile.Selection? = null
    @Volatile private var terminal: IOException? = null

    override suspend fun connect() {
        try {
            operation(Operation.CONNECT, 12_000) { driver.open(this) }
            operation(Operation.DISCOVER) { driver.discover() }
            val profile = SonyGattProfile.select(discovered)
            diagnostic("GATT allowlist matched: service=${SonyGattProfile.SERVICE}, TX=${SonyGattProfile.TX}, RX=${SonyGattProfile.RX}")
            if (!driver.enableNotifications()) throw IOException("GATT local notification registration rejected")
            operation(Operation.SUBSCRIBE) { driver.subscribe(profile.indication) }
            selection = profile
            observe("GATT_CONFIG txWrite=${if (profile.writeWithResponse) "with_response" else "without_response"} " +
                "rx=${if (profile.indication) "indication" else "notification"} frameChunkLimit=20 mtuRequest=none")
            diagnostic("GATT subscribed; ATT payload <=20 bytes; awaiting MDR identity")
        } catch (e: Exception) { close(); throw e }
    }

    private suspend fun operation(kind: Operation, timeout: Long = operationTimeoutMs, start: () -> Boolean) = operationMutex.withLock {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closed) throw terminal ?: IOException("GATT 已關閉。")
            pending = kind to completion
        }
        diagnostic("GATT stage=$kind")
        try {
            if (!start()) throw IOException("GATT $kind: Android 未接受操作。")
            try { withTimeout(timeout) { completion.await() } }
            catch (e: TimeoutCancellationException) { throw IOException("GATT $kind 回覆逾時；未確認控制結果。", e) }
        } catch (e: Exception) { close(); throw e }
        finally { synchronized(lock) { if (pending?.second === completion) pending = null } }
    }

    override suspend fun read(): ByteArray {
        checkOpen()
        return incoming.receive().also { checkOpen() }
    }
    private fun checkOpen() = synchronized(lock) { if (closed) throw terminal ?: IOException("GATT 已關閉。") }
    override suspend fun write(bytes: ByteArray) = frameMutex.withLock {
        val profile = selection ?: throw IOException("GATT 尚未完成服務與通知驗證。")
        for (chunk in SonyGattProfile.fragments(bytes)) operation(Operation.WRITE) { driver.write(chunk, profile.writeWithResponse) }
    }
    private fun completed(kind: Operation, status: Int) {
        val task = synchronized(lock) { if (!closed && pending?.first == kind) pending?.second else null } ?: return
        if (status == 0) task.complete(Unit) else fail(IOException("GATT $kind status=$status；控制結果未確認。"))
    }
    override fun connected(status: Int, connected: Boolean) {
        if (closed) return
        diagnostic("GATT connection status=$status connected=$connected")
        if (status != 0 || !connected) fail(IOException("GATT 連線結束 status=$status；原因未確認。"))
        else completed(Operation.CONNECT, status)
    }
    override fun disconnected(status: Int) {
        if (closed) return
        // Only recognized link-end statuses qualify; authentication, local termination,
        // service invalidation and unknown Android errors keep their fail-closed behavior.
        if (status == 0 || status == 8) {
            diagnostic("GATT disconnected status=$status")
            fail(TransportDisconnected("GATT 控制傳輸已中斷 status=$status。"))
        } else connected(status, false)
    }
    override fun services(status: Int, services: List<SonyGattProfile.Service>) {
        if (closed) return
        discovered = services
        diagnostic("GATT discovered serviceCount=${services.size}")
        services.take(32).forEach { service ->
            diagnostic("GATT service=${service.uuid}")
            if (service.uuid.equals(SonyGattProfile.SERVICE, true)) service.characteristics.take(24).forEach {
                diagnostic("GATT characteristic=${it.uuid} properties=0x${it.properties.toString(16)} cccd=${it.descriptors.any { d -> d.equals(SonyGattProfile.CCCD, true) }}")
            }
        }
        completed(Operation.DISCOVER, status)
    }
    override fun descriptorWritten(status: Int, service: String, characteristic: String, descriptor: String) {
        if (service.equals(SonyGattProfile.SERVICE, true) && characteristic.equals(SonyGattProfile.RX, true) && descriptor.equals(SonyGattProfile.CCCD, true))
            completed(Operation.SUBSCRIBE, status)
    }
    override fun characteristicWritten(status: Int, service: String, characteristic: String) {
        if (service.equals(SonyGattProfile.SERVICE, true) && characteristic.equals(SonyGattProfile.TX, true)) completed(Operation.WRITE, status)
    }
    /** An unsolicited platform callback is evidence only; it never completes a pending GATT operation. */
    override fun mtuChanged(mtu: Int, status: Int) {
        val stage = synchronized(lock) { if (closed) return; pending?.first?.name ?: "IDLE" }
        observe("GATT_MTU_CALLBACK mtu=$mtu status=$status pendingStage=$stage " +
            "source=callback_only frameChunkLimit=20")
    }
    private fun observe(text: String) { runCatching { diagnostic(text) } }
    override fun notification(service: String, characteristic: String, value: ByteArray) {
        if (closed || !service.equals(SonyGattProfile.SERVICE, true) || !characteristic.equals(SonyGattProfile.RX, true)) return
        if (value.isEmpty()) return
        if (value.size > 512 || incoming.trySend(value.copyOf()).isFailure) fail(IOException("GATT 回報緩衝超限，已停止連線。"))
    }
    private fun fail(error: IOException) {
        synchronized(lock) { if (closed || terminal != null) return; terminal = error }
        diagnostic(error.message ?: "GATT failure")
        close()
    }
    override fun close() {
        val task = synchronized(lock) {
            if (closed) return
            closed = true
            selection = null
            // A concurrent Session.close must wait for the old driver to finish releasing GATT.
            runCatching { driver.close() }.onFailure {
                // Do not retain a recoverable cause when release itself could not be confirmed.
                terminal = IOException("GATT 關閉未確認；停止恢復：${ConnectionDiagnostics.describe(it)}")
            }
            pending?.second.also { pending = null }
        }
        val reason = terminal ?: IOException("GATT 已關閉。")
        task?.completeExceptionally(reason)
        incoming.close(reason)
    }
}

