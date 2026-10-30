package dev.sounddeck

import android.content.res.Configuration
import android.view.View
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.SoundDeckWidget
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test

class WidgetContrastTest {
    @Test fun referencePaletteHasReadableTextAndDistinctControlsInBothHostThemes() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            for (dark in listOf(false,true)) {
                val config=Configuration(i.targetContext.resources.configuration).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
                val c=i.targetContext.createConfigurationContext(config)
                for ((fg,bg) in listOf(R.color.widget_text to R.color.widget_bg,R.color.widget_text to R.color.widget_button,
                    R.color.widget_on_selected to R.color.widget_selected,R.color.widget_disabled to R.color.widget_bg,
                    R.color.widget_battery_low to R.color.widget_bg,R.color.widget_battery_unknown to R.color.widget_bg)) {
                    assertTrue("contrast >= 4.5 dark=$dark",ColorUtils.calculateContrast(c.getColor(fg),c.getColor(bg))>=4.5)
                }
                assertTrue(ColorUtils.calculateContrast(c.getColor(R.color.widget_selected),c.getColor(R.color.widget_button))>=3)
                val s=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b),noiseSettled = true, noise=NoiseState(NoiseMode.AMBIENT,7,false,1))
                val root=SoundDeckWidget.render(c,s,true).apply(c,FrameLayout(c))
                val d=c.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((360*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((360*d).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                assertEquals("環境聲音", root.findViewById<TextView>(R.id.widget_ambient).text.toString())
                assertEquals(6,root.findViewById<ProgressBar>(R.id.widget_track).progress)
                assertEquals(10,SoundDeckWidget.tickIds.size)
                SoundDeckWidget.tickIds.forEach {
                    val tick=root.findViewById<TextView>(it)
                    assertTrue(tick.width/d>=33f && tick.height/d>=47.9f)
                    assertFalse(tick.contentDescription.contains("目前強度"))
                }
                assertFalse(root.findViewById<TextView>(R.id.widget_eq).isClickable)
            }
        }
    }

    @Test fun cachedRemoteViewsResolveColorsInTheCurrentHostTheme() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            fun context(dark:Boolean)=i.targetContext.createConfigurationContext(Configuration(i.targetContext.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b,0x50),noiseSettled = true, noise=NoiseState(NoiseMode.AMBIENT,12,true,1),
                eq=SonyEvent.Equalizer(0,emptyList()),eqAvailable=true,eqCapability=SonyEvent.EqCapability(6,21,listOf(EqPreset(0,""))))
            for(dark in listOf(false,true)) {
                val host=context(dark)
                val cached=SoundDeckWidget.render(context(!dark),state,true)
                val root=cached.apply(host,FrameLayout(host))
                for(id in listOf(R.id.widget_ambient,R.id.widget_eq_previous,R.id.widget_eq_next)) {
                    assertEquals(host.getColor(if(id == R.id.widget_ambient) R.color.widget_on_selected else R.color.widget_text),root.findViewById<TextView>(id).currentTextColor)
                }
                assertEquals(host.getColor(R.color.widget_selected),root.findViewById<TextView>(R.id.widget_tick_12).currentTextColor)
            }
        }
    }
}
