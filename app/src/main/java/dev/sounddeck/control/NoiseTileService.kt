package dev.sounddeck.control

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.sounddeck.MainActivity
import dev.sounddeck.protocol.NoiseMode
import dev.sounddeck.repository

class NoiseTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val state = repository.state.value
        qsTile?.apply {
            label = "SoundDeck 降噪"
            this.state = when {
                state.pending != null -> Tile.STATE_UNAVAILABLE
                state.ready && state.noise?.mode == NoiseMode.ANC -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= 29) subtitle = state.pending ?: state.noise?.mode?.label ?: "點選連線"
            if (Build.VERSION.SDK_INT >= 30) stateDescription = state.noise?.mode?.label ?: "尚未連線"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { handleClick() } else handleClick()
    }
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // Intent API only runs on API 26–33; API 34+ uses PendingIntent.
    private fun handleClick() {
        if (repository.state.value.canControlNoise) ControlService.start(this, ControlService.TOGGLE)
        else if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(ControlService.openApp(this))
        else startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    companion object {
        fun refresh(context: Context) = requestListeningState(context, ComponentName(context, NoiseTileService::class.java))
    }
}
