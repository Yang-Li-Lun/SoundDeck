package dev.sounddeck

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import dev.sounddeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShortWindowRegressionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fullModeTargetFitsAboveMessageAtShortHeightWithLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                Box(Modifier.width(640.dp).height(320.dp)) { SoundDeckTheme {
                    SoundDeckScreen(HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b),
                        noise=NoiseState(NoiseMode.AMBIENT,7,false,1),message="耳機回報與要求不同；畫面保留實際狀態，請重新整理。"),
                        true,{},{},{},{},{},{},{},{})
                } }
            }
        }
        compose.onNodeWithTag("mode_AMBIENT").performScrollTo()
        val target=compose.onNodeWithTag("mode_AMBIENT").fetchSemanticsNode()
        val viewport=compose.onNodeWithTag("page_scroll").fetchSemanticsNode()
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val version=i.targetContext.packageManager.getPackageInfo(i.targetContext.packageName,0).versionName
        val dir=java.io.File(i.targetContext.getExternalFilesDir(null),"validation-${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        androidx.test.uiautomator.UiDevice.getInstance(i).takeScreenshot(java.io.File(dir,"short-window-$version.png"))
        dir.resolve("short-window-$version.txt").writeText("actualVersion=$version; target=${target.boundsInRoot}; fullSize=${target.size}; viewport=${viewport.boundsInRoot}")
        assertTrue("full mode target must fit: ${target.boundsInRoot} vs ${target.size}; viewport=${viewport.boundsInRoot}",target.boundsInRoot.height>=target.size.height-1)
    }
}
