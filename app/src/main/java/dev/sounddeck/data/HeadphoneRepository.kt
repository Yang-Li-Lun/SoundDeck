package dev.sounddeck.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PairedHeadphone(val name: String, val address: String, val radio: DeviceRadio,
                           val cachedServices: Set<String>, val label: String) {
    val description get() = "$label · ${radio.name} · ${address.takeLast(5)}"
}

class HeadphoneRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("sounddeck", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(HeadphoneState())
    val state = mutable.asStateFlow()
    private val feedbackSequence = java.util.concurrent.atomic.AtomicLong()
    private val controller = SonyController({ state.value }, ::message) {
        val id = feedbackSequence.incrementAndGet()
        mutable.update { it.copy(message = null, appliedSettingId = id, appliedSettingAtMillis = android.os.SystemClock.elapsedRealtime()) }
    }
    private var job: Job? = null
    private var session: MdrSession? = null
    @Volatile private var connectionGeneration = 0L
    private var commands: Channel<ControlAction>? = null
    private val foregroundPolicy = ForegroundConnectionPolicy()
    private val successfulConnections = SuccessfulConnectionStore(preferences)
    private val controlServices = MutableStateFlow<Set<Any>>(emptySet())
    val selectedAddress get() = preferences.getString("address", null)
    val controlPreference get() = runCatching { ControlPreference.valueOf(preferences.getString("controlPreference", "AUTO")!!) }.getOrDefault(ControlPreference.AUTO)

    fun setControlPreference(value: ControlPreference) { preferences.edit().putString("controlPreference", value.name).apply() }
    fun hasPermission() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedHeadphone> {
        if (!hasPermission()) return emptyList()
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { DeviceProfiles.advertised(it.name) != null }.sortedBy { it.address }
                .mapIndexed { index, device -> PairedHeadphone(device.name ?: "WF-1000XM5", device.address,
                    when(device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> DeviceRadio.CLASSIC
                        BluetoothDevice.DEVICE_TYPE_LE -> DeviceRadio.LE
                        BluetoothDevice.DEVICE_TYPE_DUAL -> DeviceRadio.DUAL
                        else -> DeviceRadio.UNKNOWN
                    }, device.uuids?.map { it.uuid.toString().lowercase(Locale.ROOT) }?.toSet().orEmpty(), "記錄 ${index + 1}") }
        }.getOrElse { error -> message("讀取已配對裝置失敗：${error.javaClass.simpleName}"); emptyList() }
    }

    fun manualSelectionStarted() = foregroundPolicy.manualAction()
    fun foregroundStarted() = foregroundPolicy.enter()
    fun foregroundStopped() = foregroundPolicy.leave()
    fun serviceStarted(owner: Any) { controlServices.update { it + owner } }
    fun serviceStopped(owner: Any) { controlServices.update { it - owner } }
    fun bluetoothUnavailable() {
        foregroundPolicy.bluetoothOff()
        diagnostic("Bluetooth unavailable; release control session")
        if (job?.isCompleted == false) disconnect(manual = false)
    }
    fun bluetoothAvailable() {
        foregroundPolicy.bluetoothOn()
        diagnostic("Bluetooth ON; foreground reconnect eligibility check")
    }
    @SuppressLint("MissingPermission")
    suspend fun automaticTarget(): SuccessfulEndpoint? {
        // No overlapping socket/GATT/session and no service left from an earlier disconnect.
        if (job?.isActive == true) return null
        job?.join()
        if (withTimeoutOrNull(5_000) { controlServices.first { it.isEmpty() } } == null) return null
        val endpoint = successfulConnections.read()
        val address = endpoint?.address
        val transport = endpoint?.transport
        val eligible = hasPermission() && runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true &&
                address != null && transport != null && pairedDevices().any { it.address == address }
        }.getOrDefault(false)
        return if (foregroundPolicy.claim(eligible, job?.isCompleted == false)) endpoint else null
    }

    fun message(text: String?) { mutable.update { it.copy(message = text, appliedSettingId = null) } }
    fun dismissAppliedSetting(id: Long) {
        mutable.update { if (it.appliedSettingId == id) it.copy(appliedSettingId = null) else it }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String?, preferred: ControlPreference = controlPreference, automatic: Boolean = false) {
        if (automatic && !foregroundPolicy.allowsAutomaticStart) return
        if (!automatic) foregroundPolicy.manualConnection()
        if (job?.isCompleted == false) return
        if (!hasPermission()) { message("請先授予附近裝置權限。"); return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { message("請先在系統設定開啟藍牙。"); return }
        val records = pairedDevices()
        val chosen = records.firstOrNull { it.address == address }
        if (chosen == null) { message("請先在系統藍牙設定配對 WF-1000XM5，再選擇裝置。"); return }
        val profile = DeviceProfiles.advertised(chosen.name) ?: return
        preferences.edit().putString("address", chosen.address).apply()
        val generation = ++connectionGeneration
        mutable.value = HeadphoneState(connection = ConnectionState.CONNECTING, endpointLabel = chosen.label)
        records.forEach { diagnostic("Paired ${it.label} radio=${it.radio} cachedServices=${it.cachedServices.sorted().joinToString()}") }
        val routes = ControlRoute.plan(chosen.radio, chosen.cachedServices, preferred)
        diagnostic("Selected ${chosen.label}; preference=$preferred routes=${routes.joinToString()}; audio mode unchanged")
        val queue = Channel<ControlAction>(1)
        commands = queue
        job = scope.launch {
            try {
                ConnectionAttempts.run(routes, { route, error -> diagnostic("$route failed: $error") }) { route, established ->
                    mutable.update { HeadphoneState(connection = ConnectionState.CONNECTING, controlTransport = route,
                        endpointLabel = chosen.label, diagnostics = it.diagnostics) }
                    diagnostic("Trying ${chosen.label} / ${route.label}")
                    val device = adapter.getRemoteDevice(chosen.address)
                    val transport: MdrTransport = when(route) {
                        ControlTransport.BLE -> GattMdrTransport(BluetoothGattDriver(context, device), ::diagnostic)
                        ControlTransport.RFCOMM -> {
                            diagnostic("RFCOMM public SDP service=${profile.serviceUuid}")
                            BluetoothTransport(device, profile)
                        }
                    }
                    val wire = MdrSession(transport,
                        { event -> mutable.update { if (generation == connectionGeneration) it.receive(event, System.currentTimeMillis()) else it } },
                        { text -> if (generation == connectionGeneration) diagnostic(text) })
                    session = wire
                    wire.useSession {
                        mutable.update { it.copy(connection = ConnectionState.SYNCING) }
                        val protocolInfo = SonyProtocol.parse(request(SonyProtocol.protocol, 0x01)!!) as? SonyEvent.Protocol
                        if (protocolInfo?.table1 != true) throw IdentityRejected("耳機未回報支援的 MDR v2 Table 1。")
                        request(SonyProtocol.model, 0x05)
                        if (DeviceProfiles.find(state.value.model) != profile) throw IdentityRejected("耳機回報型號不符；已停止控制。")
                        request(SonyProtocol.firmware, 0x05)
                        request(SonyProtocol.functions, 0x07)
                        if (state.value.functions == null) throw IOException("無法驗證耳機功能清單。")
                        if (state.value.functions?.contains(0x50) == true) request(SonyProtocol.eqCapability, 0x51)
                        controller.refresh(this)
                        established()
                        // A failed selection must never replace the last verified control endpoint.
                        successfulConnections.record(chosen.address, route)
                        mutable.update { it.copy(connection = ConnectionState.READY, message = null) }
                        diagnostic("MDR identity/capabilities/state verified via ${route.label}; codec=${state.value.codec ?: "unknown"}")
                        while (currentCoroutineContext().isActive) {
                            val action = withTimeoutOrNull(60_000) { queue.receive() } ?: ControlAction.Refresh
                            try { controller.execute(this, action) }
                            catch (e: IllegalArgumentException) { message(e.message ?: "目前無法使用此功能。") }
                            finally { mutable.update { it.copy(pending = null) } }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { HeadphoneState(connection = ConnectionState.ERROR, endpointLabel = chosen.label,
                    message = "控制連線未確認。請查看診斷；同名記錄不一定是同一控制端點。\n${e.message.orEmpty()}", diagnostics = it.diagnostics) }
            } finally {
                session?.close(); session = null; queue.close(); commands = null
                mutable.update { if (it.connection == ConnectionState.ERROR) it else
                    HeadphoneState(message = "控制連線已中斷。", diagnostics = it.diagnostics) }
            }
        }
    }

    fun disconnect(manual: Boolean = true) {
        if (manual) foregroundPolicy.manualAction()
        connectionGeneration++
        session?.close()
        job?.cancel()
        mutable.update { HeadphoneState(message = "控制連線已中斷。", diagnostics = it.diagnostics) }
    }
    fun submit(action: ControlAction) {
        if (!state.value.ready) { message("請先連線耳機，讀取目前狀態後再操作。"); return }
        if (state.value.pending != null) return
        mutable.update { it.copy(pending = "等待耳機確認…", message = null, appliedSettingId = null) }
        if (commands?.trySend(action)?.isSuccess != true) mutable.update { it.copy(pending = null, message = "指令未送出；請重試。") }
    }
    private fun diagnostic(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        mutable.update { it.copy(diagnostics = (it.diagnostics + "$stamp $text").takeLast(120)) }
    }
}
