package dev.sounddeck.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public profile queries only. Binding a proxy does not connect an audio device or change routing. */
internal class AndroidAudioEndpointDiagnostics(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val selected: BluetoothDevice,
    private val aliases: EndpointAliases,
    private val diagnostic: (String) -> Unit,
) {
    private fun emit(text: String) { runCatching { diagnostic("AUDIO_ENDPOINT $text") } }

    suspend fun capture() = withContext(Dispatchers.IO) {
        emit("phase=connection_snapshot sdk=${Build.VERSION.SDK_INT} aliasScope=${aliases.scope} " +
            "activeAudio=unknown codecSource=MDR_report snapshotAtomic=false")
        coroutineScope {
            launch { captureProfile(BluetoothProfile.A2DP, "A2DP") }
            if (Build.VERSION.SDK_INT >= 33) launch { captureProfile(BluetoothProfile.LE_AUDIO, "LE_AUDIO") }
            else emit("profile=LE_AUDIO status=api_below_33 relation=unknown")
        }
    }

    @SuppressLint("MissingPermission") // Existing BLUETOOTH_CONNECT grant; revocation is handled as unknown.
    private suspend fun captureProfile(profile: Int, name: String) {
        val started = System.nanoTime()
        val result = ReadOnlyProfileProbe<BluetoothProfile>(
            open = { connected, disconnected ->
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                        if (id == profile) connected(proxy)
                        else runCatching { adapter.closeProfileProxy(id, proxy) }
                    }
                    override fun onServiceDisconnected(id: Int) { if (id == profile) disconnected() }
                }, profile)
            },
            close = { adapter.closeProfileProxy(profile, it) },
            read = { proxy ->
                check(adapter.isEnabled)
                val snapshot = if (Build.VERSION.SDK_INT >= 33 && profile == BluetoothProfile.LE_AUDIO)
                    readLeAudio(proxy as BluetoothLeAudio)
                else AudioEndpointSnapshot(proxy.connectedDevices.map { AudioPeer(it.address) })
                check(adapter.isEnabled)
                snapshot
            },
        ).capture()
        currentCoroutineContext().ensureActive()
        emit("profile=$name status=${result.first} durationMs=${(System.nanoTime() - started) / 1_000_000}")
        val snapshot = result.second
        if (snapshot == null) emit("profile=$name relation=unknown")
        else AudioEndpointDiagnostics.lines(snapshot, selected.address, aliases).forEach { emit("profile=$name $it") }
    }

    @RequiresApi(33)
    @SuppressLint("MissingPermission")
    private fun readLeAudio(proxy: BluetoothLeAudio): AudioEndpointSnapshot {
        var groupRead = "observed"
        fun group(device: BluetoothDevice): Int? = try {
            proxy.getGroupId(device).takeIf { it != BluetoothLeAudio.GROUP_ID_INVALID && it >= 0 }
        } catch (_: SecurityException) { groupRead = "permission_denied"; null }
        catch (_: Exception) { groupRead = "read_failed"; null }
        val devices = proxy.connectedDevices
        val selectedGroup = group(selected)
        return AudioEndpointSnapshot(devices.map { AudioPeer(it.address, group(it)) }, selectedGroup, groupRead)
    }
}
