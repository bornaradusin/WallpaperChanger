package io.github.bornaradusin.wallpaperchanger

import android.app.KeyguardManager
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
import android.os.Build
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * The live wallpaper, like Wallpaper Changer: a second after the screen goes off (once Android's
 * lock animation is over), the next picture or video is loaded, put up and its colours given to
 * Android while the phone sleeps. Nothing happens during the animation, and waking and unlocking
 * change nothing, so nothing stutters.
 *
 * On Android 14 and newer it can be the lock screen's wallpaper separately from the home
 * screen's; each one shows its own rotation when the lock screen has its own.
 */
class WallService : WallpaperService() {

    private val engines = mutableSetOf<Engine>()
    private val main = Handler(Looper.getMainLooper())
    private var lastSsid: String? = null

    /**
     * Whether the lock screen is up. With the lock screen on its own rotation, the wallpaper shows
     * the lock screen's one while this is true and the home screen's once unlocked.
     */
    private val locked = MutableStateFlow(false)

    /** The change after the screen went off, waiting for the lock animation to finish. */
    private val change = Runnable {
        locked.value = true
        app.library.rescanIfDue()
        app.library.screenOff()
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Only the real wallpaper moves things on, not a preview in the wallpaper picker
                    if (engines.none { !it.isPreview }) return
                    // Keep the phone from sleeping fully until the next one is loaded and up
                    runCatching {
                        getSystemService(PowerManager::class.java)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WallpaperChanger:change")
                            .acquire(10_000L)
                    }
                    // Like Wallpaper Changer: a second later, when Android has finished locking
                    main.removeCallbacks(change)
                    main.postDelayed(change, CHANGE_DELAY_MS)
                }
                // Back on before it happened: leave it for the next lock
                Intent.ACTION_SCREEN_ON -> {
                    main.removeCallbacks(change)
                    locked.value = keyguardLocked()
                }
                // Unlocked: over to the home screen's (already loaded, so it's instant)
                Intent.ACTION_USER_PRESENT -> locked.value = false
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
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        locked.value = keyguardLocked()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi,
            )
        }
        Schedule.update(this, app.library.data.value)
    }

    override fun onDestroy() {
        main.removeCallbacks(change)
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifi) }
        super.onDestroy()
    }

    override fun onCreateEngine(): WallpaperService.Engine = Engine()

    private fun keyguardLocked() = runCatching { getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    inner class Engine : WallpaperService.Engine() {

        private val thread = HandlerThread("wallpaper").apply { start() }
        private val gl = Handler(thread.looper)
        private val renderer = Renderer()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Volatile private var visibleNow = false
        @Volatile private var screenW = 1080
        @Volatile private var screenH = 2400
        private var colors: WallpaperColors? = null
        private var lastTap = 0L
        private var lastTapX = 0
        private var lastTapY = 0
        private var xOffset = 0.5f

        /** Which screen Android put this wallpaper on (only the lock screen when set there on its own). */
        private val flags = MutableStateFlow(Screen.HOME)

        /**
         * What one slot of the renderer holds. Slot 0 is the home screen's; slot 1 the lock
         * screen's, when the lock screen has its own and both share this one wallpaper.
         * Render thread only.
         */
        private inner class Slot(val index: Int) {
            var target: Item? = null
            var shownKey: String? = null
            var loadingKey: String? = null
            var player: MediaPlayer? = null
            var colors: WallpaperColors? = null
        }

        private val slots = arrayOf(Slot(0), Slot(1))
        /** The slot whose colours Android is told about: always the home screen's. */
        private var colorSlot = 0

        private fun key(item: Item) = item.id + "|" + item.crop

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            engines.add(this)
            val library = app.library
            flags.value = whichScreen()
            scope.launch {
                combine(library.data, flags, locked) { d, screen, isLocked ->
                    val own = d.settings.lockOwn
                    when {
                        // Set on the lock screen on its own (Android 14+): just the lock screen's
                        screen == Screen.LOCK -> Plan(d.item(Screen.LOCK), null, 0)
                        // One wallpaper for both screens: the lock screen's while locked, the home screen's after
                        own -> Plan(d.item(Screen.HOME), d.item(Screen.LOCK), if (isLocked && !isPreview) 1 else 0)
                        else -> Plan(d.item(Screen.HOME), null, 0)
                    }
                }.distinctUntilChanged().collect { plan -> gl.post { apply(plan) } }
            }
            scope.launch {
                library.data.map { it.settings }.distinctUntilChanged().collect { s ->
                    gl.post {
                        renderer.scaling = s.scaling
                        renderer.darken = s.darken / 100f
                        renderer.offset = if (s.scroll) xOffset else 0.5f
                        slots.forEach { it.player?.isLooping = s.videoEnd == VideoEnd.LOOP }
                        renderer.draw()
                    }
                }
            }
        }

        override fun onDestroy() {
            engines.remove(this)
            scope.cancel()
            gl.post {
                slots.forEach { releasePlayer(it) }
                renderer.stop()
            }
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            gl.post {
                renderer.start(holder.surface)
                slots.forEach { it.shownKey = null; it.loadingKey = null }
                slots.forEach { load(it) }
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
                slots.forEach { releasePlayer(it); it.shownKey = null; it.loadingKey = null }
                renderer.stop()
                done.countDown()
            }
            done.await(1, TimeUnit.SECONDS)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl.post { playOnlyActive(restart = visible) }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            this.xOffset = xOffset
            if (!app.library.data.value.settings.scroll) return
            gl.post {
                renderer.offset = xOffset
                if (slots[renderer.active].player == null) renderer.draw()
            }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !isPreview && app.library.data.value.settings.doubleTap) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 400 && abs(x - lastTapX) < 120 && abs(y - lastTapY) < 120) {
                    lastTap = 0
                    app.library.next(home = true, lock = false)
                } else {
                    lastTap = now
                    lastTapX = x
                    lastTapY = y
                }
            }
            return null
        }

        override fun onComputeColors(): WallpaperColors? = colors

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            flags.value = whichScreen()
        }

        private fun whichScreen(): Screen {
            if (isPreview || Build.VERSION.SDK_INT < 34) return Screen.HOME
            return if (wallpaperFlags == WallpaperManager.FLAG_LOCK) Screen.LOCK else Screen.HOME
        }

        // ---- what to show (render thread) ----

        private fun apply(plan: Plan) {
            slots[0].target = plan.home
            slots[1].target = plan.lock
            if (plan.lock == null) releaseSlot(slots[1])
            slots.forEach { load(it) }
            renderer.setActive(plan.active)
            playOnlyActive(restart = false)
        }

        /** Only the slot on screen plays; the other waits, paused, ready to switch to. */
        private fun playOnlyActive(restart: Boolean) {
            val once = app.library.data.value.settings.videoEnd == VideoEnd.ONCE
            slots.forEach { slot ->
                val p = slot.player ?: return@forEach
                runCatching {
                    if (visibleNow && slot.index == renderer.active) {
                        // Play once: from the start each time the wallpaper comes into view
                        if (once && (restart || !p.isPlaying)) p.seekTo(0)
                        if (!p.isPlaying) p.start()
                    } else if (p.isPlaying) {
                        p.pause()
                    }
                }
            }
        }

        private fun releaseSlot(slot: Slot) {
            releasePlayer(slot)
            if (slot.shownKey != null) renderer.clear(slot.index)
            slot.shownKey = null
            slot.loadingKey = null
            slot.colors = null
        }

        /** Gets the slot's target loaded and into the renderer, unless it already is. */
        private fun load(slot: Slot) {
            val item = slot.target
            if (item == null) {
                if (slot.index == 0 && slot.shownKey != null) releaseSlot(slot)
                return
            }
            if (!renderer.ready) return
            val k = key(item)
            if (k == slot.shownKey || k == slot.loadingKey) return
            slot.loadingKey = k
            if (item.video) {
                val p = MediaPlayer()
                runCatching {
                    p.setDataSource(applicationContext, Uri.parse(item.uri))
                    p.setVolume(0f, 0f)
                    p.setOnPreparedListener { videoReady(slot, item, it) }
                    p.setOnErrorListener { mp, _, _ -> if (slot.loadingKey == k) slot.loadingKey = null; mp.release(); true }
                    p.prepareAsync()
                }.onFailure { p.release(); slot.loadingKey = null }
                scope.launch(Dispatchers.IO) { frameColors(item)?.let { c -> gl.post { gotColors(slot, k, c) } } }
            } else {
                scope.launch(Dispatchers.IO) {
                    val bitmap = decode(item) ?: run { gl.post { if (slot.loadingKey == k) slot.loadingKey = null }; return@launch }
                    val c = colorsOf(bitmap)
                    gl.post {
                        if (slot.loadingKey != k || !renderer.ready) {
                            bitmap.recycle()
                            return@post
                        }
                        releasePlayer(slot)
                        renderer.showImage(slot.index, bitmap, item.crop)
                        bitmap.recycle()
                        slot.loadingKey = null
                        slot.shownKey = k
                        c?.let { gotColors(slot, k, it) }
                    }
                }
            }
        }

        private fun videoReady(slot: Slot, item: Item, p: MediaPlayer) {
            val k = key(item)
            if (slot.loadingKey != k || !renderer.ready) {
                p.release()
                return
            }
            releasePlayer(slot)
            p.setSurface(renderer.videoSurface(slot.index, item.crop, { renderer.videoFrame(slot.index) }, gl))
            renderer.videoSize(slot.index, p.videoWidth, p.videoHeight)
            p.setOnVideoSizeChangedListener { _, w, h -> renderer.videoSize(slot.index, w, h) }
            p.isLooping = app.library.data.value.settings.videoEnd == VideoEnd.LOOP
            p.setOnErrorListener { _, _, _ -> true }
            slot.player = p
            slot.loadingKey = null
            slot.shownKey = k
            // Started for its first frame; paused again unless it's the one on screen
            p.start()
            if (!visibleNow || slot.index != renderer.active) p.pause()
        }

        private fun gotColors(slot: Slot, k: String, c: WallpaperColors) {
            if (slot.shownKey != k && slot.loadingKey != k) return
            slot.colors = c
            if (slot.index == colorSlot && slot.shownKey == k) main.post { setColors(c) }
        }

        /** Tells Android, and so Material You and the launcher, the wallpaper's colours. */
        private fun setColors(c: WallpaperColors) {
            if (c == colors) return
            colors = c
            notifyColorsChanged()
        }

        private fun releasePlayer(slot: Slot) {
            slot.player?.let { runCatching { it.reset(); it.release() } }
            slot.player = null
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

        /**
         * The wallpaper's colours, worked out like Wallpaper Changer: from the whole picture, and
         * with Darken on, its three main colours darkened the same way.
         */
        private fun colorsOf(bitmap: Bitmap): WallpaperColors? = runCatching {
            dimmed(WallpaperColors.fromBitmap(bitmap))
        }.getOrNull()

        private fun dimmed(c: WallpaperColors): WallpaperColors {
            val dim = app.library.data.value.settings.darken / 100f
            if (dim <= 0f) return c
            val f = 1f - dim
            fun d(color: android.graphics.Color) = android.graphics.Color.valueOf(color.red() * f, color.green() * f, color.blue() * f)
            return WallpaperColors(d(c.primaryColor), c.secondaryColor?.let(::d), c.tertiaryColor?.let(::d))
        }

        private fun frameColors(item: Item): WallpaperColors? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(applicationContext, Uri.parse(item.uri))
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)?.let { f ->
                    dimmed(WallpaperColors.fromBitmap(f)).also { f.recycle() }
                }
            }
        }.getOrNull()
    }

    /** What an engine shows: the home screen's, the lock screen's (if it has its own), and which is on screen. */
    private data class Plan(val home: Item?, val lock: Item?, val active: Int)

    private companion object {
        const val MAX_TEXTURE = 4096
        /** How long Wallpaper Changer waits after the screen goes off before changing. */
        const val CHANGE_DELAY_MS = 1000L
    }
}
