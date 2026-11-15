package io.github.bornaradusin.wallpaperchanger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bornaradusin.wallpaperchanger.Action
import io.github.bornaradusin.wallpaperchanger.Data
import io.github.bornaradusin.wallpaperchanger.Library
import io.github.bornaradusin.wallpaperchanger.R
import io.github.bornaradusin.wallpaperchanger.Rule
import io.github.bornaradusin.wallpaperchanger.Schedule
import io.github.bornaradusin.wallpaperchanger.Trigger
import io.github.bornaradusin.wallpaperchanger.app
import java.util.Locale

private val DAY_LETTERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun describe(rule: Rule, data: Data): Pair<String, String> {
    val whenText = when (val t = rule.trigger) {
        is Trigger.Time -> {
            val days = when (t.days) {
                Trigger.ALL_DAYS -> "every day"
                Trigger.WEEKDAYS -> "on weekdays"
                Trigger.WEEKENDS -> "at weekends"
                else -> DAY_LETTERS.filterIndexed { i, _ -> t.days and (1 shl i) != 0 }.joinToString(", ")
            }
            String.format(Locale.getDefault(), "At %02d:%02d %s", t.hour, t.minute, days)
        }
        is Trigger.Place -> "Arriving at " + t.label.ifBlank { String.format(Locale.US, "%.4f, %.4f", t.lat, t.lon) }
        is Trigger.Wifi -> "Connecting to Wi-Fi \"${t.ssid}\""
    }
    val what = when (val a = rule.action) {
        Action.Next -> "Next wallpaper"
        is Action.SwitchAlbum -> (if (data.settings.lockOwn) "Switch the home screen to album: " else "Switch to album: ") +
            (data.albums.firstOrNull { it.id == a.album }?.name ?: "(deleted album)")
    }
    return whenText to what
}

@Composable
fun RulesTab(open: (Page) -> Unit) {
    val context = LocalContext.current
    val library = context.app.library
    val data by library.data.collectAsStateWithLifecycle()
    var permissionsOk by remember { mutableStateOf(true) }
    LifecycleResumeEffect(data.rules) {
        val needsLocation = data.rules.any { it.enabled && it.trigger !is Trigger.Time }
        permissionsOk = !needsLocation || (Schedule.hasLocation(context) && Schedule.hasBackgroundLocation(context))
        onPauseOrDispose { }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Rules change the wallpaper, or switch album, at a time, on arriving at a place, or on joining a Wi-Fi network.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!permissionsOk) item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Location permission not granted - place and Wi-Fi rules won't work!")
                    TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
                    Text("Set Location to \"Allow all the time\".", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (data.rules.isEmpty()) item { Text("No rules yet.", Modifier.padding(16.dp)) }
        items(data.rules, key = { it.id }) { rule ->
            val (whenText, what) = describe(rule, data)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { open(Page.RulePage(rule.id)) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(whenText, style = MaterialTheme.typography.titleSmall)
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(rule.enabled, { library.saveRule(rule.copy(enabled = it)) })
                }
            }
        }
        item { Row(Modifier.padding(16.dp)) { Button(onClick = { open(Page.RulePage(null)) }) { Text("Add rule") } } }
    }
}

private enum class Kind(val label: String) { TIME("Time"), PLACE("Place"), WIFI("Wi-Fi") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleScreen(ruleId: String?, back: () -> Unit) {
    val context = LocalContext.current
    val library = context.app.library
    val data by library.data.collectAsStateWithLifecycle()
    val existing = data.rules.firstOrNull { it.id == ruleId }

    var kind by remember {
        mutableStateOf(when (existing?.trigger) { is Trigger.Place -> Kind.PLACE; is Trigger.Wifi -> Kind.WIFI; else -> Kind.TIME })
    }
    val time = existing?.trigger as? Trigger.Time
    val timeState = rememberTimePickerState(time?.hour ?: 8, time?.minute ?: 0, is24Hour = true)
    var days by remember { mutableStateOf(time?.days ?: Trigger.ALL_DAYS) }
    var showClock by remember { mutableStateOf(false) }

    val place = existing?.trigger as? Trigger.Place
    var lat by remember { mutableStateOf(place?.lat) }
    var lon by remember { mutableStateOf(place?.lon) }
    var radius by remember { mutableStateOf(place?.radius ?: 200f) }
    var label by remember { mutableStateOf(place?.label.orEmpty()) }

    var ssid by remember { mutableStateOf((existing?.trigger as? Trigger.Wifi)?.ssid.orEmpty()) }

    var action by remember { mutableStateOf(existing?.action ?: Action.Next) }
    var albumMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var askBackground by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && !Schedule.hasBackgroundLocation(context)) askBackground = true
    }
    fun needLocation() {
        if (!Schedule.hasLocation(context)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (!Schedule.hasBackgroundLocation(context)) {
            askBackground = true
        }
    }

    val canSave = when (kind) {
        Kind.TIME -> days != 0
        Kind.PLACE -> lat != null && lon != null
        Kind.WIFI -> ssid.isNotBlank()
    } && (action !is Action.SwitchAlbum || data.albums.any { it.id == (action as Action.SwitchAlbum).album })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add rule" else "Rule") },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                actions = { if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("When", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = {
                            kind = k
                            if (k != Kind.TIME) needLocation()
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
                    ) { Text(k.label) }
                }
            }

            when (kind) {
                Kind.TIME -> {
                    OutlinedButton(onClick = { showClock = true }) {
                        Text(String.format(Locale.getDefault(), "%02d:%02d", timeState.hour, timeState.minute), style = MaterialTheme.typography.headlineMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(days == Trigger.ALL_DAYS, { days = Trigger.ALL_DAYS }, { Text("Every day") })
                        FilterChip(days == Trigger.WEEKDAYS, { days = Trigger.WEEKDAYS }, { Text("Weekdays") })
                        FilterChip(days == Trigger.WEEKENDS, { days = Trigger.WEEKENDS }, { Text("Weekends") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LETTERS.forEachIndexed { i, d ->
                            val on = days and (1 shl i) != 0
                            FilterChip(on, { days = days xor (1 shl i) }, { Text(d) })
                        }
                    }
                }
                Kind.PLACE -> {
                    Text("Tap the map where the rule should happen.", style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp))) {
                        PlaceMap(lat, lon, radius) { la, lo -> lat = la; lon = lo }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val here = lastLocation(context)
                            if (here == null) needLocation() else { lat = here.first; lon = here.second }
                        }) { Text("Use my location") }
                    }
                    Text("Radius: ${radius.toInt()} m")
                    Slider(radius, { radius = it }, valueRange = 100f..2000f)
                    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Name (like Home or Work)") }, singleLine = true)
                }
                Kind.WIFI -> {
                    OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth(), label = { Text("Wi-Fi name") }, singleLine = true)
                    OutlinedButton(onClick = {
                        val now = currentSsid(context)
                        if (now == null) needLocation() else ssid = now
                    }) { Text("Use the Wi-Fi I'm on") }
                    Text("Android only tells apps the Wi-Fi name with location allowed all the time.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Do", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(action == Action.Next, { action = Action.Next }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Next wallpaper") }
                SegmentedButton(
                    action is Action.SwitchAlbum,
                    { action = Action.SwitchAlbum((action as? Action.SwitchAlbum)?.album ?: data.albums.first().id) },
                    SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Switch album") }
            }
            (action as? Action.SwitchAlbum)?.let { a ->
                Box {
                    OutlinedButton(onClick = { albumMenu = true }) {
                        Text("Switch to album: " + (data.albums.firstOrNull { it.id == a.album }?.name ?: "pick one"))
                    }
                    DropdownMenu(albumMenu, { albumMenu = false }) {
                        data.albums.forEach { al ->
                            DropdownMenuItem(text = { Text(al.name) }, onClick = { action = Action.SwitchAlbum(al.id); albumMenu = false })
                        }
                    }
                }
            }

            Button(
                enabled = canSave,
                onClick = {
                    val trigger = when (kind) {
                        Kind.TIME -> Trigger.Time(timeState.hour, timeState.minute, days)
                        Kind.PLACE -> Trigger.Place(lat!!, lon!!, radius, label.trim())
                        Kind.WIFI -> Trigger.Wifi(ssid.trim())
                    }
                    library.saveRule(Rule(existing?.id ?: Library.newId(), trigger, action, existing?.enabled ?: true))
                    back()
                },
            ) { Text("Save") }
        }
    }

    if (showClock) {
        AlertDialog(
            onDismissRequest = { showClock = false },
            title = { Text("Select time") },
            text = { TimePicker(timeState) },
            confirmButton = { TextButton(onClick = { showClock = false }) { Text("OK") } },
        )
    }
    if (askBackground) {
        AlertDialog(
            onDismissRequest = { askBackground = false },
            title = { Text("Location all the time") },
            text = { Text("Place and Wi-Fi rules need location access even while the app isn't open. In the app's settings, set Location to \"Allow all the time\".") },
            confirmButton = { TextButton(onClick = { askBackground = false; openAppSettings(context) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { askBackground = false }) { Text("Not now") } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete rule?") },
            text = { Text("Do you really want to delete this rule?") },
            confirmButton = { TextButton(onClick = { library.deleteRule(existing); confirmDelete = false; back() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@SuppressLint("MissingPermission")
fun lastLocation(context: Context): Pair<Double, Double>? {
    if (!Schedule.hasLocation(context)) return null
    val lm = context.getSystemService(LocationManager::class.java)
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

private fun currentSsid(context: Context): String? {
    if (!Schedule.hasLocation(context)) return null
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    return (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
}
