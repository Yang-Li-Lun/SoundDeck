package dev.sounddeck

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import dev.sounddeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Success events are fixtures; this test never sends a Bluetooth command. */
class FeedbackUiTest {
    @get:Rule val compose=createComposeRule()
    private var state by mutableStateOf(HeadphoneState(connection=ConnectionState.READY,
        functions=setOf(0x6b),noise=NoiseState(NoiseMode.AMBIENT,12,false,1)))
    private val dismissed=mutableListOf<Long>()
    private fun screen() {
        compose.setContent { SoundDeckTheme("dark",false) {
            SoundDeckScreen(state,true,{},{},{},{},{},{},{},{},onDismissApplied={
                dismissed.add(it)
                if(state.appliedSettingId==it) state=state.copy(appliedSettingId=null)
            })
        } }
        compose.onNodeWithTag("mode_AMBIENT").performScrollTo()
        compose.mainClock.autoAdvance=false
    }
    private fun success(id:Long) {
        compose.runOnUiThread { state=state.copy(message=null,appliedSettingId=id,appliedSettingAtMillis=SystemClock.elapsedRealtime()) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
    }
    @Test fun successHoldsThenFadesWithoutMovingContentOrCoveringNavigation() {
        screen()
        val before=compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot
        success(1)
        compose.onNodeWithTag("applied_pill").assertIsDisplayed()
        compose.onNodeWithTag("message_banner").assertDoesNotExist()
        val pill=compose.onNodeWithTag("applied_pill").fetchSemanticsNode().boundsInRoot
        val nav=compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("pill below content",pill.top>=before.bottom)
        assertTrue("pill above navigation",pill.bottom<=nav.top)
        assertEquals(before,compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot)
        save("synthetic-pill-visible.png")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("applied_pill").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(380)
        compose.onNodeWithTag("applied_pill").assertExists() // exit animation, not an immediate removal
        save("synthetic-pill-fading.png")
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("applied_pill").assertDoesNotExist()
        assertEquals(listOf(1L),dismissed)
        assertEquals(before,compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot)
        save("synthetic-pill-gone.png")
    }
    @Test fun repeatedSuccessReplacesTimerAndErrorDoesNotAutoDismiss() {
        screen();success(1)
        compose.mainClock.advanceTimeBy(500)
        success(2)
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("applied_pill").assertCountEquals(1)
        assertTrue("old timer must be cancelled",dismissed.isEmpty())
        compose.runOnUiThread { state=state.copy(appliedSettingId=null,message="設定遭拒絕；畫面保留耳機實際狀態。") }
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithTag("applied_pill").assertDoesNotExist()
        compose.onNodeWithTag("message_banner").assertIsDisplayed()
        assertTrue(dismissed.isEmpty())
    }
    @Test fun oldBackgroundSuccessDoesNotReappearWhenScreenOpens() {
        state=state.copy(appliedSettingId=9,appliedSettingAtMillis=SystemClock.elapsedRealtime()-10000)
        screen();compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("applied_pill").assertDoesNotExist()
    }
    private fun shortPill(width:Int,height:Int) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                Box(Modifier.size(width.dp,height.dp)) { SoundDeckTheme("light",false) {
                    SoundDeckScreen(state,true,{},{},{},{},{},{},{},{})
                } }
            }
        }
        compose.onNodeWithTag("mode_OFF").performScrollTo().assertIsDisplayed()
        val viewport=compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance=false
        success(7)
        val pill=compose.onNodeWithTag("applied_pill").assertIsDisplayed().fetchSemanticsNode()
        val mode=compose.onNodeWithTag("mode_OFF").fetchSemanticsNode()
        assertEquals(viewport,compose.onNodeWithTag("page_scroll").fetchSemanticsNode().boundsInRoot)
        assertTrue(pill.boundsInRoot.top>=viewport.bottom)
        assertTrue(mode.boundsInRoot.height>=mode.size.height-1)
        assertTrue(pill.boundsInRoot.height>=pill.size.height-1)
        assertTrue(pill.boundsInRoot.bottom<=height && pill.boundsInRoot.right<=width)
        compose.onNodeWithText("設定已套用").assertIsDisplayed()
        save("synthetic-pill-${width}x$height-font2.png")
    }
    @Test fun shortLandscapeLargeFontPillDoesNotCoverControls()=shortPill(640,320)
    @Test fun narrowShortLargeFontPillDoesNotCoverControls()=shortPill(320,360)
    private fun save(name:String) {
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(i.targetContext.getExternalFilesDir(null),"validation-${BuildConfig.VERSION_NAME}").apply{mkdirs()}
        androidx.test.uiautomator.UiDevice.getInstance(i).takeScreenshot(java.io.File(dir,name))
    }
}
