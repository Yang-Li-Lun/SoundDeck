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

class BatteryPresentationTest {
    @get:Rule val compose=createComposeRule()
    private fun verify(font:Float) {
        val s=HeadphoneState(left=BatteryReading(12,true),case=BatteryReading(null,true),right=BatteryReading(89,false))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,font)) {
            Box(Modifier.size(360.dp,800.dp)) { SoundDeckTheme { SoundDeckScreen(s,true,{},{},{},{},{},{},{},{}) } }
        } }
        val labels=listOf("左耳","充電盒","右耳")
        val positions=labels.map { compose.onNodeWithTag("battery_$it").fetchSemanticsNode().positionInRoot }
        assertTrue(positions.zipWithNext().all { (a,b)->if(font>1.3) a.y<b.y else a.x<b.x })
        for((label,value) in listOf("左耳" to "12%","充電盒" to "—","右耳" to "89%")) {
            compose.onNodeWithTag("battery_$label").performScrollTo()
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("battery_$label")),useUnmergedTree=true).assertIsDisplayed()
        }
        compose.onAllNodesWithContentDescription("充電中",useUnmergedTree=true).assertCountEquals(2)
    }
    @Test fun horizontalLeftCaseRightPreservesUnknownAndCharging()=verify(1f)
    @Test fun stackedLeftCaseRightPreservesUnknownAndCharging()=verify(2f)
}
