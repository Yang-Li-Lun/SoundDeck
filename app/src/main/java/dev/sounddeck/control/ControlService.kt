package dev.sounddeck.control

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.sounddeck.MainActivity
import dev.sounddeck.R
import dev.sounddeck.data.*
import dev.sounddeck.protocol.ControlPreference
import dev.sounddeck.repository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val bluetoothOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) in listOf(android.bluetooth.BluetoothAdapter.STATE_OFF, android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF)) {
                repository.bluetoothUnavailable(); stopSelf()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        repository.serviceStarted(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL, "耳機連線狀態", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, bluetoothOff,
            IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!repository.hasPermission()) { repository.message("請開啟 SoundDeck 授予附近裝置權限。"); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(repository.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: RuntimeException) { repository.message("無法啟動背景控制：${e.javaClass.simpleName}"); stopSelf(); return START_NOT_STICKY }
        when(intent?.action) {
            CONNECT -> repository.connect(intent.getStringExtra("address") ?: repository.selectedAddress)
            AUTO_CONNECT -> repository.connect(intent.getStringExtra("address"),
                runCatching { ControlPreference.valueOf(intent.getStringExtra("transport")!!) }.getOrDefault(ControlPreference.AUTO), automatic = true)
            DISCONNECT -> { repository.disconnect(); stopSelf() }
            else -> ShortcutActions.resolve(intent?.action,intent?.getIntExtra("level",-1) ?: -1,repository.state.value.noise?.mode)?.let(repository::submit)
        }
        if (observer == null) observer = scope.launch {
            repository.state.map { it.copy(diagnostics = emptyList(), diagnosticsDropped = 0) }.distinctUntilChanged().collect { state ->
                SoundDeckWidget.updateAll(this@ControlService)
                NoiseTileService.refresh(this@ControlService)
                if (state.connection in listOf(ConnectionState.ERROR, ConnectionState.INTERRUPTED, ConnectionState.DISCONNECTED)) stopSelf()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        observer?.cancel(); scope.cancel()
        if (repository.state.value.connection in listOf(ConnectionState.READY, ConnectionState.SYNCING, ConnectionState.CONNECTING, ConnectionState.RECOVERING)) repository.disconnect(manual = false)
        unregisterReceiver(bluetoothOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        SoundDeckWidget.updateAll(this); NoiseTileService.refresh(this)
        repository.serviceStopped(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: HeadphoneState): Notification = StatusNotification.build(this, state)
    companion object {
        const val AUTO_CONNECT = "dev.sounddeck.AUTO_CONNECT"
        const val OFF = "dev.sounddeck.OFF"
        const val LEVEL_DOWN = "dev.sounddeck.LEVEL_DOWN"
        const val LEVEL_UP = "dev.sounddeck.LEVEL_UP"
        const val LEVEL_SET = "dev.sounddeck.LEVEL_SET"
        const val VOICE_FOCUS = "dev.sounddeck.VOICE_FOCUS"
        const val EQ_PREVIOUS = "dev.sounddeck.EQ_PREVIOUS"
        const val EQ_NEXT = "dev.sounddeck.EQ_NEXT"
        const val CONNECT = "dev.sounddeck.CONNECT"
        const val DISCONNECT = "dev.sounddeck.DISCONNECT"
        const val ANC = "dev.sounddeck.ANC"
        const val AMBIENT = "dev.sounddeck.AMBIENT"
        const val TOGGLE = "dev.sounddeck.TOGGLE"
        const val CHANNEL = "headphone-control"
        private const val NOTIFICATION = 100
        fun start(context: Context, action: String, address: String? = null, transport: String? = null) {
            if (!context.repository.hasPermission()) { context.repository.message("請先授予附近裝置權限。"); return }
            try { ContextCompat.startForegroundService(context, Intent(context, ControlService::class.java)
                .setAction(action).putExtra("address", address).putExtra("transport", transport)) }
            catch (e: RuntimeException) { context.repository.message("系統暫時不允許背景控制，請開啟 App 後重試。") }
        }
        fun pending(context: Context, action: String, level: Int = -1): PendingIntent = PendingIntent.getForegroundService(context,
            31 * action.hashCode() + level, Intent(context, ControlService::class.java).setAction(action).putExtra("level", level), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun batteryText(s: HeadphoneState) = "左耳 ${s.left?.percent?.let { "$it%" } ?: "—"}｜充電盒 ${s.case?.percent?.let { "$it%" } ?: "—"}｜右耳 ${s.right?.percent?.let { "$it%" } ?: "—"}"
    }
}
