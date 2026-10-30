@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.sounddeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import dev.sounddeck.BuildConfig
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlin.math.roundToInt

@Composable fun SoundDeckScreen(
    state: HeadphoneState, permissionGranted: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onAction: (ControlAction) -> Unit,
    onBluetoothSettings: () -> Unit, onAppSettings: () -> Unit, onNotifications: () -> Unit,
    onExport: () -> Unit, onDismissMessage: () -> Unit, onLicenses: () -> Unit = {}, onDismissApplied: (Long) -> Unit = {},
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("聆聽", "音效", "設定")
    val icons = listOf(Icons.Rounded.Headphones, Icons.Rounded.Equalizer, Icons.Rounded.Tune)
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    Surface(Modifier.fillMaxSize()) {
    // Own system bars / cutouts once; children must not apply them a second time.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val shortWindow = maxHeight < 480.dp
        val rail = maxWidth >= 840.dp || shortWindow
        val wide = maxWidth >= 840.dp && !shortWindow
        Scaffold(contentWindowInsets = WindowInsets(0,0,0,0),
            topBar = { if (!shortWindow) ScreenHeader(state, onAction) },
            bottomBar = { Column {
                state.message?.let { message -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).heightIn(max = if (shortWindow) 56.dp else 96.dp).verticalScroll(rememberScrollState()).testTag("message_banner"),
                            style = MaterialTheme.typography.bodyMedium)
                        when (state.messageRecovery) {
                            MessageRecovery.REFRESH -> if (state.ready) TextButton(
                                onClick = { onAction(ControlAction.Refresh) }, enabled = state.pending == null,
                                contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("message_refresh")) {
                                Text("重新整理")
                            }
                            MessageRecovery.RECONNECT -> if (!state.ready && state.connection != ConnectionState.CONNECTING &&
                                state.connection != ConnectionState.SYNCING && state.connection != ConnectionState.RECOVERING) TextButton(
                                onClick = onConnect, contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.testTag("message_reconnect")) { Text("重新連線") }
                            null -> Unit
                        }
                        IconButton(onClick = onDismissMessage) { Icon(Icons.Rounded.Close, "關閉訊息") }
                    }
                } }
                if (!rail) NavigationBar(windowInsets = WindowInsets(0,0,0,0), modifier = Modifier.testTag("bottom_navigation")) {
                    labels.forEachIndexed { i, label -> NavigationBarItem(selected = page == i, onClick = { page = i },
                        icon = { Icon(icons[i], null) }, label = { Text(label) }, modifier = Modifier.testTag("nav_$i")) }
                }
            } },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            val feedbackHeight = if (!state.ready || state.message != null) 0.dp else with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() } + 28.dp
            val eqViewportHeight = maxHeight - feedbackHeight
            Row(Modifier.fillMaxSize().padding(bottom = feedbackHeight)) {
                if (rail) NavigationRail(windowInsets = WindowInsets(0,0,0,0), modifier = Modifier.verticalScroll(rememberScrollState()).testTag("side_navigation")) {
                    labels.forEachIndexed { i, label -> NavigationRailItem(selected = page == i, onClick = { page = i },
                        icon = { Icon(icons[i], label) }, label = if (shortWindow) null else { { Text(label) } }, modifier = Modifier.testTag("nav_$i")) }
                }
                Column(Modifier.weight(1f).verticalScroll(scroll).testTag("page_scroll")
                    .padding(horizontal = if (wide) 24.dp else 16.dp).padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shortWindow) ScreenHeader(state, onAction)
                    when(page) {
                        0 -> if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HeadphoneCard(state, permissionGranted, onConnect, onDisconnect); BatteryRow(state)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                NoiseCard(state, onAction)
                                SpeakCard(state, onAction)
                            }
                        } else {
                            HeadphoneCard(state, permissionGranted, onConnect, onDisconnect)
                            BatteryRow(state)
                            NoiseCard(state, onAction)
                            SpeakCard(state, onAction)
                        }
                        1 -> {
                            EqualizerCard(state, onAction, eqViewportHeight)
                            SectionCard {
                                SettingSwitch("DSEE", state.dsee, state.canControlDsee) { onAction(ControlAction.Dsee(it)) }
                            }
                        }
                        2 -> {
                            SectionCard {
                                SectionTitle(Icons.Rounded.Bluetooth, "連線與權限")
                                ActionRow("通知設定", "顯示模式與電量", Icons.Rounded.Notifications, onNotifications)
                                ActionRow("系統藍牙設定", "", Icons.Rounded.Bluetooth, onBluetoothSettings)
                                ActionRow("App 權限", if (permissionGranted) "附近裝置已允許" else "需要附近裝置權限", Icons.Rounded.Security, onAppSettings)
                            }
                            SectionCard {
                                SectionTitle(Icons.Rounded.DeveloperBoard, "裝置資訊")
                                InfoRow("型號", state.model ?: "—")
                                InfoRow("韌體", state.firmware ?: "—")
                                InfoRow("音訊編碼", state.codec ?: "—")
                                InfoRow("控制通道", state.controlTransport?.label ?: "—")
                                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("export_diagnostics")) {
                                    Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("匯出診斷")
                                }
                            }
                            Text("SoundDeck ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onLicenses, modifier = Modifier.testTag("licenses")) { Text("開源授權") }
                        }
                    }
                }
            }
            AppliedSettingPill(state.appliedSettingId, state.appliedSettingAtMillis, state.message == null && state.pending == null,
                onDismissApplied, Modifier.align(Alignment.BottomCenter).height(feedbackHeight).padding(bottom = 8.dp))
            }
        }
    }
}

}

@Composable private fun ScreenHeader(state: HeadphoneState, action: (ControlAction) -> Unit) {
    Surface {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SoundDeck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.ready) IconButton(onClick = { action(ControlAction.Refresh) }, enabled = state.pending == null) {
                    Icon(Icons.Rounded.Refresh, "重新讀取")
                }
                StatusPill(state)
            }
        }
    }
}

@Composable private fun StatusPill(state: HeadphoneState) {
    Surface(color = if (state.ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(if (state.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            Text(state.connection.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable private fun HeadphoneCard(state: HeadphoneState, permission: Boolean, connect: () -> Unit, disconnect: () -> Unit) {
    val busy = state.connection == ConnectionState.CONNECTING || state.connection == ConnectionState.SYNCING ||
        state.connection == ConnectionState.RECOVERING
    SectionCard {
        SectionTitle(Icons.Rounded.Headphones, state.model ?: "WF-1000XM5")
        Spacer(Modifier.height(12.dp))
        if (state.ready || busy) OutlinedButton(onClick = disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (state.connection == ConnectionState.RECOVERING) "取消恢復連線" else if (busy) "取消連線" else "中斷連線")
        } else Button(onClick = connect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connect")) {
            Icon(Icons.Rounded.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text(if (permission) "連接耳機" else "授權並連線")
        }
    }
}
@Composable private fun BatteryRow(state: HeadphoneState) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || fontScale > 1.3f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (stacked) 1 else 3) {
            listOf("左耳" to state.left, "充電盒" to state.case, "右耳" to state.right).forEach { (label, reading) ->
                Surface((if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).testTag("battery_$label"), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(reading?.percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge)
                            if (reading?.charging == true) Icon(Icons.Rounded.BatteryChargingFull, "充電中", Modifier.size(20.dp))
                        }
                        if (reading?.chargingStatus == 3) Text("已充滿", style = MaterialTheme.typography.labelSmall)
                        else if (reading?.percent == null) Text("尚未回報", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
@Composable private fun NoiseCard(state: HeadphoneState, action: (ControlAction) -> Unit) {
    val reportedLevel = (state.noise?.level ?: 1).coerceIn(1,20)
    var level by remember(reportedLevel, state.pending) { mutableFloatStateOf(reportedLevel.toFloat()) }
    SectionCard {
        SectionTitle(Icons.Rounded.GraphicEq, "環境聲音控制")
        Spacer(Modifier.height(12.dp))
        ModeSelector(state.confirmedNoise?.mode, state.canControlNoise) { action(ControlAction.Noise(it)) }
        if (state.noiseSettled == false) Text(state.noiseStatusLabel, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("環境聲音強度", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(state.noise?.let { "${it.level} / 20" } ?: "— / 20", color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = level, onValueChange = { level = it }, valueRange = 1f..20f, steps = 18,
            enabled = state.canControlNoise && state.noise?.mode == NoiseMode.AMBIENT,
            onValueChangeFinished = {
                action(ControlAction.AmbientLevel(level.roundToInt()))
                level = reportedLevel.toFloat()
            }, modifier = Modifier.testTag("ambient_level"))
        if (level.roundToInt() != reportedLevel) Text("調整至 ${level.roundToInt()}", style = MaterialTheme.typography.labelSmall)
        SettingSwitch("聚焦人聲", state.noise?.voice, state.canControlNoise && state.noise?.mode == NoiseMode.AMBIENT) {
            action(ControlAction.VoiceFocus(it))
        }
    }
}

@Composable private fun SpeakCard(state: HeadphoneState, action: (ControlAction) -> Unit) {
    SectionCard {
        SettingSwitch("Speak-to-Chat", state.speak, state.canControlSpeak) { action(ControlAction.Speak(it)) }
        SpeakSensitivitySetting(state, action)
    }
}

@Composable private fun SpeakSensitivitySetting(state: HeadphoneState, action: (ControlAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val enabled = state.canControlSpeakSensitivity
    LaunchedEffect(enabled) { if (!enabled) menu = false }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("語音偵測靈敏度", style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(onClick = { menu = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("speak_sensitivity")) {
                Text(state.speakSettings?.sensitivity?.label ?: "尚未回報", Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                SpeakSensitivity.entries.forEach { sensitivity ->
                    DropdownMenuItem(text = { Text(sensitivity.label) },
                        modifier = Modifier.testTag("speak_sensitivity_${sensitivity.name}"), onClick = {
                            menu = false
                            if (enabled) action(ControlAction.SpeakDetection(sensitivity))
                        })
                }
            }
        }
    }
}

@Composable private fun EqualizerCard(state: HeadphoneState, action: (ControlAction) -> Unit, viewportHeight: androidx.compose.ui.unit.Dp) {
    var menu by remember { mutableStateOf(false) }
    val eq = state.eq
    val cap = state.eqCapability
    val bands = (state.eqTarget?.bands ?: eq?.bands)?.map { it - 10 }.orEmpty()
    val canEdit = state.canAdjustEqBands
    val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
    val sliderHeight = (viewportHeight - labelHeight * 3 - 16.dp).coerceIn(48.dp, 180.dp)
    LaunchedEffect(state.canControlEq) { if (!state.canControlEq) menu = false }
    SectionCard {
        SectionTitle(Icons.Rounded.Equalizer, "等化器")
        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            OutlinedButton(onClick = { menu = true }, enabled = state.canControlEq, modifier = Modifier.fillMaxWidth().testTag("eq_presets")) {
                val presetId = state.eqTarget?.preset ?: eq?.preset
                Text(presetId?.let { cap?.presets?.firstOrNull { preset -> preset.id == it }?.displayName ?: EqPresetNames.label(it) } ?: "等待耳機回報預設", Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded = menu && state.canControlEq, onDismissRequest = { menu = false }) {
                cap?.presets?.forEach { preset -> DropdownMenuItem(text = { Text(preset.displayName) }, onClick = {
                    menu = false; action(ControlAction.Equalizer(preset.id))
                }) }
            }
        }
        if (bands.size == 6 && bands.all { it in -10..10 }) {
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columnWidth = maxOf(52.dp * LocalDensity.current.fontScale, maxWidth / 6)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("eq_bands")) {
                    listOf("Clear\nBass", "400\nHz", "1\nkHz", "2.5\nkHz", "6.3\nkHz", "16\nkHz").forEachIndexed { i, label ->
                        Column(Modifier.width(columnWidth), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${bands[i]}", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("eq_gain_$i"))
                            val change: (Float) -> Unit = { value ->
                                val gain = value.roundToInt()
                                if (canEdit && gain != bands[i]) action(ControlAction.Equalizer(state.customEqPreset!!,
                                    bands.mapIndexed { index, current -> (if (index == i) gain else current) + 10 }))
                            }
                            Box(Modifier.width(48.dp).height(sliderHeight).testTag("eq_band_$i").clearAndSetSemantics {
                                contentDescription = label.replace('\n', ' ')
                                stateDescription = "${bands[i]}"
                                progressBarRangeInfo = ProgressBarRangeInfo(bands[i].toFloat(), -10f..10f, 19)
                                if (!canEdit) disabled()
                                setProgress { value ->
                                    val target = value.coerceIn(-10f, 10f).roundToInt()
                                    if (!canEdit || target == bands[i]) false else { change(target.toFloat()); true }
                                }
                            }) {
                                // Keep accessibility / scrolling bounds vertical while rotating the Material control.
                                Slider(bands[i].toFloat(), change, Modifier.fillMaxSize().layout { measurable, constraints ->
                                    val placeable = measurable.measure(Constraints.fixed(constraints.maxHeight, constraints.maxWidth))
                                    layout(placeable.height, placeable.width) {
                                        placeable.place((placeable.height - placeable.width) / 2,
                                            (placeable.width - placeable.height) / 2)
                                    }
                                }.rotate(270f), enabled = canEdit, valueRange = -10f..10f, steps = 19)
                            }
                            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Text(if (state.eqTarget != null) "正在套用自訂 EQ，等待耳機確認。" else if (state.eqAvailable == false) "耳機回報目前無法調整 EQ。" else if (!state.ready) "連線後載入預設。" else if (!canEdit) "目前無法調整自訂 EQ。" else "調整頻段會自動切換至自訂 EQ。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}


@Composable private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
@Composable private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun SettingSwitch(title: String, checked: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("switch_$title"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (checked == null) Text("尚未回報", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked == true, onCheckedChange = onChange, enabled = enabled)
    }
}
@Composable private fun ActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    // Content sets the height; no button intrinsic-height assumptions or clipped labels.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .testTag("settings_$title").clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon, null, modifier = Modifier.size(20.dp).testTag("settings_icon_$title"),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun AppliedSettingPill(id: Long?, createdAtMillis: Long, allowed: Boolean, dismiss: (Long) -> Unit, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(id, allowed) {
        val remaining = (1000 - (android.os.SystemClock.elapsedRealtime() - createdAtMillis)).coerceIn(0, 1000)
        visible = id != null && allowed && remaining > 0
        if (id != null && allowed) {
            delay(remaining)
            visible = false
            delay(250)
            currentDismiss(id)
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(250))) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 3.dp,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("applied_pill")
                    .semantics { liveRegion = LiveRegionMode.Polite }) {
                Text("耳機回報設定相符", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
@Composable private fun InfoRow(label: String, value: String) {
    if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    } else Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
    }
}
