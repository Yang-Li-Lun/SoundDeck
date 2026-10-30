package dev.sounddeck

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import dev.sounddeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Layout fixtures, never Bluetooth acceptance or a production demo mode. */
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun exercise(fontScale: Float, width: Int = 320, height: Int = 640, dark: Boolean = false) {
        val actions = mutableListOf<ControlAction>()
        var exports=0; var licenses=0; var bluetooth=0; var permissions=0; var notifications=0
        val state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b,0x50,0xe2,0xfc), dsee=false,dseeAvailable=true,speak=false,speakPreview=0,
            speakSettings=SpeakSettings(SpeakSensitivity.HIGH,1),
            noiseSettled = true, noise = NoiseState(NoiseMode.AMBIENT, 12, false, 1), left = BatteryReading(100,true), right = BatteryReading(0,false),
            eq = SonyEvent.Equalizer(0xa1,List(6){10}), eqAvailable = true,
            eqCapability = SonyEvent.EqCapability(6,21,listOf(EqPreset(0,""),EqPreset(0xa1,""))),
            message = "耳機回報與要求不同；保留實際狀態。")
        compose.setContent {
            val density = 1f
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) {
                Box(Modifier.width(width.dp).height(height.dp)) { SoundDeckTheme(if(dark) "dark" else "light", false) {
                    SoundDeckScreen(state,true,{},{},{actions.add(it)},{bluetooth++},{permissions++},{notifications++},{exports++},{},{licenses++})
                } }
            }
        }
        compose.onNodeWithTag("mode_OFF").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(ControlAction.Noise(NoiseMode.OFF), actions.last())
        val control = compose.onNodeWithTag("mode_OFF").fetchSemanticsNode().boundsInRoot
        val banner = compose.onNodeWithTag("message_banner").fetchSemanticsNode().boundsInRoot
        assertTrue("message must not cover controls", control.bottom <= banner.top)
        compose.onNodeWithTag("ambient_level").performScrollTo().assertIsDisplayed()
        fullyVisible("ambient_level")
        compose.onNodeWithTag("switch_聚焦人聲").performScrollTo().assertIsDisplayed()
        fullyVisible("switch_聚焦人聲")
        navigate(1,width,height)
        compose.onNodeWithTag("eq_bands").performScrollTo()
        val eqWidth = compose.onNodeWithTag("eq_bands").fetchSemanticsNode().boundsInRoot.width
        if (width==320 && height==640 && fontScale==1f) {
            val bounds=(1..5).map { compose.onNodeWithTag("eq_band_$it").fetchSemanticsNode().boundsInRoot }
            assertTrue("five bands share one row on a normal phone",bounds.all { it.top==bounds.first().top })
            assertTrue("all bands fit without horizontal scrolling",bounds.last().right-bounds.first().left<=eqWidth+1)
        }
        for (i in 1..5) {
            compose.onNodeWithTag("eq_band_$i").performScrollTo().assertIsDisplayed().assertIsEnabled()
            fullyVisible("eq_band_$i")
            val bounds=compose.onNodeWithTag("eq_band_$i").fetchSemanticsNode().boundsInRoot
            assertTrue("each band retains a 48dp touch target",bounds.width>=48 && bounds.height>=48)
        }
        compose.onNodeWithTag("eq_apply").assertDoesNotExist()
        val band = compose.onNodeWithTag("eq_band_5").fetchSemanticsNode().boundsInRoot
        assertTrue("vertical slider retains a 48dp touch target", band.width >= 48 && band.height >= 48)
        assertTrue("slider fits the viewport",band.height <= compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot.height+1)
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder=java.io.File(context.getExternalFilesDir(null),"validation-${BuildConfig.VERSION_NAME}").apply { mkdirs() }
        folder.resolve("synthetic-eq-${width}x$height-font$fontScale-${if(dark) "dark" else "light"}.png")
            .outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithTag("eq_band_0").performScrollTo().assertIsDisplayed().assertIsEnabled()
        fullyVisible("eq_band_0")
        val bass=compose.onNodeWithTag("eq_band_0").fetchSemanticsNode().boundsInRoot
        assertTrue("Clear Bass is horizontal",bass.width>=bass.height)
        compose.onNodeWithTag("eq_band_0").assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("switch_DSEE").performScrollTo().assertIsDisplayed()
        fullyVisible("switch_DSEE")
        compose.onNodeWithTag("switch_Speak-to-Chat").assertDoesNotExist()
        navigate(0,width,height)
        compose.onNodeWithTag("switch_Speak-to-Chat").performScrollTo().assertIsDisplayed()
        fullyVisible("switch_Speak-to-Chat")
        compose.onNodeWithTag("speak_sensitivity").performScrollTo().assertIsDisplayed().assertIsEnabled()
        fullyVisible("speak_sensitivity")
        folder.resolve("synthetic-sensitivity-${width}x$height-font$fontScale-${if(dark) "dark" else "light"}.png")
            .outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithTag("speak_sensitivity").performClick()
        compose.onNodeWithTag("speak_sensitivity_LOW").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(ControlAction.SpeakDetection(SpeakSensitivity.LOW),actions.last())
        compose.onNodeWithText("高靈敏度").assertExists()
        navigate(2,width,height)
        for(title in listOf("通知設定","系統藍牙設定","App 權限")) {
            compose.onNodeWithTag("settings_$title").performScrollTo()
            fullyVisible("settings_$title")
            val row=compose.onNodeWithTag("settings_$title").fetchSemanticsNode().boundsInRoot
            val icon=compose.onNodeWithTag("settings_icon_$title",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("icon inside row",row.contains(icon.center) && icon.width>=19 && icon.height>=19)
            assertTrue("row has no excessive blank height",row.height <= 150)
        }
        compose.onNodeWithText("通知設定").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("系統藍牙設定").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("App 權限").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1,notifications); assertEquals(1,bluetooth); assertEquals(1,permissions)
        compose.onNodeWithText("SoundDeck ${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SoundDeck 0.1.0").assertDoesNotExist()
        compose.onNodeWithText("外觀").assertDoesNotExist()
        compose.onNodeWithText("快捷操作").assertDoesNotExist()
        compose.onNodeWithTag("export_diagnostics").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1,exports)
        compose.onNodeWithTag("licenses").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1,licenses)
        val last=compose.onNodeWithTag("licenses").fetchSemanticsNode().boundsInRoot
        val bannerBounds=compose.onNodeWithTag("message_banner").fetchSemanticsNode().boundsInRoot
        assertTrue("settings must remain above message / navigation",last.bottom<=bannerBounds.top)
        fullyVisible("licenses")
        navigate(0,width,height)
        compose.onNodeWithText("桌面小工具").assertDoesNotExist()
        compose.onNodeWithText("快速設定").assertDoesNotExist()
        compose.onNodeWithText("充電狀態未知").assertDoesNotExist()
    }
    private fun navigate(index: Int,width: Int,height: Int) {
        val target=compose.onNodeWithTag("nav_$index")
        if(height<480 || width>=840) target.performScrollTo()
        target.assertIsDisplayed().performClick()
    }
    private fun fullyVisible(tag: String) {
        val n=compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue("$tag vertically clipped: ${n.boundsInRoot} vs ${n.size}",n.boundsInRoot.height >= n.size.height-1)
        assertTrue("$tag horizontally clipped",n.boundsInRoot.width >= n.size.width-1)
        // Inspect actual measured glyph layouts, including the entire EQ / switch content.
        compose.onAllNodes(hasTextLayout(),useUnmergedTree=true).fetchSemanticsNodes().forEach { text ->
            val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            text.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.forEach {
                // TextLayoutResult rounds its integer size; allow one physical pixel only.
                val right=(0 until it.lineCount).maxOfOrNull { line -> it.getLineRight(line)-it.getLineLeft(line) } ?: 0f
                assertTrue("glyphs clipped: ${it.layoutInput.text}, right=$right size=${it.size}",right <= it.size.width + 1f)
                assertTrue("glyph height clipped: ${it.layoutInput.text}",it.getLineBottom(it.lineCount-1) <= it.size.height + 1f)
                assertFalse(it.multiParagraph.didExceedMaxLines)
            }
        }
    }
    private fun hasTextLayout()=SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
    @Test fun narrowShortWindowMaximumFontAndMessage() = exercise(2f,320,360)
    @Test fun shortLandscapeMaximumFontAndMessage() = exercise(2f,640,320)
    @Test fun wideMaximumFontAndMessage() = exercise(2f,900,640)
    @Test fun narrowNormalFont() = exercise(1f)
    @Test fun narrowLargeFont() = exercise(1.5f)
    @Test fun narrowMaximumFont() = exercise(2f)
    @Test fun darkNarrowMaximumFont() = exercise(2f,dark=true)
    @Test fun darkShortMaximumFont() = exercise(2f,640,320,dark=true)
}
