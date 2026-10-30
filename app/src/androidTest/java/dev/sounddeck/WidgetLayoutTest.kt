package dev.sounddeck

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.SoundDeckWidget
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutTest {
    private fun View.findViewsWithTextCount(value: String): Int {
        val matches=ArrayList<View>()
        findViewsWithText(matches,value,View.FIND_VIEWS_WITH_TEXT)
        return matches.size
    }

    @Test fun compactAndExpandedRemoteViewsFitAndShowAllThreeBatteriesAndModes() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b,0x50),
            noiseSettled = true, noise = NoiseState(NoiseMode.AMBIENT, 12, false, 1), left = BatteryReading(100,true), right = BatteryReading(0,false),
            case = BatteryReading(null,true), eq = SonyEvent.Equalizer(0x10,emptyList()), eqAvailable = true,
            eqCapability = SonyEvent.EqCapability(6,21,listOf(EqPreset(0x10,""))))
        instrument.runOnMainSync {
            for (dpi in listOf(160,420,640)) for (dark in listOf(false,true)) for (scale in listOf(1f,1.5f,2f)) for ((large,dpHeight) in listOf(false to 160,false to 180,true to 220,true to 278,true to 360)) {
                val base = instrument.targetContext
                val config = Configuration(base.resources.configuration).apply {
                    fontScale=scale
                    densityDpi=dpi
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
                val context = base.createConfigurationContext(config)
                val density = context.resources.displayMetrics.density
                val width = (250*density).toInt()
                val height = (dpHeight*density).toInt()
                val root = SoundDeckWidget.render(context,state,large).apply(context,FrameLayout(context))
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                root.layout(0,0,width,height)
                fun checkVisible(id: Int) {
                    val child = root.findViewById<TextView>(id)
                    val rect = android.graphics.Rect(); child.getDrawingRect(rect)
                    (root as ViewGroup).offsetDescendantRectToMyCoords(child,rect)
                    assertTrue("id=$id scale=$scale large=$large bottom=${rect.bottom} height=$height", rect.top >= 0 && rect.left >= 0 && rect.bottom <= height && rect.right <= width)
                    assertTrue(child.isEnabled)
                    val layout = child.layout
                    assertNotNull(layout)
                    assertTrue("text must not be ellipsized", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                    assertEquals("all text must be laid out", child.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("text height name=${context.resources.getResourceEntryName(id)} scale=$scale large=$large layout=${layout.height} available=${child.height-child.compoundPaddingTop-child.compoundPaddingBottom} textSize=${child.textSize}", layout.height <= child.height-child.compoundPaddingTop-child.compoundPaddingBottom)
                }
                listOf(R.id.widget_title,R.id.widget_status,R.id.widget_anc,R.id.widget_ambient,R.id.widget_off,R.id.widget_left,R.id.widget_right,R.id.widget_case).forEach(::checkVisible)
                assertTrue(root.findViewById<TextView>(R.id.widget_left).text.contains("100%"))
                assertTrue(root.findViewById<TextView>(R.id.widget_right).text.contains("0%"))
                assertEquals("--%",root.findViewById<TextView>(R.id.widget_case).text.toString())
                (listOf(R.id.widget_eq,R.id.widget_eq_previous,R.id.widget_eq_next)+SoundDeckWidget.tickIds.toList()).forEach(::checkVisible)
                assertEquals("明亮",root.findViewById<TextView>(R.id.widget_eq).text.toString())
                assertEquals("100%",root.findViewById<TextView>(R.id.widget_left).text.toString())
                assertEquals("0%",root.findViewById<TextView>(R.id.widget_right).text.toString())
                val batteries=listOf(R.id.widget_left_group,R.id.widget_case_group,R.id.widget_right_group).map { root.findViewById<View>(it) }
                assertTrue(batteries.zipWithNext().all{it.first.right<=it.second.left})
                assertEquals(1, batteries.map { it.height }.distinct().size)
                for (group in batteries) {
                    val row=(group as ViewGroup).getChildAt(1) as ViewGroup
                    assertTrue("battery row fits its column",row.left>=0 && row.right<=group.width)
                    assertTrue("battery and percentage do not overlap",row.getChildAt(0).right<=row.getChildAt(1).left)
                    assertTrue("battery row is centered",kotlin.math.abs(row.left+row.right-group.width)<=1)
                }
                val batteryTexts=listOf(R.id.widget_left,R.id.widget_case,R.id.widget_right).map { root.findViewById<TextView>(it) }
                assertEquals("battery text baselines are aligned",1,batteryTexts.map { it.baseline }.distinct().size)
                val batteryTops=batteries.map { group ->
                    val row=(group as ViewGroup).getChildAt(1)
                    group.top+row.top
                }
                assertEquals("battery rows share one horizontal baseline",1,batteryTops.distinct().size)
                assertEquals("removed ambient summary is absent",0,root.findViewsWithTextCount("環境聲音 12 / 20"))
                assertEquals("removed voice summary is absent",0,root.findViewsWithTextCount("聚焦人聲"))
                // Launcher may supply less than the declared 180dp minimum in landscape.
                // In supported sizes, both control rows retain full 48dp hit targets.
                if (dpHeight >= 180) {
                    for (id in listOf(R.id.widget_anc,R.id.widget_ambient,R.id.widget_off,R.id.widget_eq_previous,R.id.widget_eq_next)) {
                        val control=root.findViewById<View>(id)
                        assertTrue("48dp target ${context.resources.getResourceEntryName(id)} at $dpHeight/$dpi: ${control.height/density}",
                            control.height >= (48*density).toInt())
                    }
                }
                val rows=(0 until (root as ViewGroup).childCount).map { root.getChildAt(it) }
                assertTrue("rows do not overlap at $dpHeight/$dpi",rows.zipWithNext().all { it.first.bottom <= it.second.top })
                val modes=root.findViewById<View>(R.id.widget_modes)
                val eq=root.findViewById<View>(R.id.widget_eq_row)
                // LinearLayout assigns residual integer pixels to later weighted rows.
                assertTrue("equal control proportions at $dpHeight/$dpi: ${modes.height},${eq.height}",
                    kotlin.math.abs(modes.height-eq.height)<=2)
                for (id in listOf(R.id.widget_left_device,R.id.widget_case_device,R.id.widget_right_device)) {
                    val icon=root.findViewById<android.widget.ImageView>(id)
                    assertTrue(icon.width>0 && icon.height>0)
                    assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,icon.scaleType)
                }
                for (id in listOf(R.id.widget_anc,R.id.widget_ambient,R.id.widget_off)) {
                    assertFalse(root.findViewById<TextView>(id).text.contains("✓"))
                }
                for (id in listOf(R.id.widget_eq_previous,R.id.widget_eq_next)) {
                    val arrow=root.findViewById<TextView>(id)
                    assertEquals(android.graphics.Color.TRANSPARENT,(arrow.background as android.graphics.drawable.ColorDrawable).color)
                    assertTrue(arrow.isClickable)
                }
                assertFalse(root.isClickable)
                assertNull(root.stateListAnimator)
                val modeIds=listOf(R.id.widget_anc,R.id.widget_ambient,R.id.widget_off)
                assertTrue(modeIds.map{root.findViewById<View>(it).left}.zipWithNext().all{it.first<it.second})
            }
        }
    }
    @Test fun pendingWidgetDisablesActionsAndDoesNotShowRequestedMode() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val context = instrument.targetContext
            val state = HeadphoneState(connection = ConnectionState.READY, functions = setOf(0x6b),
                noiseSettled = true, noise = NoiseState(NoiseMode.ANC,10,false,0), pending = "等待耳機確認…")
            val root = SoundDeckWidget.render(context,state,true).apply(context,FrameLayout(context))
            (listOf(R.id.widget_anc,R.id.widget_ambient,R.id.widget_off,R.id.widget_eq_previous,R.id.widget_eq_next)+SoundDeckWidget.tickIds.toList()).forEach {
                assertFalse(root.findViewById<View>(it).isEnabled)
            }
            assertEquals("等待確認",root.findViewById<TextView>(R.id.widget_status).text.toString())
        }
    }

    @Test fun ancDisablesEveryLevelAndCurrentValueIsNotARequestedValue() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val c=instrument.targetContext
            val s=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b),noiseSettled = true, noise=NoiseState(NoiseMode.ANC,7,false,0))
            val root=SoundDeckWidget.render(c,s,true).apply(c,FrameLayout(c))
            SoundDeckWidget.tickIds.forEach { assertFalse(root.findViewById<View>(it).isEnabled) }
            assertTrue(SoundDeckWidget.tickIds.none { root.findViewById<TextView>(it).contentDescription.contains("目前強度") })
            assertEquals(6,root.findViewById<android.widget.ProgressBar>(R.id.widget_track).progress)
        }
    }

    @Test fun refreshingReportedStateKeepsEveryElementInPlace() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val context=instrument.targetContext
            val density=context.resources.displayMetrics.density
            val ready=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b,0x50),
                noiseSettled = true, noise=NoiseState(NoiseMode.AMBIENT,20,false,1),left=BatteryReading(100,false),
                case=BatteryReading(53,false),right=BatteryReading(100,false),
                eq=SonyEvent.Equalizer(0xa0,emptyList()),eqAvailable=true,
                eqCapability=SonyEvent.EqCapability(6,21,listOf(EqPreset(0xa0,""),EqPreset(0x10,""))))
            for ((width,height) in listOf(250 to 160,250 to 180,250 to 220,360 to 278,360 to 360)) {
                val expanded=height>=220
                val root=SoundDeckWidget.render(context,ready,expanded).apply(context,FrameLayout(context))
                fun positions(): List<android.graphics.Rect> {
                    root.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((height*density).toInt(),View.MeasureSpec.EXACTLY))
                    root.layout(0,0,root.measuredWidth,root.measuredHeight)
                    val views=mutableListOf<View>()
                    fun collect(view: View) {
                        views+=view
                        if(view is ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index))
                    }
                    collect(root)
                    return views.map { view -> android.graphics.Rect().also {
                        view.getDrawingRect(it)
                        (root as ViewGroup).offsetDescendantRectToMyCoords(view,it)
                    } }
                }
                val expected=positions()
                for(state in listOf(ready.copy(left=BatteryReading(19,true),case=BatteryReading(null,null),right=BatteryReading(0,false)),
                    ready.copy(noiseSettled = true, noise=NoiseState(NoiseMode.ANC,7,true,0),eq=SonyEvent.Equalizer(0x10,emptyList()),pending="等待確認"),
                    HeadphoneState(),ready)) {
                    SoundDeckWidget.render(context,state,expanded).reapply(context,root)
                    assertEquals("refresh must preserve geometry at ${width}x$height",expected,positions())
                }
            }
        }
    }
}
