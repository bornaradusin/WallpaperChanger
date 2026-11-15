package io.github.bornaradusin.wallpaperchanger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import io.github.bornaradusin.wallpaperchanger.ui.Thumb
import java.io.File

/** From a shortcut on the home screen: next wallpaper, or pause/resume. Then gone. */
class ShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = app.library
        when (intent.action) {
            Schedule.ACTION_PAUSE -> {
                library.togglePaused()
                WidgetUpdates.refresh(this)
                Toast.makeText(this, if (library.data.value.settings.paused) "Rotation paused" else "Rotation resumed", Toast.LENGTH_SHORT).show()
            }
            Schedule.ACTION_NEXT_ALBUM -> library.nextAlbum()
            else -> library.next()
        }
        finish()
    }
}

/** Pick which picture or video to show now (from the widget). */
class SelectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val library = app.library
                val pool = remember { library.data.value.pool }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Select wallpaper") },
                    text = {
                        if (pool.isEmpty()) Text("No wallpapers added")
                        else LazyVerticalGrid(
                            GridCells.Fixed(3), Modifier.heightIn(max = 480.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(pool, key = { it.id }) { item ->
                                Thumb(item, Modifier.aspectRatio(0.6f).clip(RoundedCornerShape(8.dp)).clickable { library.show(item); finish() })
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}

/** Opens what's on the wallpaper now in the gallery (from the widget). */
class OpenCurrentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = app.library.data.value.currentItem
        if (item == null) {
            Toast.makeText(this, "No wallpaper image set", Toast.LENGTH_SHORT).show()
        } else {
            var uri = Uri.parse(item.uri)
            if (uri.scheme == "file") uri = FileProvider.getUriForFile(this, "$packageName.files", File(uri.path!!))
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (item.video) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(view) }.onFailure {
                Toast.makeText(this, "No app on this device can open this image", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

/** Pictures or videos shared from another app (like the gallery) go into an album. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "The image could not be added", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            AppTheme {
                val library = app.library
                val albums = library.data.value.albums
                var newName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(if (uris.size == 1) "Add to album" else "Add ${uris.size} to album") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            albums.forEach { a ->
                                Surface(Modifier.fillMaxWidth().clickable { add(a.id, a.name, uris) }) {
                                    Text(a.name, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("Or a new album") }, singleLine = true)
                            TextButton(onClick = { val a = library.addAlbum(newName); add(a.id, a.name, uris) }, enabled = newName.isNotBlank()) { Text("Add new album") }
                        }
                    },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    private fun add(album: String, name: String, uris: List<Uri>) {
        val type = intent.type
        Thread {
            app.library.addShared(album, uris, type)
            runOnUiThread {
                Toast.makeText(this, if (uris.size == 1) "Image added to album $name" else "${uris.size} images added to album $name", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }
}

