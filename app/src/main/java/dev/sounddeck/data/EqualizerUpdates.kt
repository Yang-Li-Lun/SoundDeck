package dev.sounddeck.data

import kotlinx.coroutines.delay

/** Main-thread queue: keep only the latest drag target, without cancelling a wire transaction. */
class EqualizerUpdates {
    private var latest: ControlAction.Equalizer? = null
    var active = false
        private set

    /** True only when the session must enqueue a new drain operation. */
    fun offer(target: ControlAction.Equalizer): Boolean {
        require(target.bands.size == 6 && target.bands.all { it in 0..20 })
        latest = target.copy(bands = target.bands.toList())
        if (active) return false
        active = true
        return true
    }

    suspend fun drain(apply: suspend (ControlAction.Equalizer) -> Boolean) {
        var confirmed: ControlAction.Equalizer? = null
        try {
            while (latest != null) {
                // Bounded sampling also flushes the final value after the user stops dragging.
                delay(150)
                val target = latest ?: break
                latest = null
                if (target != confirmed && !apply(target)) break
                confirmed = target
            }
        } finally { clear() }
    }

    fun clear() { latest = null; active = false }
}
