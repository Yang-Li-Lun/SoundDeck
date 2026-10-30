@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.sounddeck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.sounddeck.control.*
import dev.sounddeck.data.*
import dev.sounddeck.protocol.ControlPreference
import dev.sounddeck.ui.SoundDeckScreen
import dev.sounddeck.ui.SoundDeckTheme

class MainActivity : ComponentActivity() {
    private var automaticJob: Job? = null
    private val powerObserver = BluetoothPowerObserver(
        off = { automaticJob?.cancel(); repository.bluetoothUnavailable() },
        on = { repository.bluetoothAvailable(); requestAutomaticConnection() },
    )
    private fun requestAutomaticConnection() {
        if (automaticJob?.isActive == true) return
        automaticJob = lifecycleScope.launch {
            repository.automaticTarget()?.let { ControlService.start(this@MainActivity, ControlService.AUTO_CONNECT, it.address, it.transport.name) }
        }
    }
    override fun onStart() { super.onStart(); powerObserver.start(this) }
    private var showDevices by mutableStateOf(false)
    private var showLicenses by mutableStateOf(false)
    private var resumeGeneration by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (repository.hasPermission()) showDevices = true else repository.message("附近裝置權限尚未允許。可到設定重新授權。")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeGeneration++ }
    private val saveDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(diagnosticText()) }
                ?: error("無法開啟檔案")
        }.onSuccess { repository.message("診斷摘要已儲存。") }.onFailure { repository.message("無法儲存診斷摘要。") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            SoundDeckTheme {
                val windowSize = LocalWindowInfo.current.containerSize
                val shortDialog = with(LocalDensity.current) { windowSize.height.toDp() } < 480.dp
                SoundDeckScreen(state = state,
                    permissionGranted = remember(resumeGeneration, showDevices) { repository.hasPermission() },
                    onConnect = ::requestConnect,
                    onDisconnect = { ControlService.start(this, ControlService.DISCONNECT) },
                    onAction = repository::submit,
                    onBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    },
                    onExport = { saveDiagnostics.launch("SoundDeck-diagnostics.txt") },
                    onDismissMessage = { repository.message(null) },
                        onDismissApplied = repository::dismissAppliedSetting, onLicenses = { showLicenses = true })
                if (showLicenses) {
                    val notices = remember { assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }
                    AlertDialog(onDismissRequest = { showLicenses = false }, title = if (shortDialog) null else { { Text("開源授權") } },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (shortDialog) Text("開源授權", style = MaterialTheme.typography.titleLarge)
                            Text(notices, style = MaterialTheme.typography.bodySmall)
                        } },
                        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("完成") } })
                }
                if (showDevices) {
                    val devices = remember(resumeGeneration, showDevices) { repository.pairedDevices() }
                    var preference by remember { mutableStateOf(repository.controlPreference) }
                    AlertDialog(onDismissRequest = { showDevices = false }, title = if (shortDialog) null else { { Text("選擇已配對的耳機") } },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (shortDialog) Text("選擇已配對的耳機", style = MaterialTheme.typography.titleLarge)
                                Text("選擇配對記錄與控制通道；音訊設定保持不變。")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ControlPreference.entries.forEach { option ->
                                        FilterChip(selected = preference == option, onClick = { preference = option }, label = { Text(option.label) })
                                    }
                                }
                                if (devices.isEmpty()) Text("沒有找到已配對的 WF-1000XM5。", color = MaterialTheme.colorScheme.error)
                                devices.forEach { device ->
                                    OutlinedButton(onClick = {
                                        repository.setControlPreference(preference)
                                        showDevices = false
                                        ControlService.start(this@MainActivity, ControlService.CONNECT, device.address)
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            Text(device.name)
                                            device.successfulTransport?.let { transport ->
                                                Text("上次成功控制 · ${transport.label}", style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary)
                                            }
                                            Text(device.description, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }, confirmButton = { TextButton(onClick = {
                            showDevices = false; startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }) { Text("藍牙設定") } }, dismissButton = { TextButton(onClick = { showDevices = false }) { Text("取消") } })
                }
            }
        }
    }
    override fun onResume() {
        super.onResume(); resumeGeneration++
        repository.foregroundStarted()
        requestAutomaticConnection()
        SoundDeckWidget.updateAll(this); NoiseTileService.refresh(this)
    }
    override fun onStop() {
        powerObserver.stop(this); automaticJob?.cancel()
        super.onStop()
        if (!isChangingConfigurations) repository.foregroundStopped()
    }
    private fun requestConnect() {
        repository.manualSelectionStarted()
        if (!repository.hasPermission() && Build.VERSION.SDK_INT >= 31) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else showDevices = true
    }
    private fun diagnosticText(): String = repository.state.value.let { s ->
        buildString {
            appendLine("SoundDeck ${BuildConfig.VERSION_NAME} · ${java.time.Instant.now()}")
            appendLine("連線：${s.connection.label}")
            appendLine("控制通道：${s.controlTransport?.label ?: "未確認"} / ${s.endpointLabel ?: "未選擇"}")
            appendLine("耳機回報編碼：${s.codec ?: "未知"}；GATT 連線本身不代表 LC3 音訊。")
            appendLine("耳機回報型號：${s.model ?: "未知"} / 韌體：${s.firmware ?: "未知"}")
            appendLine("協定：${s.protocolVersion ?: "未知"}")
            appendLine("能力：${s.functions?.sorted()?.joinToString { it.toString(16) } ?: "未知"}")
            appendLine("降噪狀態：${s.noise ?: "未知"}; settled=${s.noiseSettled ?: "未知"}")
            appendLine("DSEE Auto：${s.dsee}; available=${s.dseeAvailable}")
            appendLine("Speak-to-Chat：${s.speak}; preview=${s.speakPreview}; settings=${s.speakSettings}")
            appendLine("電量：左耳 ${s.left} / 充電盒 ${s.case} / 右耳 ${s.right}")
            appendLine("電量回報時間：${s.batteryUpdatedAt ?: "無"}")
            appendLine("EQ：${s.eq}; 預設：${s.eqCapability?.presets}")
            appendLine("最後回報時間：${s.updatedAt ?: "無"}")
            appendLine("訊息：${s.message ?: "無"}")
            appendLine("被動 MDR 記錄：sid/op/req 為本機關聯編號；TX transportComplete、ACK、回讀相符均不證明聲學效果。")
            appendLine("僅記錄允許的固定長度設定／狀態／電量 payload；其他 payload 遮蔽，不含藍牙位址、配對金鑰或音訊。")
            appendLine("最多 ${HeadphoneState.MAX_DIAGNOSTICS} 筆；已淘汰 ${s.diagnosticsDropped} 筆。請於各項操作後匯出。")
            appendLine(s.diagnostics.joinToString("\n"))
        }
    }
}








