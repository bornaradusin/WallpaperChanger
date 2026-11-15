package io.github.bornaradusin.wallpaperchanger.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bornaradusin.wallpaperchanger.R
import io.github.bornaradusin.wallpaperchanger.WallService

private enum class Tab(val label: String, val icon: Int) {
    ALBUMS("Albums", R.drawable.ic_albums),
    RULES("Rules", R.drawable.ic_rules),
    SETTINGS("Settings", R.drawable.ic_settings),
}

/** Something opened over the tabs. */
sealed interface Page {
    data class AlbumPage(val album: String) : Page
    data class CropPage(val item: String) : Page
    data class RulePage(val rule: String?) : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    var tab by rememberSaveable { mutableStateOf(Tab.ALBUMS) }
    var pages by remember { mutableStateOf(listOf<Page>()) }
    val open: (Page) -> Unit = { pages = pages + it }
    val back: () -> Unit = { pages = pages.dropLast(1) }

    BackHandler(enabled = pages.isNotEmpty()) { back() }

    when (val page = pages.lastOrNull()) {
        is Page.AlbumPage -> AlbumScreen(page.album, open, back)
        is Page.CropPage -> CropScreen(page.item, back)
        is Page.RulePage -> RuleScreen(page.rule, back)
        null -> Scaffold(
            topBar = { TopAppBar(title = { Text("WallpaperChanger") }) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(painterResource(t.icon), null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.ALBUMS -> AlbumsTab(open)
                    Tab.RULES -> RulesTab(open)
                    Tab.SETTINGS -> SettingsTab()
                }
            }
        }
    }
}

/** Shown at the top until WallpaperChanger is the wallpaper. */
@Composable
fun NotActiveBanner() {
    val context = LocalContext.current
    var active by remember { mutableStateOf(isActive(context)) }
    LifecycleResumeEffect(Unit) {
        active = isActive(context)
        onPauseOrDispose { }
    }
    if (active) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WallpaperChanger needs its live wallpaper enabled", style = MaterialTheme.typography.titleSmall)
            Text("Set it for the home screen and lock screen.", style = MaterialTheme.typography.bodyMedium)
            Row { Button(onClick = { setAsWallpaper(context) }) { Text("Set as wallpaper") } }
        }
    }
}

fun isActive(context: Context): Boolean =
    WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

fun setAsWallpaper(context: Context) {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, WallService::class.java))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            .onFailure {
                Toast.makeText(context, "Live wallpaper setting not found. Set it from the wallpaper settings.", Toast.LENGTH_LONG).show()
            }
    }
}
