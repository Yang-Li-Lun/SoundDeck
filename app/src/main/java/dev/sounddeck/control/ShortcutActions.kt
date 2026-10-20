package dev.sounddeck.control

import dev.sounddeck.data.ControlAction
import dev.sounddeck.protocol.NoiseMode

/** Intent payloads are validated once before entering the shared controller queue. */
object ShortcutActions {
    fun resolve(action: String?, level: Int = -1, current: NoiseMode? = null): ControlAction? = when(action) {
        ControlService.ANC -> ControlAction.Noise(NoiseMode.ANC)
        ControlService.AMBIENT -> ControlAction.Noise(NoiseMode.AMBIENT)
        ControlService.OFF -> ControlAction.Noise(NoiseMode.OFF)
        ControlService.LEVEL_SET -> level.takeIf { it in 1..20 }?.let { ControlAction.AmbientLevel(it) }
        ControlService.LEVEL_DOWN -> ControlAction.AmbientStep(-1)
        ControlService.LEVEL_UP -> ControlAction.AmbientStep(1)
        ControlService.VOICE_FOCUS -> ControlAction.VoiceFocus()
        ControlService.EQ_NEXT -> ControlAction.NextEqualizer
        ControlService.EQ_PREVIOUS -> ControlAction.PreviousEqualizer
        ControlService.TOGGLE -> current?.let { ControlAction.Noise(if (it == NoiseMode.ANC) NoiseMode.AMBIENT else NoiseMode.ANC) }
        else -> null
    }
}
