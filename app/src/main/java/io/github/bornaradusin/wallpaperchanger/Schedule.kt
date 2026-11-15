package io.github.bornaradusin.wallpaperchanger

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import java.util.Calendar

/**
 * The timed changes: every X minutes, the time rules, and the place rules (Android tells the app
 * when the phone arrives). Set up again whenever they change and after the phone restarts.
 */
object Schedule {

    const val ACTION_NEXT = "io.github.bornaradusin.wallpaperchanger.NEXT"
    const val ACTION_NEXT_ALBUM = "io.github.bornaradusin.wallpaperchanger.NEXT_ALBUM"
    const val ACTION_PAUSE = "io.github.bornaradusin.wallpaperchanger.PAUSE"
    const val ACTION_INTERVAL = "io.github.bornaradusin.wallpaperchanger.INTERVAL"
    const val ACTION_RULE = "io.github.bornaradusin.wallpaperchanger.RULE"
    const val ACTION_PLACE = "io.github.bornaradusin.wallpaperchanger.PLACE"
    const val EXTRA_RULE = "rule"

    fun update(context: Context, data: Data) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

        // Every X minutes. Not waking the phone: changing a wallpaper no one can see is pointless,
        // so it happens the next time the phone is in use.
        val interval = intent(context, ACTION_INTERVAL, 0, null)
        alarms.cancel(interval)
        if (data.settings.interval > 0 && !data.settings.paused) {
            val ms = data.settings.interval * 60_000L
            alarms.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + ms, ms, interval)
        }

        // Time rules: one alarm each, for the next time it comes round
        val oldTimes = prefs.getStringSet("times", emptySet())!!
        oldTimes.forEach { alarms.cancel(intent(context, ACTION_RULE, it.hashCode(), it)) }
        val times = data.rules.filter { it.enabled && it.trigger is Trigger.Time }
        times.forEach { rule ->
            val at = nextTime(rule.trigger as Trigger.Time) ?: return@forEach
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, intent(context, ACTION_RULE, rule.id.hashCode(), rule.id))
        }

        // Place rules: Android watches for the phone arriving
        val location = context.getSystemService(LocationManager::class.java)
        val oldPlaces = prefs.getStringSet("places", emptySet())!!
        oldPlaces.forEach { runCatching { location.removeProximityAlert(intent(context, ACTION_PLACE, it.hashCode(), it, mutable = true)) } }
        val places = data.rules.filter { it.enabled && it.trigger is Trigger.Place }
        val placed = HashSet<String>()
        if (hasLocation(context)) {
            places.forEach { rule ->
                val p = rule.trigger as Trigger.Place
                runCatching {
                    addProximity(location, p, intent(context, ACTION_PLACE, rule.id.hashCode(), rule.id, mutable = true))
                    placed.add(rule.id)
                }
            }
        }

        prefs.edit()
            .putStringSet("times", times.map { it.id }.toSet())
            .putStringSet("places", placed)
            .apply()
    }

    @SuppressLint("MissingPermission")
    private fun addProximity(location: LocationManager, p: Trigger.Place, pending: PendingIntent) {
        @Suppress("DEPRECATION")
        location.addProximityAlert(p.lat, p.lon, p.radius, -1, pending)
    }

    fun hasLocation(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** When a time rule next comes round, or null if it has no days. */
    fun nextTime(t: Trigger.Time, from: Long = System.currentTimeMillis()): Long? {
        if (t.days and Trigger.ALL_DAYS == 0) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, t.hour)
            set(Calendar.MINUTE, t.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (c.timeInMillis > from && t.days and (1 shl dayIndex(c)) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** Monday is 0 … Sunday is 6. */
    private fun dayIndex(c: Calendar) = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun broadcast(context: Context, action: String): PendingIntent = intent(context, action, 0, null)

    private fun intent(context: Context, action: String, code: Int, rule: String?, mutable: Boolean = false): PendingIntent {
        val i = Intent(context, Triggers::class.java).setAction(action)
        if (rule != null) i.putExtra(EXTRA_RULE, rule)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, code, i, flags)
    }
}

/** Where the timed changes, the widget, the place rules and a restart of the phone arrive. */
class Triggers : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val library = context.app.library
        val data = library.data.value
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Schedule.update(context, data)
            Schedule.ACTION_NEXT -> library.next()
            Schedule.ACTION_NEXT_ALBUM -> library.nextAlbum()
            Schedule.ACTION_PAUSE -> {
                library.togglePaused()
                Toast.makeText(context, if (library.data.value.settings.paused) "Rotation paused" else "Rotation resumed", Toast.LENGTH_SHORT).show()
                WidgetUpdates.refresh(context)
            }
            Schedule.ACTION_INTERVAL -> if (!data.settings.paused) library.next()
            Schedule.ACTION_RULE -> {
                data.rules.firstOrNull { it.id == intent.getStringExtra(Schedule.EXTRA_RULE) }?.let { library.run(it) }
                Schedule.update(context, library.data.value) // and the next time round
            }
            Schedule.ACTION_PLACE -> {
                if (intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) {
                    data.rules.firstOrNull { it.id == intent.getStringExtra(Schedule.EXTRA_RULE) }?.let { library.run(it) }
                }
            }
        }
    }
}
