package io.github.bornaradusin.wallpaperchanger.weather

import android.content.Context
import io.github.bornaradusin.wallpaperchanger.ui.lastLocation
import android.location.LocationManager
import android.os.CancellationSignal
import io.github.bornaradusin.wallpaperchanger.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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

    /** Why the last fetch failed, or null once one works: shown in the settings. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** A sky picked in the settings to see its effect, in place of the real weather; null: the real one. */
    val preview = MutableStateFlow<Sky?>(null)

    /** What the effects show: the preview while one is picked, else the weather now. */
    fun shown(now: Long = System.currentTimeMillis()): WeatherNow? = preview.value?.let { sky ->
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val day = sky != Sky.CLEAR || hour in 7..18
        WeatherNow(sky, if (sky == Sky.THUNDER) 0.9f else 0.75f, 0.8f, 0.3f, day, 0L, 0L, "", now)
    } ?: _now.value

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
    suspend fun refreshIfStale(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!loaded) {
                loaded = true
                _now.value = runCatching { decode(JSONObject(prefs.getString("now", null) ?: return@runCatching null)) }.getOrNull()
            }
            val now = System.currentTimeMillis()
            val last = _now.value?.fetchedAt ?: 0L
            val tried = prefs.getLong("tried", 0L)
            if (!force && (now - last < REFRESH_MS || now - tried < RETRY_MS)) return@withLock
            prefs.edit().putLong("tried", now).apply()
            val where = position(context)
            if (where == null) {
                _problem.value = "Couldn't tell where you are ($whereProblem)"
                return@withLock
            }
            val (lat, lon, place) = where
            val fresh = runCatching { fetch(lat, lon, place) }
                .onFailure { _problem.value = "Couldn't get the weather: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull() ?: return@withLock
            _problem.value = null
            _now.value = fresh
            prefs.edit().putString("now", encode(fresh).toString()).apply()
        }
    }

    /** Why finding the position failed, for the settings' message. */
    private var whereProblem = ""

    /**
     * Where the phone is: Android's last known position; else, with location allowed, one quick
     * network fix (Wi-Fi and mobile masts, no GPS); else the internet connection's (kept for hours).
     */
    private suspend fun position(context: Context): Triple<Double, Double, String>? {
        val problems = mutableListOf<String>()
        lastLocation(context)?.let { (lat, lon) -> return Triple(lat, lon, "") }
        networkFix(context)?.let { (lat, lon) -> return Triple(lat, lon, "") }
        if (Schedule.hasLocation(context)) problems += "Android has no location yet"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("ipAt", 0L) < IP_PLACE_MS && prefs.contains("ipLat")) {
            return Triple(prefs.getFloat("ipLat", 0f).toDouble(), prefs.getFloat("ipLon", 0f).toDouble(), prefs.getString("ipPlace", "")!!)
        }
        // Three free lookups by the internet connection, in turn, in case one is down or busy
        val lookups = listOf<Pair<String, (JSONObject) -> Triple<Double, Double, String>?>>(
            "https://ipwho.is/?fields=success,latitude,longitude,city" to { o ->
                if (o.optBoolean("success")) Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city")) else null
            },
            "https://get.geojs.io/v1/ip/geo.json" to { o ->
                Triple(o.getString("latitude").toDouble(), o.getString("longitude").toDouble(), o.optString("city"))
            },
            "https://ipapi.co/json/" to { o ->
                if (o.has("latitude")) Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city")) else null
            },
        )
        for ((url, read) in lookups) {
            val found = runCatching { read(get(url)) }
                .onFailure { problems += "${url.substringAfter("://").substringBefore('/')}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull() ?: continue
            prefs.edit().putLong("ipAt", System.currentTimeMillis()).putFloat("ipLat", found.first.toFloat())
                .putFloat("ipLon", found.second.toFloat()).putString("ipPlace", found.third).apply()
            return found
        }
        whereProblem = problems.joinToString("; ")
        return null
    }

    /** One network position from Android (Wi-Fi and masts, never GPS), waiting at most 10 s. */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun networkFix(context: Context): Pair<Double, Double>? {
        if (!Schedule.hasLocation(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private fun fetch(lat: Double, lon: Double, place: String): WeatherNow {
        val url = String.format(
            java.util.Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=weather_code,is_day,cloud_cover,wind_speed_10m,wind_direction_10m,precipitation,rain,showers,snowfall" +
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
        // The code can say only "overcast" while it rains (a light shower between the model's
        // categories): what is actually falling right now wins.
        val coded = skyOf(code)
        val snowCm = cur.optDouble("snowfall", 0.0)                       // cm in the last 15 min
        val wet = maxOf(cur.optDouble("precipitation", 0.0), cur.optDouble("rain", 0.0) + cur.optDouble("showers", 0.0)) // mm
        val (sky, intensity) = when {
            coded.first == Sky.RAIN || coded.first == Sky.SNOW || coded.first == Sky.THUNDER ->
                coded.first to maxOf(coded.second, if (coded.first == Sky.SNOW) 0f else amount(wet))
            snowCm > 0.0 -> Sky.SNOW to (0.35f + (snowCm / 1.0).toFloat()).coerceAtMost(1f)
            wet > 0.0 -> Sky.RAIN to amount(wet)
            else -> coded
        }
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

    /** How hard it rains from mm in the last 15 minutes (light below 2.5 mm an hour, heavy above 7.6). */
    private fun amount(mm: Double): Float = when {
        mm <= 0.0 -> 0.4f
        mm < 0.15 -> 0.4f
        mm < 0.6 -> 0.6f
        mm < 1.9 -> 0.8f
        else -> 1f
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
