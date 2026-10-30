package dev.sounddeck.control

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import dev.sounddeck.R
import dev.sounddeck.data.HeadphoneState
import dev.sounddeck.protocol.*
import dev.sounddeck.repository

class SoundDeckWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = updateAll(context)
    companion object {
        val tickLevels = (2..20 step 2).toList()
        val tickIds = intArrayOf(R.id.widget_tick_2, R.id.widget_tick_4, R.id.widget_tick_6, R.id.widget_tick_8,
            R.id.widget_tick_10, R.id.widget_tick_12, R.id.widget_tick_14, R.id.widget_tick_16, R.id.widget_tick_18, R.id.widget_tick_20)
        private val tickMarkIds = intArrayOf(R.id.widget_mark_2, R.id.widget_mark_4, R.id.widget_mark_6, R.id.widget_mark_8,
            R.id.widget_mark_10, R.id.widget_mark_12, R.id.widget_mark_14, R.id.widget_mark_16, R.id.widget_mark_18, R.id.widget_mark_20)
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val state = context.repository.state.value
            manager.getAppWidgetIds(ComponentName(context, SoundDeckWidget::class.java)).forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    SizeF(250f,180f) to render(context,state,false),
                    SizeF(250f,220f) to render(context,state,true),
                )) else RemoteViews(
                    render(context,state,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 220),
                    render(context,state,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) >= 220))
                manager.updateAppWidget(id,views)
            }
        }
        /** All sizes contain all controls; render input never changes repository state. */
        fun render(context: Context, state: HeadphoneState, expanded: Boolean,
            commandIntent: (String, Int) -> PendingIntent = { action, level -> ControlService.pending(context,action,level) }): RemoteViews {
            val view = RemoteViews(context.packageName, if (expanded) R.layout.sounddeck_widget_expanded else R.layout.sounddeck_widget)
            view.setTextViewText(R.id.widget_status, if (state.pending != null) "等待確認"
                else if (state.ready && state.noiseSettled == false) "切換中" else state.connection.label)
            fun battery(textId: Int, iconId: Int, name: String, reading: BatteryReading?) {
                // Only verified, current-session values may be presented as a battery reading.
                val percent = reading?.percent?.takeIf { state.ready && it in 0..100 }
                val value = percent?.let { "$it%" } ?: "--%"
                val color = context.getColor(when {
                    percent == null -> R.color.widget_battery_unknown
                    percent < 20 -> R.color.widget_battery_low
                    else -> R.color.widget_text
                })
                view.setTextViewText(textId,value)
                view.setTextColor(textId,color)
                view.setContentDescription(textId,"$name $value")
                view.setInt(iconId,"setImageLevel",(percent ?: 0) * 100)
                view.setInt(iconId,"setColorFilter",color)
            }
            battery(R.id.widget_left,R.id.widget_left_battery,"左耳",state.left)
            battery(R.id.widget_case,R.id.widget_case_battery,"充電盒",state.case)
            battery(R.id.widget_right,R.id.widget_right_battery,"右耳",state.right)
            // Only these small targets open the App. No clickable root/elevation on the whole Widget.
            view.setOnClickPendingIntent(R.id.widget_title,ControlService.openApp(context))
            view.setOnClickPendingIntent(R.id.widget_status,ControlService.openApp(context))
            fun textColor(id: Int, resource: Int) {
                // Resolve in the host's current theme when cached RemoteViews are re-applied.
                if (Build.VERSION.SDK_INT >= 31) view.setColor(id,"setTextColor",resource)
                else view.setTextColor(id,context.getColor(resource))
            }
            fun control(id: Int, action: String, enabled: Boolean, level: Int = -1) {
                view.setOnClickPendingIntent(id,commandIntent(action,level))
                view.setBoolean(id,"setEnabled",enabled)
                textColor(id,if (enabled) R.color.widget_text else R.color.widget_disabled)
            }
            listOf(Triple(R.id.widget_anc,NoiseMode.ANC,ControlService.ANC),
                Triple(R.id.widget_ambient,NoiseMode.AMBIENT,ControlService.AMBIENT),
                Triple(R.id.widget_off,NoiseMode.OFF,ControlService.OFF)).forEach { (id,mode,action) ->
                control(id,action,state.canControlNoise)
                val selected = state.ready && state.confirmedNoise?.mode == mode
                view.setInt(id,"setBackgroundResource",if (selected) R.drawable.widget_button_selected else android.R.color.transparent)
                view.setTextViewText(id, mode.label)
                if (selected) textColor(id,R.color.widget_on_selected)
                view.setContentDescription(id,mode.label + (if (selected) "，目前模式" else "") + (if (!state.canControlNoise) "，暫不可操作" else ""))
            }
            val level = state.noise?.level?.takeIf { it in 1..20 }
            val ambient = state.canControlNoise && state.noise?.mode == NoiseMode.AMBIENT
            // The track ends at the centre of stop 20 (95% of the row).
            // (level - 1) / 19 aligns every even stop and retains odd intermediate positions.
            view.setProgressBar(R.id.widget_track,19,(level ?: 1)-1,false)
            view.setFloat(R.id.widget_track,"setAlpha",if (ambient) 1f else .35f)
            tickIds.forEachIndexed { index,id ->
                val target = tickLevels[index]
                val selected = level == target
                control(id,ControlService.LEVEL_SET,ambient,target)
                view.setTextViewText(id,"$target")
                view.setImageViewResource(tickMarkIds[index],
                    if (selected) R.drawable.widget_tick_selected else R.drawable.widget_tick_mark)
                view.setFloat(tickMarkIds[index],"setAlpha",if (ambient) 1f else .35f)
                if (ambient && selected) textColor(id,R.color.widget_selected)
                view.setContentDescription(id,"環境聲音強度 $target" + (if (selected) "，目前強度" else "") + (if (!ambient) "，暫不可操作" else ""))
            }
            val current = state.eq?.let { eq -> state.eqCapability?.presets?.firstOrNull { it.id == eq.preset }?.displayName ?: EqPresetNames.label(eq.preset) }
            view.setTextViewText(R.id.widget_eq,current ?: "—")
            view.setContentDescription(R.id.widget_eq,"目前 EQ：${current ?: "尚未回報"}")
            val eqEnabled = state.canControlEq && !state.eqCapability?.presets.isNullOrEmpty()
            listOf(R.id.widget_eq_previous to ControlService.EQ_PREVIOUS, R.id.widget_eq_next to ControlService.EQ_NEXT).forEach { (id, action) ->
                control(id,action,eqEnabled)
                view.setInt(id,"setBackgroundResource",android.R.color.transparent)
                view.setContentDescription(id,(if (id == R.id.widget_eq_previous) "上一個 EQ 預設" else "下一個 EQ 預設") + (if (!eqEnabled) "，暫不可操作" else ""))
            }
            return view
        }
    }
}
