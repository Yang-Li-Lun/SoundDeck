package dev.sounddeck

import android.app.PendingIntent
import android.content.*
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.*
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WidgetClickTest {
    @Test fun everyRenderedTickAndModeDeliversTheCorrectValidatedShortcut() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val c=instrument.targetContext
        val received=LinkedBlockingQueue<Intent>()
        val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){received.offer(intent)}}
        val action="dev.sounddeck.TEST_WIDGET_CLICK"
        ContextCompat.registerReceiver(c,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
        val scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        try {
            var root:View?=null
            instrument.runOnMainSync {
                val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b,0x50),
                    noise=NoiseState(NoiseMode.AMBIENT,12,false,1),eq=SonyEvent.Equalizer(0,emptyList()),eqAvailable=true,
                    eqCapability=SonyEvent.EqCapability(6,21,listOf(EqPreset(0,""),EqPreset(0x10,""))))
                root=SoundDeckWidget.render(c,state,true) { command,level ->
                    PendingIntent.getBroadcast(c,31*command.hashCode()+level,Intent(action).setPackage(c.packageName)
                        .putExtra("command",command).putExtra("level",level),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }.apply(c,FrameLayout(c))
            }
            scenario.onActivity { activity -> activity.setContentView(root!!,
                android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,(360*c.resources.displayMetrics.density).toInt())) }
            instrument.waitForIdleSync()
            val device=androidx.test.uiautomator.UiDevice.getInstance(instrument)
            val cases=listOf(R.id.widget_anc to ControlAction.Noise(NoiseMode.ANC),R.id.widget_ambient to ControlAction.Noise(NoiseMode.AMBIENT),
                R.id.widget_off to ControlAction.Noise(NoiseMode.OFF),
                R.id.widget_eq_next to ControlAction.NextEqualizer, R.id.widget_eq_previous to ControlAction.PreviousEqualizer)+
                SoundDeckWidget.tickIds.mapIndexed { i,id -> id to ControlAction.AmbientLevel(SoundDeckWidget.tickLevels[i]) }
            for ((id,expected) in cases) {
              // Exercise the transparent top/bottom of mode and EQ targets as well.
              val edgeTarget=id in listOf(R.id.widget_anc,R.id.widget_ambient,R.id.widget_off,R.id.widget_eq_next,R.id.widget_eq_previous)
              for (fraction in if(edgeTarget) listOf(0.02f,0.5f,0.98f) else listOf(0.5f)) {
                val xy=IntArray(2)
                instrument.runOnMainSync {
                    val child=root!!.findViewById<View>(id)
                    assertTrue(child.isEnabled)
                    child.getLocationOnScreen(xy);xy[0]+=child.width/2;xy[1]+=(child.height*fraction).toInt()
                }
                assertTrue(device.click(xy[0],xy[1]))
                val intent=received.poll(3,TimeUnit.SECONDS) ?: error("No PendingIntent for $id")
                assertEquals(expected,ShortcutActions.resolve(intent.getStringExtra("command"),intent.getIntExtra("level",-1)))
              }
            }
            assertNull(ShortcutActions.resolve(ControlService.LEVEL_SET,0))
            assertNull(ShortcutActions.resolve(ControlService.LEVEL_SET,21))
            assertNull(ShortcutActions.resolve("unknown",10))
        } finally { scenario.close(); c.unregisterReceiver(receiver) }
    }
}
