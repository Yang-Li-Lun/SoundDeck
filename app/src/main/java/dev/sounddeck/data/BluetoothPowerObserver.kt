package dev.sounddeck.data

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Public adapter broadcast only. Registered while the Activity is visible. */
class BluetoothPowerObserver(private val off: () -> Unit, private val on: () -> Unit) : BroadcastReceiver() {
    private var registered = false
    fun start(context: Context) {
        if (registered) return
        ContextCompat.registerReceiver(context, this, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }
    fun stop(context: Context) {
        if (registered) { context.unregisterReceiver(this); registered = false }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> off()
            BluetoothAdapter.STATE_ON -> on()
        }
    }
}
