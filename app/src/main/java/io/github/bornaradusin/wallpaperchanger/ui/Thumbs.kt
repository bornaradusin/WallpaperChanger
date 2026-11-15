package io.github.bornaradusin.wallpaperchanger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.bornaradusin.wallpaperchanger.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Small previews of the pictures and videos, kept in memory while the app is open. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, uri: String, video: Boolean, size: Int): Bitmap? {
        val key = "$uri@$size"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val u = Uri.parse(uri)
            // Videos: a frame a second in, as the very first one is often black
            (if (video) runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, u)
                    r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                }
            }.getOrNull() else null)
                ?: runCatching { context.contentResolver.loadThumbnail(u, Size(size, size), null) }.getOrNull()
                ?: if (video) null
                else runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, u)) { d, info, _ ->
                        val s = size.toFloat() / max(info.size.width, info.size.height)
                        if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                    }
                }.getOrNull()
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }
}

@Composable
fun Thumb(item: Item, modifier: Modifier = Modifier, size: Int = 320, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { bitmap = Thumbs.load(context, item.uri, item.video, size) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = scale) }
    }
}
