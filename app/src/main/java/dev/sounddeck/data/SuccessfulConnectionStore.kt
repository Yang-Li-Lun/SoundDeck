package dev.sounddeck.data

import android.content.SharedPreferences
import dev.sounddeck.protocol.ControlTransport

data class SuccessfulEndpoint(val address: String, val transport: ControlTransport)

/** Only the repository's completed MDR initialization calls record(). Selection alone is not success. */
class SuccessfulConnectionStore(private val preferences: SharedPreferences) {
    fun read(): SuccessfulEndpoint? {
        val address = preferences.getString("successfulAddress", null) ?: return null
        val transport = runCatching { ControlTransport.valueOf(preferences.getString("successfulTransport", "")!!) }.getOrNull() ?: return null
        return SuccessfulEndpoint(address, transport)
    }
    fun record(address: String, transport: ControlTransport) {
        preferences.edit().putString("successfulAddress", address).putString("successfulTransport", transport.name).apply()
    }
}
