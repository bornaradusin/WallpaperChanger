package io.github.bornaradusin.wallpaperchanger.weather

import android.content.Context
import io.github.bornaradusin.wallpaperchanger.ui.lastLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sin

/** What the sky is doing, as the effects show it. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

/**
 * The weather where the phone is, for the effects over the wallpaper.
 *
 * [intensity] is how hard it rains or snows (0 … 1), [wind] how far the wind leans it across the
 * screen (-1 left … 1 right), [cloud] the cloud cover (0 … 1).
 */
data class WeatherNow(
    val sky: Sky,
    val intensity: Float,
    val cloud: Float,
    val wind: Float,
    val isDayAtFetch: Boolean,
    /** Today's sunrise and sunset (ms), when known: day and night follow the sun between fetches. */
    val sunrise: Long,
    val sunset: Long,
    val place: String,
    val fetchedAt: Long,
) {
    fun isDay(now: Long = System.currentTimeMillis()): Boolean =
        if (sunrise > 0 && sunset > sunrise && now - fetchedAt < DAY_MS) now in sunrise until sunset else isDayAtFetch

    private companion object { const val DAY_MS = 24 * 60 * 60 * 1000L }
}

/**
 * Fetches the weather from Open-Meteo (free, no key) for where the phone last was.
 *
 * Light on the battery: it never turns on GPS. The position is the one Android already has from
 * other apps ([lastLocation]); without location permission, or with none known, the approximate
 * one from the internet connection. It is fetched at most every [REFRESH_MS], and only when the
 * wallpaper comes into view, never in the background; the last one is kept across restarts.
 */
object WeatherFeed {

    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now.asStateFlow()

    private val lock = Mutex()
    private var loaded = false
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L
    private const val IP_PLACE_MS = 6 * 60 * 60 * 1000L
    private const val PREFS = "weather"

    /** Loads the kept weather (once), then fetches anew if it's older than [REFRESH_MS]. */
    suspend fun refreshIfStale(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!loaded) {
                loaded = true
                _now.value = runCatching { decode(JSONObject(prefs.getString("now", null) ?: return@runCatching null)) }.getOrNull()
            }
            val now = System.currentTimeMillis()
            val last = _now.value?.fetchedAt ?: 0L
            val tried = prefs.getLong("tried", 0L)
            if (now - last < REFRESH_MS || now - tried < RETRY_MS) return@withLock
            prefs.edit().putLong("tried", now).apply()
            val (lat, lon, place) = position(context) ?: return@withLock
            val fresh = runCatching { fetch(lat, lon, place) }.getOrNull() ?: return@withLock
            _now.value = fresh
            prefs.edit().putString("now", encode(fresh).toString()).apply()
        }
    }

    /** Where the phone is: Android's last known position, else the internet connection's (kept for hours). */
    private fun position(context: Context): Triple<Double, Double, String>? {
        lastLocation(context)?.let { (lat, lon) -> return Triple(lat, lon, "") }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("ipAt", 0L) < IP_PLACE_MS && prefs.contains("ipLat")) {
            return Triple(prefs.getFloat("ipLat", 0f).toDouble(), prefs.getFloat("ipLon", 0f).toDouble(), prefs.getString("ipPlace", "")!!)
        }
        return runCatching {
            val o = get("https://ipwho.is/?fields=success,latitude,longitude,city")
            if (!o.optBoolean("success")) return null
            val lat = o.getDouble("latitude")
            val lon = o.getDouble("longitude")
            val city = o.optString("city")
            prefs.edit().putLong("ipAt", System.currentTimeMillis()).putFloat("ipLat", lat.toFloat())
                .putFloat("ipLon", lon.toFloat()).putString("ipPlace", city).apply()
            Triple(lat, lon, city)
        }.getOrNull()
    }

    private fun fetch(lat: Double, lon: Double, place: String): WeatherNow {
        val url = String.format(
            java.util.Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=weather_code,is_day,cloud_cover,wind_speed_10m,wind_direction_10m" +
                "&daily=sunrise,sunset&timeformat=unixtime&timezone=auto&forecast_days=1",
            lat, lon,
        )
        val o = get(url)
        val cur = o.getJSONObject("current")
        val code = cur.getInt("weather_code")
        val speed = cur.optDouble("wind_speed_10m", 0.0)            // km/h
        val from = Math.toRadians(cur.optDouble("wind_direction_10m", 0.0))
        // The wind comes from [from]: it blows the other way, and its east-west part leans the rain
        val lean = (-sin(from) * min(speed / 40.0, 1.0)).toFloat()
        val daily = o.optJSONObject("daily")
        val rise = daily?.optJSONArray("sunrise")?.optLong(0) ?: 0L
        val set = daily?.optJSONArray("sunset")?.optLong(0) ?: 0L
        val (sky, intensity) = skyOf(code)
        return WeatherNow(
            sky = sky,
            intensity = intensity,
            cloud = (cur.optDouble("cloud_cover", 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            wind = lean.coerceIn(-1f, 1f),
            isDayAtFetch = cur.optInt("is_day", 1) == 1,
            sunrise = rise * 1000L,
            sunset = set * 1000L,
            place = place,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** WMO weather codes, as Open-Meteo gives them, and how hard it falls. */
    internal fun skyOf(code: Int): Pair<Sky, Float> = when (code) {
        0 -> Sky.CLEAR to 0f
        1, 2 -> Sky.PARTLY to 0f
        3 -> Sky.CLOUDY to 0f
        45, 48 -> Sky.FOG to 0f
        51, 53, 55, 56, 57 -> Sky.RAIN to 0.35f          // drizzle
        61, 66, 80 -> Sky.RAIN to 0.5f
        63, 81 -> Sky.RAIN to 0.75f
        65, 67, 82 -> Sky.RAIN to 1f
        77 -> Sky.SNOW to 0.3f                           // snow grains
        71, 85 -> Sky.SNOW to 0.45f
        73 -> Sky.SNOW to 0.7f
        75, 86 -> Sky.SNOW to 1f
        95, 96, 99 -> Sky.THUNDER to 0.9f
        else -> Sky.CLOUDY to 0f
    }

    private fun get(url: String): JSONObject =
        client.newCall(Request.Builder().url(url).header("User-Agent", "WallpaperChanger").build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            JSONObject(it.body!!.string())
        }

    private fun encode(w: WeatherNow) = JSONObject()
        .put("sky", w.sky.name).put("intensity", w.intensity.toDouble()).put("cloud", w.cloud.toDouble())
        .put("wind", w.wind.toDouble()).put("isDay", w.isDayAtFetch).put("sunrise", w.sunrise).put("sunset", w.sunset)
        .put("place", w.place).put("fetchedAt", w.fetchedAt)

    private fun decode(o: JSONObject) = WeatherNow(
        sky = Sky.valueOf(o.getString("sky")),
        intensity = o.getDouble("intensity").toFloat(),
        cloud = o.getDouble("cloud").toFloat(),
        wind = o.getDouble("wind").toFloat(),
        isDayAtFetch = o.getBoolean("isDay"),
        sunrise = o.getLong("sunrise"),
        sunset = o.getLong("sunset"),
        place = o.optString("place"),
        fetchedAt = o.getLong("fetchedAt"),
    )
}
