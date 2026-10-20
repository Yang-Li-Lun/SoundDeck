package dev.sounddeck.data

/** One foreground attempt, plus one attempt per observed Bluetooth OFF -> ON cycle. */
class ForegroundConnectionPolicy {
    private var foreground = false
    private var consumed = false
    private var manualSuppression = false
    private var sawPowerOff = false
    fun enter() { if (!foreground) { foreground = true; consumed = false; manualSuppression = false } }
    fun leave() { foreground = false; sawPowerOff = false }
    fun manualAction() { consumed = true; manualSuppression = true }
    fun manualConnection() { consumed = true; manualSuppression = false }
    fun bluetoothOff() { sawPowerOff = true }
    fun bluetoothOn() {
        if (sawPowerOff) {
            sawPowerOff = false
            if (foreground && !manualSuppression) consumed = false
        }
    }
    val allowsAutomaticStart get() = foreground && !manualSuppression
    fun claim(eligible: Boolean, busy: Boolean): Boolean {
        if (!allowsAutomaticStart || consumed || !eligible || busy) return false
        consumed = true
        return true
    }
}
