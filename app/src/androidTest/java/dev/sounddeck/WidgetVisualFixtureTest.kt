package dev.sounddeck

import android.content.res.Configuration
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.SoundDeckWidget
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Test
import java.io.File

/** Source fixture images only; never writes synthetic state into the repository or Launcher. */
class WidgetVisualFixtureTest {
    @Test fun renderLabeledSyntheticFixturesForLightDarkAndMinimumSizes() {
        val i=InstrumentationRegistry.getInstrumentation()
        val folderName=InstrumentationRegistry.getArguments().getString("fixtureFolder") ?: "validation-widget-spacing"
        require(folderName.matches(Regex("[a-zA-Z0-9-]+")))
        val folder=File(i.targetContext.getExternalFilesDir(null),folderName).apply{mkdirs()}
        folder.resolve("README.txt").writeText("SYNTHETIC layout fixtures, not headphone or Launcher control evidence.\n")
        val manager=AppWidgetManager.getInstance(i.targetContext)
        val hostReport=buildString {
            appendLine("ACTUAL Launcher widget options; no state is injected into Launcher.")
            manager.getAppWidgetIds(ComponentName(i.targetContext,SoundDeckWidget::class.java)).forEach { id ->
                val info=manager.getAppWidgetInfo(id)
                if(Build.VERSION.SDK_INT>=31) appendLine("id=$id targetCells=${info.targetCellWidth}x${info.targetCellHeight}")
                val options=manager.getAppWidgetOptions(id)
                appendLine("id=$id options=$options")
                @Suppress("DEPRECATION")
                if(Build.VERSION.SDK_INT>=31) appendLine("sizes=${options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)}")
            }
        }
        folder.resolve("actual-launcher-options.txt").writeText(hostReport)
        val metrics=StringBuilder("state,canvasWidthDp,canvasHeightDp,theme,id,xDp,yDp,widthDp,heightDp,visibleWidthDp,visibleHeightDp\n")
        i.runOnMainSync {
            for (mode in listOf("reference","battery","even","odd","disabled")) for (dark in listOf(false,true)) for ((width,height) in listOf(250 to 160,250 to 180,250 to 220,360 to 278,360 to 360)) {
                val config=Configuration(i.targetContext.resources.configuration).apply {
                    fontScale=if(width==250) 2f else 1f
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
                val c=i.targetContext.createConfigurationContext(config)
                val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x6b,0x50),
                    noiseSettled = true, noise=NoiseState(if(mode in listOf("reference","disabled")) NoiseMode.ANC else NoiseMode.AMBIENT,when(mode) { "reference" -> 20; "odd" -> 7; else -> 12 },mode=="odd",1),
                    left=BatteryReading(if(mode=="battery") 19 else 100,true),right=BatteryReading(if(mode=="battery") null else if(mode=="reference") 100 else 99,null),
                    case=BatteryReading(if(mode=="battery") 20 else if(mode=="reference") 53 else 63,false),eq=SonyEvent.Equalizer(0xa0,emptyList()),eqAvailable=true,
                    eqCapability=SonyEvent.EqCapability(6,21,listOf(EqPreset(0xa0,""))))
                val root=SoundDeckWidget.render(c,state,height>=220).apply(c,FrameLayout(c))
                val pxWidth=(width*c.resources.displayMetrics.density).toInt()
                val pxHeight=(height*c.resources.displayMetrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(pxWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(pxHeight,View.MeasureSpec.EXACTLY))
                root.layout(0,0,pxWidth,pxHeight)
                for (id in listOf(R.id.widget_title,R.id.widget_left_device,R.id.widget_case_device,R.id.widget_right_device,
                    R.id.widget_left_battery,R.id.widget_left,R.id.widget_case_battery,R.id.widget_case,R.id.widget_right_battery,R.id.widget_right,
                    R.id.widget_modes,R.id.widget_ambient,R.id.widget_levels,R.id.widget_track,R.id.widget_mark_2,R.id.widget_tick_2,
                    R.id.widget_mark_20,R.id.widget_tick_20,R.id.widget_eq_row,R.id.widget_eq_next)) {
                    val view=root.findViewById<View>(id)
                    val position=android.graphics.Rect(); view.getDrawingRect(position)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view,position)
                    val d=c.resources.displayMetrics.density
                    val visible=android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
                    if(view is android.widget.ImageView) {
                        visible.set(view.drawable.bounds)
                        view.imageMatrix.mapRect(visible)
                        visible.right=visible.left+visible.width()*view.scaleX
                        visible.bottom=visible.top+visible.height()*view.scaleY
                    } else if(view.background is android.graphics.drawable.InsetDrawable) {
                        view.background.setBounds(0,0,view.width,view.height)
                        visible.set((view.background as android.graphics.drawable.InsetDrawable).drawable!!.bounds)
                    }
                    metrics.appendLine("$mode,$width,$height,${if(dark) "dark" else "light"},${c.resources.getResourceEntryName(id)},${position.left/d},${position.top/d},${view.width/d},${view.height/d},${visible.width()/d},${visible.height()/d}")
                }
                val bitmap=Bitmap.createBitmap(pxWidth,pxHeight,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                folder.resolve("synthetic-widget-$mode-${width}x$height-${if(dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        folder.resolve("layout-metrics.csv").writeText(metrics.toString())
    }
}
