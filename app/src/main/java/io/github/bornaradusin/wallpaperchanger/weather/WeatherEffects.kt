package io.github.bornaradusin.wallpaperchanger.weather

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * The weather drawn over the wallpaper, in the spirit of Samsung's weather wallpaper effects: fine
 * slanted rain in three depths with drops forming and sliding on the glass, lightning in a storm,
 * soft snow drifting in three depths, slow fog and cloud shadows, a warm sun glow, and stars at
 * night. All of it is one shader over the whole screen, drawn after the wallpaper; a layer the
 * weather doesn't need costs nothing. Render thread only.
 */
class WeatherEffects {

    /** How much of each layer shows (0 … 1), from the weather and the time of day. */
    data class Mix(
        val rain: Float = 0f,
        val drops: Float = 0f,
        val snow: Float = 0f,
        val fog: Float = 0f,
        val cloud: Float = 0f,
        val sun: Float = 0f,
        val stars: Float = 0f,
        val night: Float = 0f,
        val storm: Boolean = false,
        val wind: Float = 0f,
    ) {
        val any: Boolean get() = rain > 0f || drops > 0f || snow > 0f || fog > 0f || cloud > 0f || sun > 0f || stars > 0f || night > 0f || storm
    }

    var mix = Mix()

    /** Called on the render thread as each lightning strike starts (for the buzz). */
    var onStrike: (() -> Unit)? = null

    private var program = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    // Lightning: the next strike's time, and the current one's start (seconds)
    private var nextStrike = 4f
    private var strikeAt = -100f
    private val random = Random(System.nanoTime())

    /** Makes the shader on the current GL context (again after the surface comes back). */
    fun start() {
        program = build(VERTEX, FRAGMENT)
        nextStrike = 4f
        strikeAt = -100f
    }

    /** Draws over what's on screen; [time] in seconds since the effects started. */
    fun draw(width: Int, height: Int, time: Float) {
        val m = mix
        if (program == 0 || !m.any) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)   // the shader gives premultiplied colour
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(pos)
        fun u(name: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), v)
        // Shader time wraps so it keeps its precision on phones with only medium floats
        u("uTime", time % 600f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRes"), width.toFloat(), height.toFloat())
        u("uRain", m.rain); u("uDrops", m.drops); u("uSnow", m.snow); u("uFog", m.fog); u("uCloud", m.cloud)
        u("uSun", m.sun); u("uStars", m.stars); u("uNight", m.night); u("uWind", m.wind)
        u("uFlash", if (m.storm) flash(time) else 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** A storm's lightning: every 7–18 s a strike, a bright flash and a weaker second one. */
    private fun flash(time: Float): Float {
        if (time >= nextStrike) {
            strikeAt = time
            onStrike?.invoke()
            nextStrike = time + 7f + random.nextFloat() * 11f
        }
        val t = time - strikeAt
        if (t < 0f || t > 0.6f) return 0f
        val first = if (t < 0.12f) 1f - t / 0.12f else 0f
        val second = if (t in 0.2f..0.6f) 0.6f * (1f - (t - 0.2f) / 0.4f) else 0f
        return maxOf(first, second)
    }

    private fun build(vertex: String, fragment: String): Int {
        fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.w("WeatherEffects", "compile: " + GLES20.glGetShaderInfoLog(it))
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                android.util.Log.w("WeatherEffects", "shader: " + GLES20.glGetProgramInfoLog(it))
                GLES20.glDeleteProgram(it)
                failed = true
                return 0
            }
        }
    }

    companion object {

        /** The phone's graphics couldn't build the effects: the settings say so. */
        @Volatile var failed = false

        /** The layers for this weather now: subtle, as a wallpaper effect should be. */
        fun mixFor(w: WeatherNow?, now: Long = System.currentTimeMillis()): Mix {
            if (w == null) return Mix()
            val day = w.isDay(now)
            val night = if (day) 0f else 1f
            return when (w.sky) {
                Sky.CLEAR -> Mix(sun = if (day) 1f else 0f, stars = night, night = night * 0.5f)
                Sky.PARTLY -> Mix(sun = if (day) 0.6f else 0f, stars = night * 0.5f, cloud = 0.45f, night = night * 0.5f, wind = w.wind)
                Sky.CLOUDY -> Mix(cloud = 0.85f, night = night * 0.6f, wind = w.wind)
                Sky.FOG -> Mix(fog = 1f, cloud = 0.4f, night = night * 0.6f, wind = w.wind)
                Sky.RAIN -> Mix(rain = w.intensity, drops = w.intensity, cloud = 0.55f, night = night * 0.6f, wind = w.wind)
                Sky.SNOW -> Mix(snow = w.intensity, cloud = 0.5f, night = night * 0.6f, wind = w.wind)
                Sky.THUNDER -> Mix(rain = w.intensity, drops = w.intensity, cloud = 0.9f, night = 0.35f + night * 0.4f, storm = true, wind = w.wind)
            }
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform float uTime;
            uniform vec2 uRes;
            uniform float uRain, uDrops, uSnow, uFog, uCloud, uSun, uStars, uNight, uFlash, uWind;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float noise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
            }
            float fbm(vec2 p) {
                float v = 0.0, a = 0.5;
                for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; }
                return v;
            }

            // Falling streaks: each column falls at its own speed and offset, so nothing lines up;
            // each streak has its own place, length, thickness and brightness, bright at its head
            float rainLayer(vec2 uv, float scale, float speed, float density, float seed) {
                uv.x += uv.y * uWind * 0.35;
                vec2 p = uv * vec2(scale, scale * 0.22);
                float col = floor(p.x);
                float hc = hash(vec2(col, seed));
                p.y += uTime * speed * (0.7 + 0.6 * hc) + hc * 37.0;
                vec2 c = vec2(col, floor(p.y)) + seed;
                vec2 f = fract(p);
                float on = step(hash(c), density);
                float x = abs(f.x - (0.15 + 0.7 * hash(c + 1.3)));
                float width = 0.03 + 0.05 * hash(c + 2.9);
                float len = 0.2 + 0.6 * hash(c + 4.1);
                float t = (f.y - hash(c + 6.7) * (1.0 - len)) / len;
                float along = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.25, 1.0, t));
                float bright = 0.45 + 0.55 * hash(c + 8.8);
                return on * (1.0 - smoothstep(0.0, width, x)) * along * bright;
            }

            // Drops on the glass: they gather, then a few run down and are gone
            vec2 dropsLayer(vec2 uv, float scale) {
                vec2 p = uv * scale;
                vec2 c = floor(p), f = fract(p) - 0.5;
                float h = hash(c);
                float life = fract(uTime * 0.05 + h * 7.0);
                vec2 o = vec2(hash(c + 3.1) - 0.5, hash(c + 7.7) - 0.5) * 0.5;
                float slide = step(0.75, hash(c + 2.2)) * max(0.0, life - 0.55) * 2.2;
                o.y -= slide;
                float r = (0.07 + 0.1 * hash(c + 5.0)) * smoothstep(0.0, 0.25, life);
                vec2 d = (f - o) * vec2(1.0, 0.8);
                float dist = length(d);
                float body = (1.0 - smoothstep(r * 0.6, r, dist)) * step(0.45, h) * (1.0 - smoothstep(0.85, 1.0, life));
                // a bright spot up and to the left, a dark rim below: light through a bead of water
                float glint = (1.0 - smoothstep(0.0, r * 0.45, length(d - vec2(-r * 0.3, r * 0.35)))) * body;
                return vec2(body, glint);
            }

            // Soft flakes, swaying as they fall
            float snowLayer(vec2 uv, float scale, float speed, float size) {
                vec2 p = uv * scale;
                p.y += uTime * speed;
                p.x += uTime * uWind * speed * 0.5;
                vec2 c = floor(p);
                p.x += sin(c.y * 1.7 + uTime * 0.8 + hash(c) * 6.28) * 0.18;
                c = floor(p);
                vec2 f = fract(p);
                vec2 at = vec2(0.2 + 0.6 * hash(c + 1.7), 0.2 + 0.6 * hash(c + 4.3));
                float s = size * (0.6 + 0.8 * hash(c + 9.1));
                return (1.0 - smoothstep(s * 0.15, s, length(f - at))) * step(0.3, hash(c));
            }

            vec4 over(vec4 acc, vec3 col, float a) { return vec4(col * a + acc.rgb * (1.0 - a), a + acc.a * (1.0 - a)); }

            void main() {
                vec2 uv = gl_FragCoord.xy / uRes.y;       // square units; y up
                float top = gl_FragCoord.y / uRes.y;       // 0 bottom … 1 top
                float aspect = uRes.x / uRes.y;
                vec4 acc = vec4(0.0);

                if (uNight > 0.0) acc = over(acc, vec3(0.02, 0.03, 0.08), 0.22 * uNight);

                // Clouds: soft, lit from above, drifting across the top of the screen with the wind
                if (uCloud > 0.0) {
                    float drift = uTime * 0.012 * (0.5 + abs(uWind)) * (uWind < 0.0 ? -1.0 : 1.0);
                    vec2 q = vec2(uv.x * 0.9 + drift, uv.y * 1.7);
                    float n = fbm(q * 1.4);
                    float detail = fbm(q * 3.3 + 5.0);
                    float shape = smoothstep(0.42, 0.72, n * 0.75 + detail * 0.4);
                    float high = smoothstep(0.3, 0.9, top);
                    vec3 lit = mix(vec3(0.95, 0.96, 0.98), vec3(0.32, 0.35, 0.42), uNight);
                    vec3 col = mix(lit * 0.62, lit, smoothstep(0.35, 0.7, detail));
                    acc = over(acc, vec3(0.06, 0.07, 0.1), 0.14 * uCloud * high);
                    acc = over(acc, col, 0.7 * shape * high * uCloud);
                }

                // Mist: thin wisps drifting low along the bottom, never over the whole picture
                if (uFog > 0.0) {
                    float n = fbm(vec2(uv.x * 2.2 + uTime * 0.025, uv.y * 6.0 - uTime * 0.006));
                    float band = 1.0 - smoothstep(0.0, 0.4, top);
                    float wisp = smoothstep(0.48, 0.78, n);
                    acc = over(acc, vec3(0.86, 0.88, 0.91), 0.32 * wisp * band * uFog);
                }

                if (uSun > 0.0) {
                    vec2 sun = vec2(aspect * 0.85, 1.05);
                    vec2 d = uv - sun;
                    float dist = length(d);
                    float glow = exp(-dist * 2.6) * 0.45 + exp(-dist * 9.0) * 0.35;
                    float ang = atan(d.y, d.x);
                    float rays = pow(0.5 + 0.5 * sin(ang * 14.0 + uTime * 0.15), 6.0) * exp(-dist * 1.8) * 0.18;
                    acc = over(acc, vec3(1.0, 0.86, 0.6), clamp((glow + rays) * uSun, 0.0, 0.6));
                }

                if (uStars > 0.0) {
                    vec2 p = uv * 60.0;
                    vec2 c = floor(p), f = fract(p);
                    float h = hash(c);
                    float star = step(0.985, h) * (1.0 - smoothstep(0.0, 0.08, length(f - vec2(hash(c + 2.0), hash(c + 5.0)))));
                    float twinkle = 0.55 + 0.45 * sin(uTime * (1.0 + 2.0 * hash(c + 8.0)) + h * 40.0);
                    float sky = smoothstep(0.35, 0.9, top);
                    acc = over(acc, vec3(1.0), clamp(star * twinkle * sky * uStars, 0.0, 1.0));
                }

                if (uRain > 0.0) {
                    float l1 = rainLayer(uv, 75.0, 2.4, 0.5 * uRain, 1.0);
                    float l2 = rainLayer(uv, 48.0, 3.1, 0.42 * uRain, 17.0);
                    float l3 = rainLayer(uv, 30.0, 4.0, 0.32 * uRain, 43.0);
                    float l4 = rainLayer(uv, 17.0, 5.2, 0.2 * uRain, 71.0);
                    float a = l1 * 0.25 + l2 * 0.35 + l3 * 0.5 + l4 * 0.65;
                    acc = over(acc, vec3(0.85, 0.9, 1.0), clamp(a, 0.0, 0.75));
                }

                if (uDrops > 0.0) {
                    vec2 d1 = dropsLayer(uv, 7.0);
                    vec2 d2 = dropsLayer(uv + 11.3, 12.0);
                    float body = max(d1.x, d2.x * 0.8) * uDrops;
                    float glint = max(d1.y, d2.y) * uDrops;
                    acc = over(acc, vec3(0.75, 0.82, 0.9), 0.3 * body);
                    acc = over(acc, vec3(1.0), 0.7 * glint);
                }

                if (uSnow > 0.0) {
                    float far = snowLayer(uv, 26.0, 0.35, 0.05);
                    float mid = snowLayer(uv + 4.2, 14.0, 0.55, 0.07);
                    float near = snowLayer(uv + 8.9, 7.0, 0.8, 0.09);
                    float a = (far * 0.45 + mid * 0.7 + near * 0.85) * uSnow;
                    acc = over(acc, vec3(1.0), clamp(a, 0.0, 0.9));
                }

                if (uFlash > 0.0) acc = over(acc, vec3(0.9, 0.93, 1.0), 0.55 * uFlash * (0.6 + 0.4 * top));

                gl_FragColor = acc;
            }
        """
    }
}
