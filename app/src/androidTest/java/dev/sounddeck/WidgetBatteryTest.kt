package dev.sounddeck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.SoundDeckWidget
import dev.sounddeck.data.ConnectionState
import dev.sounddeck.data.HeadphoneState
import dev.sounddeck.protocol.BatteryReading
import dev.sounddeck.protocol.SonyProtocol
import org.junit.Assert.*
import org.junit.Test

class WidgetBatteryTest {
    @Test fun allPercentagesFillContinuouslyWithFixedOutlineAndStrictLowBatteryBoundary() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val c = i.targetContext
            val state = HeadphoneState(connection = ConnectionState.READY)
            val root = SoundDeckWidget.render(c,state,false).apply(c,FrameLayout(c))
            val icon = root.findViewById<ImageView>(R.id.widget_left_battery)
            val label = root.findViewById<TextView>(R.id.widget_left)
            var previousFill = -1
            val outlineAlphas = mutableListOf<Int>()
            // Large bounds let the pixel test distinguish each 1% ClipDrawable level.
            val bitmap = Bitmap.createBitmap(600,200,Bitmap.Config.ARGB_8888)
            for (percent in 0..100) {
                SoundDeckWidget.render(c,state.copy(left=BatteryReading(percent,false)),false).reapply(c,root)
                val expectedColor = c.getColor(if(percent<20) R.color.widget_battery_low else R.color.widget_text)
                assertEquals("$percent%",label.text.toString())
                assertEquals(expectedColor,label.currentTextColor)
                assertEquals(percent*100,icon.drawable.level)
                bitmap.eraseColor(Color.TRANSPARENT)
                icon.drawable.setBounds(0,0,600,200)
                val layers=icon.drawable as LayerDrawable
                layers.getDrawable(1).draw(Canvas(bitmap))
                val fill = (0 until 600).count { Color.alpha(bitmap.getPixel(it,100)) > 200 }
                if (percent==0) assertEquals(0,fill)
                else assertTrue("fill increases at $percent%",fill>previousFill)
                previousFill=fill
                if(percent>0) assertEquals(expectedColor,bitmap.getPixel(layers.getDrawable(1).bounds.left,100))
                // The frame is the same width and opacity at every level.
                bitmap.eraseColor(Color.TRANSPARENT)
                layers.getDrawable(0).draw(Canvas(bitmap))
                outlineAlphas += Color.alpha(bitmap.getPixel(300,13))
            }
            assertEquals(1,outlineAlphas.distinct().size)
            assertTrue(outlineAlphas.first()>0)
            for (percent in listOf(null,-1,101,255)) {
                SoundDeckWidget.render(c,state.copy(left=BatteryReading(percent,true)),false).reapply(c,root)
                assertEquals("--%",label.text.toString())
                assertEquals(0,icon.drawable.level)
                assertEquals(c.getColor(R.color.widget_battery_unknown),label.currentTextColor)
            }
            bitmap.recycle()
        }
    }

    @Test fun notificationsMapLeftCaseRightAndInvalidateWithoutRecreatingViews() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val c = i.targetContext
            var state = HeadphoneState(connection=ConnectionState.READY)
            fun receive(vararg bytes: Int) {
                state = state.receive(SonyProtocol.parse(ByteArray(bytes.size) { bytes[it].toByte() })!!,100)
            }
            val ids = listOf(R.id.widget_left,R.id.widget_case,R.id.widget_right)
            val icons = listOf(R.id.widget_left_battery,R.id.widget_case_battery,R.id.widget_right_battery)
            for (expanded in listOf(false,true)) {
                val root = SoundDeckWidget.render(c,state,expanded).apply(c,FrameLayout(c))
                fun check(values: List<Int?>) {
                    SoundDeckWidget.render(c,state,expanded).reapply(c,root)
                    values.forEachIndexed { n,p ->
                        val color=c.getColor(when { p==null -> R.color.widget_battery_unknown; p<20 -> R.color.widget_battery_low; else -> R.color.widget_text })
                        assertEquals(p?.let{"$it%"} ?: "--%",root.findViewById<TextView>(ids[n]).text.toString())
                        assertEquals(color,root.findViewById<TextView>(ids[n]).currentTextColor)
                        assertEquals((p ?: 0)*100,root.findViewById<ImageView>(icons[n]).drawable.level)
                    }
                }
                receive(0x25,9,19,0,100,0,20,20); check(listOf(19,null,100))
                receive(0x25,10,20,0,20); check(listOf(19,20,100))
                receive(0x25,9,0,0,255,255,20,20); check(listOf(0,20,null))
                receive(0x25,10,255,255,20); check(listOf(0,null,null))
                // A retained snapshot cannot expose numbers once control connectivity is lost.
                for (connection in listOf(ConnectionState.DISCONNECTED,ConnectionState.ERROR,ConnectionState.CONNECTING,ConnectionState.SYNCING)) {
                    state=state.copy(connection=connection,left=BatteryReading(88,false),case=BatteryReading(77,false),right=BatteryReading(66,false))
                    check(listOf(null,null,null))
                }
                // Reconnect starts without a cached reading; only the case has reported so far.
                state=HeadphoneState(connection=ConnectionState.READY)
                receive(0x23,10,50,0,20); check(listOf(null,50,null))
                state=HeadphoneState(connection=ConnectionState.READY)
            }
        }
    }
}
