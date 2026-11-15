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
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the wallpaper with OpenGL, so a picture and a video can take turns on the same surface:
 * a picture as an ordinary texture, a video as frames from a [SurfaceTexture]. Both are cropped to
 * fill the screen. Everything here runs on one thread, the engine's render thread.
 */
class Renderer {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    private var imageProgram = 0
    private var videoProgram = 0
    private var imageTexture = 0
    private var videoTexture = 0

    private var width = 1
    private var height = 1

    /** What to draw: a picture, a video, or nothing (black). */
    private enum class Showing { NOTHING, IMAGE, VIDEO }
    private var showing = Showing.NOTHING
    private var contentWidth = 1
    private var contentHeight = 1

    private var videoSurfaceTexture: SurfaceTexture? = null
    private val videoMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v: a strip covering the screen, picture right way up
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }
    // Video frames come upside down compared with pictures; the SurfaceTexture's matrix expects this
    private val videoQuad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

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
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        imageProgram = program(VERTEX, FRAGMENT_IMAGE)
        videoProgram = program(VERTEX, FRAGMENT_VIDEO)
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        imageTexture = textures[0]
        videoTexture = textures[1]
        setUp(GLES20.GL_TEXTURE_2D, imageTexture)
        setUp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
    }

    fun resize(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        if (ready) GLES20.glViewport(0, 0, width, height)
    }

    fun stop() {
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        showing = Showing.NOTHING
    }

    /** Shows a picture from now on. */
    fun showImage(bitmap: Bitmap) {
        if (!ready) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        contentWidth = bitmap.width
        contentHeight = bitmap.height
        showing = Showing.IMAGE
        draw()
    }

    /** A surface for a video player to play into; frames show once [videoSize] and [onFrame] come. */
    fun videoSurface(onFrame: () -> Unit, handler: android.os.Handler): Surface {
        videoSurfaceTexture?.release()
        val texture = SurfaceTexture(videoTexture)
        texture.setOnFrameAvailableListener({ onFrame() }, handler)
        videoSurfaceTexture = texture
        return Surface(texture)
    }

    fun videoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            contentWidth = w
            contentHeight = h
        }
    }

    /** A new video frame is waiting: take it and draw it. */
    fun videoFrame() {
        val texture = videoSurfaceTexture ?: return
        if (!ready) return
        texture.updateTexImage()
        texture.getTransformMatrix(videoMatrix)
        showing = Showing.VIDEO
        draw()
    }

    fun clear() {
        showing = Showing.NOTHING
        draw()
    }

    fun draw() {
        if (!ready) return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        when (showing) {
            Showing.NOTHING -> Unit
            Showing.IMAGE -> drawQuad(imageProgram, GLES20.GL_TEXTURE_2D, imageTexture, quad, crop(identity()))
            Showing.VIDEO -> drawQuad(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture, videoQuad, multiply(videoMatrix, crop(identity())))
        }
        EGL14.eglSwapBuffers(display, surface)
    }

    /** Texture coordinates that show the middle of the content, filling the screen without stretching. */
    private fun crop(m: FloatArray): FloatArray {
        val content = contentWidth.toFloat() / contentHeight
        val screen = width.toFloat() / height
        val sx: Float
        val sy: Float
        if (content > screen) {
            sx = screen / content
            sy = 1f
        } else {
            sx = 1f
            sy = content / screen
        }
        Matrix.translateM(m, 0, (1f - sx) / 2f, (1f - sy) / 2f, 0f)
        Matrix.scaleM(m, 0, sx, sy, 1f)
        return m
    }

    private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16).also { Matrix.multiplyMM(it, 0, a, 0, b, 0) }

    private fun drawQuad(program: Int, target: Int, texture: Int, buffer: FloatBuffer, texMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        buffer.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(pos)
        buffer.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
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
                vTex = (uTex * aTex).xy;
            }
        """
        const val FRAGMENT_IMAGE = """
            precision mediump float;
            uniform sampler2D sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
        const val FRAGMENT_VIDEO = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
