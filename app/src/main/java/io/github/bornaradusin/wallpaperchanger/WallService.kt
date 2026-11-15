package io.github.bornaradusin.wallpaperchanger

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The live wallpaper. Each time the phone locks (the screen goes off) it moves on to the next
 * picture or video, so a new one is waiting on the lock screen and the home screen.
 */
class WallService : WallpaperService() {

    private val engines = mutableSetOf<Engine>()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only the real wallpaper moves things on, not a preview in the wallpaper picker
            if (engines.any { !it.isPreview }) app.library.next()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    override fun onCreateEngine(): WallpaperService.Engine = Engine()

    inner class Engine : WallpaperService.Engine() {

        private val thread = HandlerThread("wallpaper").apply { start() }
        private val gl = Handler(thread.looper)
        private val renderer = Renderer()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var loading: Job? = null

        private var player: MediaPlayer? = null
        private var playerReady = false
        private var shown: Media? = null
        private var visibleNow = false
        private var colors: WallpaperColors? = null
        private var screenW = 1080
        private var screenH = 2400

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            engines.add(this)
            scope.launch {
                app.library.state.map { it.current }.distinctUntilChanged().collect { show(it) }
            }
        }

        override fun onDestroy() {
            engines.remove(this)
            scope.cancel()
            gl.post {
                releasePlayer()
                renderer.stop()
            }
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            gl.post { renderer.start(holder.surface) }
            // The surface is new: put back what was showing
            shown?.let { val again = it; shown = null; show(again) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width
            screenH = height
            gl.post {
                renderer.resize(width, height)
                renderer.draw()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            gl.post { renderer.draw() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            gl.post {
                releasePlayer()
                renderer.stop()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl.post {
                val p = player ?: return@post
                if (!playerReady) return@post
                if (visible) {
                    if (app.library.state.value.settings.videoEnd == VideoEnd.ONCE) {
                        // Play once: from the start each time the wallpaper comes into view
                        p.seekTo(0)
                    }
                    p.start()
                } else if (p.isPlaying) {
                    p.pause()
                }
            }
        }

        override fun onComputeColors(): WallpaperColors? = colors

        /** Swaps to a picture or a video. */
        private fun show(media: Media?) {
            if (media == shown && media != null) return
            shown = media
            loading?.cancel()
            gl.post { releasePlayer() }
            if (media == null) {
                gl.post { renderer.clear() }
                return
            }
            loading = scope.launch {
                if (media.video) {
                    gl.post { playVideo(media) }
                    val frame = withContext(Dispatchers.IO) { firstFrame(Uri.parse(media.uri)) }
                    frame?.let { setColors(it) }
                } else {
                    val bitmap = withContext(Dispatchers.IO) { decode(Uri.parse(media.uri)) } ?: return@launch
                    setColors(bitmap)
                    gl.post {
                        renderer.showImage(bitmap)
                        bitmap.recycle()
                    }
                }
            }
        }

        /** Tells Android (and so Material You and the launcher) the new wallpaper's colours. */
        private fun setColors(bitmap: Bitmap) {
            colors = runCatching { WallpaperColors.fromBitmap(bitmap) }.getOrNull()
            notifyColorsChanged()
        }

        // ---- on the render thread ----

        private fun playVideo(media: Media) {
            if (!renderer.ready) return
            val loop = app.library.state.value.settings.videoEnd == VideoEnd.LOOP
            val p = MediaPlayer()
            player = p
            playerReady = false
            runCatching {
                p.setDataSource(applicationContext, Uri.parse(media.uri))
                p.setSurface(renderer.videoSurface({ renderer.videoFrame() }, gl))
                p.setVolume(0f, 0f)
                p.isLooping = loop
                p.setOnVideoSizeChangedListener { _, w, h -> renderer.videoSize(w, h) }
                p.setOnPreparedListener {
                    playerReady = true
                    renderer.videoSize(it.videoWidth, it.videoHeight)
                    // Starting and pausing straight away puts the first frame on screen while locked
                    it.start()
                    if (!visibleNow) it.pause()
                }
                // Play once: the last frame stays on screen when it ends
                p.setOnErrorListener { _, _, _ -> true }
                p.prepareAsync()
            }.onFailure { releasePlayer() }
        }

        private fun releasePlayer() {
            player?.let { runCatching { it.reset(); it.release() } }
            player = null
            playerReady = false
        }

        // ---- loading ----

        /** A picture just big enough to fill the screen, decoded so it can go to OpenGL. */
        private fun decode(uri: Uri): Bitmap? = runCatching {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                // Big enough to cover the screen, never bigger than a texture can be
                val cover = max(screenW.toFloat() / w, screenH.toFloat() / h)
                val limit = MAX_TEXTURE.toFloat() / max(w, h)
                val scale = minOf(1f, cover, limit)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }.getOrNull()

        private fun firstFrame(uri: Uri): Bitmap? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(applicationContext, uri)
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_TEXTURE = 4096
    }
}
