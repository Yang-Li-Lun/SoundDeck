package dev.sounddeck.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xff456344), onPrimary = Color.White,
    primaryContainer = Color(0xffd5e8cc), onPrimaryContainer = Color(0xff233d23),
    secondary = Color(0xff53634e), secondaryContainer = Color(0xffe0e7d7),
    tertiary = Color(0xff92664a), tertiaryContainer = Color(0xffffdbc6),
    background = Color(0xfff8f9f2), surface = Color(0xfff8f9f2),
    surfaceContainer = Color(0xffedf0e6), surfaceContainerLow = Color(0xfff2f4ec),
    surfaceContainerHigh = Color(0xffe6eade), onSurface = Color(0xff1a2119),
    onSurfaceVariant = Color(0xff566050), outlineVariant = Color(0xffd1d8c9),
)
private val Dark = darkColorScheme(
    primary = Color(0xffb5d2aa), onPrimary = Color(0xff20381e),
    primaryContainer = Color(0xff374f30), onPrimaryContainer = Color(0xffd5e8cc),
    secondary = Color(0xffc0cbb5), secondaryContainer = Color(0xff3e4b38),
    tertiary = Color(0xffe9b996), background = Color(0xff11170f), surface = Color(0xff11170f),
    surfaceContainer = Color(0xff1e261b), surfaceContainerLow = Color(0xff192116),
    surfaceContainerHigh = Color(0xff293124), onSurface = Color(0xffe1e7d9),
    onSurfaceVariant = Color(0xffbec8b6), outlineVariant = Color(0xff414d3b),
)

@Composable fun SoundDeckTheme(theme: String = "system", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when(theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        context.activityOrNull()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}
