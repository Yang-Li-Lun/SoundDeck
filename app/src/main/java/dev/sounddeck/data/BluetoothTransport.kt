package dev.sounddeck.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.sounddeck.protocol.MdrTransport
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission") // Runtime permission is checked before service/transport creation.
class BluetoothTransport(device: BluetoothDevice, profile: DeviceProfile) : MdrTransport {
    private val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(profile.serviceUuid))
    override suspend fun connect() = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { close() }
        Thread({
            try {
                socket.connect()
                if (continuation.isActive) continuation.resume(Unit) else close()
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IOException(
                    "RFCOMM 控制連線未建立；不代表 LE Audio 音訊故障。", e))
            }
        }, "sounddeck-rfcomm-connect").start()
    }
    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val count = socket.inputStream.read(buffer)
        if (count <= 0) byteArrayOf() else buffer.copyOf(count)
    }
    override suspend fun write(bytes: ByteArray) = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { close() }
        Thread({
            try {
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                if (continuation.isActive) continuation.resume(Unit)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }, "sounddeck-rfcomm-write").start()
    }
    override fun close() { runCatching { socket.close() } }
}

