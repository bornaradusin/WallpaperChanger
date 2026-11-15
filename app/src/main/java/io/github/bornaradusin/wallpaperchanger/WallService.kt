package io.github.bornaradusin.wallpaperchanger

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * The live wallpaper. When the screen goes off, the next picture or video is picked and got ready
 * in the background; when the phone is unlocked it is put up at once. Nothing heavy ever happens
 * on the phone's main thread: files are read on a background thread, and drawing has a thread of
 * its own.
 */
class WallService : WallpaperService() {

    private val engines = mutableSetOf<Engine>()
    private val main = Handler(Looper.getMainLooper())
    private var lastSsid: String? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only the real wallpaper moves things on, not a preview in the wallpaper picker
            if (engines.none { !it.isPreview }) return
            val library = app.library
            val settings = library.data.value.settings
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    library.rescanIfDue()
                    if (settings.onUnlock && !settings.paused) library.prepareNext()
                }
                Intent.ACTION_USER_PRESENT -> if (settings.onUnlock && !settings.paused) library.showPrepared()
            }
        }
    }

    /** Wi-Fi rules: told when the phone joins a network. */
    private val wifi = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: return
            main.post {
                if (ssid == lastSsid) return@post
                lastSsid = ssid
                val library = app.library
                library.data.value.rules
                    .filter { it.enabled && (it.trigger as? Trigger.Wifi)?.ssid.equals(ssid, ignoreCase = true) }
                    .forEach { library.run(it) }
            }
        }

        override fun onLost(network: Network) {
            main.post { lastSsid = null }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi,
            )
        }
        Schedule.update(this, app.library.data.value)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifi) }
        super.onDestroy()
    }

    override fun onCreateEngine(): WallpaperService.Engine = Engine()

    /** A picture or video loaded and ready to go on screen. */
    private sealed class Ready(val item: Item) {
        class Picture(item: Item, val bitmap: Bitmap) : Ready(item)
        class Video(item: Item, val player: MediaPlayer) : Ready(item)

        fun release() {
            when (this) {
                is Picture -> bitmap.recycle()
                is Video -> runCatching { player.release() }
            }
        }
    }

    inner class Engine : WallpaperService.Engine() {

        private val thread = HandlerThread("wallpaper").apply { start() }
        private val gl = Handler(thread.looper)
        private val renderer = Renderer()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val colorsByKey = ConcurrentHashMap<String, WallpaperColors>()

        @Volatile private var visibleNow = false
        @Volatile private var screenW = 1080
        @Volatile private var screenH = 2400
        private var colors: WallpaperColors? = null
        private var lastTap = 0L
        private var lastTapX = 0
        private var lastTapY = 0
        private var xOffset = 0.5f

        // ---- render thread only ----
        private var target: Item? = null
        private var upcoming: Item? = null
        private var shownKey: String? = null
        private var ready: Ready? = null
        private var loadingKey: String? = null
        private var player: MediaPlayer? = null

        private fun key(item: Item) = item.id + "|" + item.crop

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            engines.add(this)
            val library = app.library
            scope.launch { library.data.map { it.currentItem }.distinctUntilChanged().collect { item -> gl.post { setTarget(item) } } }
            scope.launch { library.pending.collect { item -> gl.post { setUpcoming(item) } } }
            scope.launch {
                library.data.map { it.settings }.distinctUntilChanged().collect { s ->
                    gl.post {
                        renderer.scaling = s.scaling
                        renderer.darken = s.darken / 100f
                        renderer.offset = if (s.scroll) xOffset else 0.5f
                        player?.isLooping = s.videoEnd == VideoEnd.LOOP
                        renderer.draw()
                    }
                }
            }
        }

        override fun onDestroy() {
            engines.remove(this)
            scope.cancel()
            gl.post {
                releasePlayer()
                ready?.release()
                ready = null
                renderer.stop()
            }
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            gl.post {
                renderer.start(holder.surface)
                shownKey = null
                setTarget(target)
            }
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
            // The surface has to be let go of before this returns
            val done = CountDownLatch(1)
            gl.post {
                releasePlayer()
                renderer.stop()
                shownKey = null
                done.countDown()
            }
            done.await(1, TimeUnit.SECONDS)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl.post {
                val p = player ?: return@post
                runCatching {
                    if (visible) {
                        // Play once: from the start each time the wallpaper comes into view
                        if (app.library.data.value.settings.videoEnd == VideoEnd.ONCE) p.seekTo(0)
                        p.start()
                    } else if (p.isPlaying) {
                        p.pause()
                    }
                }
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            this.xOffset = xOffset
            if (!app.library.data.value.settings.scroll) return
            gl.post {
                renderer.offset = xOffset
                if (player == null) renderer.draw()
            }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !isPreview && app.library.data.value.settings.doubleTap) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 400 && abs(x - lastTapX) < 120 && abs(y - lastTapY) < 120) {
                    lastTap = 0
                    app.library.next()
                } else {
                    lastTap = now
                    lastTapX = x
                    lastTapY = y
                }
            }
            return null
        }

        override fun onComputeColors(): WallpaperColors? = colors

        // ---- what to show (render thread) ----

        private fun setTarget(item: Item?) {
            target = item
            if (item == null) {
                releasePlayer()
                shownKey = null
                renderer.clear()
                return
            }
            if (!renderer.ready || key(item) == shownKey) return
            val r = ready
            if (r != null && key(r.item) == key(item)) swap(r) else load(item)
        }

        private fun setUpcoming(item: Item?) {
            upcoming = item
            if (item == null) return
            val k = key(item)
            if (k == shownKey || k == loadingKey || ready?.let { key(it.item) } == k) return
            load(item)
        }

        private fun load(item: Item) {
            val k = key(item)
            if (loadingKey == k) return
            loadingKey = k
            if (item.video) {
                val p = MediaPlayer()
                runCatching {
                    p.setDataSource(applicationContext, Uri.parse(item.uri))
                    p.setVolume(0f, 0f)
                    p.setOnPreparedListener { loaded(Ready.Video(item, it)) }
                    p.setOnErrorListener { mp, _, _ -> if (loadingKey == k) loadingKey = null; mp.release(); true }
                    p.prepareAsync()
                }.onFailure { p.release(); loadingKey = null }
                scope.launch(Dispatchers.IO) { frameColors(item)?.let { gotColors(k, it) } }
            } else {
                scope.launch(Dispatchers.IO) {
                    val bitmap = decode(item) ?: run { gl.post { if (loadingKey == k) loadingKey = null }; return@launch }
                    colorsOf(bitmap)?.let { colorsByKey[k] = it }
                    gl.post { loaded(Ready.Picture(item, bitmap)) }
                }
            }
        }

        private fun loaded(r: Ready) {
            val k = key(r.item)
            if (loadingKey != k) {
                r.release()
                return
            }
            loadingKey = null
            ready?.release()
            ready = r
            if (target?.let { key(it) } == k) swap(r) else reportEarly(k)
        }

        /**
         * The next one is ready while the screen is off: tell Android its colours now. Android
         * re-themes the phone straight away only for colours that come while the screen is on
         * (that is what freezes the phone for a moment); while it's off, it happens unseen. On
         * unlock only the picture changes, and the colours are already right.
         */
        private fun reportEarly(k: String) {
            if (upcoming?.let { key(it) } != k || screenOn()) return
            colorsByKey[k]?.let { c -> main.post { setColors(c) } }
        }

        private fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

        /** Puts a loaded picture or video on screen. Quick: everything slow was done beforehand. */
        private fun swap(r: Ready) {
            if (!renderer.ready) return
            ready = null
            releasePlayer()
            val item = r.item
            when (r) {
                is Ready.Picture -> {
                    renderer.showImage(r.bitmap, item.crop)
                    r.bitmap.recycle()
                }
                is Ready.Video -> {
                    val p = r.player
                    p.setSurface(renderer.videoSurface(item.crop, { renderer.videoFrame() }, gl))
                    renderer.videoSize(p.videoWidth, p.videoHeight)
                    p.setOnVideoSizeChangedListener { _, w, h -> renderer.videoSize(w, h) }
                    p.isLooping = app.library.data.value.settings.videoEnd == VideoEnd.LOOP
                    p.setOnErrorListener { _, _, _ -> true }
                    player = p
                    // Started and paused when hidden, so its first frame is on screen
                    p.start()
                    if (!visibleNow) p.pause()
                }
            }
            val k = key(item)
            shownKey = k
            colorsByKey[k]?.let { c -> main.post { setColors(c) } }
            // Keep only what's still needed
            colorsByKey.keys.retainAll(setOfNotNull(k, upcoming?.let { key(it) }))
        }

        private fun gotColors(k: String, c: WallpaperColors) {
            colorsByKey[k] = c
            gl.post { if (shownKey == k) main.post { setColors(c) } else reportEarly(k) }
        }

        /** Tells Android, and so Material You and the launcher, the new wallpaper's colours. */
        private fun setColors(c: WallpaperColors) {
            if (c == colors) return
            colors = c
            notifyColorsChanged()
        }

        private fun releasePlayer() {
            player?.let { runCatching { it.reset(); it.release() } }
            player = null
        }

        // ---- loading (background thread) ----

        /** A picture just big enough for the screen, ready to go to OpenGL. */
        private fun decode(item: Item): Bitmap? = runCatching {
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(item.uri))
            val full = app.library.data.value.settings.fullResolution
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val cw = item.crop?.width ?: 1f
                val ch = item.crop?.height ?: 1f
                val limit = MAX_TEXTURE.toFloat() / max(w, h)
                val cover = max(screenW / (w * cw), screenH / (h * ch))
                val scale = if (full) minOf(1f, limit) else minOf(1f, cover, limit)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }.getOrNull()

        private fun colorsOf(bitmap: Bitmap): WallpaperColors? = runCatching {
            val small = Bitmap.createScaledBitmap(bitmap, 96, (96f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
            WallpaperColors.fromBitmap(small).also { if (small !== bitmap) small.recycle() }
        }.getOrNull()

        private fun frameColors(item: Item): WallpaperColors? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(applicationContext, Uri.parse(item.uri))
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96)?.let { f ->
                    WallpaperColors.fromBitmap(f).also { f.recycle() }
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_TEXTURE = 4096
    }
}
