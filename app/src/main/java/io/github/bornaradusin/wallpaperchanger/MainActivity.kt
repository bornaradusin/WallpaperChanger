package io.github.bornaradusin.wallpaperchanger

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val context = LocalContext.current
    val library = context.app.library
    val state by library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var active by remember { mutableStateOf(isActive(context)) }
    LifecycleResumeEffect(Unit) {
        active = isActive(context)
        onPauseOrDispose { }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { library.addFolder(it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) library.addFiles(uris)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WallpaperChanger", style = MaterialTheme.typography.headlineMedium)

        // ---- is it the wallpaper? ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    Text("WallpaperChanger is your wallpaper", style = MaterialTheme.typography.titleMedium)
                    Text("Each time you lock the phone, the next picture or video is put up.")
                } else {
                    Text("Not your wallpaper yet", style = MaterialTheme.typography.titleMedium)
                    Text("Set it as the wallpaper on the home screen and lock screen. Then each lock puts up the next picture or video.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { setAsWallpaper(context) }) { Text(if (active) "Set again" else "Set as wallpaper") }
                    FilledTonalButton(onClick = { library.next() }, enabled = state.media.isNotEmpty()) { Text("Next now") }
                }
                state.current?.let { Text("Showing: ${it.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        // ---- sources ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pictures and videos", style = MaterialTheme.typography.titleMedium)
                val pictures = state.media.count { !it.video }
                val videos = state.media.size - pictures
                Text(
                    if (state.sources.isEmpty()) "Add a folder, or pick some pictures and videos."
                    else "$pictures pictures, $videos videos",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.sources.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (source.folder) "Folder: " else "File: ") + source.name,
                            Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { library.remove(source) }) { Text("Remove") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("image/*", "video/*")) }) { Text("Add files") }
                }
                if (state.sources.isNotEmpty()) {
                    TextButton(onClick = { library.rescan() }) { Text("Look through folders again") }
                }
            }
        }

        // ---- settings ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Toggle("Shuffle", "Random order; nothing repeats until everything has been shown", state.settings.shuffle) {
                    library.setSettings(state.settings.copy(shuffle = it))
                }
                Toggle("Include subfolders", "Also use what is in folders inside the added folders", state.settings.subfolders) {
                    library.setSettings(state.settings.copy(subfolders = it))
                }
                Text("Videos", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    VideoEnd.entries.forEachIndexed { i, end ->
                        SegmentedButton(
                            selected = state.settings.videoEnd == end,
                            onClick = { library.setSettings(state.settings.copy(videoEnd = end)) },
                            shape = SegmentedButtonDefaults.itemShape(i, VideoEnd.entries.size),
                        ) { Text(if (end == VideoEnd.LOOP) "Loop" else "Play once") }
                    }
                }
                Text(
                    if (state.settings.videoEnd == VideoEnd.LOOP) "Videos play over and over while you can see them."
                    else "Videos play from the start each time the wallpaper comes into view, then stop on the last frame.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Videos are always muted.", style = MaterialTheme.typography.bodySmall)
            }
        }

        UpdateCard()
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun UpdateCard() {
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

private fun isActive(context: android.content.Context): Boolean =
    WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

private fun setAsWallpaper(context: android.content.Context) {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, WallService::class.java))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            .onFailure { Toast.makeText(context, "Couldn't open the wallpaper picker", Toast.LENGTH_LONG).show() }
    }
}
