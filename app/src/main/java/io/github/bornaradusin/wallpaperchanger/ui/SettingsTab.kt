package io.github.bornaradusin.wallpaperchanger.ui

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bornaradusin.wallpaperchanger.AppUpdate
import io.github.bornaradusin.wallpaperchanger.BuildConfig
import io.github.bornaradusin.wallpaperchanger.Item
import io.github.bornaradusin.wallpaperchanger.Scaling
import io.github.bornaradusin.wallpaperchanger.VideoEnd
import io.github.bornaradusin.wallpaperchanger.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INTERVALS = listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "24 hours")
private val RESCANS = listOf(0 to "Off", 1 to "1 hour", 6 to "6 hours", 12 to "12 hours", 24 to "24 hours")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val library = context.app.library
    val data by library.data.collectAsStateWithLifecycle()
    val s = data.settings
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NotActiveBanner()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Section("When to change") {
                Toggle("Change wallpaper on lockscreen unlock", "The next one goes up while the screen is off, so it's on the lock screen when you wake the phone and nothing changes as you unlock", s.onUnlock) {
                    library.setSettings { c -> c.copy(onUnlock = it) }
                }
                Choice("Change wallpaper every", INTERVALS, s.interval) { library.setSettings { c -> c.copy(interval = it) } }
                Toggle("Change wallpaper when double-tapping on home screen", null, s.doubleTap) {
                    library.setSettings { c -> c.copy(doubleTap = it) }
                }
                Toggle("Pause rotation", "Nothing changes on its own until you turn this off. Next still works.", s.paused) {
                    library.setSettings { c -> c.copy(paused = it) }
                }
            }

            Section("Order") {
                Toggle("Random wallpaper order", "Nothing repeats until everything has been shown", s.random) {
                    library.setSettings { c -> c.copy(random = it) }
                }
                Toggle("Include subfolders", "Also use what is in folders inside the added folders", s.subfolders) {
                    library.setSettings { c -> c.copy(subfolders = it) }
                }
                Choice("Automatically rescan added folders for new images every", RESCANS, s.rescanHours) {
                    library.setSettings { c -> c.copy(rescanHours = it) }
                }
            }

            Section("Image position") {
                Text("How to handle images which don't fit the home screen", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(Scaling.FILL to "Fill", Scaling.FIT to "Fit", Scaling.STRETCH to "Stretch", Scaling.NONE to "None")
                    options.forEachIndexed { i, (v, label) ->
                        SegmentedButton(s.scaling == v, { library.setSettings { it.copy(scaling = v) } }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                    }
                }
                Text(
                    when (s.scaling) {
                        Scaling.FILL -> "Fills the screen; the edges that don't fit are cut off"
                        Scaling.FIT -> "All of it shows, with black bars"
                        Scaling.STRETCH -> "Stretched to the shape of the screen"
                        Scaling.NONE -> "No special handling: shown at its own size, in the middle"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Toggle("Scroll with the home screen", "Recommended: wide pictures move as you swipe between pages", s.scroll) {
                    library.setSettings { c -> c.copy(scroll = it) }
                }
                Toggle("Full resolution", "Loads pictures at their full size instead of the screen's. Sharper when zoomed in or cropped, uses more memory.", s.fullResolution) {
                    library.setSettings { c -> c.copy(fullResolution = it) }
                }
                var dim by remember(s.darken) { mutableFloatStateOf(s.darken.toFloat()) }
                Text("Darken wallpaper: ${dim.toInt()}%", style = MaterialTheme.typography.bodyLarge)
                Text("A dark overlay for better readability of your home screen items", style = MaterialTheme.typography.bodySmall)
                Slider(dim, { dim = it }, valueRange = 0f..80f, onValueChangeFinished = { library.setSettings { c -> c.copy(darken = dim.toInt()) } })
            }

            Section("Videos") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(s.videoEnd == VideoEnd.LOOP, { library.setSettings { it.copy(videoEnd = VideoEnd.LOOP) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Loop") }
                    SegmentedButton(s.videoEnd == VideoEnd.ONCE, { library.setSettings { it.copy(videoEnd = VideoEnd.ONCE) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Play once") }
                }
                Text(
                    if (s.videoEnd == VideoEnd.LOOP) "Videos play over and over while you can see them."
                    else "Videos play from the start each time the wallpaper comes into view, then stop on the last frame.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Videos are always muted.", style = MaterialTheme.typography.bodySmall)
            }

            Section("Weather effects") {
                WeatherSettings(s.weatherEffects) { v -> library.setSettings { it.copy(weatherEffects = v) } }
            }

            Section("Lock screen") {
                LockScreenSettings(s.lockOwn) { on -> library.setSettings { it.copy(lockOwn = on) } }
            }

            UpdateCard()
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Whether the lock screen has its own rotation. */
@Composable
private fun LockScreenSettings(own: Boolean, onChange: (Boolean) -> Unit) {
    Toggle(
        "Lock screen has its own pictures and videos",
        "Each album then has a Home and a Lock switch, so the lock screen can use its own albums and folders",
        own, onChange,
    )
    Text(
        if (own) "While the phone is locked the wallpaper shows the lock screen's; once unlocked, the home screen's. Both are loaded ahead, so the switch is instant. Keep WallpaperChanger set for the home and lock screen together."
        else "The lock screen shows the same as the home screen.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<Int, String>>, value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: "$value") }
            DropdownMenu(open, { open = false }) {
                options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(v); open = false }) }
            }
        }
    }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val updates = context.app.updates
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppUpdate?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            status?.let { Text(it) }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            val update = found
            if (update == null) {
                OutlinedButton(onClick = {
                    status = "Checking…"
                    scope.launch {
                        status = runCatching { updates.check() }.fold(
                            onSuccess = { u -> found = u; if (u == null) "You have the newest version" else "Version ${u.version} is out" },
                            onFailure = { "Couldn't check: ${it.message}" },
                        )
                    }
                }) { Text("Check for updates") }
            } else {
                if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodySmall)
                Button(enabled = progress == null, onClick = {
                    if (!updates.canInstall()) {
                        context.startActivity(updates.installPermission())
                        return@Button
                    }
                    scope.launch {
                        progress = 0f
                        runCatching { updates.download(update) { p -> progress = p } }
                            .onSuccess { updates.install(it) }
                            .onFailure { status = "Download failed: ${it.message}" }
                        progress = null
                    }
                }) { Text("Download and install ${update.version}") }
            }
        }
    }
}

