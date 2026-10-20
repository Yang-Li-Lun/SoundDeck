package dev.sounddeck.protocol

/** Deliberately limited to MDR v2 table 1, capability 0x6b for NC/ambient control. */
object SonyProtocol {
    const val SERVICE_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
    val protocol = bytes(0x00, 0)
    val functions = bytes(0x06, 0)
    val model = bytes(0x04, 1)
    val firmware = bytes(0x04, 2)
    val noise = bytes(0x66, 0x17)
    val eq = bytes(0x56, 0)
    val eqCapability = bytes(0x50, 0, 1) // English
    val eqStatus = bytes(0x52, 0)
    val dsee = bytes(0xe6, 1)
    val dseeStatus = bytes(0xe2, 1)
    val speak = bytes(0xf6, 0x0c)
    // MDR v2 Table 1 SMART_TALKING_MODE_TYPE2 extended parameters, separate from preview.
    val speakSettings = bytes(0xfa, 0x0c)

    fun setNoise(current: NoiseState, mode: NoiseMode = current.mode,
                 level: Int = current.level, voice: Boolean = current.voice): ByteArray {
        require(level in 0..20) // Zero may be reported outside Ambient mode; preserve it on unrelated changes.
        return bytes(0x68, 0x17, 1, if (mode == NoiseMode.OFF) 0 else 1,
            if (mode == NoiseMode.AMBIENT) 1 else if (mode == NoiseMode.OFF) current.wireMode else 0,
            if (voice) 1 else 0, level)
    }
    fun setDsee(enabled: Boolean) = bytes(0xe8, 1, if (enabled) 1 else 0)
    fun setSpeak(enabled: Boolean, preview: Int): ByteArray {
        require(preview in 0..1)
        return bytes(0xf8, 0x0c, if (enabled) 0 else 1, preview)
    }
    fun setSpeakSensitivity(current: SpeakSettings, sensitivity: SpeakSensitivity): ByteArray {
        require(current.modeOutTime in 0..3)
        return bytes(0xfc, 0x0c, sensitivity.code, current.modeOutTime)
    }
    fun setEq(preset: Int, bands: List<Int> = emptyList()): ByteArray {
        require(preset in 0..255 && (bands.isEmpty() || bands.size == 6))
        require(bands.all { it in 0..20 })
        return bytes(0x58, 0, preset, bands.size) + bands.map { it.toByte() }.toByteArray()
    }

    // Prefer the legacy variants when both are declared, matching the pinned upstream sync policy.
    fun batteryRequests(functions: Set<Int>): List<ByteArray> = buildList {
        if (0x21 in functions) add(bytes(0x22, 1)) else if (0x29 in functions) add(bytes(0x22, 9))
        if (0x22 in functions) add(bytes(0x22, 2)) else if (0x2a in functions) add(bytes(0x22, 10))
    }

    fun parse(p: ByteArray): SonyEvent? {
        if (p.size < 2) return null
        val b = p.map { it.u() }
        fun exact(n: Int) = b.size == n
        return when (b[0]) {
            0x01 -> if (exact(8) && b[1] == 0 && b[6] in 0..1 && b[7] in 0..1)
                SonyEvent.Protocol(b.subList(2, 6).fold(0L) { a, v -> (a shl 8) or v.toLong() }, b[6] == 0) else null
            0x07 -> if (b.size >= 3 && b[1] == 0 && b.size == 3 + b[2] * 2)
                SonyEvent.Functions((0 until b[2]).map { b[3 + it * 2] }.toSet()) else null
            0x05 -> if (b.size >= 3 && b[1] in 1..2 && b.size == 3 + b[2])
                SonyEvent.Identity(b[1], p.copyOfRange(3, p.size).toString(Charsets.UTF_8).filterNot { it.isISOControl() }) else null
            0x67, 0x69 -> if (exact(7) && b[1] == 0x17 && b[2] in 0..1 && b[3] in 0..1 &&
                b[4] in 0..1 && b[5] in 0..1 && b[6] in 0..20)
                SonyEvent.Noise(NoiseState(if (b[3] == 0) NoiseMode.OFF else if (b[4] == 0) NoiseMode.ANC else NoiseMode.AMBIENT,
                    b[6], b[5] == 1, b[4]), b[2] == 1) else null
            0x23, 0x25 -> when {
                b[1] == 1 && exact(6) -> SonyEvent.Battery(battery(b[2], b[3]), battery(b[4], b[5]), null, true)
                b[1] == 2 && exact(4) -> SonyEvent.Battery(null, null, battery(b[2], b[3]), false)
                b[1] == 9 && exact(8) -> SonyEvent.Battery(battery(b[2], b[3], b[6]), battery(b[4], b[5], b[7]), null, true)
                b[1] == 10 && exact(5) -> SonyEvent.Battery(null, null, battery(b[2], b[3], b[4]), false)
                else -> null
            }
            0xe7, 0xe9 -> if (b[1] == 1 && exact(3) && b[2] in 0..1) SonyEvent.Dsee(b[2] == 1) else null
            0xe3, 0xe5 -> if (b[1] == 1 && exact(3) && b[2] in 0..1) SonyEvent.DseeAvailable(b[2] == 0) else null
            0xf7, 0xf9 -> if (b[1] == 0x0c && exact(4) && b[2] in 0..1 && b[3] in 0..1)
                SonyEvent.Speak(b[2] == 0, b[3]) else null
            0xfb, 0xfd -> if (b[1] == 0x0c && exact(4) && b[2] in 0..2 && b[3] in 0..3)
                SonyEvent.SpeakConfiguration(SpeakSettings(SpeakSensitivity.entries.single { it.code == b[2] }, b[3])) else null
            0x57, 0x59 -> if (b[1] == 0 && b.size >= 4 && b[3] <= 16 && b.size == 4 + b[3])
                SonyEvent.Equalizer(b[2], b.drop(4)) else null
            0x53, 0x55 -> if (b[1] == 0 && exact(3) && b[2] in 0..1) SonyEvent.EqAvailable(b[2] == 0) else null
            0x51 -> parseEqCapability(b, p)
            0x13, 0x15 -> if (b[1] == 2 && exact(3)) SonyEvent.Codec(when(b[2]) {
                1 -> "SBC"; 2 -> "AAC"; 0x10 -> "LDAC"; 0x30 -> "LC3"; 0 -> "尚未回報"; else -> "其他 (${b[2]})"
            }) else null
            else -> null
        }
    }
    private fun battery(level: Int, charging: Int, threshold: Int? = null): BatteryReading? {
        val percent = level.takeIf { it in 0..100 }
        val status = charging.takeIf { it in 0..3 }
        // Threshold is separate metadata, never a substitute for an unknown percentage.
        if (percent == null && status == null) return null
        return BatteryReading(percent, when (status) { 1 -> true; 0, 3 -> false; else -> null },
            threshold?.takeIf { it in 0..100 }, status)
    }

    private fun parseEqCapability(b: List<Int>, raw: ByteArray): SonyEvent? {
        if (b.size < 5 || b[1] != 0 || b[2] !in 1..16 || b[3] !in 1..127) return null
        var cursor = 5
        val presets = mutableListOf<EqPreset>()
        repeat(b[4]) {
            if (cursor + 2 > b.size) return null
            val id = b[cursor++]; val length = b[cursor++]
            if (cursor + length > b.size) return null
            val name = raw.copyOfRange(cursor, cursor + length).toString(Charsets.UTF_8).filterNot { it.isISOControl() }
            presets += EqPreset(id, name); cursor += length
        }
        if (cursor != b.size) return null
        return SonyEvent.EqCapability(b[2], b[3], presets)
    }
}

enum class NoiseMode(val label: String) { ANC("降噪"), AMBIENT("環境聲音"), OFF("關閉") }
enum class SpeakSensitivity(val code: Int, val label: String) { AUTO(0, "自動"), HIGH(1, "高靈敏度"), LOW(2, "低靈敏度") }
/** modeOutTime is preserved from the device; SoundDeck does not expose a timeout control. */
data class SpeakSettings(val sensitivity: SpeakSensitivity, val modeOutTime: Int)
data class NoiseState(val mode: NoiseMode, val level: Int, val voice: Boolean, val wireMode: Int)
data class BatteryReading(val percent: Int?, val charging: Boolean?, val threshold: Int? = null,
                          val chargingStatus: Int? = if (charging == true) 1 else if (charging == false) 0 else null) {
    val statusLabel get() = when (chargingStatus) { 0 -> "未充電"; 1 -> "充電中"; 3 -> "已充滿"; else -> "充電狀態未知" }
}
data class EqPreset(val id: Int, val name: String) {
    val displayName get() = EqPresetNames.label(id, name)
}
sealed interface SonyEvent {
    data class Protocol(val version: Long, val table1: Boolean): SonyEvent
    data class Functions(val values: Set<Int>): SonyEvent
    data class Identity(val type: Int, val value: String): SonyEvent
    data class Noise(val value: NoiseState, val settled: Boolean): SonyEvent
    data class Battery(val left: BatteryReading?, val right: BatteryReading?, val case: BatteryReading?, val earbuds: Boolean): SonyEvent
    data class Dsee(val enabled: Boolean): SonyEvent
    data class DseeAvailable(val enabled: Boolean): SonyEvent
    data class Speak(val enabled: Boolean, val preview: Int): SonyEvent
    data class SpeakConfiguration(val value: SpeakSettings): SonyEvent
    data class Equalizer(val preset: Int, val bands: List<Int>): SonyEvent
    data class EqCapability(val bands: Int, val steps: Int, val presets: List<EqPreset>): SonyEvent
    data class EqAvailable(val enabled: Boolean): SonyEvent
    data class Codec(val value: String): SonyEvent
}
