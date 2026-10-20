package dev.sounddeck.protocol

import java.io.IOException
import java.util.Locale

/** UUID facts and framing references are pinned in docs/LE_AUDIO.md. No prefix/first-writable matching. */
object SonyGattProfile {
    const val SERVICE = "5b833e20-6bc7-4802-8e9a-723ceca4bd8f"
    const val TX = "5b833c60-6bc7-4802-8e9a-723ceca4bd8f"
    const val RX = "5b833c61-6bc7-4802-8e9a-723ceca4bd8f"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val WRITE = 0x08
    const val WRITE_NO_RESPONSE = 0x04
    const val NOTIFY = 0x10
    const val INDICATE = 0x20

    data class Characteristic(val uuid: String, val properties: Int, val descriptors: List<String> = emptyList())
    data class Service(val uuid: String, val characteristics: List<Characteristic>)
    data class Selection(val writeWithResponse: Boolean, val indication: Boolean)

    fun select(services: List<Service>): Selection {
        val service = services.filter { it.uuid.equals(SERVICE, true) }.singleOrNull()
            ?: throw IOException("未找到唯一的 Sony MDR BLE 控制服務；此記錄的控制角色尚未確認。")
        val tx = service.characteristics.filter { it.uuid.equals(TX, true) }.singleOrNull()
            ?: throw IOException("Sony BLE 發送特徵不符；未寫入任何控制封包。")
        val rx = service.characteristics.filter { it.uuid.equals(RX, true) }.singleOrNull()
            ?: throw IOException("Sony BLE 回報特徵不符；未寫入任何控制封包。")
        if (tx.properties and (WRITE or WRITE_NO_RESPONSE) == 0 || rx.properties and (NOTIFY or INDICATE) == 0)
            throw IOException("Sony BLE 特徵屬性不符；未寫入任何控制封包。")
        if (rx.descriptors.count { it.equals(CCCD, true) } != 1)
            throw IOException("Sony BLE 回報特徵缺少唯一的 CCCD。")
        return Selection(tx.properties and WRITE != 0, rx.properties and NOTIFY == 0)
    }

    /** 20 bytes is safe at the minimum ATT MTU (23), including when another client negotiates a larger MTU. */
    fun fragments(frame: ByteArray): List<ByteArray> {
        require(frame.isNotEmpty() && frame.size <= (MdrCodec.MAX_PAYLOAD + 7) * 2 + 2)
        return frame.asList().chunked(20).map { it.toByteArray() }
    }
}

/** Only public device metadata is used as a hint. Neither transport proves the audio codec or earbud side. */
enum class DeviceRadio { UNKNOWN, CLASSIC, LE, DUAL }
enum class ControlTransport(val label: String) { BLE("BLE GATT"), RFCOMM("RFCOMM") }
enum class ControlPreference(val label: String) { AUTO("自動"), BLE("BLE"), RFCOMM("RFCOMM") }
object ControlRoute {
    fun plan(radio: DeviceRadio, cachedServices: Set<String>, preference: ControlPreference): List<ControlTransport> {
        if (preference == ControlPreference.BLE) return listOf(ControlTransport.BLE)
        if (preference == ControlPreference.RFCOMM) return listOf(ControlTransport.RFCOMM)
        val services = cachedServices.map { it.lowercase(Locale.ROOT) }.toSet()
        return when {
            radio == DeviceRadio.LE -> listOf(ControlTransport.BLE)
            radio == DeviceRadio.CLASSIC -> listOf(ControlTransport.RFCOMM)
            SonyGattProfile.SERVICE in services -> listOf(ControlTransport.BLE, ControlTransport.RFCOMM)
            SonyProtocol.SERVICE_UUID in services -> listOf(ControlTransport.RFCOMM, ControlTransport.BLE)
            else -> listOf(ControlTransport.BLE, ControlTransport.RFCOMM)
        }
    }
}
