package dev.sounddeck.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import dev.sounddeck.protocol.NoiseMode

/** The indicator only follows a reported mode, never a pending tap. */
@Composable fun ModeSelector(mode: NoiseMode?, enabled: Boolean, select: (NoiseMode) -> Unit) {
    val modes = listOf(NoiseMode.ANC, NoiseMode.AMBIENT, NoiseMode.OFF)
    val height = (48 * LocalDensity.current.fontScale.coerceIn(1f, 2f)).dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 240.dp) {
        // A narrow split window needs wrapping targets rather than clipped three-column labels.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { item ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (mode == item) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = mode == item, enabled = enabled, role = Role.RadioButton, onClick = { select(item) })
                    .testTag("mode_${item.name}").padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(item.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                        color = (if (mode == item) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = if (enabled) 1f else .6f))
                }
            }
        }
    } else {
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp).selectableGroup()) {
        val segment = maxWidth / 3
        val index = modes.indexOf(mode)
        val position by animateDpAsState(segment * index.coerceAtLeast(0), tween(220, easing = FastOutSlowInEasing), label = "reportedMode")
        if (index >= 0) Box(Modifier.offset { IntOffset(position.roundToPx(),0) }.width(segment).fillMaxHeight()
            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).testTag("mode_indicator"))
        Row(Modifier.fillMaxSize()) {
            modes.forEach { item ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                    .selectable(selected = mode == item, enabled = enabled, role = Role.RadioButton, onClick = { select(item) })
                    .testTag("mode_${item.name}"), contentAlignment = Alignment.Center) {
                    Text(item.label, Modifier.padding(horizontal = 2.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        color = (if (mode == item) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = if (enabled) 1f else .6f))
                }
            }
        }
    }
}
    }
    }
