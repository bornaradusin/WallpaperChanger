package io.github.bornaradusin.wallpaperchanger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import io.github.bornaradusin.wallpaperchanger.VideoEnd
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bornaradusin.wallpaperchanger.Album
import io.github.bornaradusin.wallpaperchanger.Item
import io.github.bornaradusin.wallpaperchanger.R
import io.github.bornaradusin.wallpaperchanger.Screen
import io.github.bornaradusin.wallpaperchanger.app

@Composable
fun AlbumsTab(open: (Page) -> Unit) {
    val library = LocalContext.current.app.library
    val data by library.data.collectAsStateWithLifecycle()
    val scanning by library.scanning.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Album?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Album?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { NotActiveBanner() }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    data.currentItem?.let { Thumb(it, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) }
                    if (data.lockHasOwn) data.lockItem?.let {
                        Thumb(it, Modifier.padding(start = 6.dp).size(64.dp).clip(RoundedCornerShape(8.dp)))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(if (data.lockHasOwn) "Home · Lock screen" else "Showing now", style = MaterialTheme.typography.labelMedium)
                        Text(data.currentItem?.name ?: "Nothing yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (data.lockHasOwn) Text("Lock: " + (data.lockItem?.name ?: "nothing yet"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (data.settings.paused) Text("Rotation paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    FilledTonalButton(onClick = { library.next() }, enabled = data.items.size > 1) { Text("Next") }
                }
            }
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        items(data.albums, key = { it.id }) { album ->
            val items = data.items.filter { it.album == album.id }
            var menu by remember { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { open(Page.AlbumPage(album.id)) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val first = items.firstOrNull()
                    if (first != null) Thumb(first, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    else Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val videos = items.count { it.video }
                        Text(
                            "${items.size - videos} pictures" + if (videos > 0) ", $videos videos" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (data.settings.lockOwn) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Home", style = MaterialTheme.typography.labelSmall)
                            Switch(album.active, { library.setActive(album, it, Screen.HOME) })
                        }
                        Column(Modifier.padding(start = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Lock", style = MaterialTheme.typography.labelSmall)
                            Switch(album.lock, { library.setActive(album, it, Screen.LOCK) })
                        }
                    } else {
                        Switch(album.active, { library.setActive(album, it) })
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), "Rename or delete this album") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; naming = album })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = album })
                        }
                    }
                }
            }
        }
        item {
            // What each screen is using, at a glance
            fun names(screen: io.github.bornaradusin.wallpaperchanger.Screen) =
                data.albums.filter { it.id in data.albumsFor(screen) }.joinToString(", ") { it.name }.ifBlank { "nothing" }
            val homeOn = data.albums.any { it.active }
            val lockOn = data.albums.any { it.lock }
            Text(
                if (data.settings.lockOwn) {
                    "Home screen: " + names(Screen.HOME) + (if (!homeOn) " (no album switched on for Home, so it uses the lock screen's)" else "") +
                        "\nLock screen: " + (if (lockOn) names(Screen.LOCK) else "same as the home screen (no album switched on for Lock)")
                } else {
                    "Using: " + names(Screen.HOME) + if (!homeOn) " (no album switched on, so all of them)" else ""
                },
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = { creating = true }) { Text("Add new album") }
            }
            Text(
                if (data.settings.lockOwn) "Home and Lock choose which albums each screen uses, so the lock screen can have its own pictures, videos and folders."
                else "Switch albums on or off to choose which ones the rotation uses, so you can have one for winter and one for summer and change between them.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (creating) NameDialog("Add new album", "", { creating = false }) { library.addAlbum(it); creating = false }
    naming?.let { a -> NameDialog("Rename", a.name, { naming = null }) { library.renameAlbum(a, it); naming = null } }
    deleting?.let { a ->
        val count = data.items.count { it.album == a.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete album?") },
            text = { Text("Do you really want to delete the album \"${a.name}\" and the $count pictures and videos in it from the app? Your original photos stay on your device.") },
            confirmButton = { TextButton(onClick = { library.deleteAlbum(a); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun NameDialog(title: String, start: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Album name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(albumId: String, open: (Page) -> Unit, back: () -> Unit) {
    val library = LocalContext.current.app.library
    val data by library.data.collectAsStateWithLifecycle()
    val scanning by library.scanning.collectAsStateWithLifecycle()
    val album = data.albums.firstOrNull { it.id == albumId }
    if (album == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { back() }
        return
    }
    val items = data.items.filter { it.album == albumId }
    val folders = data.folders.filter { it.album == albumId }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picked by remember { mutableStateOf<Item?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) library.addFiles(albumId, uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { library.addFolder(albumId, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) album.name else "${selected.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { if (selected.isEmpty()) back() else selected = emptySet() }) {
                        Icon(painterResource(R.drawable.ic_back), "Back")
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected = items.map { it.id }.toSet() }) { Text("All") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(110.dp),
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pickFiles.launch(arrayOf("image/*", "video/*")) }) { Text("Add pictures/videos") }
                        OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
                    }
                    if (folders.isNotEmpty()) {
                        Text("These folders are looked through for new pictures and videos:", style = MaterialTheme.typography.bodySmall)
                        folders.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(20.dp))
                                Text(f.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { library.removeFolder(f) }) { Text("Remove") }
                            }
                        }
                        Row {
                            TextButton(onClick = { library.rescan() }, enabled = !scanning) { Text(if (scanning) "Scanning folders…" else "Scan folders now") }
                        }
                    }
                    if (items.isEmpty()) Text("No pictures yet. Add some, or a folder of them.", Modifier.padding(vertical = 16.dp))
                }
            }
            items(items, key = { it.id }) { item ->
                val isSelected = item.id in selected
                Box(
                    Modifier.aspectRatio(0.6f).clip(RoundedCornerShape(8.dp))
                        .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (isSelected) selected - item.id else selected + item.id
                                else picked = item
                            },
                            onLongClick = { selected = selected + item.id },
                        ),
                ) {
                    Thumb(item, Modifier.fillMaxSize())
                    if (item.video) {
                        Icon(
                            painterResource(R.drawable.ic_play), "Video",
                            Modifier.align(Alignment.Center).size(32.dp).background(Color(0x66000000), RoundedCornerShape(16.dp)).padding(4.dp),
                            tint = Color.White,
                        )
                        item.videoEnd?.let { end ->
                            Text(
                                if (end == VideoEnd.ONCE) "Once" else "Loop",
                                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                color = Color.White, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    val now = listOfNotNull(
                        if (item.id == data.current) (if (data.settings.lockOwn) "Home" else "Now") else null,
                        if (data.settings.lockOwn && item.id == data.lockCurrent) "Lock" else null,
                    ).joinToString(" · ")
                    if (now.isNotEmpty()) {
                        Text(
                            now, Modifier.align(Alignment.TopStart).padding(4.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    picked?.let { pickedItem ->
        // Follow the library, so a change made here shows straight away
        val item = data.items.firstOrNull { it.id == pickedItem.id } ?: pickedItem
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Thumb(item, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), size = 720, scale = androidx.compose.ui.layout.ContentScale.Fit)
                    if (data.settings.lockOwn) {
                        TextButton(onClick = { library.show(item, Screen.HOME); picked = null }) { Text("Set for the home screen now") }
                        TextButton(onClick = { library.show(item, Screen.LOCK); picked = null }) { Text("Set for the lock screen now") }
                    } else {
                        TextButton(onClick = { library.show(item); picked = null }) { Text("Set as wallpaper now") }
                    }
                    if (item.video) {
                        Text("When it ends", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        val general = if (data.settings.videoEnd == VideoEnd.LOOP) "Loop" else "Play once"
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val options = listOf(null to "Default", VideoEnd.LOOP to "Loop", VideoEnd.ONCE to "Play once")
                            options.forEachIndexed { i, (end, label) ->
                                SegmentedButton(
                                    item.videoEnd == end,
                                    { library.setVideoEnd(item, end) },
                                    SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                        Text(
                            when (item.videoEnd) {
                                null -> "Follows Settings › Videos ($general)."
                                VideoEnd.LOOP -> "Plays over and over while you can see it."
                                VideoEnd.ONCE -> "Plays from the start each time it comes into view, then stops on its last frame."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { picked = null; open(Page.CropPage(item.id)) }) { Text(if (item.crop == null) "Crop" else "Change crop") }
                    if (item.crop != null) TextButton(onClick = { library.setCrop(item, null); picked = null }) { Text("Remove crop") }
                    TextButton(onClick = { library.remove(listOf(item)); picked = null }) { Text("Delete from the album") }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Close") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete?") },
            text = { Text("Do you really want to delete the ${selected.size} selected from the album? Your original files stay on your device.") },
            confirmButton = {
                TextButton(onClick = {
                    library.remove(items.filter { it.id in selected })
                    selected = emptySet()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

