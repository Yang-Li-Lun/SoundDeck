package dev.sounddeck.data

import dev.sounddeck.protocol.*

enum class ConnectionState(val label: String) {
    DISCONNECTED("尚未連線"), CONNECTING("正在連線"), SYNCING("讀取耳機狀態"), READY("控制已連線"),
    ERROR("連線失敗"), INTERRUPTED("控制連線中斷"), RECOVERING("等待恢復連線")
}

enum class MessageRecovery { REFRESH, RECONNECT }
data class HeadphoneState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val model: String? = null,
    val controlTransport: ControlTransport? = null,
    val endpointLabel: String? = null,
    val firmware: String? = null,
    val protocolVersion: Long? = null,
    val functions: Set<Int>? = null,
    val noise: NoiseState? = null,
    val noiseSettled: Boolean? = null,
    val left: BatteryReading? = null,
    val right: BatteryReading? = null,
    val case: BatteryReading? = null,
    val dsee: Boolean? = null,
    val dseeAvailable: Boolean? = null,
    val speak: Boolean? = null,
    val speakPreview: Int? = null,
    val speakSettings: SpeakSettings? = null,
    val eq: SonyEvent.Equalizer? = null,
    val eqCapability: SonyEvent.EqCapability? = null,
    val eqAvailable: Boolean? = null,
    val eqTarget: ControlAction.Equalizer? = null,
    val codec: String? = null,
    val pending: String? = null,
    val message: String? = null,
    val messageRecovery: MessageRecovery? = null,
    val appliedSettingId: Long? = null,
    val appliedSettingAtMillis: Long = 0,
    val updatedAt: Long? = null,
    val batteryUpdatedAt: Long? = null,
    val diagnostics: List<String> = emptyList(),
    val diagnosticsDropped: Long = 0,
) {
    val ready get() = connection == ConnectionState.READY
    val confirmedNoise get() = noise.takeIf { noiseSettled == true }
    val noiseStatusLabel get() = if (noiseSettled == false) "耳機仍在切換中" else confirmedNoise?.mode?.label ?: "環境聲音模式尚未確認"
    val canControlNoise get() = ready && pending == null && functions?.contains(0x6b) == true && confirmedNoise != null
    val canControlEq get() = ready && pending == null && functions?.contains(0x50) == true && eq != null && eqCapability != null && eqAvailable == true
    val customEqPreset get() = listOfNotNull(eqTarget?.preset, eq?.preset?.takeIf { it in 0xa0..0xa2 }, 0xa1, 0xa0, 0xa2)
        .firstOrNull { id -> eqCapability?.presets?.any { it.id == id } == true }
    val canAdjustEqBands get() = ready && (pending == null || eqTarget != null) && functions?.contains(0x50) == true &&
        eqAvailable == true && eqCapability?.bands == 6 && eqCapability.steps == 21 &&
        eq?.bands?.size == 6 && eq.bands.all { it in 0..20 } && customEqPreset != null
    val canControlDsee get() = ready && pending == null && functions?.contains(0xe2) == true && dsee != null && dseeAvailable == true
    val canControlSpeak get() = ready && pending == null && functions?.contains(0xfc) == true && speak != null && speakPreview != null
    val canControlSpeakSensitivity get() = ready && pending == null && functions?.contains(0xfc) == true && speakSettings != null
    fun withDiagnostic(text: String): HeadphoneState {
        val dropped = (diagnostics.size + 1 - MAX_DIAGNOSTICS).coerceAtLeast(0)
        return copy(diagnostics = (diagnostics + text.take(512)).takeLast(MAX_DIAGNOSTICS),
            diagnosticsDropped = diagnosticsDropped + dropped)
    }
    fun receive(event: SonyEvent, now: Long): HeadphoneState = when(event) {
        is SonyEvent.Protocol -> copy(protocolVersion = event.version)
        is SonyEvent.Functions -> copy(functions = event.values)
        is SonyEvent.Identity -> if (event.type == 1) copy(model = event.value) else copy(firmware = event.value)
        is SonyEvent.Noise -> copy(noise = event.value, noiseSettled = event.settled,
            appliedSettingId = if (event.settled) appliedSettingId else null)
        is SonyEvent.Battery -> if (event.earbuds) copy(left = event.left, right = event.right, batteryUpdatedAt = now) else copy(case = event.case, batteryUpdatedAt = now)
        is SonyEvent.Dsee -> copy(dsee = event.enabled)
        is SonyEvent.DseeAvailable -> copy(dseeAvailable = event.enabled)
        is SonyEvent.Speak -> copy(speak = event.enabled, speakPreview = event.preview)
        is SonyEvent.SpeakConfiguration -> copy(speakSettings = event.value)
        is SonyEvent.Equalizer -> copy(eq = event)
        is SonyEvent.EqCapability -> copy(eqCapability = event)
        is SonyEvent.EqAvailable -> copy(eqAvailable = event.enabled)
        is SonyEvent.Codec -> copy(codec = event.value)
    }.copy(updatedAt = now)

    companion object { const val MAX_DIAGNOSTICS = 1024 }
}

/** Add profiles only after checking their transport and wire variants. */
data class DeviceProfile(val model: String, val serviceUuid: String)
object DeviceProfiles {
    val supported = listOf(DeviceProfile("WF-1000XM5", SonyProtocol.SERVICE_UUID))
    fun find(name: String?) = supported.firstOrNull { it.model.equals(name, ignoreCase = true) }
    fun advertised(name: String?): DeviceProfile? = name?.trim()?.let {
        find(if (it.startsWith("LE_", true)) it.substring(3) else it)
    }
}

sealed interface ControlAction {
    data class Noise(val mode: NoiseMode, val level: Int? = null, val voice: Boolean? = null): ControlAction
    data class Dsee(val enabled: Boolean): ControlAction
    data class Speak(val enabled: Boolean): ControlAction
    data class SpeakDetection(val sensitivity: SpeakSensitivity): ControlAction
    /** null toggles against the fresh device response, never a cached Widget snapshot. */
    data class VoiceFocus(val enabled: Boolean? = null): ControlAction
    data class Equalizer(val preset: Int, val bands: List<Int> = emptyList()): ControlAction
    data class AmbientStep(val delta: Int): ControlAction { init { require(delta == -1 || delta == 1) } }
    data class AmbientLevel(val level: Int): ControlAction { init { require(level in 1..20) } }
    data object PreviousEqualizer: ControlAction
    data object NextEqualizer: ControlAction
    data object Refresh: ControlAction
}


