package dev.sounddeck

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import dev.sounddeck.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture rendering / callback test only, never packaged as a demo or headset acceptance. */
@RunWith(AndroidJUnit4::class)
class SyntheticControlUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sensitivityWaitsForReadbackAndUpdatesFromExternalNotification() {
        var state by mutableStateOf(HeadphoneState(connection=ConnectionState.READY,functions=setOf(0xfc),
            speak=false,speakPreview=1,speakSettings=SpeakSettings(SpeakSensitivity.AUTO,3)))
        val actions=mutableListOf<ControlAction>()
        compose.setContent { SoundDeckTheme {
            SoundDeckScreen(state,true,{},{},{actions.add(it)},{},{},{},{},{})
        } }
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("speak_sensitivity").performScrollTo().performClick()
        compose.onNodeWithTag("speak_sensitivity_HIGH").performClick()
        assertEquals(ControlAction.SpeakDetection(SpeakSensitivity.HIGH),actions.single())
        compose.onNodeWithText("自動").assertExists()
        compose.onNodeWithTag("applied_pill").assertDoesNotExist()
        compose.runOnIdle { state=state.copy(pending="等待確認") }
        compose.onNodeWithTag("speak_sensitivity").assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(pending=null,message="耳機回報與要求不同") }
        compose.onNodeWithText("自動").assertExists()
        compose.runOnIdle { state=state.receive(SonyEvent.SpeakConfiguration(SpeakSettings(SpeakSensitivity.LOW,3)),1) }
        compose.onNodeWithText("低靈敏度").assertExists()
        compose.runOnIdle { state=HeadphoneState() }
        compose.onNodeWithTag("speak_sensitivity").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("低靈敏度").assertDoesNotExist()
    }
    @Test fun voiceFocusAppCallbackDoesNotOptimisticallyChangeReportedState() {
        val actions=mutableListOf<ControlAction>()
        val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b),
            noise=NoiseState(NoiseMode.AMBIENT,15,false,1))
        compose.setContent { SoundDeckTheme {
            SoundDeckScreen(state,true,{},{},{actions.add(it)},{},{},{},{},{})
        } }
        compose.onNodeWithTag("switch_聚焦人聲").performScrollTo()
        val toggle=compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("switch_聚焦人聲")))
        toggle.performClick()
        assertEquals(ControlAction.VoiceFocus(true),actions.single())
        toggle.assertIsOff()
    }
    @Test fun controlsDispatchDesiredActionAndWaitForReportedState() {
        val actions = mutableListOf<ControlAction>()
        val state = HeadphoneState(connection = ConnectionState.READY, model = "WF-1000XM5", functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.ANC, 12, false, 0))
        compose.setContent {
            SoundDeckTheme("light", false) {
                SoundDeckScreen(state, true, {}, {}, { actions.add(it) }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("mode_AMBIENT").performScrollTo().performClick()
        assertEquals(ControlAction.Noise(NoiseMode.AMBIENT), actions.single())
        compose.onNodeWithTag("mode_ANC").assertIsSelected()
        compose.onNodeWithTag("mode_AMBIENT").assertIsNotSelected()
    }
    @Test fun pendingTransactionDisablesCompetingControls() {
        val state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.AMBIENT, 12, false, 1), pending = "等待耳機確認…")
        compose.setContent { SoundDeckTheme("dark", false) {
            SoundDeckScreen(state, true, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("mode_ANC").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ambient_level").assertIsNotEnabled()
    }
    @Test fun rejectedAmbientAdjustmentRestoresTheReportedLevel() {
        var state by mutableStateOf(HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
            noise = NoiseState(NoiseMode.AMBIENT, 5, false, 1)))
        val actions = mutableListOf<ControlAction>()
        compose.setContent { SoundDeckTheme("light", false) {
            SoundDeckScreen(state, true, {}, {}, { actions.add(it) }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("ambient_level").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        assertEquals(ControlAction.AmbientLevel(15), actions.single())
        compose.runOnIdle { state = state.copy(pending = "等待耳機確認…") }
        compose.onNodeWithTag("ambient_level").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(pending = null, message = "耳機回報與要求不同") }
        compose.onNodeWithText("5 / 20").assertExists()
        compose.onNodeWithText("15 / 20").assertDoesNotExist()
        compose.onNodeWithTag("ambient_level").assertIsEnabled()
    }

    @Test fun longEqMenuCanReachLastDeclaredPresetAndSoundSwitchesDispatch() {
        val actions=mutableListOf<ControlAction>()
        val presets=listOf(0,0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0xa0,0xa1,0xa2).map { EqPreset(it,"") }
        val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x50,0xe2,0xfc),
            eq=SonyEvent.Equalizer(0xa1,List(6){10}),eqAvailable=true,eqCapability=SonyEvent.EqCapability(6,21,presets),
            dsee=false,dseeAvailable=true,speak=false,speakPreview=0)
        compose.setContent { SoundDeckTheme {
            SoundDeckScreen(state,true,{},{},{actions.add(it)},{},{},{},{},{})
        } }
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("eq_presets").performScrollTo().performClick()
        compose.onNodeWithText(EqPresetNames.label(0xa2)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(ControlAction.Equalizer(0xa2),actions.last())
        compose.onNodeWithTag("switch_DSEE").performScrollTo()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("switch_DSEE"))).performClick()
        assertEquals(ControlAction.Dsee(true),actions.last())
        compose.onNodeWithTag("switch_Speak-to-Chat").performScrollTo()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("switch_Speak-to-Chat"))).performClick()
        assertEquals(ControlAction.Speak(true),actions.last())
    }
}
