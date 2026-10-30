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
                           val cachedServices: Set<String>, val label: String,
                           val successfulTransport: ControlTransport? = null) {
    val description get() = "$label · ${radio.name} · ${address.takeLast(5)}"
}

class HeadphoneRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("sounddeck", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(HeadphoneState())
    val state = mutable.asStateFlow()
    private val feedbackSequence = java.util.concurrent.atomic.AtomicLong()
    private val controller = SonyController({ state.value }, { message(it, MessageRecovery.REFRESH) }, onApplied = {
        val id = feedbackSequence.incrementAndGet()
        mutable.update { it.copy(message = null, messageRecovery = null, appliedSettingId = id, appliedSettingAtMillis = android.os.SystemClock.elapsedRealtime()) }
    })
    private var job: Job? = null
    private var session: MdrSession? = null
    private var recovering = false
    @Volatile private var connectionGeneration = 0L
    private var commands: Channel<ControlAction>? = null
    private val equalizerUpdates = EqualizerUpdates()
    private val foregroundPolicy = ForegroundConnectionPolicy()
    private val successfulConnections = SuccessfulConnectionStore(preferences)
    private val endpointAliases = EndpointAliases()
    private val controlServices = MutableStateFlow<Set<Any>>(emptySet())
    val selectedAddress get() = preferences.getString("address", null)
    val controlPreference get() = runCatching { ControlPreference.valueOf(preferences.getString("controlPreference", "AUTO")!!) }.getOrDefault(ControlPreference.AUTO)

    fun setControlPreference(value: ControlPreference) { preferences.edit().putString("controlPreference", value.name).apply() }
    fun hasPermission() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedHeadphone> {
        if (!hasPermission()) return emptyList()
        val successful = successfulConnections.read()
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { DeviceProfiles.advertised(it.name) != null }.sortedBy { it.address }
                .mapIndexed { index, device -> PairedHeadphone(device.name ?: "WF-1000XM5", device.address,
                    when(device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> DeviceRadio.CLASSIC
                        BluetoothDevice.DEVICE_TYPE_LE -> DeviceRadio.LE
                        BluetoothDevice.DEVICE_TYPE_DUAL -> DeviceRadio.DUAL
                        else -> DeviceRadio.UNKNOWN
                    }, device.uuids?.map { it.uuid.toString().lowercase(Locale.ROOT) }?.toSet().orEmpty(), "記錄 ${index + 1}",
                    successful?.takeIf { it.address == device.address }?.transport) }
        }.getOrElse { error -> message("讀取已配對裝置失敗：${error.javaClass.simpleName}"); emptyList() }
    }

    fun manualSelectionStarted() {
        foregroundPolicy.manualAction()
        if (recovering) disconnect(manual = false)
    }
    fun foregroundStarted() = foregroundPolicy.enter()
    fun foregroundStopped() {
        foregroundPolicy.leave()
        if (recovering) disconnect(manual = false)
    }
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

    fun message(text: String?, recovery: MessageRecovery? = null) {
        mutable.update { it.copy(message = text, messageRecovery = if (text == null) null else recovery, appliedSettingId = null) }
    }
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
        val lastVerified = successfulConnections.read()
        val endpointContext = "aliasScope=${endpointAliases.scope} selected=${endpointAliases.id(chosen.address)} " +
            "record=${chosen.label} radio=${chosen.radio} preference=$preferred automatic=$automatic " +
            "lastVerified=${lastVerified?.let { endpointAliases.id(it.address) } ?: "none"} " +
            "sameAsLastVerified=${lastVerified?.let { it.address == chosen.address } ?: "unknown"} " +
            "lastVerifiedTransport=${lastVerified?.transport ?: "unknown"}"
        var verified = false
        var unconfirmedSetting = false
        job = scope.launch {
            try {
                SessionRecovery(allowed = {
                    foregroundPolicy.allowsAutomaticStart && !unconfirmedSetting && hasPermission() &&
                        generation == connectionGeneration && runCatching { adapter.isEnabled }.getOrDefault(false)
                }, diagnostic = ::diagnostic).run(waiting = { attempt, delayMs ->
                    recovering = true
                    mutable.update { HeadphoneState(connection = ConnectionState.RECOVERING, endpointLabel = chosen.label,
                        controlTransport = it.controlTransport, diagnostics = it.diagnostics, diagnosticsDropped = it.diagnosticsDropped,
                        message = "控制連線中斷；${delayMs / 1000} 秒後嘗試恢復（$attempt/2）。可取消恢復連線。") }
                }) { verifiedRoute, recovered ->
                    val queue = Channel<ControlAction>(1)
                    commands = queue
                    try {
                        ConnectionAttempts.run(verifiedRoute?.let { listOf(it) } ?: routes,
                            { route, error -> diagnostic("$route failed: $error") }) { route, established ->
                            mutable.update { HeadphoneState(connection = ConnectionState.CONNECTING, controlTransport = route,
                                endpointLabel = chosen.label, diagnostics = it.diagnostics, diagnosticsDropped = it.diagnosticsDropped) }
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
                            wire.recordDiagnostic("CONTROL_ENDPOINT $endpointContext route=$route")
                            // A separate diagnostic job never delays an MDR request or changes endpoint selection.
                            val audioSnapshot = scope.launch(Dispatchers.IO) {
                                try {
                                    AndroidAudioEndpointDiagnostics(context, adapter, device, endpointAliases,
                                        wire::recordDiagnostic).capture()
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { wire.recordDiagnostic("AUDIO_ENDPOINT status=read_failed relation=unknown") }
                            }
                            try { wire.useSession {
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
                                verified = true
                                recovered(route)
                                recovering = false
                                mutable.update { it.copy(connection = ConnectionState.READY, message = null) }
                                diagnostic("MDR identity/capabilities/state verified via ${route.label}; codec=${state.value.codec ?: "unknown"}")
                                recordDiagnostic("CONTROL_ENDPOINT verified=true route=$route selected=${endpointAliases.id(chosen.address)} " +
                                    "codecReport=${state.value.codec ?: "unknown"} audioRelation=see_connection_snapshot acousticEffect=unknown")
                                while (currentCoroutineContext().isActive) {
                                    val action = withTimeoutOrNull(60_000) { queue.receive() } ?: ControlAction.Refresh
                                    unconfirmedSetting = action != ControlAction.Refresh
                                    try {
                                        if (action is ControlAction.Equalizer && action.bands.isNotEmpty()) {
                                            equalizerUpdates.drain { target ->
                                                controller.execute(this, target)
                                                state.value.eq?.let { it.preset == target.preset && it.bands == target.bands } == true &&
                                                    state.value.message == null
                                            }
                                        } else controller.execute(this, action)
                                        unconfirmedSetting = false
                                    }
                                    catch (e: IllegalArgumentException) {
                                        unconfirmedSetting = false
                                        diagnostic("Control rejected: ${ConnectionDiagnostics.describe(e)}")
                                        message(e.message ?: "目前無法使用此功能。", MessageRecovery.REFRESH)
                                    }
                                    finally {
                                        equalizerUpdates.clear()
                                        mutable.update { it.copy(pending = null, eqTarget = null) }
                                    }
                                }
                            } } finally { audioSnapshot.cancel() }
                        }
                    } finally {
                        // useSession has closed the transport and joined its reader before another attempt.
                        session?.close(); session = null
                        queue.cancel(); commands = null
                        equalizerUpdates.clear()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                diagnostic("Session ended verified=$verified settingUnconfirmed=$unconfirmedSetting: ${ConnectionDiagnostics.describe(e)}")
                mutable.update { HeadphoneState(connection = if (verified) ConnectionState.INTERRUPTED else ConnectionState.ERROR,
                    endpointLabel = chosen.label, controlTransport = it.controlTransport,
                    message = if (unconfirmedSetting) "設定尚未確認；控制連線已中斷。請重新連線並讀取耳機狀態，設定不會自動重送。"
                        else if (verified) "控制連線已中斷。請重新連線；詳細原因請查看診斷。"
                        else "連線失敗，控制連線尚未確認。請重新選擇配對記錄與通道；詳細原因請查看診斷。",
                    messageRecovery = MessageRecovery.RECONNECT, diagnostics = it.diagnostics, diagnosticsDropped = it.diagnosticsDropped) }
            } finally {
                recovering = false
                mutable.update { if (it.connection in listOf(ConnectionState.ERROR, ConnectionState.INTERRUPTED)) it else
                    HeadphoneState(message = "控制連線已中斷。", diagnostics = it.diagnostics, diagnosticsDropped = it.diagnosticsDropped) }
            }
        }
    }

    fun disconnect(manual: Boolean = true) {
        if (manual) foregroundPolicy.manualAction()
        if (recovering) diagnostic("Recovery cancelled: ${if (manual) "manual disconnect" else "lifecycle/power/selection"}")
        connectionGeneration++
        session?.close()
        job?.cancel()
        equalizerUpdates.clear()
        mutable.update { HeadphoneState(message = "控制連線已中斷。", diagnostics = it.diagnostics, diagnosticsDropped = it.diagnosticsDropped) }
    }
    fun submit(action: ControlAction) {
        if (!state.value.ready) { message("請先連線耳機，讀取目前狀態後再操作。", MessageRecovery.RECONNECT); return }
        if (action is ControlAction.Equalizer && action.bands.isNotEmpty()) {
            if (!state.value.canAdjustEqBands || action.preset != state.value.customEqPreset ||
                action.bands.size != 6 || action.bands.any { it !in 0..20 }) return
            val start = equalizerUpdates.offer(action)
            mutable.update { it.copy(pending = "等待耳機確認…", eqTarget = action, message = null,
                messageRecovery = null, appliedSettingId = null) }
            if (!start) return
            if (commands?.trySend(action)?.isSuccess != true) {
                equalizerUpdates.clear()
                mutable.update { it.copy(pending = null, eqTarget = null, message = "指令未送出；請重試。") }
            }
            return
        }
        if (state.value.pending != null) return
        mutable.update { it.copy(pending = "等待耳機確認…", message = null, messageRecovery = null, appliedSettingId = null) }
        if (commands?.trySend(action)?.isSuccess != true) mutable.update { it.copy(pending = null, message = "指令未送出；請重試。") }
    }
    private fun diagnostic(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        mutable.update { it.withDiagnostic("$stamp ${ConnectionDiagnostics.redact(text)}") }
    }
}
