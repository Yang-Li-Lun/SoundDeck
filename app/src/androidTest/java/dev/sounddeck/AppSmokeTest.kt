package dev.sounddeck

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.TextView
import android.view.ViewGroup
import dev.sounddeck.control.ControlService
import dev.sounddeck.control.NoiseTileService
import dev.sounddeck.control.SoundDeckWidget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsWithoutBluetoothPermissionOrFakeData() {
        compose.onNodeWithText("SoundDeck").assertIsDisplayed()
        compose.onNodeWithText("尚未連線").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(3)
        compose.onNodeWithTag("mode_ANC").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ambient_level").assertIsNotEnabled()
    }
    @Test fun navigationAndSettingsAreUsableOffline() {
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithText("等化器").assertIsDisplayed()
        compose.onNodeWithText("等待耳機回報預設").assertExists()
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithText("通知欄控制").assertDoesNotExist()
        compose.onNodeWithText("連線與權限").assertIsDisplayed()
        compose.onNodeWithTag("mode_ANC").assertDoesNotExist()
        compose.onNodeWithText("外觀").assertDoesNotExist()
        compose.onNodeWithText("快捷操作").assertDoesNotExist()
        compose.onNodeWithTag("licenses").performScrollTo().assertIsDisplayed()

    }
    @Test fun widgetProviderAndProtectedTileAreRegistered() {
        val context = compose.activity
        assertNotNull(AppWidgetManager.getInstance(context).installedProviders.firstOrNull {
            it.provider == ComponentName(context, SoundDeckWidget::class.java) })
        @Suppress("DEPRECATION")
        val tile = context.packageManager.getServiceInfo(ComponentName(context, NoiseTileService::class.java), 0)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, ControlService::class.java), 0)
        assertFalse(service.exported)
        compose.runOnUiThread { SoundDeckWidget.updateAll(context); NoiseTileService.refresh(context) }
    }
    @Test fun coldShortcutCannotSetStateOrStartFakeSession() {
        compose.runOnUiThread { ControlService.start(compose.activity, ControlService.ANC) }
        compose.waitForIdle()
        assertFalse(compose.activity.repository.state.value.ready)
        assertNull(compose.activity.repository.state.value.noise)
        assertNull(compose.activity.repository.state.value.left)
    }
    @Test fun realRemoteViewsWidgetBindsRendersAndOpensAppOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        val manager = AppWidgetManager.getInstance(activity)
        val host = AppWidgetHost(activity, 9001)
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(activity, SoundDeckWidget::class.java)))
            compose.runOnUiThread {
                host.startListening()
                SoundDeckWidget.updateAll(activity)
            }
            instrumentation.waitForIdleSync()
            compose.runOnUiThread {
                val widget = host.createView(activity, id, manager.getAppWidgetInfo(id))
                activity.setContentView(widget, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
                assertEquals("SoundDeck", widget.findViewById<TextView>(R.id.widget_title).text.toString())
                assertEquals("降噪", widget.findViewById<TextView>(R.id.widget_anc).text.toString())
                assertFalse(widget.findViewById<TextView>(R.id.widget_anc).isEnabled)
                assertTrue(widget.findViewById<TextView>(R.id.widget_status).text.contains("尚未連線"))
                assertTrue(widget.findViewById<TextView>(R.id.widget_title).performClick())
            }
            instrumentation.waitForIdleSync()
            assertFalse(activity.repository.state.value.ready)
        } finally {
            host.stopListening(); host.deleteAppWidgetId(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
