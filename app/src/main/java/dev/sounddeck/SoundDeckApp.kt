package dev.sounddeck

import android.app.Application
import android.content.Context
import dev.sounddeck.data.HeadphoneRepository

class SoundDeckApp : Application() {
    val repository by lazy { HeadphoneRepository(this) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Refresh resource-based appearance without opening a Bluetooth connection.
        dev.sounddeck.control.SoundDeckWidget.updateAll(this)
    }
}
val Context.repository: HeadphoneRepository get() = (applicationContext as SoundDeckApp).repository
