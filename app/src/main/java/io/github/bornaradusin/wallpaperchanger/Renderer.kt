package io.github.bornaradusin.wallpaperchanger

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the wallpaper with OpenGL, so pictures and videos can take turns on the same surface: a
 * picture as an ordinary texture, a video as frames from a [SurfaceTexture]. Everything here runs
 * on the engine's own render thread, never the phone's main thread.
 */
class Renderer {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var imageProgram = 0
    private var videoProgram = 0

    private var width = 1
    private var height = 1

    private enum class Showing { NOTHING, IMAGE, VIDEO }

    /**
     * Two things can be loaded at once: the home screen's and the lock screen's. Only the
     * [active] one is drawn, so switching between them on unlock is instant.
     */
    private inner class Slot {
        var imageTexture = 0
        var videoTexture = 0
        var showing = Showing.NOTHING
        var contentWidth = 1
        var contentHeight = 1
        var crop: Crop? = null
        /** A new video waits for its first frame; until then what was there stays. */
        var waitingForFrame = false
        var surfaceTexture: SurfaceTexture? = null
        val videoMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    private val slots = arrayOf(Slot(), Slot())
    var active = 0
        private set

    var scaling = Scaling.FILL
    var darken = 0f
    /** 0 … 1: where the home screen has been scrolled to (0.5 when it doesn't scroll). */
    var offset = 0.5f

    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    val ready: Boolean get() = surface != EGL14.EGL_NO_SURFACE

    fun start(window: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], window, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        imageProgram = program(VERTEX, FRAGMENT_IMAGE)
        videoProgram = program(VERTEX, FRAGMENT_VIDEO)
        val textures = IntArray(4)
        GLES20.glGenTextures(4, textures, 0)
        slots.forEachIndexed { i, slot ->
            slot.imageTexture = textures[i * 2]
            slot.videoTexture = textures[i * 2 + 1]
            setUp(GLES20.GL_TEXTURE_2D, slot.imageTexture)
            setUp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, slot.videoTexture)
        }
        GLES20.glViewport(0, 0, width, height)
    }

    fun resize(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        if (ready) GLES20.glViewport(0, 0, width, height)
    }

    fun stop() {
        slots.forEach {
            it.surfaceTexture?.release()
            it.surfaceTexture = null
            it.showing = Showing.NOTHING
            it.waitingForFrame = false
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    /** Which loaded one is drawn. */
    fun setActive(slot: Int) {
        if (slot == active) return
        active = slot
        draw()
    }

    /** Puts a picture in a slot (and on screen, if it's the active one). */
    fun showImage(slot: Int, bitmap: Bitmap, crop: Crop?) {
        if (!ready) return
        val s = slots[slot]
        s.surfaceTexture?.release()
        s.surfaceTexture = null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s.imageTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        s.contentWidth = bitmap.width
        s.contentHeight = bitmap.height
        s.crop = crop
        s.showing = Showing.IMAGE
        s.waitingForFrame = false
        if (slot == active) draw()
    }

    /** A surface for a video to play into a slot. What's there stays until its first frame comes. */
    fun videoSurface(slot: Int, crop: Crop?, onFrame: () -> Unit, handler: Handler): Surface {
        val s = slots[slot]
        s.surfaceTexture?.release()
        val texture = SurfaceTexture(s.videoTexture)
        texture.setOnFrameAvailableListener({ onFrame() }, handler)
        s.surfaceTexture = texture
        s.crop = crop
        s.waitingForFrame = true
        return Surface(texture)
    }

    fun videoSize(slot: Int, w: Int, h: Int) {
        if (w > 0 && h > 0) {
            slots[slot].contentWidth = w
            slots[slot].contentHeight = h
        }
    }

    fun videoFrame(slot: Int) {
        val s = slots[slot]
        val texture = s.surfaceTexture ?: return
        if (!ready) return
        texture.updateTexImage()
        texture.getTransformMatrix(s.videoMatrix)
        s.showing = Showing.VIDEO
        s.waitingForFrame = false
        if (slot == active) draw()
    }

    fun clear(slot: Int) {
        val s = slots[slot]
        s.surfaceTexture?.release()
        s.surfaceTexture = null
        s.showing = Showing.NOTHING
        s.waitingForFrame = false
        if (slot == active) draw()
    }

    fun draw() {
        val s = slots[active]
        if (!ready || s.waitingForFrame) return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        when (s.showing) {
            Showing.NOTHING -> Unit
            Showing.IMAGE -> drawQuad(s, imageProgram, GLES20.GL_TEXTURE_2D, s.imageTexture, video = false, identity)
            Showing.VIDEO -> drawQuad(s, videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.videoTexture, video = true, s.videoMatrix)
        }
        EGL14.eglSwapBuffers(display, surface)
    }

    /**
     * Where on screen the content goes, and which part of it shows, for the chosen [scaling].
     * Fills [vertices] with x, y, u, v for a strip of four corners.
     */
    private fun layout(slot: Slot, video: Boolean) {
        val c = slot.crop
        val contentWidth = slot.contentWidth
        val contentHeight = slot.contentHeight
        // The part of the picture in use (all of it, or the cropped part), in texture terms
        val cl = c?.l ?: 0f
        val ct = c?.t ?: 0f
        val cw = c?.width ?: 1f
        val ch = c?.height ?: 1f
        val w = contentWidth * cw
        val h = contentHeight * ch

        // Screen rectangle (-1 … 1) and visible part of the content (0 … 1)
        var x0 = -1f; var x1 = 1f; var y0 = -1f; var y1 = 1f
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        when (scaling) {
            Scaling.STRETCH -> Unit
            Scaling.FILL -> {
                val scale = max(width / w, height / h)
                val tw = (width / scale) / w
                val th = (height / scale) / h
                u0 = (1f - tw) * offset; u1 = u0 + tw
                v0 = (1f - th) / 2f; v1 = v0 + th
            }
            Scaling.FIT -> {
                val scale = min(width / w, height / h)
                val dw = w * scale / width
                val dh = h * scale / height
                x0 = -dw; x1 = dw; y0 = -dh; y1 = dh
            }
            Scaling.NONE -> {
                // As it is, pixel for pixel, in the middle
                val dw = min(w, width.toFloat())
                val dh = min(h, height.toFloat())
                x0 = -dw / width; x1 = dw / width; y0 = -dh / height; y1 = dh / height
                val tw = dw / w
                val th = dh / h
                u0 = (1f - tw) * offset; u1 = u0 + tw
                v0 = (1f - th) / 2f; v1 = v0 + th
            }
        }
        // Into the cropped part
        u0 = cl + u0 * cw; u1 = cl + u1 * cw
        v0 = ct + v0 * ch; v1 = ct + v1 * ch
        // Pictures have v going down; video frames come the other way up
        fun v(top: Boolean) = if (video) 1f - (if (top) v0 else v1) else (if (top) v0 else v1)

        vertices.position(0)
        vertices.put(floatArrayOf(
            x0, y0, u0, v(false),
            x1, y0, u1, v(false),
            x0, y1, u0, v(true),
            x1, y1, u1, v(true),
        ))
        vertices.position(0)
    }

    private fun drawQuad(slot: Slot, program: Int, target: Int, texture: Int, video: Boolean, texMatrix: FloatArray) {
        layout(slot, video)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        vertices.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(pos)
        vertices.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDim"), darken)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setUp(target: Int, texture: Int) {
        GLES20.glBindTexture(target, texture)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTex * vec4(aTex.xy, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_IMAGE = """
            precision mediump float;
            uniform sampler2D sTex;
            uniform float uDim;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(sTex, vTex);
                gl_FragColor = vec4(c.rgb * (1.0 - uDim), 1.0);
            }
        """
        const val FRAGMENT_VIDEO = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            uniform float uDim;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(sTex, vTex);
                gl_FragColor = vec4(c.rgb * (1.0 - uDim), 1.0);
            }
        """
    }
}
