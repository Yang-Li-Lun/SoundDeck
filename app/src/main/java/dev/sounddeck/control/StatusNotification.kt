package dev.sounddeck.control

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.sounddeck.R
import dev.sounddeck.data.HeadphoneState

/** Status only. A pending command never replaces the last reported mode or batteries. */
object StatusNotification {
    fun build(context: Context, state: HeadphoneState): Notification {
        val mode = state.noise?.mode?.label ?: "環境聲音模式尚未回報"
        val battery = ControlService.batteryText(state)
        return NotificationCompat.Builder(context, ControlService.CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones).setContentTitle(mode)
            .setContentText(battery).setStyle(NotificationCompat.BigTextStyle().bigText(battery))
            .setShowWhen(false).setContentIntent(ControlService.openApp(context)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
