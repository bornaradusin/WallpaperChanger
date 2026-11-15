package io.github.bornaradusin.wallpaperchanger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bornaradusin.wallpaperchanger.Crop
import io.github.bornaradusin.wallpaperchanger.R
import io.github.bornaradusin.wallpaperchanger.app
import kotlin.math.min
import kotlin.math.roundToInt

/** Picks the part of a picture (or video) to show: a frame the shape of the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(itemId: String, back: () -> Unit) {
    val context = LocalContext.current
    val library = context.app.library
    val data by library.data.collectAsStateWithLifecycle()
    val item = data.items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { back() }
        return
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { bitmap = Thumbs.load(context, item.uri, item.video, 1440) }

    val metrics = context.resources.displayMetrics
    val screenAspect = min(metrics.widthPixels, metrics.heightPixels).toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)

    // The frame, in fractions of the picture: centre and how big (1 = as big as it can be)
    var cx by remember { mutableFloatStateOf(item.crop?.let { (it.l + it.r) / 2 } ?: 0.5f) }
    var cy by remember { mutableFloatStateOf(item.crop?.let { (it.t + it.b) / 2 } ?: 0.5f) }
    var size by remember { mutableFloatStateOf(1f) }
    var sizeSet by remember { mutableStateOf(item.crop == null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop selection") },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), "Back") } },
            )
        },
    ) { padding ->
        val b = bitmap
        if (b == null) {
            Column(Modifier.fillMaxSize().padding(padding), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator() }
            return@Scaffold
        }
        val imgAspect = b.width.toFloat() / b.height
        // Frame size in picture fractions, at its biggest
        val maxFw = min(1f, screenAspect / imgAspect)
        val maxFh = maxFw * imgAspect / screenAspect
        if (!sizeSet) {
            item.crop?.let { size = (it.width / maxFw).coerceIn(0.2f, 1f) }
            sizeSet = true
        }
        val fw = maxFw * size
        val fh = maxFh * size
        cx = cx.coerceIn(fw / 2, 1 - fw / 2)
        cy = cy.coerceIn(fh / 2, 1 - fh / 2)

        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = min(boxW / b.width, boxH / b.height)
                val shownW = b.width * scale
                val shownH = b.height * scale
                val left = (boxW - shownW) / 2
                val top = (boxH - shownH) / 2
                with(density) {
                    Image(
                        b.asImageBitmap(), null,
                        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(shownW.toDp(), shownH.toDp()).align(Alignment.TopStart),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(b) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            cx += drag.x / shownW
                            cy += drag.y / shownH
                        }
                    },
                ) {
                    val fl = left + (cx - fw / 2) * shownW
                    val ft = top + (cy - fh / 2) * shownH
                    val frameW = fw * shownW
                    val frameH = fh * shownH
                    val dim = Color(0x99000000)
                    // Darken what's left out
                    drawRect(dim, Offset(left, top), Size(shownW, ft - top))
                    drawRect(dim, Offset(left, ft + frameH), Size(shownW, top + shownH - ft - frameH))
                    drawRect(dim, Offset(left, ft), Size(fl - left, frameH))
                    drawRect(dim, Offset(fl + frameW, ft), Size(left + shownW - fl - frameW, frameH))
                    drawRect(Color.White, Offset(fl, ft), Size(frameW, frameH), style = Stroke(2.dp.toPx()))
                }
            }
            Text("Drag the frame. Size:")
            Slider(size, { size = it }, valueRange = 0.2f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    library.setCrop(item, Crop(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2))
                    back()
                }) { Text("Save") }
                TextButton(onClick = { library.setCrop(item, null); back() }) { Text("No crop") }
            }
        }
    }
}
