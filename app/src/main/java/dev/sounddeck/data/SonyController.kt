package dev.sounddeck.data

import dev.sounddeck.protocol.*

/** Capability-gated controller shared by the foreground session and all UI surfaces. */
class SonyController(private val state: () -> HeadphoneState, private val message: (String) -> Unit,
    private val onApplied: () -> Unit = { message("耳機回報設定相符。") },
    private val diagnostic: (String) -> Unit = {},
) {
    private fun supports(feature: Int) = state().functions?.contains(feature) == true
    // Only typed, protocol-bounded settings are included; never identity, names, addresses or raw payloads.
    private fun trace(wire: MdrSession, value: String) { runCatching { diagnostic(value) }; wire.recordDiagnostic(value) }
    private fun noiseDetails(value: NoiseState) = "mode=${value.mode},level=${value.level},voice=${value.voice},wireMode=${value.wireMode}"
    private fun noiseReadback(value: SonyEvent.Noise?) = value?.let { "${noiseDetails(it.value)},settled=${it.settled}" } ?: "missing"
    private fun readback(wire: MdrSession, control: String, details: String, matches: Boolean): Boolean {
        trace(wire, "CONTROL $control readback($details) matches=$matches")
        return matches
    }
    suspend fun refresh(wire: MdrSession) = wire.diagnosticOperation("Refresh") { refreshState(wire) }
    private suspend fun refreshState(wire: MdrSession) {
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

    private suspend fun readSettledNoise(wire: MdrSession): NoiseState {
        val response = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
        require(response?.settled == true && state().noiseSettled == true && state().noise == response.value) {
            "設定尚未確認；請等待耳機停止切換後重新整理。"
        }
        return response.value
    }
    private fun latestNoiseMatches(response: SonyEvent.Noise?) =
        response?.settled == true && state().noiseSettled == true && state().noise == response.value

    suspend fun execute(wire: MdrSession, action: ControlAction) =
        wire.diagnosticOperation(action.javaClass.simpleName) { executeControl(wire, action) }

    private suspend fun executeControl(wire: MdrSession, action: ControlAction) {
        require(state().ready) { "請先建立控制連線。" }
        if (action == ControlAction.Refresh) { refreshState(wire); return }
        // Resolve relative shortcuts against a fresh read, never the last Widget snapshot.
        if (action is ControlAction.AmbientStep || action is ControlAction.AmbientLevel) {
            require(state().ready && supports(0x6b) && state().noise != null) { "目前無法調整環境聲音。" }
            val noise = readSettledNoise(wire)
            require(noise.mode == NoiseMode.AMBIENT) { "請先切換至環境聲音。" }
            val level = if (action is ControlAction.AmbientLevel) action.level else (noise.level + (action as ControlAction.AmbientStep).delta).coerceIn(1, 20)
            executeControl(wire, ControlAction.Noise(NoiseMode.AMBIENT, level))
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
            executeControl(wire, ControlAction.Equalizer(presets[next].id))
            return
        }
        var unsettled = false
        val matches = when(action) {
            is ControlAction.VoiceFocus -> {
                require(supports(0x6b)) { "耳機未回報此環境聲音協定。" }
                val current = readSettledNoise(wire)
                require(current.mode == NoiseMode.AMBIENT) { "請先切換至環境聲音，並等待耳機確認。" }
                val voice = action.enabled ?: !current.voice
                val command = SonyProtocol.setNoise(current, voice = voice)
                trace(wire, "CONTROL VoiceFocus target(${noiseDetails(current.copy(voice = voice))})")
                wire.request(command)
                val response = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
                unsettled = response?.settled == false || state().noiseSettled == false
                readback(wire, "VoiceFocus", noiseReadback(response), latestNoiseMatches(response) && response!!.value == current.copy(voice = voice))
            }
            is ControlAction.Noise -> {
                require(supports(0x6b)) { "耳機未回報此環境聲音協定。" }
                val current = readSettledNoise(wire)
                val level = action.level ?: if (action.mode == NoiseMode.AMBIENT) current.level.coerceAtLeast(1) else current.level
                val voice = action.voice ?: current.voice
                val command = SonyProtocol.setNoise(current, action.mode, level, voice)
                trace(wire, "CONTROL Noise target(mode=${action.mode},level=$level,voice=$voice,wireMode=${command[4].u()})")
                wire.request(command)
                val response = SonyProtocol.parse(wire.request(SonyProtocol.noise, 0x67)!!) as? SonyEvent.Noise
                unsettled = response?.settled == false || state().noiseSettled == false
                readback(wire, "Noise", noiseReadback(response), latestNoiseMatches(response) && response!!.value.mode == action.mode &&
                    (action.level == null || response.value.level == level) &&
                    (action.voice == null || response.value.voice == voice))
            }
            is ControlAction.Dsee -> {
                require(supports(0xe2)) { "耳機未回報 DSEE 支援。" }
                wire.request(SonyProtocol.dseeStatus, 0xe3)
                require(state().dseeAvailable == true) { "目前音訊模式無法調整 DSEE。" }
                val command = SonyProtocol.setDsee(action.enabled)
                trace(wire, "CONTROL Dsee target(enabled=${action.enabled})")
                wire.request(command)
                val result = SonyProtocol.parse(wire.request(SonyProtocol.dsee, 0xe7)!!) as? SonyEvent.Dsee
                readback(wire, "Dsee", result?.let { "enabled=${it.enabled}" } ?: "missing", result?.enabled == action.enabled)
            }
            is ControlAction.Speak -> {
                require(supports(0xfc)) { "耳機未回報 Speak-to-Chat 支援。" }
                wire.request(SonyProtocol.speak, 0xf7)
                val preview = requireNotNull(state().speakPreview) { "尚未讀到 Speak-to-Chat 狀態。" }
                val command = SonyProtocol.setSpeak(action.enabled, preview)
                trace(wire, "CONTROL Speak target(enabled=${action.enabled},preview=$preview)")
                wire.request(command)
                val result = SonyProtocol.parse(wire.request(SonyProtocol.speak, 0xf7)!!) as? SonyEvent.Speak
                readback(wire, "Speak", result?.let { "enabled=${it.enabled},preview=${it.preview}" } ?: "missing", result?.enabled == action.enabled)
            }
            is ControlAction.SpeakDetection -> {
                require(supports(0xfc)) { "耳機未回報 Speak-to-Chat Type 2 支援。" }
                val before = SonyProtocol.parse(wire.request(SonyProtocol.speakSettings, 0xfb)!!) as? SonyEvent.SpeakConfiguration
                val current = requireNotNull(before?.value) { "尚未讀到偵測靈敏度；無法安全調整。" }
                val command = SonyProtocol.setSpeakSensitivity(current, action.sensitivity)
                trace(wire, "CONTROL SpeakDetection target(sensitivity=${action.sensitivity},modeOutTime=${current.modeOutTime})")
                wire.request(command)
                val response = SonyProtocol.parse(wire.request(SonyProtocol.speakSettings, 0xfb)!!) as? SonyEvent.SpeakConfiguration
                readback(wire, "SpeakDetection", response?.value?.let { "sensitivity=${it.sensitivity},modeOutTime=${it.modeOutTime}" } ?: "missing",
                    response?.value == current.copy(sensitivity = action.sensitivity))
            }
            is ControlAction.Equalizer -> {
                require(supports(0x50)) { "耳機未回報此 EQ 協定。" }
                wire.request(SonyProtocol.eqStatus, 0x53)
                val cap = requireNotNull(state().eqCapability) { "尚未讀到 EQ 能力。" }
                require(state().eqAvailable == true) { "目前音訊模式無法調整 EQ。" }
                require(cap.presets.any { it.id == action.preset }) { "耳機未回報此 EQ 預設。" }
                if (action.bands.isNotEmpty()) require(cap.bands == 6 && cap.steps == 21 &&
                    action.preset in listOf(0xa0, 0xa1, 0xa2)) { "自訂 EQ 只開放已確認的六段格式。" }
                val command = SonyProtocol.setEq(action.preset, action.bands)
                trace(wire, "CONTROL Equalizer target(preset=${action.preset},bands=${action.bands})")
                wire.request(command)
                val result = SonyProtocol.parse(wire.request(SonyProtocol.eq, 0x57)!!) as? SonyEvent.Equalizer
                readback(wire, "Equalizer", result?.let { "preset=${it.preset},bands=${it.bands}" } ?: "missing",
                    result?.preset == action.preset && (action.bands.isEmpty() || result.bands == action.bands))
            }
            ControlAction.Refresh, ControlAction.NextEqualizer, ControlAction.PreviousEqualizer, is ControlAction.AmbientStep, is ControlAction.AmbientLevel -> error("Handled above")
        }
        if (matches) onApplied()
        else if (unsettled) message("設定尚未確認；耳機仍在切換中，請重新整理。")
        else message("耳機回報與要求不同；畫面保留實際狀態，請重新整理。")
    }

}
