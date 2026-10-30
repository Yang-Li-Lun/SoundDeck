package dev.sounddeck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun eqGainLabel(value: Int): String = if (value > 0) "+$value" else "$value"

/** Material3 1.4.0 exposes no public vertical slider. Use vertical coordinates throughout. */
@Composable internal fun EqVerticalSlider(
    value: Int, confirmed: Int?, label: String, enabled: Boolean, onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val setValue: (Float) -> Boolean = { requested ->
        val target = requested.coerceIn(-10f, 10f).roundToInt()
        if (!enabled || target == currentValue) false else { change(target); true }
    }
    Canvas(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics {
            contentDescription = label
            stateDescription = "目前 ${eqGainLabel(value)}，耳機已確認 ${confirmed?.let(::eqGainLabel) ?: "未知"}"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -10f..10f, 19)
            if (!enabled) disabled()
            setProgress { setValue(it) }
        }
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            val target = when (event.key) {
                Key.DirectionUp, Key.DirectionRight -> currentValue + 1
                Key.DirectionDown, Key.DirectionLeft -> currentValue - 1
                Key.MoveHome -> -10
                Key.MoveEnd -> 10
                else -> null
            }
            if (!enabled || target == null) false else {
                if (event.type == KeyEventType.KeyDown) setValue(target.toFloat())
                true
            }
        }
        .focusable(enabled)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val inset = 12.dp.toPx()
            fun update(y: Float) {
                val fraction = ((y - inset) / (size.height - 2 * inset)).coerceIn(0f, 1f)
                val gain = (10f - fraction * 20f).roundToInt()
                if (gain != currentValue) change(gain)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                // Own touches starting on this control; the surrounding page still scrolls elsewhere.
                down.consume()
                focusRequester.requestFocus()
                update(down.position.y)
                drag(down.id) { pointer ->
                    pointer.consume()
                    update(pointer.position.y)
                }
            }
        }) {
        val inset = 12.dp.toPx()
        val x = size.width / 2
        val zero = size.height / 2
        val y = inset + (10 - value) / 20f * (size.height - 2 * inset)
        val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
        drawLine(colors.outlineVariant, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
        drawLine(colors.surfaceContainerHighest, Offset(x, inset), Offset(x, size.height - inset), 8.dp.toPx(), StrokeCap.Round)
        drawLine(active, Offset(x, zero), Offset(x, y), 8.dp.toPx(), StrokeCap.Round)
        drawLine(colors.onSurfaceVariant, Offset(x - 6.dp.toPx(), zero), Offset(x + 6.dp.toPx(), zero), 1.dp.toPx())
        if (focused && enabled) drawCircle(colors.primary, 12.dp.toPx(), Offset(x, y))
        drawCircle(active, 10.dp.toPx(), Offset(x, y))
        drawCircle(colors.surface, 10.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}
