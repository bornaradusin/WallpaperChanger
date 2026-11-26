package io.github.bornaradusin.wallpaperchanger.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import io.github.bornaradusin.wallpaperchanger.weather.WeatherEffects
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bornaradusin.wallpaperchanger.Schedule
import io.github.bornaradusin.wallpaperchanger.WeatherScreens
import io.github.bornaradusin.wallpaperchanger.weather.Sky
import io.github.bornaradusin.wallpaperchanger.weather.WeatherFeed

/**
 * Settings › Weather effects: where the weather shows over the wallpaper, what it is now, and the
 * location it goes by (the phone's last known one, never its own GPS fix, else an approximate one).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WeatherSettings(screens: WeatherScreens, onChange: (WeatherScreens) -> Unit) {
    val context = LocalContext.current
    val weather by WeatherFeed.now.collectAsState()
    var located by remember { mutableStateOf(Schedule.hasLocation(context)) }
    var always by remember { mutableStateOf(Schedule.hasBackgroundLocation(context)) }
    LifecycleResumeEffect(Unit) {
        located = Schedule.hasLocation(context)
        always = Schedule.hasBackgroundLocation(context)
        onPauseOrDispose { }
    }
    // Opening the settings fetches the weather if it's old, so the line below is current
    LaunchedEffect(screens) { if (screens != WeatherScreens.OFF) runCatching { WeatherFeed.refreshIfStale(context.applicationContext) } }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        located = Schedule.hasLocation(context)
    }

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val options = listOf(
            WeatherScreens.BOTH to "Both", WeatherScreens.HOME to "Home",
            WeatherScreens.LOCK to "Lock", WeatherScreens.OFF to "Off",
        )
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(screens == v, { onChange(v) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
    Text(
        when (screens) {
            WeatherScreens.BOTH -> "Rain, snow, fog, clouds, sun and stars over your wallpaper on the home and lock screens, as the weather is where you are"
            WeatherScreens.HOME -> "The weather over your wallpaper on the home screen only"
            WeatherScreens.LOCK -> "The weather over your wallpaper on the lock screen only"
            WeatherScreens.OFF -> "No weather over your wallpaper"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (screens == WeatherScreens.OFF) return

    val problem by WeatherFeed.problem.collectAsState()
    val preview by WeatherFeed.preview.collectAsState()
    val scope = rememberCoroutineScope()
    if (WeatherEffects.failed) Text("This phone's graphics couldn't draw the effects. Please report it on GitHub.", color = MaterialTheme.colorScheme.error)
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (weather == null && problem == null) Text("Getting the weather…", style = MaterialTheme.typography.bodyMedium)
    weather?.let { w ->
        val what = when (w.sky) {
            Sky.CLEAR -> if (w.isDay()) "Clear and sunny" else "Clear night, with stars"
            Sky.PARTLY -> "Partly cloudy"
            Sky.CLOUDY -> "Cloudy"
            Sky.FOG -> "Fog"
            Sky.RAIN -> if (w.intensity >= 0.75f) "Heavy rain" else if (w.intensity <= 0.35f) "Drizzle" else "Rain"
            Sky.SNOW -> if (w.intensity >= 0.75f) "Heavy snow" else "Snow"
            Sky.THUNDER -> "Thunderstorm"
        }
        val minutes = ((System.currentTimeMillis() - w.fetchedAt) / 60_000L).coerceAtLeast(0)
        val where = if (w.place.isNotBlank()) " in ${w.place}" else ""
        Text("Now: $what$where (updated ${if (minutes < 1) "just now" else "$minutes min ago"})", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton({ scope.launch { runCatching { WeatherFeed.refreshIfStale(context.applicationContext, force = true) } } }) { Text("Check the weather now") }

    val onWallpaper by WeatherFeed.onWallpaper.collectAsState()
    Text("Last time your wallpaper was on screen: ${onWallpaper ?: "not seen yet"}", style = MaterialTheme.typography.bodyMedium)

    // Test mode: one effect on the wallpaper whatever the weather, clearly on or off
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Test mode", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (preview == null) "Off: the wallpaper shows the real weather"
                else "ON: the wallpaper shows the test effect below, not the real weather. Turn off when done.",
                style = MaterialTheme.typography.bodySmall,
                color = if (preview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Switch(preview != null, { on -> WeatherFeed.preview.value = if (on) Sky.RAIN else null })
    }
    if (preview != null) {
        val tries = listOf(Sky.RAIN to "Rain", Sky.THUNDER to "Storm", Sky.SNOW to "Snow", Sky.FOG to "Fog", Sky.CLOUDY to "Clouds", Sky.CLEAR to "Sun / stars")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tries.forEach { (sky, label) -> FilterChip(preview == sky, { WeatherFeed.preview.value = sky }, label = { Text(label) }) }
        }
        Text("Go to the home screen to see it.", style = MaterialTheme.typography.bodySmall)
    }

    Text(
        "The weather comes from Open-Meteo for where the phone last was. It uses the location Android already has " +
            "from other apps and never turns on GPS itself, and checks at most every half hour, only while the wallpaper " +
            "is showing. The effects stop whenever the screen is off.",
        style = MaterialTheme.typography.bodySmall,
    )
    when {
        !located -> {
            Text("Without location, it goes by an approximate one from your internet connection.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Use my location") }
        }
        !always -> {
            Text(
                "To follow you while the phone is locked too, set this app's Location to \"Allow all the time\".",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton({ openAppSettings(context) }) { Text("Open location settings") }
        }
    }
}
