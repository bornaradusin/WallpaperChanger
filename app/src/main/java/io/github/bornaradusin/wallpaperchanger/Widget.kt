package io.github.bornaradusin.wallpaperchanger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** What a widget does when tapped. */
enum class WidgetAction(val label: String) {
    NEXT("Next wallpaper"),
    NEXT_ALBUM("Next album"),
    SELECT("Select wallpaper"),
    OPEN("Open current image"),
    PAUSE("Pause/Resume rotation"),
}

data class WidgetLook(val action: WidgetAction, val text: String, val textColor: Int, val icon: Boolean)

object WidgetUpdates {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun look(context: Context, id: Int): WidgetLook {
        val p = prefs(context)
        val action = runCatching { WidgetAction.valueOf(p.getString("$id.action", null)!!) }.getOrDefault(WidgetAction.NEXT)
        return WidgetLook(
            action,
            p.getString("$id.text", null) ?: action.label,
            p.getInt("$id.color", 0xFFFFFFFF.toInt()),
            p.getBoolean("$id.icon", true),
        )
    }

    fun save(context: Context, id: Int, look: WidgetLook) {
        prefs(context).edit()
            .putString("$id.action", look.action.name)
            .putString("$id.text", look.text)
            .putInt("$id.color", look.textColor)
            .putBoolean("$id.icon", look.icon)
            .apply()
        draw(context, AppWidgetManager.getInstance(context), id)
    }

    fun forget(context: Context, ids: IntArray) {
        val e = prefs(context).edit()
        ids.forEach { id -> listOf("action", "text", "color", "icon").forEach { e.remove("$id.$it") } }
        e.apply()
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, Widget::class.java)).forEach { draw(context, manager, it) }
    }

    fun draw(context: Context, manager: AppWidgetManager, id: Int) {
        val look = look(context, id)
        val views = RemoteViews(context.packageName, R.layout.widget)
        val paused = context.app.library.data.value.settings.paused
        val text = if (look.action == WidgetAction.PAUSE && look.text == look.action.label) {
            if (paused) "Resume rotation" else "Pause rotation"
        } else {
            look.text
        }
        views.setTextViewText(R.id.widget_text, text)
        views.setViewVisibility(R.id.widget_text, if (text.isBlank()) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.widget_text, look.textColor)
        views.setViewVisibility(R.id.widget_icon, if (look.icon) View.VISIBLE else View.GONE)
        views.setImageViewResource(
            R.id.widget_icon,
            when (look.action) {
                WidgetAction.PAUSE -> if (paused) R.drawable.ic_shortcut_play else R.drawable.ic_shortcut_pause
                WidgetAction.NEXT_ALBUM -> R.drawable.ic_shortcut_albums
                else -> R.mipmap.ic_launcher
            },
        )
        views.setOnClickPendingIntent(R.id.widget_root, tap(context, look.action, id))
        manager.updateAppWidget(id, views)
    }

    private fun tap(context: Context, action: WidgetAction, id: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return when (action) {
            WidgetAction.NEXT -> Schedule.broadcast(context, Schedule.ACTION_NEXT)
            WidgetAction.NEXT_ALBUM -> Schedule.broadcast(context, Schedule.ACTION_NEXT_ALBUM)
            WidgetAction.PAUSE -> Schedule.broadcast(context, Schedule.ACTION_PAUSE)
            WidgetAction.SELECT -> PendingIntent.getActivity(context, id, Intent(context, SelectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            WidgetAction.OPEN -> PendingIntent.getActivity(context, id, Intent(context, OpenCurrentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        }
    }
}

class Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUpdates.draw(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) = WidgetUpdates.forget(context, ids)
}
