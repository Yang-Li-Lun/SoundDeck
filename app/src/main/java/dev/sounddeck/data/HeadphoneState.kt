package dev.sounddeck.data

import dev.sounddeck.protocol.*

enum class ConnectionState(val label: String) {
    DISCONNECTED("尚未連線"), CONNECTING("正在連線"), SYNCING("讀取耳機狀態"), READY("控制已連線"), ERROR("連線中斷")
}
data class HeadphoneState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val model: String? = null,
    val controlTransport: ControlTransport? = null,
    val endpointLabel: String? = null,
    val firmware: String? = null,
    val protocolVersion: Long? = null,
    val functions: Set<Int>? = null,
    val noise: NoiseState? = null,
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
    val codec: String? = null,
    val pending: String? = null,
    val message: String? = null,
    val appliedSettingId: Long? = null,
    val appliedSettingAtMillis: Long = 0,
    val updatedAt: Long? = null,
    val batteryUpdatedAt: Long? = null,
    val diagnostics: List<String> = emptyList(),
) {
    val ready get() = connection == ConnectionState.READY
    val canControlNoise get() = ready && pending == null && functions?.contains(0x6b) == true && noise != null
    val canControlEq get() = ready && pending == null && functions?.contains(0x50) == true && eq != null && eqCapability != null && eqAvailable == true
    val canControlDsee get() = ready && pending == null && functions?.contains(0xe2) == true && dsee != null && dseeAvailable == true
    val canControlSpeak get() = ready && pending == null && functions?.contains(0xfc) == true && speak != null && speakPreview != null
    val canControlSpeakSensitivity get() = ready && pending == null && functions?.contains(0xfc) == true && speakSettings != null
    fun receive(event: SonyEvent, now: Long): HeadphoneState = when(event) {
        is SonyEvent.Protocol -> copy(protocolVersion = event.version)
        is SonyEvent.Functions -> copy(functions = event.values)
        is SonyEvent.Identity -> if (event.type == 1) copy(model = event.value) else copy(firmware = event.value)
        is SonyEvent.Noise -> copy(noise = event.value)
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


