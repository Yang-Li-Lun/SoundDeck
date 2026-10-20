package dev.sounddeck

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sounddeck.protocol.NoiseMode
import dev.sounddeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModeAnimationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun indicatorMovesOnlyAfterReportAndAnimatesBetweenSegments() {
        var reported by mutableStateOf(NoiseMode.ANC)
        val requests=mutableListOf<NoiseMode>()
        compose.setContent { SoundDeckTheme { ModeSelector(reported,true){requests.add(it)} } }
        compose.mainClock.autoAdvance=false
        try {
            val before=compose.onNodeWithTag("mode_indicator").fetchSemanticsNode().boundsInRoot.left
            compose.onNodeWithTag("mode_AMBIENT").performClick()
            assertEquals(listOf(NoiseMode.AMBIENT),requests)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(before,compose.onNodeWithTag("mode_indicator").fetchSemanticsNode().boundsInRoot.left)
            compose.runOnIdle { reported=NoiseMode.AMBIENT }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(80)
            val during=compose.onNodeWithTag("mode_indicator").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(300)
            val after=compose.onNodeWithTag("mode_indicator").fetchSemanticsNode().boundsInRoot.left
            assertTrue("indicator moves smoothly",during>before && during<after)
            compose.onNodeWithTag("mode_AMBIENT").assertIsSelected()
        } finally { compose.mainClock.autoAdvance=true }
    }
}
