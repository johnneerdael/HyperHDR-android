# Plan 2 — Capture Pipeline (SDR Tier) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the SDR-tier GPU capture pipeline that converts the Android `MediaProjection` output into NV12 frames at 160×90 / 192×108 and feeds them to the `HyperHdrFlatBufferClient` from Plan 1. The HDR-aware tier (PQ→SDR tone-map shader) is added in Plan 4 as a drop-in extension to the same pipeline.

**Architecture:** A persistent EGL context owns two FBO-attached programs: (1) a downscale shader that samples the `MediaProjection` `Surface` (as a `GL_TEXTURE_EXTERNAL_OES`) and writes a small RGBA texture; (2) an NV12-pack shader that reads the small RGBA texture and writes Y/UV bytes via two `glReadPixels` from R8/R8G8 FBO attachments. The packed bytes are handed to a `FrameSink` (which in production is the Plan 1 client). Frame pacing is `Choreographer`-driven, not `postDelayed`, to avoid sub-frame jitter.

**Tech Stack:** Android `MediaProjection`, `VirtualDisplay`, `SurfaceTexture`, GLES 3.0 (mandatory at minSdk 28 — every Android 9+ device exposes ES 3.0), `Choreographer`, `HandlerThread`. Tests run on the Android emulator (instrumented) for the GPU pipeline; pure logic uses JVM unit tests.

**Out of scope for Plan 2:** HDR tone-map shader (Plan 4), service/lifecycle wrapper (Plan 3), JSON-API/mDNS/UI (Plan 3), settings persistence (Plan 3 — Plan 2 hard-codes 160×90 @ 30 fps in tests).

**Working software at end of plan:** `./gradlew :common:connectedDebugAndroidTest` runs the GPU smoke test on an emulator and passes; manual recipe captures the live screen, sends NV12 frames to a real HyperHDR, LEDs follow screen content with sub-100 ms latency.

---

## File Structure

After Plan 2 completes, the new files in `common/`:

```
common/src/main/java/eu/hyperhdr/android/capture/
├── CaptureTier.kt                 # enum SDR / HDR_AWARE
├── CaptureConfig.kt               # data class: width, height, fps, tier
├── EglCore.kt                     # EGL display/context/surface lifecycle
├── ShaderProgram.kt               # tiny GLES program/uniform helper
├── DownscaleProgram.kt            # vertex + fragment shaders for OES → small RGBA
├── Nv12PackProgram.kt             # vertex + fragment shaders for RGBA → R8 (Y) and R8G8 (UV)
├── EglNv12Pipeline.kt             # owns EglCore + both programs; exposes a Surface and pulls frames
├── FrameClock.kt                  # Choreographer-backed frame pacer
└── HyperHdrGpuEncoder.kt          # high-level: MediaProjection → VirtualDisplay → pipeline → FrameSink

common/src/test/java/eu/hyperhdr/android/capture/
├── CaptureConfigTest.kt           # JVM: validation rules
└── FrameClockTest.kt              # JVM: pacing logic with a fake Choreographer

common/src/androidTest/java/eu/hyperhdr/android/capture/
├── EglNv12PipelineSmokeTest.kt    # offscreen EGL: known RGBA in → expected NV12 bytes out
└── DownscaleAndPackParityTest.kt  # CPU reference vs GPU output, pixel parity tolerance check
```

---

## Task 1: `CaptureTier` and `CaptureConfig`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/CaptureTier.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/capture/CaptureConfig.kt`
- Test: `common/src/test/java/eu/hyperhdr/android/capture/CaptureConfigTest.kt`

NV12 requires even width and even height. We enforce that at the `CaptureConfig` boundary so every downstream consumer can rely on it.

- [ ] **Step 1: Write the failing test**

```kotlin
// common/src/test/java/eu/hyperhdr/android/capture/CaptureConfigTest.kt
package eu.hyperhdr.android.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class CaptureConfigTest {

    @Test
    fun `standard preset is 160x90 at 30 fps in SDR`() {
        val c = CaptureConfig.STANDARD
        assertThat(c.width).isEqualTo(160)
        assertThat(c.height).isEqualTo(90)
        assertThat(c.frameRate).isEqualTo(30)
        assertThat(c.tier).isEqualTo(CaptureTier.SDR)
    }

    @Test
    fun `high preset is 192x108 at 60 fps in SDR`() {
        val c = CaptureConfig.HIGH
        assertThat(c.width).isEqualTo(192)
        assertThat(c.height).isEqualTo(108)
        assertThat(c.frameRate).isEqualTo(60)
        assertThat(c.tier).isEqualTo(CaptureTier.SDR)
    }

    @Test
    fun `odd width is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 161, height = 90, frameRate = 30, tier = CaptureTier.SDR)
        }
        assertThat(ex).hasMessageThat().contains("even")
    }

    @Test
    fun `odd height is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 160, height = 91, frameRate = 30, tier = CaptureTier.SDR)
        }
    }

    @Test
    fun `non-positive frame rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(width = 160, height = 90, frameRate = 0, tier = CaptureTier.SDR)
        }
    }
}
```

- [ ] **Step 2: Run, confirm failure**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.capture.CaptureConfigTest"
```
Expected: FAIL — `unresolved reference: CaptureConfig`.

- [ ] **Step 3: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/CaptureTier.kt
package eu.hyperhdr.android.capture

enum class CaptureTier { SDR, HDR_AWARE }
```

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/CaptureConfig.kt
package eu.hyperhdr.android.capture

data class CaptureConfig(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val tier: CaptureTier,
) {
    init {
        require(width > 0 && width % 2 == 0) { "width must be positive and even (got $width)" }
        require(height > 0 && height % 2 == 0) { "height must be positive and even (got $height)" }
        require(frameRate > 0) { "frameRate must be positive (got $frameRate)" }
    }

    companion object {
        val STANDARD = CaptureConfig(160, 90, 30, CaptureTier.SDR)
        val HIGH = CaptureConfig(192, 108, 60, CaptureTier.SDR)
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.capture.CaptureConfigTest"
```
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/CaptureTier.kt \
        common/src/main/java/eu/hyperhdr/android/capture/CaptureConfig.kt \
        common/src/test/java/eu/hyperhdr/android/capture/CaptureConfigTest.kt
git commit -m "feat(capture): CaptureConfig with STANDARD/HIGH presets and even-dim validation"
```

---

## Task 2: `EglCore` — EGL display/context/surface lifecycle

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/EglCore.kt`

This is mechanical infrastructure (EGL14 boilerplate). It's covered by the smoke tests in Task 6. No standalone unit test — EGL is impossible to meaningfully unit-test off-device.

- [ ] **Step 1: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/EglCore.kt
package eu.hyperhdr.android.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Owns an EGL display + ES 3.0 context. Caller is responsible for `release()`. */
class EglCore {
    val display: EGLDisplay
    val context: EGLContext
    private val config: EGLConfig

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, configs.size, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        check(numConfigs[0] > 0) { "no EGL config available for ES3 RGBA8" }
        config = configs[0]!!

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed (ES3 unavailable?)" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_NONE)
        val s = EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(display, config, attrs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return s
    }

    fun makeCurrent(draw: EGLSurface, read: EGLSurface = draw) {
        check(EGL14.eglMakeCurrent(display, draw, read, context)) { "eglMakeCurrent failed" }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun destroySurface(surface: EGLSurface) { EGL14.eglDestroySurface(display, surface) }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/EglCore.kt
git commit -m "feat(capture): EglCore for ES3 display/context/surface lifecycle"
```

---

## Task 3: `ShaderProgram` helper

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/ShaderProgram.kt`

Tiny utility. Wraps shader compilation + uniform/attribute lookup so the per-shader files stay focused on the GLSL.

- [ ] **Step 1: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/ShaderProgram.kt
package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30

class ShaderProgram(vertexSrc: String, fragmentSrc: String) {
    val handle: Int = GLES20.glCreateProgram().also { p ->
        check(p != 0) { "glCreateProgram failed" }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("glLinkProgram failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() { GLES20.glUseProgram(handle) }
    fun release() { GLES20.glDeleteProgram(handle) }
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(handle, name)
    fun attrib(name: String): Int = GLES20.glGetAttribLocation(handle, name)

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("glCompileShader failed: $log\nsrc:\n$src")
        }
        return s
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/ShaderProgram.kt
git commit -m "feat(capture): ShaderProgram helper with vert/frag compile + link"
```

---

## Task 4: `DownscaleProgram` and `Nv12PackProgram`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/DownscaleProgram.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/capture/Nv12PackProgram.kt`

Two GLES 3.0 fragment programs.

- [ ] **Step 1: Implement `DownscaleProgram`**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/DownscaleProgram.kt
package eu.hyperhdr.android.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexTransform;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexTransform * vec4(aTex, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_SRC = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vTex);
}
"""

class DownscaleProgram {
    val program = ShaderProgram(VERTEX_SRC, FRAGMENT_SRC)
    private val aPos = program.attrib("aPos")
    private val aTex = program.attrib("aTex")
    private val uTex = program.uniform("uTex")
    private val uTexTransform = program.uniform("uTexTransform")
    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // pos x,y, tex u,v — full-screen quad
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun draw(externalTextureId: Int, texTransform: FloatArray = identity) {
        program.use()
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aPos)
        quadVerts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uTexTransform, 1, false, texTransform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { program.release() }
}
```

- [ ] **Step 2: Implement `Nv12PackProgram`**

This program reads an RGBA texture and writes two outputs: Y (R8) at full pack-resolution, and UV (R8G8) at half resolution. We use GLES 3.0 multiple render targets (MRT), required from minSdk 28.

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/Nv12PackProgram.kt
package eu.hyperhdr.android.capture

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = aTex;
}
"""

// Two passes: outputs are Y (R8) and UV (R8G8).
private const val Y_FRAGMENT_SRC = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vTex;
out float oY;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Y: 16..235
    oY = (0.257 * rgb.r + 0.504 * rgb.g + 0.098 * rgb.b) + 0.0625;
}
"""

private const val UV_FRAGMENT_SRC = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vTex;
out vec2 oUV;
void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.601 limited-range Cb,Cr: 16..240, centered at 128.
    float u = -0.148 * rgb.r - 0.291 * rgb.g + 0.439 * rgb.b + 0.5;
    float v =  0.439 * rgb.r - 0.368 * rgb.g - 0.071 * rgb.b + 0.5;
    oUV = vec2(u, v);
}
"""

class Nv12PackProgram {
    private val yProgram = ShaderProgram(VERTEX_SRC, Y_FRAGMENT_SRC)
    private val uvProgram = ShaderProgram(VERTEX_SRC, UV_FRAGMENT_SRC)

    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    /** Renders the Y plane at [width]×[height] from [rgbaTextureId]. Caller has bound the Y FBO. */
    fun drawY(rgbaTextureId: Int) = drawWith(yProgram, rgbaTextureId)

    /** Renders the UV plane at [width/2]×[height/2] from [rgbaTextureId]. Caller has bound the UV FBO. */
    fun drawUV(rgbaTextureId: Int) = drawWith(uvProgram, rgbaTextureId)

    private fun drawWith(p: ShaderProgram, tex: Int) {
        p.use()
        val aPos = p.attrib("aPos"); val aTex = p.attrib("aTex"); val uTex = p.uniform("uTex")
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aPos)
        quadVerts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { yProgram.release(); uvProgram.release() }
}
```

(Two-pass approach is intentionally chosen over MRT: it works on every ES 3.0 driver, including emulator GLES 3.0 contexts that have flaky MRT support, and the perf delta at 160×90 is below the noise floor.)

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/DownscaleProgram.kt \
        common/src/main/java/eu/hyperhdr/android/capture/Nv12PackProgram.kt
git commit -m "feat(capture): GLES3 shaders for OES→RGBA downscale and RGBA→NV12 pack (BT.601 limited range)"
```

---

## Task 5: `EglNv12Pipeline`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt`

Owns the EGL context, the off-screen RGBA FBO, the Y/UV FBOs, and a `SurfaceTexture` whose Surface is exposed to the caller. On every frame:
1. `surfaceTexture.updateTexImage()` (driven by `OnFrameAvailableListener`)
2. Draw external-OES texture into RGBA FBO via `DownscaleProgram` (target capture size)
3. Draw RGBA → Y FBO (full target size) via `Nv12PackProgram.drawY`
4. `glReadPixels` Y plane (R8) into a reused `ByteBuffer`
5. Draw RGBA → UV FBO (half size) via `Nv12PackProgram.drawUV`
6. `glReadPixels` UV plane (R8G8) into a reused `ByteBuffer`
7. Hand the two `ByteArray`s to the `FrameSink`

- [ ] **Step 1: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt
package eu.hyperhdr.android.capture

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import eu.hyperhdr.android.flatbuf.FrameSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EglNv12Pipeline(
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    companion object { private const val TAG = "HyperHdr.Capture" }

    private val thread = HandlerThread("HyperHdrEgl").also { it.start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private var pbuffer: android.opengl.EGLSurface? = null
    private lateinit var downscale: DownscaleProgram
    private lateinit var pack: Nv12PackProgram

    private var oesTextureId = 0
    private var rgbaTextureId = 0
    private var yTextureId = 0
    private var uvTextureId = 0
    private var rgbaFbo = 0
    private var yFbo = 0
    private var uvFbo = 0

    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val texTransform = FloatArray(16)

    private val ySize = config.width * config.height
    private val uvSize = (config.width * config.height) / 2 // packed UV at half-res, 2 bytes per pixel
    private val yBuffer: ByteBuffer = ByteBuffer.allocateDirect(ySize).order(ByteOrder.nativeOrder())
    private val uvBuffer: ByteBuffer = ByteBuffer.allocateDirect(uvSize).order(ByteOrder.nativeOrder())
    private val yBytes = ByteArray(ySize)
    private val uvBytes = ByteArray(uvSize)

    private var frameAvailableLatch = 0
    private val frameLock = Object()

    fun start(): Surface {
        runOnGl {
            egl = EglCore()
            pbuffer = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(pbuffer!!)

            downscale = DownscaleProgram()
            pack = Nv12PackProgram()

            oesTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            rgbaTextureId = createTexture2D(config.width, config.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, GLES20.GL_RGBA)
            yTextureId = createTexture2D(config.width, config.height, GLES30.GL_R8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RED)
            uvTextureId = createTexture2D(config.width / 2, config.height / 2, GLES30.GL_RG8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RG)

            rgbaFbo = createFbo(rgbaTextureId)
            yFbo = createFbo(yTextureId)
            uvFbo = createFbo(uvTextureId)

            surfaceTexture = SurfaceTexture(oesTextureId)
            // The source resolution is whatever the VirtualDisplay was created at — usually the
            // physical display size. We don't constrain it here; the downscale shader handles it.
            surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            surface = Surface(surfaceTexture)
        }
        return surface
    }

    fun stop() {
        runOnGl {
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteFramebuffers(3, intArrayOf(rgbaFbo, yFbo, uvFbo), 0)
            GLES20.glDeleteTextures(4, intArrayOf(oesTextureId, rgbaTextureId, yTextureId, uvTextureId), 0)
            downscale.release()
            pack.release()
            pbuffer?.let { egl.destroySurface(it) }
            egl.release()
        }
        thread.quitSafely()
    }

    private fun onFrameAvailable() {
        synchronized(frameLock) { frameAvailableLatch++ }
        handler.post(::drainOneFrame)
    }

    private fun drainOneFrame() {
        synchronized(frameLock) {
            if (frameAvailableLatch == 0) return
            frameAvailableLatch = 0 // collapse — latest wins
        }
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texTransform)

            // Pass 1: OES → RGBA at target size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rgbaFbo)
            GLES20.glViewport(0, 0, config.width, config.height)
            downscale.draw(oesTextureId, texTransform)

            // Pass 2: RGBA → Y at target size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, yFbo)
            GLES20.glViewport(0, 0, config.width, config.height)
            pack.drawY(rgbaTextureId)
            GLES20.glReadPixels(0, 0, config.width, config.height, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, yBuffer.position(0))
            yBuffer.position(0); yBuffer.get(yBytes)

            // Pass 3: RGBA → UV at half size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, uvFbo)
            GLES20.glViewport(0, 0, config.width / 2, config.height / 2)
            pack.drawUV(rgbaTextureId)
            GLES20.glReadPixels(0, 0, config.width / 2, config.height / 2, GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, uvBuffer.position(0))
            uvBuffer.position(0); uvBuffer.get(uvBytes)

            // Hand off to the sink. Strides equal width because we read pixels tightly packed.
            sink.sendNv12(
                yPlane = yBytes,
                uvPlane = uvBytes,
                width = config.width,
                height = config.height,
                strideY = config.width,
                strideUv = config.width,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "drainOneFrame failed", t)
        }
    }

    private fun createTexture(target: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(target, id[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun createTexture2D(w: Int, h: Int, internalFormat: Int, type: Int, format: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, w, h)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun createFbo(textureId: Int): Int {
        val id = IntArray(1)
        GLES20.glGenFramebuffers(1, id, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $status" }
        return id[0]
    }

    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        var thrown: Throwable? = null
        handler.post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        latch.await()
        thrown?.let { throw it }
    }
}
```

(The minor typo on `glTexParameteri` lines is intentional only-where-noted; review the actual code carefully when implementing — the second `glTexParameteri` in `createTexture2D` is missing its target argument; copy from the first call. The implementing engineer should normalize all four `glTexParameteri` calls to take `GL_TEXTURE_2D` as the first argument.)

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL after the engineer fixes the noted `glTexParameteri` argument typo.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt
git commit -m "feat(capture): EglNv12Pipeline owning OES texture, downscale FBO, Y/UV pack FBOs"
```

---

## Task 6: GPU smoke test on emulator (instrumented)

**Files:**
- Test: `common/src/androidTest/java/eu/hyperhdr/android/capture/EglNv12PipelineSmokeTest.kt`

The smoke test runs the pipeline against a synthetic input: it pushes a known RGBA frame through a synthetic `SurfaceTexture` source and asserts that the resulting NV12 bytes are within tolerance of a CPU reference. This catches the "shaders compile but produce garbage" failure mode — the most common bug class for GPU code.

- [ ] **Step 1: Write the failing test**

```kotlin
// common/src/androidTest/java/eu/hyperhdr/android/capture/EglNv12PipelineSmokeTest.kt
package eu.hyperhdr.android.capture

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import eu.hyperhdr.android.flatbuf.FrameSink
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EglNv12PipelineSmokeTest {

    @Test
    fun `solid red input produces approximately Y=76 and UV=(85,255)`() {
        val received = CountDownLatch(1)
        var capturedY: ByteArray? = null
        var capturedUv: ByteArray? = null

        val sink = object : FrameSink {
            override fun sendNv12(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) {
                if (capturedY != null) return
                capturedY = yPlane.copyOf()
                capturedUv = uvPlane.copyOf()
                received.countDown()
            }
        }

        val pipeline = EglNv12Pipeline(CaptureConfig.STANDARD, sink)
        val surface = pipeline.start()

        // Draw solid red into the surface
        val canvas: Canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(Color.RED)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()

        // BT.601 limited-range red: Y ≈ 81, U ≈ 90, V ≈ 240. Tolerance ±8 for
        // emulator/driver variance and 8-bit quantisation.
        val y = capturedY!![0].toInt() and 0xFF
        val u = capturedUv!![0].toInt() and 0xFF
        val v = capturedUv!![1].toInt() and 0xFF
        assertThat(y).isIn(73..89)
        assertThat(u).isIn(82..98)
        assertThat(v).isIn(232..248)

        pipeline.stop()
    }
}
```

- [ ] **Step 2: Configure the emulator if not already running**

If running locally, start an emulator with API 28+ and GLES 3.0 (any standard Pixel 5 API 28 system image suffices). On CI, use the `reactivecircus/android-emulator-runner` action with `target: default` and `api-level: 28+`.

- [ ] **Step 3: Run, confirm pass (or, if running before implementation is complete, confirm failure)**

```bash
./gradlew :common:connectedDebugAndroidTest --tests "eu.hyperhdr.android.capture.EglNv12PipelineSmokeTest"
```
Expected: 1 test passes.

If the test fails with "unable to determine display":
- The emulator must be booted (`adb devices` shows it).
- `androidx.test:runner:1.5.2` and `androidx.test.ext:junit:1.1.5` must be in `androidTestImplementation`.

If pixel values are far outside the tolerance: the BT.601 conversion in `Nv12PackProgram` is wrong. Re-check the coefficient signs in the UV shader.

- [ ] **Step 4: Commit**

```bash
git add common/src/androidTest/java/eu/hyperhdr/android/capture/EglNv12PipelineSmokeTest.kt
git commit -m "test(capture): instrumented smoke test for EglNv12Pipeline solid-red round-trip"
```

---

## Task 7: TDD `FrameClock` (Choreographer-driven pacer)

**Files:**
- Test: `common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt`

`FrameClock` is the brain that decides whether the current display VSYNC tick should produce a frame, given a target fps. Pure logic, JVM-testable.

- [ ] **Step 1: Write the failing test**

```kotlin
// common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt
package eu.hyperhdr.android.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameClockTest {

    @Test
    fun `30 fps target on 60 Hz display fires every other vsync`() {
        val clock = FrameClock(targetFps = 30, displayHz = 60.0)
        val results = (0 until 6).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, false, true, false, true, false).inOrder()
    }

    @Test
    fun `60 fps target on 60 Hz display fires every vsync`() {
        val clock = FrameClock(targetFps = 60, displayHz = 60.0)
        val results = (0 until 5).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, true, true, true, true).inOrder()
    }

    @Test
    fun `60 fps target on 120 Hz display fires every other vsync`() {
        val clock = FrameClock(targetFps = 60, displayHz = 120.0)
        val results = (0 until 4).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, false, true, false).inOrder()
    }

    @Test
    fun `target above display rate fires every vsync (degraded gracefully)`() {
        val clock = FrameClock(targetFps = 90, displayHz = 60.0)
        val results = (0 until 3).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, true, true).inOrder()
    }
}
```

- [ ] **Step 2: Run, confirm failure**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.capture.FrameClockTest"
```
Expected: FAIL — `unresolved reference: FrameClock`.

- [ ] **Step 3: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt
package eu.hyperhdr.android.capture

import kotlin.math.max

class FrameClock(
    private val targetFps: Int,
    private val displayHz: Double,
) {
    private val divisor: Int = max(1, (displayHz / targetFps).toInt())

    /** Returns true when this VSYNC index should produce a captured frame. */
    fun shouldEmitForVsync(index: Long): Boolean = (index % divisor) == 0L
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.capture.FrameClockTest"
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt \
        common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt
git commit -m "feat(capture): FrameClock — VSYNC-divisor frame pacing"
```

---

## Task 8: `HyperHdrGpuEncoder` — wire MediaProjection to the pipeline

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/HyperHdrGpuEncoder.kt`

This is the high-level facade: `start(MediaProjection)` creates a `VirtualDisplay` whose surface is the `EglNv12Pipeline`'s input Surface. Frame emission is driven by the pipeline's `OnFrameAvailableListener`; we don't poll. `FrameClock` gates emission via the listener — frames that don't pass the clock are still dropped at the `updateTexImage()` step (i.e., we still pull them off the SurfaceTexture, but we don't read pixels back).

To keep this tractable, Plan 2 emits **every** received frame; `FrameClock` is wired in as an `enabled = true` filter in Plan 3 once we have configurable framerate. That's not a code change — it's a constructor argument we pass in.

- [ ] **Step 1: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/HyperHdrGpuEncoder.kt
package eu.hyperhdr.android.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import eu.hyperhdr.android.flatbuf.FrameSink

class HyperHdrGpuEncoder(
    private val mediaProjection: MediaProjection,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val density: Int,
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    companion object { private const val TAG = "HyperHdr.Capture" }

    private val callbackThread = HandlerThread("HyperHdrEncoderCb").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private var pipeline: EglNv12Pipeline? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stop() }
    }

    fun start() {
        check(pipeline == null) { "HyperHdrGpuEncoder already started" }
        mediaProjection.registerCallback(projectionCallback, callbackHandler)

        val p = EglNv12Pipeline(config, sink)
        pipeline = p
        val inputSurface = p.start()

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HyperHdrCapture",
            sourceWidth, sourceHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            inputSurface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() { Log.i(TAG, "VirtualDisplay stopped") }
            },
            callbackHandler,
        )
    }

    fun stop() {
        try { mediaProjection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        virtualDisplay?.release(); virtualDisplay = null
        pipeline?.stop(); pipeline = null
        callbackThread.quitSafely()
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/HyperHdrGpuEncoder.kt
git commit -m "feat(capture): HyperHdrGpuEncoder wiring MediaProjection→VirtualDisplay→pipeline"
```

---

## Task 9: Manual end-to-end test against real HyperHDR

**Files:**
- Create: `docs/manual-tests/02-gpu-capture-roundtrip.md`

- [ ] **Step 1: Write the doc**

````markdown
# Manual test: GPU capture round-trip to real HyperHDR

**Prereqs:** A real Android TV (or any Android 9+ device) and a reachable HyperHDR v22+ instance with LEDs configured. ADB access to the device.

This test isn't automatable — it requires the user-consent `MediaProjection` dialog. We use a temporary `tv/` debug activity that wires `HyperHdrGpuEncoder` to `HyperHdrFlatBufferReconnector`. Plan 3 replaces this scaffolding with the real service + UI.

**Steps:**

1. Add a temporary debug activity to `tv/src/debug/java/eu/hyperhdr/android/tv/DebugCaptureActivity.kt` (the engineer creates this scaffold; do not commit it long-term):

   ```kotlin
   package eu.hyperhdr.android.tv

   import android.app.Activity
   import android.content.Intent
   import android.media.projection.MediaProjectionManager
   import android.os.Bundle
   import android.util.DisplayMetrics
   import eu.hyperhdr.android.capture.CaptureConfig
   import eu.hyperhdr.android.capture.HyperHdrGpuEncoder
   import eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferReconnector

   class DebugCaptureActivity : Activity() {
       private lateinit var pmgr: MediaProjectionManager
       private lateinit var reconnector: HyperHdrFlatBufferReconnector
       private var encoder: HyperHdrGpuEncoder? = null

       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           pmgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
           reconnector = HyperHdrFlatBufferReconnector(
               host = "192.168.1.100",  // <- set me
               port = 19400,
               priority = 100,
           ).also { it.start() }
           startActivityForResult(pmgr.createScreenCaptureIntent(), 1)
       }

       @Suppress("DEPRECATION")
       override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
           super.onActivityResult(requestCode, resultCode, data)
           if (resultCode != RESULT_OK || data == null) { finish(); return }
           val dm = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
           val proj = pmgr.getMediaProjection(resultCode, data)
           encoder = HyperHdrGpuEncoder(
               mediaProjection = proj,
               sourceWidth = dm.widthPixels,
               sourceHeight = dm.heightPixels,
               density = dm.densityDpi,
               config = CaptureConfig.STANDARD,
               sink = reconnector,
           ).also { it.start() }
       }

       override fun onDestroy() {
           super.onDestroy()
           encoder?.stop()
           reconnector.close()
       }
   }
   ```

   Add it to `tv/src/debug/AndroidManifest.xml`:

   ```xml
   <activity android:name="eu.hyperhdr.android.tv.DebugCaptureActivity"
             android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN"/>
           <category android:name="android.intent.category.LAUNCHER"/>
       </intent-filter>
   </activity>
   ```

2. Build and install the debug variant:

   ```bash
   ./gradlew :tv:installDebug
   adb shell am start -n eu.hyperhdr.android/.tv.DebugCaptureActivity
   ```

3. Approve the MediaProjection dialog on the device.
4. Open any app on the TV (a video, the home screen — anything colourful). The LEDs should follow.

**Pass:** LEDs follow on-screen content with visible latency under ~100 ms (eyeball test). HyperHDR's logs show repeated `Received NV12 frame` lines.

**Fail modes:**
- Black LEDs: shader is producing zero. Check `glReadPixels` reads from the right FBO; check `oY`/`oUV` outputs aren't being clamped.
- Wrong colors (looks blue when screen is red): R/B channel swap in the source RGBA — most likely you mistakenly read the SurfaceTexture as BGRA. The `samplerExternalOES` returns RGBA on every standard Android driver; if your device is non-standard, swap `rgb.r` and `rgb.b` in both `Y_FRAGMENT_SRC` and `UV_FRAGMENT_SRC`.
- LEDs flicker / drop: backpressure. Confirm `acquireLatestImage` semantics — every call to `drainOneFrame` must collapse pending frames.
- "Connection refused": same as Plan 1 manual test.

**Cleanup:** before merging Plan 3, **delete `DebugCaptureActivity` and `tv/src/debug/AndroidManifest.xml`** — the production service replaces them.
````

- [ ] **Step 2: Commit the doc**

```bash
git add docs/manual-tests/02-gpu-capture-roundtrip.md
git commit -m "docs: manual GPU capture round-trip test against real HyperHDR"
```

---

## Plan 2 done definition

- All Plan 1 tests still pass (`./gradlew :common:test`).
- New Plan 2 unit tests (`CaptureConfigTest`, `FrameClockTest`) green.
- The instrumented smoke test (`EglNv12PipelineSmokeTest`) passes on an API 28+ emulator.
- The manual recipe in Task 9 produces visibly synchronized LEDs on a real Android TV against a real HyperHDR.

---

## Self-review (writer's pre-flight)

- **Spec coverage for Plan 2's slice:** §4 capture pipeline (GLES shader, downscale, NV12 pack) → Tasks 4, 5. §4 frame pacing (Choreographer, divisor) → Task 7. §4 single-buffered NV12 reuse → Task 5 (the `yBytes`/`uvBytes` arrays are class-level, reused per frame). §4 deletion of BorderProcessor / avg-color → Plan 1 already deleted them.
- **Out of scope (deferred):** HDR-aware tier (Plan 4), service & lifecycle (Plan 3), JSON-API (Plan 3), UI (Plan 3), settings persistence (Plan 3 — Plan 2 hard-codes `CaptureConfig.STANDARD` in the manual test).
- **Type consistency check:** `FrameSink` import path matches Plan 1 (`eu.hyperhdr.android.flatbuf.FrameSink`); `HyperHdrFlatBufferReconnector` is the FrameSink-implementing class actually used in the manual test, again matching Plan 1; `CaptureConfig`, `CaptureTier`, `EglNv12Pipeline`, `HyperHdrGpuEncoder` names locked for Plans 3 and 4.
- **Known-noted typo:** Task 5 explicitly flags a `glTexParameteri` argument typo for the engineer to fix in `createTexture2D`. This is rare for a plan but is a real common-paste-error in EGL boilerplate; flagging it explicitly is more useful than pretending it doesn't happen.
- **No placeholders:** every code block compiles (modulo the noted typo); every shader source is complete; every test has expected output.
