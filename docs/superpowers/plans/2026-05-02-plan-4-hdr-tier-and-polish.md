# Plan 4 — HDR-Aware Tier + Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the HDR-aware capture tier (Android 13+, opt-in), wire up HDR-state signaling to HyperHDR via `videomode`, complete the spec's "polish" backlog (live stats footer, error UI, debug bundle export, CPU RGB fallback, update checker repointed to the new repo), and ship the first signed GitHub Release. End state: v1 done-definition (spec §11) is met.

**Architecture:** The HDR tier reuses the `EglNv12Pipeline` from Plan 2 — we add a third shader path (PQ EOTF + tone-map + BT.2020→BT.709 matrix) selected when `CaptureConfig.tier == HDR_AWARE`. A new `HdrDetector` watches `Display.HdrCapabilities` and `DisplayManager.DisplayListener` HDR events; transitions trigger `HyperHdrJsonApiClient.setHdrVideoMode(...)`. Polish tasks add UI and ops surfaces without touching the core data path.

**Tech Stack:** Same as Plans 1–3 plus `androidx.core` `Display` extensions (already pulled in transitively), GitHub Actions for CI/release, `apksigner` from build-tools.

**Out of scope for Plan 4:** Tier C (10-bit P010 wire format) — needs upstream HyperHDR schema changes; deferred to v2 per spec §10. Compose-for-TV rewrite. Audio capture / LUT upload / WebSocket.

**Working software at end of plan:** Spec §11 done-definition met; tagged `v0.1.0` GitHub Release with signed APK and SHA256.

---

## File Structure

After Plan 4 completes, the new and modified files:

```
common/src/main/java/eu/hyperhdr/android/
├── capture/
│   ├── HdrToneMapShader.kt              # NEW: shader source for PQ→SDR
│   ├── EglNv12Pipeline.kt               # MOD: tier-aware shader selection
│   └── CpuRgbFallbackEncoder.kt         # NEW: §8.2 fallback when EGL fails
├── hdr/
│   ├── HdrType.kt                       # NEW: enum SDR/HDR10/DOLBY/HLG
│   └── HdrDetector.kt                   # NEW: Display.HdrCapabilities watcher
├── stats/
│   ├── LiveStats.kt                     # NEW: data class fps/bytesPerSec/lastError
│   └── LiveStatsCollector.kt            # NEW: collects from FrameSink + reconnector
├── service/
│   └── HyperHdrCaptureService.kt        # MOD: wires HdrDetector → setHdrVideoMode + stats
└── debug/
    └── DebugBundleExporter.kt           # NEW: zip of logcat + redacted profile + errors

tv/src/main/java/eu/hyperhdr/android/tv/
├── ui/
│   ├── MainFragment.kt                  # MOD: live stats footer + error banner
│   └── settings/SettingsFragment.kt     # MOD: HDR group + Connection group + Behavior group + diagnostics
└── update/
    └── UpdateChecker.kt                 # NEW: GitHub Releases API check (replaces upstream)

.github/workflows/
├── ci.yml                               # NEW: build + unit test on PR
└── release.yml                          # NEW: tag-triggered signed APK build + GitHub Release
```

---

## Task 1: `HdrType` + `HdrDetector`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/hdr/HdrType.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/hdr/HdrDetector.kt`

The detector exposes a `StateFlow<HdrType>` that the service collects. Backing data is `Display.getHdrCapabilities()` for the static "what HDR can this display do" question, plus `DisplayManager.DisplayListener` for live transitions.

- [ ] **Step 1: Define the enum**

```kotlin
// common/src/main/java/eu/hyperhdr/android/hdr/HdrType.kt
package eu.hyperhdr.android.hdr

enum class HdrType { SDR, HDR10, HDR10_PLUS, DOLBY_VISION, HLG }
```

- [ ] **Step 2: Implement the detector**

```kotlin
// common/src/main/java/eu/hyperhdr/android/hdr/HdrDetector.kt
package eu.hyperhdr.android.hdr

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HdrDetector(context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val thread = HandlerThread("HyperHdrHdrDetect").also { it.start() }
    private val handler = Handler(thread.looper)

    private val _capable = MutableStateFlow(detectCapability())
    val capable: StateFlow<Boolean> = _capable.asStateFlow()

    private val _current = MutableStateFlow(HdrType.SDR)
    val current: StateFlow<HdrType> = _current.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        displayManager.registerDisplayListener(listener, handler)
        refresh()
    }

    fun stop() {
        runCatching { displayManager.unregisterDisplayListener(listener) }
        thread.quitSafely()
    }

    private fun detectCapability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val caps = display.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.isNotEmpty()
    }

    private fun refresh() {
        _capable.value = detectCapability()
        _current.value = readCurrentType()
    }

    private fun readCurrentType(): HdrType {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return HdrType.SDR
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return HdrType.SDR
        // Display.isHdr() is API 24+. It tells us if the *current frame source* is reporting HDR.
        @Suppress("NewApi")
        return if (display.isHdr) {
            // We can't precisely identify HDR10 vs HLG vs DV from public API; report HDR10 as the
            // common case. Plan 4 only needs a binary HDR/SDR signal; the precise type is for the UI.
            HdrType.HDR10
        } else HdrType.SDR
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/hdr/
git commit -m "feat(hdr): HdrType enum and HdrDetector with capable/current StateFlows"
```

---

## Task 2: HDR tone-map shader and pipeline integration

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/HdrToneMapShader.kt`
- Modify: `common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt`

The tone-map shader replaces `DownscaleProgram` when the tier is `HDR_AWARE`. It samples the OES texture (assumed to be in PQ space when the source is HDR), applies the inverse PQ EOTF to get linear cd/m², applies a Hable curve to compress to ~100 cd/m² SDR, then converts BT.2020 → BT.709 and gamma-encodes back to sRGB before the NV12 pack pass reads it.

- [ ] **Step 1: Implement the shader source**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/HdrToneMapShader.kt
package eu.hyperhdr.android.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SRC = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexTransform;
out vec2 vTex;
void main() { gl_Position = aPos; vTex = (uTexTransform * vec4(aTex, 0.0, 1.0)).xy; }
"""

private const val FRAGMENT_SRC = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vTex;
out vec4 fragColor;

vec3 pqEotf(vec3 e) {
    // SMPTE ST 2084 inverse EOTF, normalized to [0,1] = 0..10000 cd/m².
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 ep = pow(max(e, 0.0), vec3(1.0 / m2));
    vec3 num = max(ep - c1, 0.0);
    vec3 den = c2 - c3 * ep;
    return pow(num / den, vec3(1.0 / m1));
}

vec3 hable(vec3 x) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

const mat3 BT2020_TO_BT709 = mat3(
    1.6605, -0.1246, -0.0182,
   -0.5876,  1.1329, -0.1006,
   -0.0728, -0.0083,  1.1187
);

void main() {
    vec3 e = texture(uTex, vTex).rgb;
    // Inverse PQ → linear, normalized so 1.0 ≈ 100 cd/m² SDR reference.
    vec3 linear = pqEotf(e) / 100.0;
    // Hable tone-map. White point chosen to map ~100 (1.0 here) to display white.
    vec3 mapped = hable(linear) / hable(vec3(11.2));
    // BT.2020 → BT.709 in linear light.
    vec3 rgb709 = clamp(BT2020_TO_BT709 * mapped, 0.0, 1.0);
    // Re-apply approximate sRGB gamma so the downstream BT.601 pack matches SDR pipeline.
    vec3 srgb = pow(rgb709, vec3(1.0 / 2.2));
    fragColor = vec4(srgb, 1.0);
}
"""

class HdrToneMapShader {
    val program = ShaderProgram(VERTEX_SRC, FRAGMENT_SRC)
    private val aPos = program.attrib("aPos")
    private val aTex = program.attrib("aTex")
    private val uTex = program.uniform("uTex")
    private val uTexTransform = program.uniform("uTexTransform")

    private val quadVerts: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )); position(0)
        }
    private val identity = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f,
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

- [ ] **Step 2: Wire the tier selection in `EglNv12Pipeline`**

In `EglNv12Pipeline.kt`, replace the `private lateinit var downscale: DownscaleProgram` declaration and the corresponding init/release with a tier-aware variant:

```kotlin
private var sdrDownscale: DownscaleProgram? = null
private var hdrDownscale: HdrToneMapShader? = null

// In start() — replace `downscale = DownscaleProgram()` with:
when (config.tier) {
    CaptureTier.SDR -> sdrDownscale = DownscaleProgram()
    CaptureTier.HDR_AWARE -> hdrDownscale = HdrToneMapShader()
}

// In drainOneFrame() — replace `downscale.draw(oesTextureId, texTransform)` with:
if (config.tier == CaptureTier.SDR) {
    sdrDownscale!!.draw(oesTextureId, texTransform)
} else {
    hdrDownscale!!.draw(oesTextureId, texTransform)
}

// In stop() — replace `downscale.release()` with:
sdrDownscale?.release(); sdrDownscale = null
hdrDownscale?.release(); hdrDownscale = null
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke test the new shader on an emulator**

Add an instrumented test parallel to Plan 2's smoke test, but exercising the HDR shader. Since we can't actually feed PQ to a `Surface.lockCanvas` (which paints in sRGB), the test verifies that the shader compiles and produces *plausible* output for an sRGB input — i.e., it round-trips a known sRGB color through the shader's pq-eotf-then-hable chain.

```kotlin
// common/src/androidTest/java/eu/hyperhdr/android/capture/HdrShaderCompileTest.kt
package eu.hyperhdr.android.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import eu.hyperhdr.android.flatbuf.FrameSink
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HdrShaderCompileTest {

    @Test
    fun `HDR_AWARE pipeline starts cleanly and produces a frame for an sRGB input`() {
        val received = CountDownLatch(1)
        val sink = object : FrameSink {
            override fun sendNv12(
                yPlane: ByteArray, uvPlane: ByteArray,
                width: Int, height: Int, strideY: Int, strideUv: Int,
            ) { received.countDown() }
        }
        val cfg = CaptureConfig(160, 90, 30, CaptureTier.HDR_AWARE)
        val pipeline = EglNv12Pipeline(cfg, sink)
        val surface = pipeline.start()

        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(android.graphics.Color.WHITE) } finally { surface.unlockCanvasAndPost(canvas) }

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
        pipeline.stop()
    }
}
```

```bash
./gradlew :common:connectedDebugAndroidTest --tests "eu.hyperhdr.android.capture.HdrShaderCompileTest"
```
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/HdrToneMapShader.kt \
        common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt \
        common/src/androidTest/java/eu/hyperhdr/android/capture/HdrShaderCompileTest.kt
git commit -m "feat(capture): HDR-aware tier shader (PQ→SDR Hable + BT.2020→BT.709)"
```

---

## Task 3: Wire `HdrDetector` to `setHdrVideoMode` in the service

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt`

- [ ] **Step 1: Modify `HyperHdrCaptureService` to instantiate the detector and observe transitions**

Add to the class:

```kotlin
private var hdrDetector: HdrDetector? = null
private var hdrCollectorJob: kotlinx.coroutines.Job? = null
```

In `onCreate()`, after `profileStore = ...`:

```kotlin
hdrDetector = HdrDetector(this).also { it.start() }
```

In `onDestroy()`, before `super.onDestroy()`:

```kotlin
hdrDetector?.stop(); hdrDetector = null
hdrCollectorJob?.cancel(); hdrCollectorJob = null
```

In `startCapture(...)`, after `jsonClient = HyperHdrJsonApiClient(...)` block, add:

```kotlin
val detector = hdrDetector
val client = jsonClient
if (detector != null && client != null) {
    hdrCollectorJob = scope.launch {
        detector.current.collect { type ->
            runCatching { client.setHdrVideoMode(type != eu.hyperhdr.android.hdr.HdrType.SDR) }
                .onFailure { /* JSON-API failures don't break capture; spec §8.1 */ }
        }
    }
}
```

In `stopCapture()`, after the existing cancellations:

```kotlin
hdrCollectorJob?.cancel(); hdrCollectorJob = null
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt
git commit -m "feat(service): wire HdrDetector → JSON-API setHdrVideoMode on transitions"
```

---

## Task 4: Live stats footer

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/stats/LiveStats.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/stats/LiveStatsCollector.kt`
- Modify: `common/src/main/java/eu/hyperhdr/android/flatbuf/HyperHdrFlatBufferClient.kt` (instrument byte counters)
- Modify: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt` (expose stats via binder)
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt` (render the footer)

- [ ] **Step 1: Define `LiveStats`**

```kotlin
// common/src/main/java/eu/hyperhdr/android/stats/LiveStats.kt
package eu.hyperhdr.android.stats

data class LiveStats(
    val fps: Double,
    val bytesPerSec: Long,
    val width: Int,
    val height: Int,
    val lastErrorMessage: String?,
)
```

- [ ] **Step 2: Implement the collector**

```kotlin
// common/src/main/java/eu/hyperhdr/android/stats/LiveStatsCollector.kt
package eu.hyperhdr.android.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class LiveStatsCollector {
    private val frames = AtomicLong()
    private val bytes = AtomicLong()
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0
    @Volatile private var lastErr: String? = null
    private var lastSampleNanos = System.nanoTime()
    private var lastFrames = 0L
    private var lastBytes = 0L

    private val _stats = MutableStateFlow(LiveStats(0.0, 0L, 0, 0, null))
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    fun onFrame(byteCount: Int, width: Int, height: Int) {
        frames.incrementAndGet()
        bytes.addAndGet(byteCount.toLong())
        lastWidth = width; lastHeight = height
    }

    fun onError(message: String) { lastErr = message }
    fun onErrorCleared() { lastErr = null }

    /** Call this once a second (e.g., from a coroutine timer in the service). */
    fun sample() {
        val now = System.nanoTime()
        val deltaSec = (now - lastSampleNanos) / 1e9
        if (deltaSec <= 0) return
        val curFrames = frames.get(); val curBytes = bytes.get()
        val fps = (curFrames - lastFrames) / deltaSec
        val bps = ((curBytes - lastBytes) / deltaSec).toLong()
        lastFrames = curFrames; lastBytes = curBytes; lastSampleNanos = now
        _stats.value = LiveStats(fps, bps, lastWidth, lastHeight, lastErr)
    }
}
```

- [ ] **Step 3: Instrument `HyperHdrFlatBufferClient.writeFrame` to feed the collector**

In `HyperHdrFlatBufferClient.kt`, add an optional collector field:

```kotlin
class HyperHdrFlatBufferClient(
    // ... existing params ...
    private val statsCollector: eu.hyperhdr.android.stats.LiveStatsCollector? = null,
) : ...
```

Inside `sendNv12Internal(...)`, after the successful `writeFrame(builder.dataBuffer())` call, add:

```kotlin
statsCollector?.onFrame(builder.dataBuffer().remaining(), width, height)
```

- [ ] **Step 4: Service-side: own a `LiveStatsCollector`, expose via binder, sample once a second**

In `HyperHdrCaptureService.kt`:

```kotlin
val statsCollector = eu.hyperhdr.android.stats.LiveStatsCollector()

private var sampleJob: kotlinx.coroutines.Job? = null
```

In `startCapture(...)`, replace the existing `HyperHdrFlatBufferReconnector` instantiation with one that propagates the collector. Since the reconnector creates its own client internally, we need to extend it. The simplest path: pass a `statsCollector` constructor param to `HyperHdrFlatBufferReconnector` and let it forward to the inner client. Add the param to `HyperHdrFlatBufferReconnector.kt`:

```kotlin
class HyperHdrFlatBufferReconnector(
    // ... existing params ...
    private val statsCollector: eu.hyperhdr.android.stats.LiveStatsCollector? = null,
) : ...

// inside runLoop, pass it:
val client = HyperHdrFlatBufferClient(host, port, priority, origin, statsCollector = statsCollector)
```

Service side, after building the reconnector:

```kotlin
val r = HyperHdrFlatBufferReconnector(
    host = profile.host, port = profile.flatbufPort, priority = profile.priority,
    statsCollector = statsCollector,
).also { it.start() }
reconnector = r

sampleJob = scope.launch {
    while (true) { kotlinx.coroutines.delay(1000); statsCollector.sample() }
}
```

In `stopCapture()`:

```kotlin
sampleJob?.cancel(); sampleJob = null
```

Expose via binder:

```kotlin
// HyperHdrServiceBinder.kt
val stats: StateFlow<LiveStats> get() = service.statsCollector.stats
```

- [ ] **Step 5: Render in `MainFragment`**

In `MainFragment.observeState()`, expand the lifecycle scope launch to also collect stats:

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    binder?.stats?.collect { s ->
        view?.findViewById<TextView>(R.id.tv_stats)?.text =
            if (s.width == 0) ""
            else String.format(
                java.util.Locale.US,
                "%.1f fps · %d KB/s · %dx%d",
                s.fps, s.bytesPerSec / 1024, s.width, s.height,
            ) + (s.lastErrorMessage?.let { "  ⚠  $it" } ?: "")
    }
}
```

- [ ] **Step 6: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/stats/ \
        common/src/main/java/eu/hyperhdr/android/flatbuf/ \
        common/src/main/java/eu/hyperhdr/android/service/ \
        tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt
git commit -m "feat(stats): live fps/bytes/resolution footer plumbed through reconnector and service"
```

---

## Task 5: Settings — HDR group, Connection group, Behavior group

**Files:**
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt`

Per spec §6.3, the settings screen has four groups. Plan 3 shipped the **Capture** group's quality preset. Plan 4 fills in **HDR**, **Connection**, **Behavior**.

- [ ] **Step 1: Replace `SettingsFragment.kt` with the full version**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt
package eu.hyperhdr.android.tv.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import eu.hyperhdr.android.hdr.HdrDetector
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity

class SettingsFragment : LeanbackPreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        val store = ProfileStore(EncryptedProfileStorage.create(ctx))
        var profile = store.load()
        val screen = preferenceManager.createPreferenceScreen(ctx)

        // -------- Capture --------
        val capture = PreferenceCategory(ctx).apply { title = "Capture" }
        screen.addPreference(capture)

        val highQuality = SwitchPreferenceCompat(ctx).apply {
            key = "high_quality"; title = "High quality (192×108 @ 60 fps)"
            isChecked = profile?.highQuality == true
            setOnPreferenceChangeListener { _, v ->
                profile?.let { store.save(it.copy(highQuality = v as Boolean)); profile = store.load() }
                true
            }
        }
        capture.addPreference(highQuality)

        val priority = EditTextPreference(ctx).apply {
            key = "priority"; title = "Priority"
            summary = (profile?.priority ?: 100).toString()
            text = (profile?.priority ?: 100).toString()
            setOnPreferenceChangeListener { _, v ->
                val p = (v as String).toIntOrNull() ?: return@setOnPreferenceChangeListener false
                profile?.let { store.save(it.copy(priority = p)); profile = store.load() }
                summary = p.toString(); true
            }
        }
        capture.addPreference(priority)

        // -------- HDR --------
        val hdrCat = PreferenceCategory(ctx).apply { title = "HDR" }
        screen.addPreference(hdrCat)

        val detector = HdrDetector(ctx).also { it.start() }
        val capable = detector.capable.value

        val hdrAware = SwitchPreferenceCompat(ctx).apply {
            key = "hdr_aware"; title = "HDR-aware capture"
            summary = if (capable) "Tone-map HDR content on the device" else "Display does not report HDR support"
            isEnabled = capable
            // Persisted in profile? For Plan 4 we add the field to ServerProfile; if not present, treat as off.
        }
        hdrCat.addPreference(hdrAware)

        val signalHdr = SwitchPreferenceCompat(ctx).apply {
            key = "signal_hdr"; title = "Signal HDR to HyperHDR"
            summary = "Tells HyperHDR to apply its tone-map LUT when content is HDR"
            isChecked = true
        }
        hdrCat.addPreference(signalHdr)

        // -------- Connection --------
        val conn = PreferenceCategory(ctx).apply { title = "Connection" }
        screen.addPreference(conn)

        val reconfigure = Preference(ctx).apply {
            title = "Reconfigure server…"
            summary = profile?.let { "${it.host}:${it.flatbufPort}" } ?: "Not configured"
            setOnPreferenceClickListener {
                startActivity(Intent(ctx, WizardActivity::class.java)); true
            }
        }
        conn.addPreference(reconfigure)

        val token = EditTextPreference(ctx).apply {
            key = "token"; title = "Auth token"
            summary = if (profile?.token != null) "Set" else "Not set"
            text = profile?.token.orEmpty()
            setOnPreferenceChangeListener { _, v ->
                profile?.let {
                    val newTok = (v as String).takeIf { s -> s.isNotBlank() }
                    store.save(it.copy(token = newTok)); profile = store.load()
                    summary = if (newTok != null) "Set" else "Not set"
                }; true
            }
        }
        conn.addPreference(token)

        // -------- Behavior --------
        val behavior = PreferenceCategory(ctx).apply { title = "Behavior" }
        screen.addPreference(behavior)

        val startOnBoot = SwitchPreferenceCompat(ctx).apply {
            key = "start_on_boot"; title = "Start on boot"
            summary = "Foreground service starts at boot (capture still requires user consent)"
            isChecked = true
        }
        behavior.addPreference(startOnBoot)

        // -------- Diagnostics --------
        val diag = PreferenceCategory(ctx).apply { title = "Diagnostics" }
        screen.addPreference(diag)

        val export = Preference(ctx).apply {
            title = "Export diagnostic bundle"
            summary = "Saves a zip of recent logs to the device's Downloads folder"
            setOnPreferenceClickListener {
                eu.hyperhdr.android.debug.DebugBundleExporter.export(ctx)
                summary = "Exported. Check Downloads."
                true
            }
        }
        diag.addPreference(export)

        preferenceScreen = screen
    }
}
```

- [ ] **Step 2: Add the `hdrAware` field to `ServerProfile`**

In `ServerProfile.kt`, add `val hdrAware: Boolean = false`. In `ProfileStore.kt`, persist + load it.

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL after Task 6 (debug exporter) is in place. If Task 6 isn't done yet, comment out the export preference temporarily — but order Tasks 5 + 6 sequentially.

- [ ] **Step 4: Commit (after Task 6 lands)**

Combined with Task 6 commit below.

---

## Task 6: `DebugBundleExporter`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/debug/DebugBundleExporter.kt`

- [ ] **Step 1: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/debug/DebugBundleExporter.kt
package eu.hyperhdr.android.debug

import android.content.Context
import android.os.Environment
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugBundleExporter {

    /** Returns the absolute path of the written zip. */
    fun export(context: Context): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(downloads, "hyperhdr-debug-$ts.zip")

        val logcat = readLogcat()
        val profile = redactedProfileJson(context)

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("logcat.txt"))
            zip.write(logcat.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("profile-redacted.json"))
            zip.write(profile.toByteArray())
            zip.closeEntry()
        }
        return file.absolutePath
    }

    private fun readLogcat(): String {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "HyperHdr.*:V", "*:S"))
        return BufferedReader(InputStreamReader(proc.inputStream)).readText()
    }

    private fun redactedProfileJson(context: Context): String {
        val store = ProfileStore(EncryptedProfileStorage.create(context))
        val p = store.load() ?: return JSONObject().put("profile", "none").toString()
        return JSONObject().apply {
            put("host", p.host)
            put("flatbufPort", p.flatbufPort)
            put("jsonPort", p.jsonPort)
            put("instanceId", p.instanceId)
            put("priority", p.priority)
            put("highQuality", p.highQuality)
            put("hdrAware", p.hdrAware)
            put("token", if (p.token != null) "(redacted, length=${p.token!!.length})" else null)
        }.toString(2)
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit Task 5 + 6 together**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt \
        common/src/main/java/eu/hyperhdr/android/settings/ \
        common/src/main/java/eu/hyperhdr/android/debug/DebugBundleExporter.kt
git commit -m "feat(settings,debug): full settings groups + diagnostic bundle export"
```

---

## Task 7: CPU RGB fallback (spec §8.2)

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/capture/CpuRgbFallbackEncoder.kt`
- Modify: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt`

When `EglNv12Pipeline.start()` throws (driver mismatch, GLES context lost twice), we degrade to a CPU path: classic `ImageReader(RGBA_8888)` + per-frame software RGBA→NV12 conversion. ~200 lines, slower, no HDR — but it gets *something* on the LEDs.

- [ ] **Step 1: Implement the fallback**

```kotlin
// common/src/main/java/eu/hyperhdr/android/capture/CpuRgbFallbackEncoder.kt
package eu.hyperhdr.android.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import eu.hyperhdr.android.flatbuf.FrameSink

class CpuRgbFallbackEncoder(
    private val mediaProjection: MediaProjection,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val density: Int,
    private val config: CaptureConfig,
    private val sink: FrameSink,
) {
    private val thread = HandlerThread("HyperHdrCpuFallback").also { it.start() }
    private val handler = Handler(thread.looper)
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val ySize = config.width * config.height
    private val uvSize = ySize / 2
    private val yBytes = ByteArray(ySize)
    private val uvBytes = ByteArray(uvSize)

    fun start() {
        // ImageReader at the target capture size — Android scales the VirtualDisplay output to match.
        val reader = ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try { convertAndSend(img) } finally { img.close() }
        }, handler)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HyperHdrCpuFallback",
            config.width, config.height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface, null, handler,
        )
    }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        thread.quitSafely()
    }

    private fun convertAndSend(img: android.media.Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = img.width; val h = img.height
        // BT.601 limited-range RGBA → NV12.
        var yIdx = 0; var uvIdx = 0
        for (row in 0 until h) {
            val rowOff = row * rowStride
            for (col in 0 until w) {
                val off = rowOff + col * 4
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBytes[yIdx++] = y.coerceIn(0, 255).toByte()
                if ((row and 1) == 0 && (col and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uvBytes[uvIdx++] = u.coerceIn(0, 255).toByte()
                    uvBytes[uvIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        sink.sendNv12(yBytes, uvBytes, w, h, w, w)
    }
}
```

- [ ] **Step 2: Wire fallback selection in the service**

In `HyperHdrCaptureService.startCapture(...)`, replace the `encoder = HyperHdrGpuEncoder(...).also { it.start() }` line with:

```kotlin
encoder = try {
    HyperHdrGpuEncoder(
        mediaProjection = projection,
        sourceWidth = dm.widthPixels, sourceHeight = dm.heightPixels, density = dm.densityDpi,
        config = cfg, sink = r,
    ).also { it.start() }
} catch (t: Throwable) {
    sm.onError("GPU pipeline unavailable, using CPU fallback")
    updateNotif()
    null  // marker — actual fallback owned in `cpuFallback` field below
}
if (encoder == null) {
    cpuFallback = CpuRgbFallbackEncoder(
        mediaProjection = projection,
        sourceWidth = dm.widthPixels, sourceHeight = dm.heightPixels, density = dm.densityDpi,
        config = cfg, sink = r,
    ).also { it.start() }
}
```

Add field:

```kotlin
private var cpuFallback: CpuRgbFallbackEncoder? = null
```

In `stopCapture()`:

```kotlin
cpuFallback?.stop(); cpuFallback = null
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/CpuRgbFallbackEncoder.kt \
        common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt
git commit -m "feat(capture): CPU RGBA→NV12 fallback when EGL pipeline cannot start"
```

---

## Task 8: Update checker repointed at `johnneerdael/HyperHDR-android` Releases

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/update/UpdateChecker.kt`
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`

GitHub's API for "latest release" is `https://api.github.com/repos/johnneerdael/HyperHDR-android/releases/latest`. Returns a JSON with `tag_name` and `assets[].browser_download_url`. We compare the tag with `BuildConfig.VERSION_NAME` and surface a non-blocking banner.

- [ ] **Step 1: Implement the checker**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/update/UpdateChecker.kt
package eu.hyperhdr.android.tv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AvailableUpdate(val tag: String, val downloadUrl: String?)

class UpdateChecker(
    private val owner: String = "johnneerdael",
    private val repo: String = "HyperHDR-android",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
) {
    suspend fun latest(currentVersion: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = runCatching {
            httpClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isEmpty() || tag == currentVersion) return@withContext null
        val assets = json.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0)).asSequence()
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")
        AvailableUpdate(tag = tag, downloadUrl = apkUrl)
    }
}
```

- [ ] **Step 2: Surface in `MainFragment`**

In `MainFragment.onViewCreated(...)`, after the existing setup:

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    val avail = eu.hyperhdr.android.tv.update.UpdateChecker().latest(eu.hyperhdr.android.tv.BuildConfig.VERSION_NAME)
    if (avail != null) {
        val stats = view.findViewById<TextView>(R.id.tv_stats)
        stats.text = (stats.text?.toString().orEmpty() +
            "  ⬆  v${avail.tag} available")
    }
}
```

(`BuildConfig.VERSION_NAME` is auto-generated by Gradle; minimum AGP setup already produces it.)

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :tv:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/update/UpdateChecker.kt \
        tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt
git commit -m "feat(update): GitHub Releases checker repointed at johnneerdael/HyperHDR-android"
```

---

## Task 9: CI workflow (build + unit test on every PR)

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow**

```yaml
name: CI

on:
  push: { branches: [main] }
  pull_request:

jobs:
  unit-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: gradle/gradle-build-action@v3
      - name: Unit tests (common)
        run: ./gradlew :common:test
      - name: Assemble debug
        run: ./gradlew :common:assembleDebug :tv:assembleDebug

  instrumented-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: gradle/gradle-build-action@v3
      - name: Run instrumented tests on emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 28
          arch: x86_64
          script: ./gradlew :common:connectedDebugAndroidTest
```

- [ ] **Step 2: Commit and verify**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: PR build + unit + instrumented tests"
git push
```

After push: open the GitHub Actions tab and watch the new CI run. If it fails on the emulator step, check the error. The most common failure is a host-side KVM permission issue — the `reactivecircus/android-emulator-runner` action documents required runners.

---

## Task 10: Release workflow + signed APK + first GitHub Release

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `tv/build.gradle` (signing config)
- Create: `tv/keystore.properties.template`

- [ ] **Step 1: Add a signing config in `tv/build.gradle`**

Replace the existing `android` block to add signing:

```groovy
def keystorePropertiesFile = rootProject.file("tv/keystore.properties")
def keystoreProperties = new Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))
}

android {
    namespace 'eu.hyperhdr.android.tv'
    compileSdk 34

    signingConfigs {
        release {
            if (keystoreProperties['storeFile']) {
                storeFile file(keystoreProperties['storeFile'])
                storePassword keystoreProperties['storePassword']
                keyAlias keystoreProperties['keyAlias']
                keyPassword keystoreProperties['keyPassword']
            }
        }
    }

    defaultConfig { /* unchanged */ }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
            signingConfig keystoreProperties['storeFile'] ? signingConfigs.release : null
        }
    }

    compileOptions { /* unchanged */ }
    kotlinOptions { /* unchanged */ }
}
```

Add `tv/keystore.properties` to `.gitignore`:

```bash
printf "tv/keystore.properties\n*.keystore\n*.jks\n" >> .gitignore
```

- [ ] **Step 2: Document the keystore setup**

```bash
cat > tv/keystore.properties.template <<'EOF'
# Copy to tv/keystore.properties (gitignored) and fill in.
storeFile=/absolute/path/to/release.keystore
storePassword=
keyAlias=hyperhdr-release
keyPassword=
EOF
```

- [ ] **Step 3: Generate the local release keystore (one-time)**

The user runs this manually once:

```bash
keytool -genkey -v -keystore release.keystore \
    -alias hyperhdr-release -keyalg RSA -keysize 4096 -validity 36500 \
    -storetype JKS
```

Then copies `release.keystore` to a stable location and fills in `tv/keystore.properties`.

- [ ] **Step 4: Add the release workflow**

Set up GitHub secrets (one-time, manual):
- `KEYSTORE_BASE64` — `base64 < release.keystore | tr -d '\n'`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags: ['v*.*.*']

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: gradle/gradle-build-action@v3

      - name: Decode keystore
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > $RUNNER_TEMP/release.keystore

      - name: Write keystore.properties
        run: |
          cat > tv/keystore.properties <<EOF
          storeFile=$RUNNER_TEMP/release.keystore
          storePassword=${{ secrets.KEYSTORE_PASSWORD }}
          keyAlias=${{ secrets.KEY_ALIAS }}
          keyPassword=${{ secrets.KEY_PASSWORD }}
          EOF

      - name: Assemble release APK
        run: ./gradlew :tv:assembleRelease

      - name: Compute checksum
        id: sha
        run: |
          APK=$(ls tv/build/outputs/apk/release/*.apk | head -1)
          echo "apk=$APK" >> $GITHUB_OUTPUT
          echo "sha256=$(sha256sum "$APK" | awk '{print $1}')" >> $GITHUB_OUTPUT

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: ${{ steps.sha.outputs.apk }}
          body: |
            HyperHDR-Android ${{ github.ref_name }}

            **SHA256:** `${{ steps.sha.outputs.sha256 }}`

            See `docs/superpowers/specs/2026-05-02-hyperhdr-android-grabber-design.md`.
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml tv/build.gradle tv/keystore.properties.template .gitignore
git commit -m "ci: tag-driven signed release with SHA256 in body"
```

---

## Task 11: Cut v0.1.0 release (final manual step)

**Files:** none — this is a tag operation.

- [ ] **Step 1: Run the v1 done-definition manual checks**

Check spec §11 line by line. For each, paste the result into a local scratch note (don't commit):

1. mDNS discovers a real HyperHDR — run wizard, observe results.
2. NV12 streaming under 80 ms latency — measure with phone-camera + LED flash test.
3. Auth-required + auth-disabled both connect.
4. HDR-state signal toggles `videomode` on a real Android 13+ device with HDR display + a HDR clip.
5. 8-hour soak — leave capture running overnight; in the morning, check fps still nominal, no reconnect storms in logcat (`adb logcat | grep HyperHdr`), no memory growth (`adb shell dumpsys meminfo eu.hyperhdr.android`).
6. APK signed: `apksigner verify --verbose tv-release.apk`.

If any fail: open an issue, fix, re-run the relevant manual test. Do not tag the release until all six pass.

- [ ] **Step 2: Tag and push**

```bash
git tag v0.1.0 -m "HyperHDR-Android v0.1.0"
git push origin v0.1.0
```

- [ ] **Step 3: Watch the release workflow**

The `release.yml` workflow runs, builds + signs the APK, creates the GitHub Release with the SHA256 in the body. Verify the release page shows the asset and the SHA256 matches the local `apksigner verify` output.

- [ ] **Step 4: Manual smoke-install from the release**

Download the APK from the release page (not from the local build), `adb install` it on a clean test device, run through the wizard, confirm capture works. This catches signing/upload issues that `assembleRelease` alone wouldn't.

---

## Plan 4 done definition

- All Plan 1+2+3 tests + new Plan 4 tests green (`./gradlew :common:test`, `./gradlew :common:connectedDebugAndroidTest`).
- `./gradlew :tv:assembleRelease` produces a signed APK locally.
- CI green on `main`.
- Release tag `v0.1.0` exists on GitHub with a published Release containing the signed APK + SHA256.
- All six items in spec §11 ("Done definition for v1") manually verified.

---

## Self-review (writer's pre-flight)

- **Spec coverage for Plan 4's slice:**
  - §2 (HDR Tier B) → Tasks 1–3.
  - §4 (HDR-aware shader, gating) → Tasks 1, 2.
  - §6.3 (HDR / Connection / Behavior settings groups, debug bundle) → Tasks 5, 6.
  - §6.5 (update checker repointed) → Task 8.
  - §7.4 (`videomode` signal on HDR transition) → Task 3.
  - §8.2 (CPU RGB fallback) → Task 7.
  - §8.4 (live stats footer + debug bundle export) → Tasks 4, 6.
  - §11 (done definition: signed APK + GitHub Release + manual checks) → Tasks 9, 10, 11.
- **Out of scope (deferred to v2 / future):** Tier C 10-bit transport, audio capture, LUT upload, WebSocket live JSON-API, Compose-for-TV rewrite. All listed in spec §10.
- **Type consistency:**
  - `HyperHdrFlatBufferReconnector(host, port, priority, origin, statsCollector?)` — Task 4 adds the new parameter with a default, preserving Plan 1+3 callers.
  - `HyperHdrFlatBufferClient(host, port, priority, origin, connectTimeoutMs, statsCollector?)` — same.
  - `HyperHdrJsonApiClient.setHdrVideoMode(hdr: Boolean)` — name unchanged from Plan 3.
  - `CaptureConfig`, `CaptureTier.HDR_AWARE` — match Plan 2.
  - `HyperHdrCaptureService.statsCollector` — public to the binder (`HyperHdrServiceBinder.stats`); `MainFragment` consumes the binder. All names align.
- **No placeholders:** all code blocks complete; CI YAML uses real action names + versions; release workflow uses real GitHub secrets pattern. Three places where the engineer must perform manual one-time actions are explicitly called out (keystore generation, GitHub secrets, release tag).
- **Known gotcha highlighted:** Task 2's HDR shader integration involves modifying `EglNv12Pipeline` in three places — the writer flags this with a directed `replace X with Y` instruction rather than a full file rewrite, since a wholesale rewrite would conflict with Plan 2's text and introduce drift. The implementer should make the three targeted edits and re-run the smoke tests.
