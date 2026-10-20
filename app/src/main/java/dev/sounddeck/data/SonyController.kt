package dev.sounddeck.data

import dev.sounddeck.protocol.*

/** Capability-gated controller shared by the foreground session and all UI surfaces. */
class SonyController(private val state: () -> HeadphoneState, private val message: (String) -> Unit,
    private val onApplied: () -> Unit = { message("耳機已回報更新後的設定。") },
) {
    private fun supports(feature: Int) = state().functions?.contains(feature) == true
    suspend fun refresh(wire: MdrSession) {
        if (supports(0x6b)) wire.request(SonyProtocol.noise, 0x67)
        SonyProtocol.batteryRequests(state().functions.orEmpty()).forEach { wire.request(it, 0x23) }
        if (supports(0x50)) { wire.request(SonyProtocol.eqStatus, 0x53); wire.request(SonyProtocol.eq, 0x57) }
        if (supports(0xe2)) { wire.request(SonyProtocol.dseeStatus, 0xe3); wire.request(SonyProtocol.dsee, 0xe7) }
        if (supports(0xfc)) {
            wire.request(SonyProtocol.speak, 0xf7)
            wire.request(SonyProtocol.speakSettings, 0xfb)
        }
        if (supports(0x12)) wire.request(bytes(0x12, 2), 0x13)
    }

    suspend fun execute(wire: MdrSession, action: ControlAction) {
        require(state().ready) { "請先建立控制連線。" }
        if (action == ControlAction.Refresh) { refresh(wire); return }
        // Resolve relative shortcuts against a fresh read, never the last Widget snapshot.
        if (action is ControlAction.AmbientStep || action is ControlAction.AmbientLevel) {
            require(state().ready && supports(0x6b) && state().noise != null) { "目前無法調整環境聲音。" }
            wire.request(SonyProtocol.noise, 0x67)
            val noise = requireNotNull(state().noise)
            require(noise.mode == NoiseMode.AMBIENT) { "請先切換至環境聲音。" }
            val level = if (action is ControlAction.AmbientLevel) action.level else (noise.level + (action as ControlAction.AmbientStep).delta).coerceIn(1, 20)
            execute(wire, ControlAction.Noise(NoiseMode.AMBIENT, level))
            return
        }
        if (action == ControlAction.NextEqualizer || action == ControlAction.PreviousEqualizer) {
            require(state().ready && supports(0x50) && state().eq != null && state().eqCapability != null && state().eqAvailable == true) { "目前無法切換 EQ。" }
            wire.request(SonyProtocol.eq, 0x57)
            val presets = requireNotNull(state().eqCapability).presets
            require(presets.isNotEmpty()) { "耳機尚未提供 EQ 預設。" }
            val index = presets.indexOfFirst { it.id == state().eq?.preset }
            val next = if (index < 0) {
                if (action == ControlAction.NextEqualizer) 0 else presets.lastIndex
            } else Math.floorMod(index + if (action == ControlAction.NextEqualizer) 1 else -1, presets.size)
            execute(wire, ControlAction.Equalizer(presets[next].id))
            return
        }
        val matches = when(action) {
            is ControlAction.VoiceFocus -> {
                require(supports(0x6b)) { "耳機未回報此環境聲音協定。" }
                val before = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
                require(before?.settled == true && before.value.mode == NoiseMode.AMBIENT) { "請先切換至環境聲音，並等待耳機確認。" }
                val current = before.value
                val voice = action.enabled ?: !current.voice
                wire.request(SonyProtocol.setNoise(current, voice = voice))
                val response = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
                response?.settled == true && response.value == current.copy(voice = voice)
            }
            is ControlAction.Noise -> {
                require(supports(0x6b)) { "耳機未回報此環境聲音協定。" }
                wire.request(SonyProtocol.noise, 0x67) // Preserve the latest voice/level on every surface.
                val current = requireNotNull(state().noise) { "尚未讀到環境聲音狀態。" }
                val level = action.level ?: if (action.mode == NoiseMode.AMBIENT) current.level.coerceAtLeast(1) else current.level
                val voice = action.voice ?: current.voice
                wire.request(SonyProtocol.setNoise(current, action.mode, level, voice))
                val response = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
                response?.settled == true && response.value.mode == action.mode &&
                    (action.level == null || response.value.level == level) &&
                    (action.voice == null || response.value.voice == voice)
            }
            is ControlAction.Dsee -> {
                require(supports(0xe2)) { "耳機未回報 DSEE 支援。" }
                wire.request(SonyProtocol.dseeStatus, 0xe3)
                require(state().dseeAvailable == true) { "目前音訊模式無法調整 DSEE。" }
                wire.request(SonyProtocol.setDsee(action.enabled))
                val result = SonyProtocol.parse(wire.request(SonyProtocol.dsee, 0xe7)!!) as? SonyEvent.Dsee
                result?.enabled == action.enabled
            }
            is ControlAction.Speak -> {
                require(supports(0xfc)) { "耳機未回報 Speak-to-Chat 支援。" }
                wire.request(SonyProtocol.speak, 0xf7)
                val preview = requireNotNull(state().speakPreview) { "尚未讀到 Speak-to-Chat 狀態。" }
                wire.request(SonyProtocol.setSpeak(action.enabled, preview))
                val result = SonyProtocol.parse(wire.request(SonyProtocol.speak, 0xf7)!!) as? SonyEvent.Speak
                result?.enabled == action.enabled
            }
            is ControlAction.SpeakDetection -> {
                require(supports(0xfc)) { "耳機未回報 Speak-to-Chat Type 2 支援。" }
                val before = SonyProtocol.parse(wire.request(SonyProtocol.speakSettings, 0xfb)!!) as? SonyEvent.SpeakConfiguration
                val current = requireNotNull(before?.value) { "尚未讀到偵測靈敏度；無法安全調整。" }
                wire.request(SonyProtocol.setSpeakSensitivity(current, action.sensitivity))
                val response = SonyProtocol.parse(wire.request(SonyProtocol.speakSettings, 0xfb)!!) as? SonyEvent.SpeakConfiguration
                response?.value == current.copy(sensitivity = action.sensitivity)
            }
            is ControlAction.Equalizer -> {
                require(supports(0x50)) { "耳機未回報此 EQ 協定。" }
                wire.request(SonyProtocol.eqStatus, 0x53)
                val cap = requireNotNull(state().eqCapability) { "尚未讀到 EQ 能力。" }
                require(state().eqAvailable == true) { "目前音訊模式無法調整 EQ。" }
                require(cap.presets.any { it.id == action.preset }) { "耳機未回報此 EQ 預設。" }
                if (action.bands.isNotEmpty()) require(cap.bands == 6 && cap.steps == 21 &&
                    action.preset in listOf(0xa0, 0xa1, 0xa2)) { "自訂 EQ 只開放已確認的六段格式。" }
                wire.request(SonyProtocol.setEq(action.preset, action.bands))
                val result = SonyProtocol.parse(wire.request(SonyProtocol.eq, 0x57)!!) as? SonyEvent.Equalizer
                result?.preset == action.preset && (action.bands.isEmpty() || result.bands == action.bands)
            }
            ControlAction.Refresh, ControlAction.NextEqualizer, ControlAction.PreviousEqualizer, is ControlAction.AmbientStep, is ControlAction.AmbientLevel -> error("Handled above")
        }
        if (matches) onApplied() else message("耳機回報與要求不同；畫面保留實際狀態，請重新整理。")
    }

}
