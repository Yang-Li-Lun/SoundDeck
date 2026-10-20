package dev.sounddeck

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.SoundDeckWidget
import dev.sounddeck.data.ConnectionState
import dev.sounddeck.data.HeadphoneState
import dev.sounddeck.protocol.BatteryReading
import org.junit.Assert.*
import org.junit.Test

/** Only the two temporary test-host IDs receive synthetic values; no repository state is changed. */
class WidgetHostUpdateTest {
    @Test fun twoExistingHostsReceiveBatteryChangesAndUpdateAllClearsDisconnectedReadings() {
        val i=InstrumentationRegistry.getInstrumentation()
        val c=i.targetContext
        val manager=AppWidgetManager.getInstance(c)
        val host=AppWidgetHost(c,9002)
        val ids=List(2) { host.allocateAppWidgetId() }
        val widgets=mutableListOf<android.appwidget.AppWidgetHostView>()
        i.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            ids.forEach { assertTrue(manager.bindAppWidgetIdIfAllowed(it,ComponentName(c,SoundDeckWidget::class.java))) }
            scenario.onActivity { activity ->
                host.startListening()
                val container=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                ids.forEach { id ->
                    val widget=host.createView(activity,id,manager.getAppWidgetInfo(id))
                    widgets+=widget
                    container.addView(widget,LinearLayout.LayoutParams(-1,(220*c.resources.displayMetrics.density).toInt()))
                }
                activity.setContentView(container)
            }
            fun awaitValue(expected: String, level: Int) {
                var matches=false
                repeat(50) {
                    i.waitForIdleSync()
                    i.runOnMainSync {
                        matches=widgets.all { it.findViewById<TextView>(R.id.widget_left)?.text?.toString()==expected }
                    }
                    if(matches) {
                        i.runOnMainSync {
                            widgets.forEach { assertEquals(level,it.findViewById<ImageView>(R.id.widget_left_battery).drawable.level) }
                        }
                        return
                    }
                    Thread.sleep(20)
                }
                val actual=widgets.map { it.findViewById<TextView>(R.id.widget_left)?.text?.toString() }
                fail("Both existing hosts must show $expected; actual=$actual")
            }
            // Finish initial layout/host size selection and provider broadcasts before fixture updates.
            i.waitForIdleSync()
            androidx.test.uiautomator.UiDevice.getInstance(i).waitForIdle()
            awaitValue("--%",0)
            for(percent in listOf(100,19,20,0)) {
                val state=HeadphoneState(connection=ConnectionState.READY,left=BatteryReading(percent,false))
                ids.forEach { manager.updateAppWidget(it,SoundDeckWidget.render(c,state,true)) }
                awaitValue("$percent%",percent*100)
            }
            assertEquals(ConnectionState.DISCONNECTED,c.repository.state.value.connection)
            SoundDeckWidget.updateAll(c)
            awaitValue("--%",0)
        } finally {
            host.stopListening()
            ids.forEach(host::deleteAppWidgetId)
            scenario.close()
            i.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
