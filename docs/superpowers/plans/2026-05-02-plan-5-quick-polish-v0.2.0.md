# Plan 5 — Quick Polish (v0.2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four reviewer-flagged non-blocking improvements from Plans 1–4 and ship as v0.2.0. Items addressed: per-frame `FlatBufferBuilder` allocation (Plan 1 review I3), `FrameClock` integer-truncation rounding bug (Plan 2 review I5), `EglNv12Pipeline.start()` partial-init cleanup (Plan 2 review I3), and `EglNv12Pipeline.stop()` OES texture detach order (Plan 2 review I4).

**Architecture:** All changes are confined to `:common`. No new packages. No public-API changes — every fix is implementation-internal so Plan 3/4 callers (service, encoder, UI) continue working unchanged. Tests added or extended where the change has testable behavior; the EGL cleanup work is exercised by the existing instrumented smoke test.

**Tech Stack:** Same as Plans 1–4: Kotlin 1.9.22, AGP 8.2.1, JUnit 4.13.2, Truth 1.4.2, FlatBuffers 25.2.10. Verified on real Android 9 (UGOOS-AM6) hardware via `:common:connectedDebugAndroidTest`.

**Out of scope:** WebSocket events (Plan 6), P010 wire format (sketch + brainstorm needed), Compose-for-TV rewrite (separate milestone), `LeanbackPreferenceFragmentCompat` migration (gated on `androidx.leanback:leanback-preference` upgrade).

**Working software at end of plan:** `v0.2.0` tagged and published with signed APK. All 35 unit tests + 2 instrumented tests still green. Same signing identity as v0.1.0 (`71eda2e2ba6ff35d7cfdca3441ddf7d6bca818de6f635230390b439ce63f7081`) so existing v0.1.0 installs upgrade in place.

---

## File Structure

After Plan 5 completes, the modified files:

```
HyperHDR-android/
├── tv/build.gradle                                    # versionCode 1→2, versionName 0.1.0-dev→0.2.0
├── .github/workflows/ci.yml                           # gradle-build-action@v3 → setup-gradle@v4
├── .github/workflows/release.yml                      # same action bump
└── common/src/main/java/eu/hyperhdr/android/
    ├── capture/
    │   ├── FrameClock.kt                              # nearest-rounding instead of truncation
    │   └── EglNv12Pipeline.kt                         # partial-init cleanup + OES detach
    └── flatbuf/
        └── HyperHdrFlatBufferClient.kt                # reusable FlatBufferBuilder owned by writer

common/src/test/java/eu/hyperhdr/android/capture/
└── FrameClockTest.kt                                  # +1 test for 90Hz/60fps rounding
```

No new files. No file deletions. Public API surface unchanged.

---

## Task 1: Bump version to 0.2.0-dev and refresh CI action versions

**Files:**
- Modify: `tv/build.gradle` — versionCode 1→2, versionName "0.1.0-dev"→"0.2.0-dev"
- Modify: `.github/workflows/ci.yml` — replace `gradle/gradle-build-action@v3` with `gradle/actions/setup-gradle@v4`
- Modify: `.github/workflows/release.yml` — same action bump

The action bump removes the deprecation warning emitted by the v0.1.0 release run. The `setup-gradle@v4` action is the same plugin under its current name; behavior identical.

- [ ] **Step 1: Bump versionCode and versionName in `tv/build.gradle`**

Open `tv/build.gradle`. Find the `defaultConfig { ... }` block and change:

```groovy
        versionCode 1
        versionName "0.1.0-dev"
```

to:

```groovy
        versionCode 2
        versionName "0.2.0-dev"
```

- [ ] **Step 2: Bump CI workflow action**

In `.github/workflows/ci.yml`, find both `unit-test` and `instrumented-test` jobs. Replace:

```yaml
      - uses: gradle/gradle-build-action@v3
```

with:

```yaml
      - uses: gradle/actions/setup-gradle@v4
```

(Two occurrences in the file.)

- [ ] **Step 3: Bump release workflow action**

In `.github/workflows/release.yml`, find:

```yaml
      - uses: gradle/gradle-build-action@v3
```

Replace with:

```yaml
      - uses: gradle/actions/setup-gradle@v4
```

(One occurrence.)

- [ ] **Step 4: Verify gradle still configures and builds**

```bash
cd /Users/jneerdael/Scripts/hyperhdr/HyperHDR-android
./gradlew :tv:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. The APK manifest now reports `versionCode=2, versionName=0.2.0-dev`.

Verify via:
```bash
$HOME/Library/Android/sdk/build-tools/35.0.0/aapt dump badging tv/build/outputs/apk/debug/tv-debug.apk | grep "versionCode\|versionName"
```

Expected: line containing `versionCode='2' versionName='0.2.0-dev'`.

- [ ] **Step 5: Commit**

```bash
git add tv/build.gradle .github/workflows/ci.yml .github/workflows/release.yml
git commit -m "build: bump to 0.2.0-dev, upgrade gradle-build-action → setup-gradle@v4"
```

---

## Task 2: TDD `FrameClock` — nearest-rounding fix

**Files:**
- Test: `common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt` — add a 90Hz/60fps test that currently fails
- Modify: `common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt` — change `(displayHz / targetFps).toInt()` to nearest-rounding

The current implementation truncates: `90/60 = 1.5 → 1`, so a 60 fps target on a 90 Hz panel emits every VSYNC = 90 fps actual, not 60. Plan 2's reviewer flagged this in I5. The fix is one line.

- [ ] **Step 1: Add the failing test**

Open `common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt`. Append this method inside the existing `FrameClockTest` class (before the closing `}`):

```kotlin
    @Test
    fun `60 fps target on 90 Hz display rounds to every-other-vsync`() {
        // 90/60 = 1.5; nearest-rounding gives divisor=2, so we emit every other vsync.
        // Truncation would give divisor=1 (emit every vsync = 90 fps actual). That's the bug.
        val clock = FrameClock(targetFps = 60, displayHz = 90.0)
        val results = (0 until 6).map { clock.shouldEmitForVsync(it.toLong()) }
        assertThat(results).containsExactly(true, false, true, false, true, false).inOrder()
    }
```

- [ ] **Step 2: Run, confirm FAILURE**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.capture.FrameClockTest"
```

Expected: 4 of 5 pass; the new test fails with output like:
```
expected: containsExactly(true, false, true, false, true, false)
but was : [true, true, true, true, true, true]
```

(All `true` because divisor=1 emits on every vsync.)

- [ ] **Step 3: Fix the implementation**

Open `common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt`. Change:

```kotlin
    private val divisor: Int = max(1, (displayHz / targetFps).toInt())
```

to:

```kotlin
    private val divisor: Int = max(1, kotlin.math.round(displayHz / targetFps).toInt())
```

(Keep the `max(1, ...)` floor so target-above-display-rate still emits every VSYNC instead of producing a 0-divisor that would crash modulo.)

- [ ] **Step 4: Run, confirm PASS**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.capture.FrameClockTest"
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/FrameClock.kt \
        common/src/test/java/eu/hyperhdr/android/capture/FrameClockTest.kt
git commit -m "fix(capture): FrameClock uses nearest-rounding, not truncation, for VSYNC divisor"
```

---

## Task 3: `HyperHdrFlatBufferClient` — reuse a single FlatBufferBuilder

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/flatbuf/HyperHdrFlatBufferClient.kt`

Currently `writeOutbound()` allocates `FlatBufferBuilder(64 * 1024)` per call — ~64 KB heap allocation per frame at 30 fps = ~2 MB/s of garbage. Plan 1's reviewer flagged this in I3. Since the writer coroutine is single-threaded (CONFLATED + single consumer), we can move the builder to a coroutine-local field and call `.clear()` instead of constructing fresh.

- [ ] **Step 1: Locate the current builder allocation**

Open `common/src/main/java/eu/hyperhdr/android/flatbuf/HyperHdrFlatBufferClient.kt`. Find the `writeOutbound(msg: Outbound)` private method. It currently begins with:

```kotlin
    private fun writeOutbound(msg: Outbound) {
        val out = output ?: return
        val builder = FlatBufferBuilder(64 * 1024)
        ...
    }
```

- [ ] **Step 2: Make the builder a class-level field reused by the writer**

Replace that local `val builder = ...` with a class-level field. Add this field near the other private state (next to where `outbox`, `writerJob`, etc. are declared — search for `private val outbox =` to find the area):

```kotlin
    /** Owned exclusively by the writer coroutine; safe to reuse without synchronization. */
    private val builder = FlatBufferBuilder(64 * 1024)
```

Then in `writeOutbound`, replace `val builder = FlatBufferBuilder(64 * 1024)` with `builder.clear()` so the existing buffer is rewound for the next frame. The full updated method opening should look like:

```kotlin
    private fun writeOutbound(msg: Outbound) {
        val out = output ?: return
        builder.clear()
        val reqOff = when (msg) {
            ...
        }
        ...
    }
```

(The rest of `writeOutbound` is unchanged — it already references `builder` rather than re-binding it.)

- [ ] **Step 3: Verify all unit tests still pass**

The existing `HyperHdrFlatBufferClientTest` (4 tests) covers Register, NV12, Clear, and Color frame round-trips — exactly the surface this change affects.

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.flatbuf.*"
```

Expected: all tests pass (3 BackoffSchedule + 4 client + 1 reconnector = 8 tests).

If the NV12 wire-format test fails: `builder.clear()` may not be resetting offsets correctly across frame types. Check that the FlatBuffers `clear()` method actually resets all internal state — in 25.2.10 it does, but if the runtime version drifts this contract could change.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/flatbuf/HyperHdrFlatBufferClient.kt
git commit -m "perf(flatbuf): reuse a single FlatBufferBuilder in writer coroutine (~2 MB/s less GC)"
```

---

## Task 4: `EglNv12Pipeline.start()` — partial-init cleanup

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt`

If `start()` throws partway through (FBO #2 fails after texture #2 was created), the half-initialized state leaks: `lateinit var downscale` may be unset, the OES texture is dangling, the EGL context is current but unowned. Plan 2's reviewer flagged this in I3. The fix wraps the body in try/catch with a private `releaseUnchecked()` that handles whatever was successfully created.

This change pairs with the projection-revoke fallback in `HyperHdrCaptureService` (Plan 3 fix C2): the service falls back to `CpuRgbFallbackEncoder` only if `HyperHdrGpuEncoder.start()` throws. With proper cleanup in `EglNv12Pipeline`, the throw is now safe — no leaked GL state.

- [ ] **Step 1: Wrap `start()` body with try/catch + cleanup**

Open `common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt`. Find the `start(): Surface` method. It currently calls `runOnGl { ... }` with a block that creates EGL, FBOs, programs, etc.

Restructure it so the body runs inside a try/catch that calls a new `releaseUnchecked()` helper on any throw:

```kotlin
    fun start(): Surface {
        runOnGl {
            try {
                egl = EglCore()
                pbuffer = egl.createPbufferSurface(1, 1)
                egl.makeCurrent(pbuffer!!)

                // Tier-aware shader (added in Plan 4 Task 2; preserve the pattern)
                when (config.tier) {
                    CaptureTier.SDR -> sdrDownscale = DownscaleProgram()
                    CaptureTier.HDR_AWARE -> hdrDownscale = HdrToneMapShader()
                }
                pack = Nv12PackProgram()

                oesTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                rgbaTextureId = createTexture2D(config.width, config.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, GLES20.GL_RGBA)
                yTextureId = createTexture2D(config.width, config.height, GLES30.GL_R8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RED)
                uvTextureId = createTexture2D(config.width / 2, config.height / 2, GLES30.GL_RG8, GLES20.GL_UNSIGNED_BYTE, GLES30.GL_RG)

                rgbaFbo = createFbo(rgbaTextureId)
                yFbo = createFbo(yTextureId)
                uvFbo = createFbo(uvTextureId)

                surfaceTexture = SurfaceTexture(oesTextureId)
                surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
                surface = Surface(surfaceTexture)
            } catch (t: Throwable) {
                Log.w(TAG, "EglNv12Pipeline.start() failed mid-init, cleaning up", t)
                releaseUnchecked()
                throw t
            }
        }
        return surface
    }
```

- [ ] **Step 2: Add the `releaseUnchecked()` helper**

Below `stop()`, add this private method that releases whatever was successfully initialized, ignoring null fields and swallowing any further throws:

```kotlin
    /**
     * Best-effort cleanup of partially-initialized GL state. Safe to call when [start] threw mid-way.
     * All operations are individually guarded; any sub-failure is logged and ignored.
     */
    private fun releaseUnchecked() {
        runCatching { if (::surface.isInitialized) surface.release() }
        runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
        if (rgbaFbo != 0 || yFbo != 0 || uvFbo != 0) {
            runCatching {
                GLES20.glDeleteFramebuffers(3, intArrayOf(rgbaFbo, yFbo, uvFbo), 0)
            }
            rgbaFbo = 0; yFbo = 0; uvFbo = 0
        }
        if (oesTextureId != 0 || rgbaTextureId != 0 || yTextureId != 0 || uvTextureId != 0) {
            runCatching {
                GLES20.glDeleteTextures(4, intArrayOf(oesTextureId, rgbaTextureId, yTextureId, uvTextureId), 0)
            }
            oesTextureId = 0; rgbaTextureId = 0; yTextureId = 0; uvTextureId = 0
        }
        runCatching { sdrDownscale?.release() }; sdrDownscale = null
        runCatching { hdrDownscale?.release() }; hdrDownscale = null
        runCatching { if (::pack.isInitialized) pack.release() }
        runCatching { pbuffer?.let { if (::egl.isInitialized) egl.destroySurface(it) } }
        pbuffer = null
        runCatching { if (::egl.isInitialized) egl.release() }
    }
```

(The `::surface.isInitialized` pattern is Kotlin's check for whether a `lateinit` field has been assigned. `pack`, `egl`, `surfaceTexture`, `surface` are all `lateinit`; the texture IDs and FBOs are zero-initialized `Int` fields so we use a sentinel check.)

- [ ] **Step 3: Refactor `stop()` to delegate to `releaseUnchecked()`**

The existing `stop()` method does the same cleanup work — DRY it up by delegating:

```kotlin
    fun stop() {
        runOnGl { releaseUnchecked() }
        thread.quitSafely()
    }
```

- [ ] **Step 4: Verify the instrumented test still passes**

```bash
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :common:connectedDebugAndroidTest --tests "eu.hyperhdr.android.capture.EglNv12PipelineSmokeTest"
```

Expected: BUILD SUCCESSFUL with 1 test passing on UGOOS-AM6 (API 28).

The test exercises the happy path (start succeeds → frame → stop). The partial-init path isn't directly tested here — that would require fault injection into `glGenTextures` etc., which is impractical. But the rewritten `stop()` going through `releaseUnchecked()` is exercised by every successful test run; if cleanup ordering is wrong, this test will fail or leak GL state visible in `adb logcat`.

- [ ] **Step 5: Verify HDR shader test also still passes**

```bash
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :common:connectedDebugAndroidTest --tests "eu.hyperhdr.android.capture.HdrShaderCompileTest"
```

Expected: BUILD SUCCESSFUL with 1 test passing.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt
git commit -m "fix(capture): EglNv12Pipeline.start() rolls back partial init via releaseUnchecked()"
```

---

## Task 5: `EglNv12Pipeline.stop()` — detach OES texture before delete

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt`

When the OES texture is deleted while `SurfaceTexture` still holds a reference to it, some drivers leak the producer-side resources. Plan 2's reviewer flagged this in I4. The fix is to call `setOnFrameAvailableListener(null)` and `surfaceTexture.detachFromGLContext()` before deleting the texture.

- [ ] **Step 1: Add the detach calls inside `releaseUnchecked()`**

The `releaseUnchecked()` method from Task 4 currently releases `surface` first, then `surfaceTexture`, then deletes textures. Insert the detach steps before the texture delete:

```kotlin
    private fun releaseUnchecked() {
        runCatching { if (::surface.isInitialized) surface.release() }
        runCatching {
            if (::surfaceTexture.isInitialized) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.detachFromGLContext()
                surfaceTexture.release()
            }
        }
        if (rgbaFbo != 0 || yFbo != 0 || uvFbo != 0) {
            runCatching {
                GLES20.glDeleteFramebuffers(3, intArrayOf(rgbaFbo, yFbo, uvFbo), 0)
            }
            rgbaFbo = 0; yFbo = 0; uvFbo = 0
        }
        if (oesTextureId != 0 || rgbaTextureId != 0 || yTextureId != 0 || uvTextureId != 0) {
            runCatching {
                GLES20.glDeleteTextures(4, intArrayOf(oesTextureId, rgbaTextureId, yTextureId, uvTextureId), 0)
            }
            oesTextureId = 0; rgbaTextureId = 0; yTextureId = 0; uvTextureId = 0
        }
        runCatching { sdrDownscale?.release() }; sdrDownscale = null
        runCatching { hdrDownscale?.release() }; hdrDownscale = null
        runCatching { if (::pack.isInitialized) pack.release() }
        runCatching { pbuffer?.let { if (::egl.isInitialized) egl.destroySurface(it) } }
        pbuffer = null
        runCatching { if (::egl.isInitialized) egl.release() }
    }
```

(Both `setOnFrameAvailableListener(null)` and `detachFromGLContext()` must be called from a thread with the EGL context current — which `releaseUnchecked()` already is, because it's invoked via `runOnGl { ... }` from `stop()`. They're inside the outer `runCatching` so a failure in either is logged but doesn't block the rest of the cleanup.)

- [ ] **Step 2: Verify the instrumented tests still pass**

```bash
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :common:connectedDebugAndroidTest
```

Expected: BUILD SUCCESSFUL with 2 tests passing (`EglNv12PipelineSmokeTest` + `HdrShaderCompileTest`).

If `detachFromGLContext()` throws on a particular driver: that's why it's wrapped in `runCatching`. The fallback is the same as before this fix — slightly leaky cleanup, but still functional. We don't need a special-case workaround.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/capture/EglNv12Pipeline.kt
git commit -m "fix(capture): detach OES texture before delete in EglNv12Pipeline cleanup"
```

---

## Task 6: Bump version to 0.2.0 (release candidate) and verify everything

**Files:**
- Modify: `tv/build.gradle` — versionName "0.2.0-dev" → "0.2.0"

Final pre-tag bump. The `-dev` suffix marks "in-development" code; dropping it marks a release.

- [ ] **Step 1: Bump versionName**

In `tv/build.gradle`, change:
```groovy
        versionName "0.2.0-dev"
```
to:
```groovy
        versionName "0.2.0"
```

(`versionCode` stays at 2.)

- [ ] **Step 2: Full verification**

```bash
./gradlew :common:testDebugUnitTest
./gradlew :common:assembleDebug :tv:assembleDebug
./gradlew :tv:assembleRelease
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :common:connectedDebugAndroidTest
```

Expected:
- 35 unit tests pass.
- Both debug builds produce APKs.
- The release APK is signed (verify with `apksigner verify`):

```bash
$HOME/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs tv/build/outputs/apk/release/tv-release.apk | grep "SHA-256\|certificate DN"
```
Expected: certificate DN `CN=HyperHDR-Android, O=johnneerdael, L=Unknown, ST=Unknown, C=NL` and certificate SHA-256 `71eda2e2ba6ff35d7cfdca3441ddf7d6bca818de6f635230390b439ce63f7081` — the v0.1.0 signing identity. If the SHA-256 differs, **stop** — something has gone wrong with the keystore. Existing v0.1.0 installs would refuse to upgrade.

- 2 instrumented tests pass on UGOOS-AM6.

- [ ] **Step 3: Commit**

```bash
git add tv/build.gradle
git commit -m "build: bump to 0.2.0"
```

- [ ] **Step 4: Push, then tag**

```bash
git push origin main
git tag -a v0.2.0 -m "HyperHDR-Android v0.2.0

Quick polish release:
- FrameClock now uses nearest-rounding for VSYNC divisor (fixes 90Hz/60fps panels)
- HyperHdrFlatBufferClient reuses a single FlatBufferBuilder (~2 MB/s less GC pressure at 30 fps)
- EglNv12Pipeline.start() rolls back partial-init failures cleanly
- EglNv12Pipeline.stop() detaches OES texture before delete to satisfy strict drivers
- CI/release workflows upgraded to gradle/actions/setup-gradle@v4

Same signing identity as v0.1.0 (cert SHA-256: 71eda2e2…39ce63f7081). Upgrades in place."
git push origin v0.2.0
```

The release workflow fires on the tag, decodes the keystore, builds + signs the APK, creates the GitHub Release with the SHA-256 in the body.

- [ ] **Step 5: Watch the release run + verify the published artifact**

```bash
gh run watch --repo johnneerdael/HyperHDR-android --exit-status
```

(After the run completes:)

```bash
cd /tmp && rm -f tv-release.apk
gh release download v0.2.0 --repo johnneerdael/HyperHDR-android --pattern '*.apk' -O tv-release.apk
$HOME/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs tv-release.apk | grep "SHA-256 digest"
```

Expected: cert SHA-256 `71eda2e2ba6ff35d7cfdca3441ddf7d6bca818de6f635230390b439ce63f7081` (matches v0.1.0).

- [ ] **Step 6: Smoke install over v0.1.0 on a real device**

```bash
adb install -r /tmp/tv-release.apk
```

`-r` means "replace existing installation if present." Because the cert matches v0.1.0, Android will accept the upgrade. If the certificate didn't match you'd see `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — which is the alarm bell that the keystore identity drifted.

Open the app. The wizard flow + main screen + capture toggle should still work — Plan 5 changed no public API.

---

## Plan 5 done definition

- [ ] Six tasks done.
- [ ] All 35 unit tests + 2 instrumented tests pass.
- [ ] `:tv:assembleRelease` builds with the same v0.1.0 signing certificate.
- [ ] `v0.2.0` tag exists on GitHub with a published Release containing a signed APK whose certificate matches v0.1.0.
- [ ] `adb install -r` over a v0.1.0 install succeeds (proves cert-identity preservation).

---

## Self-review (writer's pre-flight)

- **Backlog coverage:** items 3 (FlatBufferBuilder reuse) → Task 3; 4 (FrameClock rounding) → Task 2; 5 (partial-init cleanup) → Task 4; 6 (OES detach) → Task 5. All four flagged items have tasks. Task 1 (version+CI bump) and Task 6 (release cut) are infrastructure; not strictly part of the backlog but necessary to ship as a versioned release.
- **Type consistency:** No new types introduced. `FrameClock`, `HyperHdrFlatBufferClient`, `EglNv12Pipeline` all keep their public signatures. Internal field name `builder` reused intentionally (replaces the local with the same name).
- **No placeholders:** every code block is concrete; every command has expected output; the cert SHA-256 is the literal value from v0.1.0.
- **Test coverage:** the FrameClock fix gets a new test that proves the bug existed and the fix resolves it. The FlatBufferBuilder reuse is covered by existing wire-format tests. The two `EglNv12Pipeline` cleanups are exercised by the instrumented smoke + HDR shader tests; the partial-init *failure* path isn't directly tested but its replacement (`stop()` going through `releaseUnchecked()`) is exercised on every test teardown.
- **Risk:** the `setOnFrameAvailableListener(null)` + `detachFromGLContext()` calls in Task 5 are wrapped in `runCatching` so a driver that misbehaves on either won't break cleanup. If we see field reports of leaked surfaces, that's the next thing to investigate.
