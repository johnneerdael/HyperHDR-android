# Android HDR PQ Delivery Feasibility Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a throwaway `:probe` Gradle module that captures frames from Android's MediaProjection on the Google TV Streamer 4K (192.168.50.102, API 34, HDR-capable) via two `ImageReader` configurations — HDR (DataSpace.BT2020_PQ + YCBCR_P010 hardware-buffer format) and SDR (RGBA_8888) — and produces enough offline-analysable evidence to classify the device's behaviour as Go / Partial / No-Go for real PQ delivery.

**Architecture:** Standalone APK (`eu.hyperhdr.android.probe`, min SDK 34) with a single Compose-for-TV Activity. Capture is run on a dedicated `HandlerThread` so frame callbacks don't block the main thread. Each captured `Image` writes raw planes to `frame-<mode>-NNNN.raw` plus a `metadata.jsonl` line with on-device histogram + range stats + reflected `DataSpace` / `HardwareBuffer` constant names. Offline `analyze.py` (numpy + matplotlib) reads the dump and emits `report.md` + `report-frames.html` with the Go/Partial/No-Go classification.

**Tech Stack:** Kotlin + Compose-for-TV (matches the existing `:tv` module's idiom), `kotlinx-coroutines`, Android `ImageReader` / `MediaProjection` / `DataSpace` / `HardwareBuffer` (API 34+), Python 3 (`numpy`, `matplotlib`) for offline analysis, JUnit + Truth for JVM-side tests on pure-logic classes.

**Test device:** 192.168.50.102:5555 (Google TV Streamer 4K). Do **not** use 192.168.50.98 (UGOOS-AM6, API 28 — too old for the API surface) or 192.168.50.71 (AM9 PRO — reserved by user policy).

---

## File Structure

```
HyperHDR-android/
├── settings.gradle                              # Modify: add `include ':probe'`
└── probe/                                       # NEW MODULE
    ├── build.gradle                             # Android application config, minSdk 34
    ├── src/
    │   ├── main/
    │   │   ├── AndroidManifest.xml              # Single Activity, leanback launcher
    │   │   ├── java/eu/hyperhdr/android/probe/
    │   │   │   ├── HistogramComputer.kt         # Pure logic (JVM-testable)
    │   │   │   ├── MetadataWriter.kt            # Pure logic (JVM-testable)
    │   │   │   ├── HdrProbe.kt                  # ImageReader+DataSpace HDR capture
    │   │   │   ├── SdrProbe.kt                  # ImageReader RGBA_8888 capture
    │   │   │   ├── ProbeOrchestrator.kt         # State machine HDR→SDR→Done
    │   │   │   └── MainActivity.kt              # Compose UI
    │   │   └── res/values/strings.xml           # "HDR PQ Probe" app name
    │   └── test/java/eu/hyperhdr/android/probe/
    │       ├── HistogramComputerTest.kt         # JVM unit tests
    │       └── MetadataWriterTest.kt            # JVM unit tests
    └── scripts/
        ├── analyze.py                           # Offline classifier
        └── test_analyze.py                      # Self-test for analyze.py
```

The `:common` and `:tv` modules are not modified. The probe is fully isolated.

---

## Task 1: Create the `:probe` Gradle module skeleton

**Files:**
- Modify: `settings.gradle`
- Create: `probe/build.gradle`
- Create: `probe/src/main/AndroidManifest.xml`
- Create: `probe/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the module to `settings.gradle`**

Open `settings.gradle` (project root). Add the line:

```groovy
include ':probe'
```

If `settings.gradle` already includes `:common` and `:tv` via `include ':common', ':tv'`, append `, ':probe'` to that line. If it uses one `include` per line, add a new `include ':probe'` line below the existing ones.

- [ ] **Step 2: Create `probe/build.gradle`**

```groovy
plugins {
    id 'com.android.application'
    id 'kotlin-android'
}

apply from: project(':common').projectDir.absolutePath + '/variables.gradle'

android {
    namespace 'eu.hyperhdr.android.probe'
    compileSdk 34

    defaultConfig {
        applicationId "eu.hyperhdr.android.probe"
        minSdk 34
        targetSdk 34
        versionCode 1
        versionName "0.1.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.debug   // throwaway — debug-signed is fine
        }
    }

    compileOptions {
        targetCompatibility JavaVersion.VERSION_17
        sourceCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    buildFeatures {
        compose true
        buildConfig true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.10'
    }
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"

    def composeBom = platform('androidx.compose:compose-bom:2024.02.00')
    implementation composeBom
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.material3:material3'
    debugImplementation 'androidx.compose.ui:ui-tooling'
    implementation 'androidx.tv:tv-foundation:1.0.0-alpha10'
    implementation 'androidx.tv:tv-material:1.0.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.7.0'
    implementation 'androidx.activity:activity-compose:1.8.2'

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'com.google.truth:truth:1.4.2'
}
```

- [ ] **Step 3: Create `probe/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.software.leanback" android:required="false" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar"
        tools:targetApi="34">

        <activity
            android:name="eu.hyperhdr.android.probe.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Create `probe/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<resources>
    <string name="app_name">HDR PQ Probe</string>
</resources>
```

- [ ] **Step 5: Verify the module Gradle-syncs**

Run:
```bash
./gradlew :probe:tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` and a list of probe-module tasks. If you see "Project ':probe' not found", `settings.gradle` wasn't updated correctly in Step 1.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle probe/build.gradle probe/src/main/AndroidManifest.xml probe/src/main/res/values/strings.xml
git commit -m "feat(probe): scaffold :probe Gradle module

Standalone Android application module for the HDR PQ feasibility probe.
applicationId eu.hyperhdr.android.probe (separate from production app).
minSdk 34 — explicitly only targets devices that have the
ImageReader.Builder().setDefaultDataSpace() API used by the probe.

Compose-for-TV deps mirror the :tv module so the UI patterns are
familiar. No dependency on :common or :tv — the probe is isolated."
```

---

## Task 2: `HistogramComputer` — pure-logic Y plane analysis (TDD)

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/HistogramComputer.kt`
- Create: `probe/src/test/java/eu/hyperhdr/android/probe/HistogramComputerTest.kt`

Computes a 32-bin histogram + min/max/mean of the 10-bit Y values from a P010 plane. Pure Kotlin, no Android deps, JVM-testable.

The P010 byte layout: each Y sample is a 16-bit little-endian word with the 10-bit value in the high bits and 6 zero pad bits in the low bits. To extract the 10-bit value: `(word >> 6) & 0x3FF`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package eu.hyperhdr.android.probe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HistogramComputerTest {

    /** Build a P010 Y plane buffer where every sample is the same 10-bit value. */
    private fun uniformY(value10: Int, width: Int, height: Int, rowStride: Int): ByteBuffer {
        require(rowStride >= width * 2) { "rowStride must accommodate width*2 bytes" }
        val buf = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val word16 = ((value10 and 0x3FF) shl 6).toShort()
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                buf.putShort(rowStart + x * 2, word16)
            }
        }
        return buf
    }

    @Test
    fun `uniform mid-range Y produces single-bin histogram with matching mean`() {
        val buf = uniformY(value10 = 512, width = 16, height = 8, rowStride = 32)
        val stats = HistogramComputer.compute(buf, width = 16, height = 8, rowStride = 32, pixelStride = 2)
        assertThat(stats.min).isEqualTo(512)
        assertThat(stats.max).isEqualTo(512)
        assertThat(stats.mean).isEqualTo(512)
        // Bin width is 1024/32 = 32; value 512 falls in bin index 512/32 = 16.
        assertThat(stats.histogram32.sum()).isEqualTo(16 * 8)
        assertThat(stats.histogram32[16]).isEqualTo(16 * 8)
    }

    @Test
    fun `min and max scan the full range`() {
        val buf = uniformY(value10 = 64, width = 4, height = 2, rowStride = 8)
        // Inject a single bright sample at (0,0) = 940
        buf.order(ByteOrder.LITTLE_ENDIAN).putShort(0, ((940 and 0x3FF) shl 6).toShort())
        val stats = HistogramComputer.compute(buf, width = 4, height = 2, rowStride = 8, pixelStride = 2)
        assertThat(stats.min).isEqualTo(64)
        assertThat(stats.max).isEqualTo(940)
    }

    @Test
    fun `histogram total equals width times height`() {
        val buf = uniformY(value10 = 256, width = 32, height = 16, rowStride = 64)
        val stats = HistogramComputer.compute(buf, width = 32, height = 16, rowStride = 64, pixelStride = 2)
        assertThat(stats.histogram32.sum()).isEqualTo(32 * 16)
    }

    @Test
    fun `respects rowStride larger than width times pixelStride (padded rows)`() {
        // 4 pixels wide, but each row has 4 bytes of padding after the pixel data.
        val width = 4; val height = 2; val rowStride = 12  // (4 * 2) + 4 = 12
        val buf = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val v = ((300 and 0x3FF) shl 6).toShort()
        // Row 0: 4 valid samples + 4 bytes of garbage; row 1: same
        for (y in 0 until height) {
            for (x in 0 until width) buf.putShort(y * rowStride + x * 2, v)
            // garbage bytes intentionally left as 0; if HistogramComputer reads them it'll fail
        }
        val stats = HistogramComputer.compute(buf, width, height, rowStride, pixelStride = 2)
        // Should ONLY count the 4 * 2 = 8 valid samples, all at value 300.
        assertThat(stats.histogram32.sum()).isEqualTo(8)
        assertThat(stats.min).isEqualTo(300)
        assertThat(stats.max).isEqualTo(300)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
./gradlew :probe:testDebugUnitTest --tests "eu.hyperhdr.android.probe.HistogramComputerTest" 2>&1 | tail -10
```
Expected: compile error — `HistogramComputer` does not exist yet.

- [ ] **Step 3: Implement `HistogramComputer`**

Create `probe/src/main/java/eu/hyperhdr/android/probe/HistogramComputer.kt`:

```kotlin
package eu.hyperhdr.android.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Computes a 32-bin histogram and min/max/mean over the 10-bit Y values stored in a P010
 * plane buffer. Pure logic, no Android deps, suitable for JVM unit tests.
 *
 * The P010 wire format encodes each sample as a 16-bit little-endian word: high 10 bits =
 * the 10-bit value, low 6 bits = zero pad. We extract the 10-bit value as
 * `(word >> 6) & 0x3FF`.
 *
 * The histogram has 32 bins each spanning 32 of the 0..1023 range
 * (bin width = 1024 / 32 = 32). A value `v` lands in bin `v / 32`.
 */
object HistogramComputer {

    data class Stats(
        val min: Int,
        val max: Int,
        val mean: Int,
        val histogram32: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Stats &&
            min == other.min && max == other.max && mean == other.mean &&
            histogram32.contentEquals(other.histogram32)
        override fun hashCode(): Int =
            (min * 31 + max) * 31 * 31 + mean * 31 + histogram32.contentHashCode()
    }

    /**
     * @param plane The Y plane bytes. The buffer's byte-order is overridden to LITTLE_ENDIAN
     * for reading (matching the P010 wire format) but the buffer's position is not modified.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param rowStride Bytes per row of the plane (may exceed `width * pixelStride` due to padding).
     * @param pixelStride Bytes per pixel sample (P010 Y plane = 2).
     */
    fun compute(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Stats {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(pixelStride >= 2) { "P010 sample is 2 bytes minimum" }
        require(rowStride >= width * pixelStride) { "rowStride must accommodate the row" }

        val view = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val histogram = IntArray(32)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val word = view.getShort(rowStart + x * pixelStride).toInt() and 0xFFFF
                val value10 = (word shr 6) and 0x3FF
                if (value10 < min) min = value10
                if (value10 > max) max = value10
                sum += value10
                histogram[value10 / 32]++
            }
        }
        val mean = (sum / (width.toLong() * height)).toInt()
        return Stats(min = min, max = max, mean = mean, histogram32 = histogram)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :probe:testDebugUnitTest --tests "eu.hyperhdr.android.probe.HistogramComputerTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/HistogramComputer.kt \
        probe/src/test/java/eu/hyperhdr/android/probe/HistogramComputerTest.kt
git commit -m "feat(probe): HistogramComputer for P010 Y plane analysis

Pure Kotlin logic. Reads a 16-bit little-endian P010 sample buffer,
extracts the 10-bit value via (word >> 6) & 0x3FF, and returns a
32-bin histogram + min/max/mean.

JVM-tested with 4 cases covering uniform values, full range scanning,
total-count invariant, and padded row strides."
```

---

## Task 3: `MetadataWriter` — JSONL serialisation (TDD)

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/MetadataWriter.kt`
- Create: `probe/src/test/java/eu/hyperhdr/android/probe/MetadataWriterTest.kt`

Serialises one `FrameMetadata` per line as JSON, written to a `metadata.jsonl` file. Pure Kotlin, JVM-testable. Uses `org.json.JSONObject` (already on the Android platform classpath, available in unit tests via the `JSON-java` library bundled with the JVM Android artifact, or we can use plain string assembly to avoid a dependency).

For the unit test we'll use plain string assembly to avoid pulling in `org.json` as a test dependency. The output is small enough that hand-rolled JSON is fine.

- [ ] **Step 1: Write the failing tests**

```kotlin
package eu.hyperhdr.android.probe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.StringWriter

class MetadataWriterTest {

    @Test
    fun `serializes hdr-mode frame as one JSONL line ending in newline`() {
        val out = StringWriter()
        val writer = MetadataWriter(out)
        writer.write(FrameMetadata(
            mode = "hdr",
            index = 0,
            captureTsNs = 1234567890L,
            dataspaceInt = 281542656,
            dataspaceName = "DATASPACE_BT2020_PQ",
            hardwareBufferFormatInt = 54,
            hardwareBufferFormatName = "YCBCR_P010",
            yPixelStride = 2,
            yRowStride = 7680,
            uvPixelStride = 4,
            uvRowStride = 7680,
            yMin = 196, yMax = 904, yMean = 428,
            uvUMean = 510, uvVMean = 516,
            yHistogram32Bin = intArrayOf(0, 0, 12, 89, 50, 30, 20, 10, 5, 3, 0, 0, 0, 0, 0, 0,
                                         0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        ))
        val text = out.toString()
        assertThat(text).endsWith("\n")
        assertThat(text).contains("\"mode\":\"hdr\"")
        assertThat(text).contains("\"index\":0")
        assertThat(text).contains("\"dataspace\":281542656")
        assertThat(text).contains("\"dataspace_name\":\"DATASPACE_BT2020_PQ\"")
        assertThat(text).contains("\"y_min\":196")
        assertThat(text).contains("\"y_histogram_32bin\":[0,0,12,89,50,30,20,10,5,3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]")
    }

    @Test
    fun `multiple frames produce one line each`() {
        val out = StringWriter()
        val writer = MetadataWriter(out)
        for (i in 0 until 3) {
            writer.write(FrameMetadata(
                mode = "sdr", index = i, captureTsNs = 1000L + i,
                dataspaceInt = 0, dataspaceName = "DATASPACE_UNKNOWN",
                hardwareBufferFormatInt = 1, hardwareBufferFormatName = "RGBA_8888",
                yPixelStride = 4, yRowStride = 4 * 1920,
                uvPixelStride = 0, uvRowStride = 0,
                yMin = 0, yMax = 255, yMean = 128,
                uvUMean = 0, uvVMean = 0,
                yHistogram32Bin = IntArray(32),
            ))
        }
        val lines = out.toString().split("\n").filter { it.isNotEmpty() }
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"index\":0")
        assertThat(lines[1]).contains("\"index\":1")
        assertThat(lines[2]).contains("\"index\":2")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :probe:testDebugUnitTest --tests "eu.hyperhdr.android.probe.MetadataWriterTest" 2>&1 | tail -10
```
Expected: compile error — `MetadataWriter` and `FrameMetadata` don't exist.

- [ ] **Step 3: Implement `MetadataWriter` and `FrameMetadata`**

Create `probe/src/main/java/eu/hyperhdr/android/probe/MetadataWriter.kt`:

```kotlin
package eu.hyperhdr.android.probe

import java.io.Writer

/**
 * One captured frame's metadata. Mode is "hdr" or "sdr". Histogram array always has 32 entries.
 */
data class FrameMetadata(
    val mode: String,
    val index: Int,
    val captureTsNs: Long,
    val dataspaceInt: Int,
    val dataspaceName: String,
    val hardwareBufferFormatInt: Int,
    val hardwareBufferFormatName: String,
    val yPixelStride: Int,
    val yRowStride: Int,
    val uvPixelStride: Int,
    val uvRowStride: Int,
    val yMin: Int,
    val yMax: Int,
    val yMean: Int,
    val uvUMean: Int,
    val uvVMean: Int,
    val yHistogram32Bin: IntArray,
) {
    init {
        require(yHistogram32Bin.size == 32) { "histogram must have 32 bins" }
        require(mode == "hdr" || mode == "sdr") { "mode must be 'hdr' or 'sdr'" }
    }
}

/**
 * Writes one [FrameMetadata] per line as JSON, terminated by `\n`. The output is a JSONL
 * stream — `metadata.jsonl` in the probe's run directory.
 *
 * Hand-rolled string assembly (no JSON library dependency) — output is small and we control
 * every type. Strings are escaped only against the characters we actually emit (`"` and `\`);
 * mode/dataspace_name/format_name are constants under our control, no untrusted input.
 */
class MetadataWriter(private val out: Writer) {

    fun write(m: FrameMetadata) {
        val sb = StringBuilder(512)
        sb.append('{')
        appendStr(sb, "mode", m.mode); sb.append(',')
        appendInt(sb, "index", m.index); sb.append(',')
        appendLong(sb, "capture_ts_ns", m.captureTsNs); sb.append(',')
        appendInt(sb, "dataspace", m.dataspaceInt); sb.append(',')
        appendStr(sb, "dataspace_name", m.dataspaceName); sb.append(',')
        appendInt(sb, "hardware_buffer_format", m.hardwareBufferFormatInt); sb.append(',')
        appendStr(sb, "hardware_buffer_format_name", m.hardwareBufferFormatName); sb.append(',')
        appendInt(sb, "y_pixel_stride", m.yPixelStride); sb.append(',')
        appendInt(sb, "y_row_stride", m.yRowStride); sb.append(',')
        appendInt(sb, "uv_pixel_stride", m.uvPixelStride); sb.append(',')
        appendInt(sb, "uv_row_stride", m.uvRowStride); sb.append(',')
        appendInt(sb, "y_min", m.yMin); sb.append(',')
        appendInt(sb, "y_max", m.yMax); sb.append(',')
        appendInt(sb, "y_mean", m.yMean); sb.append(',')
        appendInt(sb, "uv_u_mean", m.uvUMean); sb.append(',')
        appendInt(sb, "uv_v_mean", m.uvVMean); sb.append(',')
        appendIntArray(sb, "y_histogram_32bin", m.yHistogram32Bin)
        sb.append("}\n")
        out.write(sb.toString())
        out.flush()
    }

    private fun appendStr(sb: StringBuilder, k: String, v: String) {
        sb.append('"').append(k).append("\":\"").append(v).append('"')
    }
    private fun appendInt(sb: StringBuilder, k: String, v: Int) {
        sb.append('"').append(k).append("\":").append(v)
    }
    private fun appendLong(sb: StringBuilder, k: String, v: Long) {
        sb.append('"').append(k).append("\":").append(v)
    }
    private fun appendIntArray(sb: StringBuilder, k: String, v: IntArray) {
        sb.append('"').append(k).append("\":[")
        for (i in v.indices) {
            if (i > 0) sb.append(',')
            sb.append(v[i])
        }
        sb.append(']')
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :probe:testDebugUnitTest --tests "eu.hyperhdr.android.probe.MetadataWriterTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/MetadataWriter.kt \
        probe/src/test/java/eu/hyperhdr/android/probe/MetadataWriterTest.kt
git commit -m "feat(probe): MetadataWriter — JSONL frame metadata serialiser

FrameMetadata data class + writer that emits one line per frame to a
metadata.jsonl file. Hand-rolled string assembly, no JSON library
dependency — output keys are all constants under our control. The
format includes both integer values and resolved symbolic names for
DataSpace and HardwareBuffer constants so the dump is self-describing
even if the int encodings differ across API levels."
```

---

## Task 4: `HdrProbe` — `ImageReader` HDR capture path

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/HdrProbe.kt`

This is Android-only (requires `ImageReader`, `MediaProjection`, `Handler`). No JVM unit tests — the probe itself IS the test of this code on real hardware.

- [ ] **Step 1: Implement `HdrProbe`**

Create `probe/src/main/java/eu/hyperhdr/android/probe/HdrProbe.kt`:

```kotlin
package eu.hyperhdr.android.probe

import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Captures up to [targetFrames] HDR frames from [projection] into an [ImageReader] configured
 * for `HardwareBuffer.YCBCR_P010` + `DataSpace.DATASPACE_BT2020_PQ` and writes:
 *   - `frame-hdr-NNNN.raw`   — Y plane bytes followed by UV plane bytes (native byte order)
 *   - one line appended to [metadataWriter] per frame
 *
 * If the configuration throws (e.g. driver doesn't accept BT2020_PQ on this device), the
 * exception is caught at [run] and reported back via the returned [Result].
 *
 * Times out after [timeoutMs] if no frames arrive.
 */
class HdrProbe(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val outputDir: File,
    private val metadataWriter: MetadataWriter,
    private val targetFrames: Int = 60,
    private val timeoutMs: Long = 10_000,
) {
    companion object { private const val TAG = "HdrProbe" }

    private val thread = HandlerThread("HdrProbe").also { it.start() }
    private val handler = Handler(thread.looper)

    suspend fun run(): Result<Int> = suspendCancellableCoroutine { cont ->
        val reader: ImageReader = try {
            ImageReader.Builder(width, height)
                .setMaxImages(3)
                .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
                .setDefaultDataSpace(DataSpace.DATASPACE_BT2020_PQ)
                .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader.Builder rejected HDR config", t)
            cleanup(null, null)
            cont.resume(Result.failure(t)); return@suspendCancellableCoroutine
        }

        var frameCount = 0
        var virtualDisplay: VirtualDisplay? = null

        val timeoutRunnable = Runnable {
            Log.w(TAG, "HDR capture timed out after ${timeoutMs}ms with $frameCount frames")
            cleanup(reader, virtualDisplay)
            cont.resume(Result.success(frameCount))
        }

        reader.setOnImageAvailableListener({ r ->
            if (frameCount >= targetFrames) return@setOnImageAvailableListener
            val image: Image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image, frameCount)
                frameCount++
                if (frameCount >= targetFrames) {
                    handler.removeCallbacks(timeoutRunnable)
                    cleanup(reader, virtualDisplay)
                    cont.resume(Result.success(frameCount))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Frame $frameCount processing failed", t)
            } finally {
                image.close()
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "HdrPqProbe-HDR",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface,
                null, handler,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay creation failed", t)
            cleanup(reader, null)
            cont.resume(Result.failure(t)); return@suspendCancellableCoroutine
        }

        handler.postDelayed(timeoutRunnable, timeoutMs)
        cont.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            cleanup(reader, virtualDisplay)
        }
    }

    private fun processFrame(image: Image, index: Int) {
        val planes = image.planes
        require(planes.size >= 2) { "P010 image expected to have ≥2 planes, got ${planes.size}" }
        val y = planes[0]; val uv = planes[1]
        val yBuf = y.buffer; val uvBuf = uv.buffer

        // Persist raw planes to disk. Y plane first, then UV.
        File(outputDir, "frame-hdr-%04d.raw".format(index)).outputStream().use { fos ->
            writeBufferTo(yBuf, fos)
            writeBufferTo(uvBuf, fos)
        }

        // Compute Y stats. UV stats are quick scans.
        val yStats = HistogramComputer.compute(yBuf, width, height, y.rowStride, y.pixelStride)
        val (uMean, vMean) = computeUvMeans(uvBuf, width / 2, height / 2, uv.rowStride, uv.pixelStride)

        val dataSpaceInt = image.dataSpace
        val hwFormatInt = image.hardwareBuffer?.format ?: -1
        metadataWriter.write(FrameMetadata(
            mode = "hdr",
            index = index,
            captureTsNs = image.timestamp,
            dataspaceInt = dataSpaceInt,
            dataspaceName = ConstantNameResolver.dataSpaceName(dataSpaceInt),
            hardwareBufferFormatInt = hwFormatInt,
            hardwareBufferFormatName = ConstantNameResolver.hardwareBufferFormatName(hwFormatInt),
            yPixelStride = y.pixelStride,
            yRowStride = y.rowStride,
            uvPixelStride = uv.pixelStride,
            uvRowStride = uv.rowStride,
            yMin = yStats.min, yMax = yStats.max, yMean = yStats.mean,
            uvUMean = uMean, uvVMean = vMean,
            yHistogram32Bin = yStats.histogram32,
        ))
    }

    private fun writeBufferTo(buf: java.nio.ByteBuffer, out: FileOutputStream) {
        val view = buf.duplicate()
        view.rewind()
        val arr = ByteArray(view.remaining())
        view.get(arr)
        out.write(arr)
    }

    /** Returns (uMean, vMean) — 10-bit means of U (Cb) and V (Cr) interleaved samples. */
    private fun computeUvMeans(
        plane: java.nio.ByteBuffer, uvWidth: Int, uvHeight: Int, rowStride: Int, pixelStride: Int,
    ): Pair<Int, Int> {
        val view = plane.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var uSum = 0L; var vSum = 0L
        for (y in 0 until uvHeight) {
            val rowStart = y * rowStride
            for (x in 0 until uvWidth) {
                val pos = rowStart + x * pixelStride
                val uWord = view.getShort(pos).toInt() and 0xFFFF
                val vWord = view.getShort(pos + 2).toInt() and 0xFFFF
                uSum += (uWord shr 6) and 0x3FF
                vSum += (vWord shr 6) and 0x3FF
            }
        }
        val n = uvWidth.toLong() * uvHeight
        return (uSum / n).toInt() to (vSum / n).toInt()
    }

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }
}
```

- [ ] **Step 2: Implement `ConstantNameResolver` (referenced above)**

Create `probe/src/main/java/eu/hyperhdr/android/probe/ConstantNameResolver.kt`:

```kotlin
package eu.hyperhdr.android.probe

import android.hardware.DataSpace
import android.hardware.HardwareBuffer

/**
 * Resolves Android system constants (DataSpace.*, HardwareBuffer.*) from int back to symbolic
 * name via reflection. Used to make the probe's metadata.jsonl self-describing — even if the
 * int encoding of a constant changes between API levels, the dump still records what it was.
 */
object ConstantNameResolver {

    private val dataSpaceNames: Map<Int, String> by lazy {
        DataSpace::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && it.name.startsWith("DATASPACE_") }
            .associate { runCatching { it.getInt(null) to it.name }.getOrNull() ?: (-1 to it.name) }
            .filterKeys { it != -1 }
    }

    private val hardwareBufferFormatNames: Map<Int, String> by lazy {
        HardwareBuffer::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .filter { it.name in EXPECTED_HW_FORMATS }
            .associate { runCatching { it.getInt(null) to it.name }.getOrNull() ?: (-1 to it.name) }
            .filterKeys { it != -1 }
    }

    private val EXPECTED_HW_FORMATS = setOf(
        "RGBA_8888", "RGBX_8888", "RGB_888", "RGB_565",
        "RGBA_FP16", "RGBA_1010102",
        "BLOB", "DEPTH_16", "DEPTH_24", "DEPTH_24_STENCIL_8", "DEPTH_32F", "DEPTH_32F_STENCIL_8",
        "STENCIL_8", "YCBCR_420_888", "YCBCR_P010",
    )

    fun dataSpaceName(value: Int): String = dataSpaceNames[value] ?: "UNKNOWN(0x%08X)".format(value)

    fun hardwareBufferFormatName(value: Int): String =
        hardwareBufferFormatNames[value] ?: "UNKNOWN(0x%08X)".format(value)
}
```

- [ ] **Step 3: Verify the module compiles**

```bash
./gradlew :probe:compileDebugKotlin 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/HdrProbe.kt \
        probe/src/main/java/eu/hyperhdr/android/probe/ConstantNameResolver.kt
git commit -m "feat(probe): HdrProbe — ImageReader.YCBCR_P010 + DATASPACE_BT2020_PQ capture

Configures ImageReader.Builder with the API 34+ HDR path and routes
MediaProjection frames into it. For each frame, writes the raw Y+UV
planes to frame-hdr-NNNN.raw and a metadata line via MetadataWriter.

Catches exceptions from the Builder (some devices' drivers may reject
the HDR configuration outright) and surfaces them via Result<Int>.

ConstantNameResolver reflects DataSpace.* and HardwareBuffer.* fields
to record symbolic names alongside int values in the dump."
```

---

## Task 5: `SdrProbe` — `ImageReader` SDR baseline capture

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/SdrProbe.kt`

Mirrors `HdrProbe` but uses the simpler `ImageReader.newInstance(w, h, ImageFormat.RGBA_8888, 3)` constructor — no DataSpace request. Captures the same content for direct comparison.

- [ ] **Step 1: Implement `SdrProbe`**

```kotlin
package eu.hyperhdr.android.probe

import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SDR baseline capture. Same MediaProjection source, same target frame count, simpler
 * ImageReader configuration: ImageFormat.RGBA_8888, no DataSpace request. Output:
 *   - `frame-sdr-NNNN.raw`  — RGBA_8888 buffer (W*H*4 bytes), native byte order
 *   - one metadata line per frame via [metadataWriter]
 *
 * Y stats / histogram are computed from the RGBA luma proxy (0.299*R + 0.587*G + 0.114*B
 * scaled to the 10-bit range for fair comparison against HdrProbe's metadata format).
 * UV-mean fields are filled with sentinel zeros.
 */
class SdrProbe(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val outputDir: File,
    private val metadataWriter: MetadataWriter,
    private val targetFrames: Int = 60,
    private val timeoutMs: Long = 10_000,
) {
    companion object { private const val TAG = "SdrProbe" }

    private val thread = HandlerThread("SdrProbe").also { it.start() }
    private val handler = Handler(thread.looper)

    suspend fun run(): Result<Int> = suspendCancellableCoroutine { cont ->
        val reader = ImageReader.newInstance(width, height, ImageFormat.RGBA_8888, 3)
        var frameCount = 0
        var virtualDisplay: VirtualDisplay? = null

        val timeoutRunnable = Runnable {
            Log.w(TAG, "SDR capture timed out after ${timeoutMs}ms with $frameCount frames")
            cleanup(reader, virtualDisplay)
            cont.resume(Result.success(frameCount))
        }

        reader.setOnImageAvailableListener({ r ->
            if (frameCount >= targetFrames) return@setOnImageAvailableListener
            val image: Image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image, frameCount)
                frameCount++
                if (frameCount >= targetFrames) {
                    handler.removeCallbacks(timeoutRunnable)
                    cleanup(reader, virtualDisplay)
                    cont.resume(Result.success(frameCount))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Frame $frameCount processing failed", t)
            } finally {
                image.close()
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "HdrPqProbe-SDR",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface,
                null, handler,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay creation failed", t)
            cleanup(reader, null)
            cont.resume(Result.failure(t)); return@suspendCancellableCoroutine
        }

        handler.postDelayed(timeoutRunnable, timeoutMs)
        cont.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            cleanup(reader, virtualDisplay)
        }
    }

    private fun processFrame(image: Image, index: Int) {
        val plane = image.planes[0]
        val buf = plane.buffer

        File(outputDir, "frame-sdr-%04d.raw".format(index)).outputStream().use { fos ->
            writeBufferTo(buf, fos)
        }

        // Compute a luma proxy histogram from RGBA. R/G/B are 8-bit each; we promote each
        // computed luma to the 10-bit range (multiply by 4) so the histogram bin layout
        // matches HdrProbe's, enabling direct overlay in the offline analysis.
        val (yMin, yMax, yMean, hist) = lumaStats(buf, width, height, plane.rowStride, plane.pixelStride)

        val dataSpaceInt = image.dataSpace
        val hwFormatInt = image.hardwareBuffer?.format ?: -1
        metadataWriter.write(FrameMetadata(
            mode = "sdr",
            index = index,
            captureTsNs = image.timestamp,
            dataspaceInt = dataSpaceInt,
            dataspaceName = ConstantNameResolver.dataSpaceName(dataSpaceInt),
            hardwareBufferFormatInt = hwFormatInt,
            hardwareBufferFormatName = ConstantNameResolver.hardwareBufferFormatName(hwFormatInt),
            yPixelStride = plane.pixelStride,
            yRowStride = plane.rowStride,
            uvPixelStride = 0, uvRowStride = 0,
            yMin = yMin, yMax = yMax, yMean = yMean,
            uvUMean = 0, uvVMean = 0,
            yHistogram32Bin = hist,
        ))
    }

    private fun writeBufferTo(buf: java.nio.ByteBuffer, out: FileOutputStream) {
        val view = buf.duplicate()
        view.rewind()
        val arr = ByteArray(view.remaining())
        view.get(arr)
        out.write(arr)
    }

    /** Returns (min, max, mean, histogram32) of the 10-bit-promoted RGB luma. */
    private fun lumaStats(
        buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int,
    ): IntStats {
        val view = buf.duplicate()
        val hist = IntArray(32)
        var min = Int.MAX_VALUE; var max = Int.MIN_VALUE; var sum = 0L
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                val pos = rowStart + x * pixelStride
                val r = view.get(pos).toInt() and 0xFF
                val g = view.get(pos + 1).toInt() and 0xFF
                val b = view.get(pos + 2).toInt() and 0xFF
                val luma8 = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                val luma10 = luma8 * 4   // 8-bit → 10-bit by left-shift by 2
                if (luma10 < min) min = luma10
                if (luma10 > max) max = luma10
                sum += luma10
                hist[luma10 / 32]++
            }
        }
        val mean = (sum / (w.toLong() * h)).toInt()
        return IntStats(min, max, mean, hist)
    }

    private data class IntStats(val min: Int, val max: Int, val mean: Int, val hist: IntArray)

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :probe:compileDebugKotlin 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/SdrProbe.kt
git commit -m "feat(probe): SdrProbe — ImageReader.RGBA_8888 baseline capture

Same MediaProjection source as HdrProbe, simpler reader configuration
(no DataSpace request, ImageFormat.RGBA_8888). Computes a luma-proxy
histogram in the same 10-bit binning as HdrProbe so the analyze.py
script can overlay the two and tell whether HDR mode produced
materially different values."
```

---

## Task 6: `ProbeOrchestrator` — state machine

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/ProbeOrchestrator.kt`

Drives the full sequence: `Idle → CapturingHdr → CapturingSdr → Done(folder)`. Owns the `MediaProjection` lifetime and the output directory.

- [ ] **Step 1: Implement `ProbeOrchestrator`**

```kotlin
package eu.hyperhdr.android.probe

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level driver: takes a granted [MediaProjection], runs HdrProbe then SdrProbe against
 * it, and surfaces progress via a [StateFlow<State>]. Output goes to
 * `<context.getExternalFilesDir(null)>/probe/<UTC-iso-timestamp>/`.
 */
class ProbeOrchestrator(
    private val context: Context,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val targetFrames: Int = 60,
) {
    sealed interface State {
        data object Idle : State
        data class CapturingHdr(val frames: Int) : State
        data class CapturingSdr(val frames: Int) : State
        data class Done(val outputFolder: File, val hdrFrames: Int, val sdrFrames: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun run(projection: MediaProjection) {
        val outputDir = makeOutputDir()
        val metadataFile = File(outputDir, "metadata.jsonl")
        val density = context.resources.displayMetrics.densityDpi

        FileWriter(metadataFile, true).use { fw ->
            val writer = MetadataWriter(fw)

            _state.value = State.CapturingHdr(0)
            val hdrResult = HdrProbe(projection, width, height, density, outputDir, writer, targetFrames).run()
            val hdrFrames = hdrResult.getOrElse {
                _state.value = State.Failed("HDR capture failed: ${it.message}")
                projection.stop()
                return
            }

            _state.value = State.CapturingSdr(0)
            val sdrResult = SdrProbe(projection, width, height, density, outputDir, writer, targetFrames).run()
            val sdrFrames = sdrResult.getOrElse {
                _state.value = State.Failed("SDR capture failed: ${it.message}")
                projection.stop()
                return
            }

            projection.stop()
            _state.value = State.Done(outputDir, hdrFrames, sdrFrames)
            Log.i(TAG, "Probe complete: hdr=$hdrFrames sdr=$sdrFrames in ${outputDir.absolutePath}")
        }
    }

    private fun makeOutputDir(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir   // fallback if external storage unavailable
        val dir = File(base, "probe/$timestamp").apply { mkdirs() }
        return dir
    }

    companion object { private const val TAG = "ProbeOrchestrator" }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :probe:compileDebugKotlin 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/ProbeOrchestrator.kt
git commit -m "feat(probe): ProbeOrchestrator — state machine + output dir layout

Idle → CapturingHdr → CapturingSdr → Done(folder)/Failed. Owns the
output directory under getExternalFilesDir/probe/<UTC-timestamp>/ and
a single metadata.jsonl Writer that both probes append to. Stops the
MediaProjection at the end so the system foreground-service indicator
clears."
```

---

## Task 7: `MainActivity` — Compose UI

**Files:**
- Create: `probe/src/main/java/eu/hyperhdr/android/probe/MainActivity.kt`

Single Activity. Requests `MediaProjectionManager.createScreenCaptureIntent()`, hands the granted projection to `ProbeOrchestrator`, observes the orchestrator's state, renders a status text + Start / Share buttons.

- [ ] **Step 1: Implement `MainActivity`**

```kotlin
package eu.hyperhdr.android.probe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProbeScreen(context = this) }
    }
}

@Composable
private fun ProbeScreen(context: Context) {
    val orchestrator = remember { ProbeOrchestrator(context) }
    val state by orchestrator.state.collectAsState()
    var pendingProjection by remember { mutableStateOf<MediaProjection?>(null) }

    val mpManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val activity = context as ComponentActivity

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            pendingProjection = projection
        }
    }

    // When projection becomes available, kick off the orchestrator.
    LaunchedEffect(pendingProjection) {
        val projection = pendingProjection ?: return@LaunchedEffect
        pendingProjection = null
        activity.lifecycleScope.launch { orchestrator.run(projection) }
    }

    Surface(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("HDR PQ Probe", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(24.dp))

            val statusText = when (val s = state) {
                ProbeOrchestrator.State.Idle -> "Idle. Press Start to grant screen capture and run probe."
                is ProbeOrchestrator.State.CapturingHdr -> "Capturing HDR (${s.frames}/60)…"
                is ProbeOrchestrator.State.CapturingSdr -> "Capturing SDR (${s.frames}/60)…"
                is ProbeOrchestrator.State.Done ->
                    "Done.\nHDR=${s.hdrFrames} SDR=${s.sdrFrames}\nFolder: ${s.outputFolder.absolutePath}"
                is ProbeOrchestrator.State.Failed -> "Failed: ${s.message}"
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch(mpManager.createScreenCaptureIntent()) },
                enabled = state is ProbeOrchestrator.State.Idle ||
                          state is ProbeOrchestrator.State.Done ||
                          state is ProbeOrchestrator.State.Failed,
            ) {
                Text("Start")
            }

            if (state is ProbeOrchestrator.State.Done) {
                Spacer(Modifier.height(16.dp))
                val done = state as ProbeOrchestrator.State.Done
                Button(onClick = { shareFolder(context, done.outputFolder) }) {
                    Text("Share results")
                }
            }
        }
    }
}

private fun shareFolder(context: Context, folder: java.io.File) {
    // Best-effort share — opens a chooser with the folder URI. On most TV launchers there's
    // no useful share target; the primary intended retrieval path is `adb pull`.
    val uri: Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", folder,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share probe results")) }
}
```

- [ ] **Step 2: Add a FileProvider entry to the manifest for the optional Share action**

Open `probe/src/main/AndroidManifest.xml`. Inside the `<application>` block, before the closing `</application>` tag, add:

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/probe_file_paths" />
        </provider>
```

Create `probe/src/main/res/xml/probe_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="probe-output" path="probe/" />
</paths>
```

- [ ] **Step 3: Verify the module builds an APK end-to-end**

```bash
./gradlew :probe:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add probe/src/main/java/eu/hyperhdr/android/probe/MainActivity.kt \
        probe/src/main/AndroidManifest.xml \
        probe/src/main/res/xml/probe_file_paths.xml
git commit -m "feat(probe): MainActivity Compose UI + FileProvider for share intent

Single Compose Activity surface that observes ProbeOrchestrator.state
and renders status + Start button + Share button (when Done). Share
intent uses FileProvider with authority \${applicationId}.fileprovider
exposing the external-files probe/ subdirectory.

The primary retrieval path remains adb pull; Share is a fallback for
the cases where a TV launcher happens to surface a useful target."
```

---

## Task 8: `analyze.py` — offline classifier (TDD)

**Files:**
- Create: `probe/scripts/analyze.py`
- Create: `probe/scripts/test_analyze.py`

Pure Python 3, requires `numpy` + `matplotlib`. The script reads a probe-run folder, processes every frame, classifies the device behaviour, and writes `report.md` + `report-frames.html`.

- [ ] **Step 1: Write the failing self-test**

```python
# probe/scripts/test_analyze.py
"""Self-test for analyze.py's classifier — synthesises known frame types and asserts
the classifier labels them correctly."""

import json
import os
import sys
import tempfile
import unittest

import numpy as np

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)
from analyze import classify_frame, FrameRecord  # noqa: E402


class ClassifierTest(unittest.TestCase):

    def _record(self, mode, hist, y_min, y_max, y_mean, dataspace_name="DATASPACE_BT2020_PQ"):
        return FrameRecord(
            mode=mode, index=0,
            dataspace_int=0, dataspace_name=dataspace_name,
            y_min=y_min, y_max=y_max, y_mean=y_mean,
            uv_u_mean=512, uv_v_mean=512,
            y_histogram_32bin=list(hist),
        )

    def test_pq_shaped_when_full_range_used_with_bright_highlights(self):
        # Y values broadly distributed with substantial weight in upper bins (>=24)
        hist = np.zeros(32, dtype=int)
        hist[2:30] = 100
        hist[24:30] = 400  # bright HDR highlights
        rec = self._record("hdr", hist, y_min=64, y_max=940, y_mean=480)
        self.assertEqual(classify_frame(rec), "PQ-shaped")

    def test_sdr_stretched_when_compressed_to_low_bins(self):
        # All values bunched in 0..15 (limited-range SDR mapped to 10-bit but with no
        # bright highlights extending past mid)
        hist = np.zeros(32, dtype=int)
        hist[2:8] = 1000
        rec = self._record("hdr", hist, y_min=64, y_max=235, y_mean=120)
        self.assertEqual(classify_frame(rec), "SDR-stretched")

    def test_zero_or_broken_when_almost_all_in_first_bin(self):
        hist = np.zeros(32, dtype=int)
        hist[0] = 999_999
        hist[1] = 1
        rec = self._record("hdr", hist, y_min=0, y_max=1, y_mean=0)
        self.assertEqual(classify_frame(rec), "zero-or-broken")

    def test_dataspace_override_marks_no_go_regardless_of_histogram(self):
        # Even if the histogram looks PQ, if dataspace came back as SRGB the device overrode us.
        hist = np.zeros(32, dtype=int)
        hist[2:30] = 100; hist[24:30] = 400
        rec = self._record("hdr", hist, y_min=64, y_max=940, y_mean=480,
                           dataspace_name="DATASPACE_SRGB")
        self.assertEqual(classify_frame(rec), "dataspace-overridden")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the self-test and verify it fails**

```bash
python3 probe/scripts/test_analyze.py 2>&1 | tail -10
```
Expected: `ImportError` or `ModuleNotFoundError` — `analyze.py` doesn't exist.

- [ ] **Step 3: Implement `analyze.py`**

```python
#!/usr/bin/env python3
"""Offline classifier for the HDR PQ probe dump folder.

Usage:
    python3 analyze.py /path/to/probe-folder

Reads metadata.jsonl + frame-{hdr,sdr}-NNNN.raw and writes:
    - report.md            human-readable summary + Go/Partial/No-Go decision
    - report-frames.html   interactive per-frame histograms
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List

try:
    import numpy as np
except ImportError:
    sys.stderr.write("Need numpy. Run: pip install numpy matplotlib\n"); sys.exit(2)


@dataclass
class FrameRecord:
    mode: str
    index: int
    dataspace_int: int
    dataspace_name: str
    y_min: int
    y_max: int
    y_mean: int
    uv_u_mean: int
    uv_v_mean: int
    y_histogram_32bin: List[int]


def classify_frame(rec: FrameRecord) -> str:
    """Returns one of: PQ-shaped, SDR-stretched, zero-or-broken, dataspace-overridden."""
    if rec.mode == "hdr" and rec.dataspace_name not in ("DATASPACE_BT2020_PQ", "DATASPACE_BT2020_HLG"):
        return "dataspace-overridden"

    hist = np.array(rec.y_histogram_32bin, dtype=int)
    total = int(hist.sum())
    if total <= 0:
        return "zero-or-broken"

    # Almost all in first bin → black / broken
    if hist[0] / total > 0.95:
        return "zero-or-broken"

    # PQ characteristic: values reach into upper bins (>=24, i.e., Y >= 768)
    upper_share = hist[24:].sum() / total
    if upper_share > 0.05 and rec.y_max >= 800:
        return "PQ-shaped"

    return "SDR-stretched"


def load_records(folder: Path) -> List[FrameRecord]:
    metadata_path = folder / "metadata.jsonl"
    records: List[FrameRecord] = []
    with metadata_path.open() as f:
        for line in f:
            line = line.strip()
            if not line: continue
            obj = json.loads(line)
            records.append(FrameRecord(
                mode=obj["mode"], index=obj["index"],
                dataspace_int=obj["dataspace"], dataspace_name=obj["dataspace_name"],
                y_min=obj["y_min"], y_max=obj["y_max"], y_mean=obj["y_mean"],
                uv_u_mean=obj["uv_u_mean"], uv_v_mean=obj["uv_v_mean"],
                y_histogram_32bin=obj["y_histogram_32bin"],
            ))
    return records


def aggregate(records: List[FrameRecord]) -> dict:
    """Returns a dict with overall classifications + the Go/Partial/No-Go verdict."""
    hdr = [r for r in records if r.mode == "hdr"]
    sdr = [r for r in records if r.mode == "sdr"]

    hdr_classes = [classify_frame(r) for r in hdr]
    pq_share = hdr_classes.count("PQ-shaped") / max(1, len(hdr))
    overridden_share = hdr_classes.count("dataspace-overridden") / max(1, len(hdr))
    broken_share = hdr_classes.count("zero-or-broken") / max(1, len(hdr))

    # Compare HDR vs SDR distributions (per-frame Y histograms averaged).
    if hdr and sdr:
        hdr_avg = np.mean([np.array(r.y_histogram_32bin, dtype=float) for r in hdr], axis=0)
        sdr_avg = np.mean([np.array(r.y_histogram_32bin, dtype=float) for r in sdr], axis=0)
        # Normalise both to total=1 then take L1 distance.
        hdr_norm = hdr_avg / max(1.0, hdr_avg.sum())
        sdr_norm = sdr_avg / max(1.0, sdr_avg.sum())
        hdr_sdr_l1 = float(np.abs(hdr_norm - sdr_norm).sum())
    else:
        hdr_sdr_l1 = 0.0

    # Verdict
    if overridden_share > 0.5 or broken_share > 0.5:
        verdict = "NO-GO"
        verdict_reason = (f"{overridden_share:.0%} of HDR frames had wrong dataspace; "
                          f"{broken_share:.0%} were zero/broken")
    elif pq_share >= 0.8 and hdr_sdr_l1 > 0.15:
        verdict = "GO"
        verdict_reason = (f"{pq_share:.0%} of HDR frames are PQ-shaped, and HDR vs SDR "
                          f"histogram L1 distance = {hdr_sdr_l1:.3f} (>0.15)")
    else:
        verdict = "PARTIAL"
        verdict_reason = (f"{pq_share:.0%} PQ-shaped, HDR/SDR L1 = {hdr_sdr_l1:.3f}")

    return {
        "hdr_classes": hdr_classes,
        "pq_share": pq_share,
        "overridden_share": overridden_share,
        "broken_share": broken_share,
        "hdr_sdr_l1": hdr_sdr_l1,
        "verdict": verdict,
        "verdict_reason": verdict_reason,
        "hdr_count": len(hdr),
        "sdr_count": len(sdr),
    }


def write_report(folder: Path, records: List[FrameRecord], summary: dict) -> None:
    md_path = folder / "report.md"
    with md_path.open("w") as f:
        f.write("# HDR PQ Probe — Analysis Report\n\n")
        f.write(f"Folder: `{folder}`\n\n")
        f.write(f"## Verdict: **{summary['verdict']}**\n\n")
        f.write(f"{summary['verdict_reason']}\n\n")
        f.write("## Counts\n")
        f.write(f"- HDR frames captured: {summary['hdr_count']}\n")
        f.write(f"- SDR frames captured: {summary['sdr_count']}\n\n")
        f.write("## Per-frame classification (HDR mode)\n\n")
        f.write("| Index | DataSpace | Y min | Y max | Y mean | Class |\n")
        f.write("|---:|---|---:|---:|---:|---|\n")
        hdr_records = [r for r in records if r.mode == "hdr"]
        for r, cls in zip(hdr_records, summary["hdr_classes"]):
            f.write(f"| {r.index} | {r.dataspace_name} | {r.y_min} | {r.y_max} | {r.y_mean} | {cls} |\n")
        f.write(f"\n## HDR vs SDR comparison\n\n")
        f.write(f"L1 distance between averaged-and-normalised histograms: **{summary['hdr_sdr_l1']:.3f}**\n\n")
        f.write("Interpretation:\n")
        f.write("- L1 ≈ 0   → HDR and SDR look identical → system is silently tone-mapping\n")
        f.write("- L1 > 0.15 → meaningfully different distributions → real PQ delivery\n")
    print(f"Wrote {md_path}")


def write_html(folder: Path, records: List[FrameRecord], summary: dict) -> None:
    """Best-effort interactive histogram dump. If matplotlib isn't available, skip."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.stderr.write("matplotlib not available — skipping HTML output\n")
        return

    img_dir = folder / "report-frames"
    img_dir.mkdir(exist_ok=True)
    html_lines = [
        "<!DOCTYPE html><html><head><meta charset='utf-8'>"
        "<title>HDR PQ Probe Frames</title><style>"
        "body{font-family:sans-serif;background:#111;color:#eee;padding:24px}"
        "img{display:block;margin:8px 0}"
        ".frame{border-bottom:1px solid #333;padding:12px 0}"
        "</style></head><body>",
        f"<h1>Probe frames — verdict: {summary['verdict']}</h1>",
        f"<p>{summary['verdict_reason']}</p>",
    ]
    for r in records:
        hist = np.array(r.y_histogram_32bin, dtype=int)
        fig, ax = plt.subplots(figsize=(6, 2))
        ax.bar(range(32), hist, color="#e40b00" if r.mode == "hdr" else "#888")
        ax.set_title(f"{r.mode}#{r.index} · DS={r.dataspace_name} · Y[{r.y_min}-{r.y_max}], μ={r.y_mean}")
        ax.set_xlim(0, 31)
        path = img_dir / f"{r.mode}-{r.index:04d}.png"
        fig.tight_layout(); fig.savefig(path, facecolor="#222"); plt.close(fig)
        html_lines.append(f"<div class='frame'><img src='report-frames/{path.name}'></div>")
    html_lines.append("</body></html>")
    out = folder / "report-frames.html"
    out.write_text("\n".join(html_lines))
    print(f"Wrote {out}")


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write(f"usage: {sys.argv[0]} <probe-folder>\n"); return 2
    folder = Path(sys.argv[1])
    if not (folder / "metadata.jsonl").exists():
        sys.stderr.write(f"No metadata.jsonl in {folder}\n"); return 2

    records = load_records(folder)
    if not records:
        sys.stderr.write("No frames in metadata.jsonl\n"); return 2

    summary = aggregate(records)
    write_report(folder, records, summary)
    write_html(folder, records, summary)
    print(f"Verdict: {summary['verdict']} — {summary['verdict_reason']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run the self-test, verify it passes**

```bash
python3 probe/scripts/test_analyze.py 2>&1 | tail -10
```
Expected: `OK` with all 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add probe/scripts/analyze.py probe/scripts/test_analyze.py
git commit -m "feat(probe): analyze.py — offline classifier + Go/Partial/No-Go verdict

Reads probe-folder metadata.jsonl + raw planes, classifies each HDR
frame as PQ-shaped / SDR-stretched / zero-or-broken /
dataspace-overridden, and computes an L1 distance between the
HDR-mode and SDR-mode averaged histograms. Verdict is GO when at
least 80% of HDR frames are PQ-shaped AND the HDR/SDR L1 distance
exceeds 0.15; PARTIAL when classification is ambiguous; NO-GO when
the device overrode the dataspace or frames came back broken.

Self-test (test_analyze.py) covers each classification branch with
synthesised histograms — runs as plain unittest, no Android required."
```

---

## Task 9: Build, install, smoke-test on Google TV Streamer

**Files:** none modified (manual deployment + verification).

- [ ] **Step 1: Build the probe APK**

```bash
./gradlew :probe:assembleDebug 2>&1 | tail -3
```
Expected: `BUILD SUCCESSFUL`.

The output APK is at `probe/build/outputs/apk/debug/probe-debug.apk`.

- [ ] **Step 2: Confirm adb connection to the Google TV Streamer**

```bash
adb connect 192.168.50.102:5555
adb -s 192.168.50.102:5555 shell getprop ro.build.version.sdk
```
Expected: `34` (Android 14 / API 34).

If `adb connect` fails: developer options + ADB debugging must be on; user pairs once via the system pairing wizard. The user noted "adb still needs to be set up" earlier; if not done by the time you reach this task, **STOP and ask the user** to pair the device before proceeding.

- [ ] **Step 3: Install the probe APK**

```bash
adb -s 192.168.50.102:5555 install -r probe/build/outputs/apk/debug/probe-debug.apk
```
Expected: `Success`.

- [ ] **Step 4: Smoke-test launch (no probe run yet — just verify the Activity opens)**

```bash
adb -s 192.168.50.102:5555 shell am start -n eu.hyperhdr.android.probe/.MainActivity
sleep 3
adb -s 192.168.50.102:5555 shell pidof eu.hyperhdr.android.probe
adb -s 192.168.50.102:5555 logcat -d -v time -s AndroidRuntime:E HdrProbe:V SdrProbe:V ProbeOrchestrator:V 2>&1 | tail -20
```
Expected:
- `pidof` returns a numeric PID
- `AndroidRuntime:E` logcat empty (no fatal exceptions)
- The probe screen renders "Idle. Press Start to grant screen capture and run probe."

If the Activity crashes on launch, capture the full `AndroidRuntime` stack trace and treat it as a blocker — the probe code has a real bug. Do **not** proceed to Task 10 until launch is clean.

- [ ] **Step 5: No commit needed — this is a deployment verification step.**

---

## Task 10: Run the probe and analyse the dump

**Files:**
- Create: `docs/research/<run-date>-android-pq-probe-results.md`

This is the actual experiment. The user (or whoever runs this plan) needs to be physically near or remoted into the Google TV Streamer to play HDR content during capture.

- [ ] **Step 1: Start playing HDR content on the device**

On the Google TV Streamer 4K:
1. Open YouTube
2. Search "4K HDR 60FPS demo"
3. Play one of the popular demo clips (e.g., LG, Samsung, Sony brand demos — they're high-quality test content)
4. Confirm "HDR" badge appears on the player chrome (Google TV typically shows this)
5. Leave it playing in the background — the probe captures the displayed content via MediaProjection

If YouTube HDR isn't available, fall back to:
- A Netflix/Disney+/Prime HDR title if subscribed
- A local HEVC HDR10 file via VLC

- [ ] **Step 2: Open the probe and run capture**

On the TV, switch to the "HDR PQ Probe" launcher app (it'll appear in the leanback launcher). Press **Start**. The system will prompt for screen-capture permission; grant it.

The probe will:
1. Display "Capturing HDR (N/60)…" while it captures HDR-mode frames
2. Then "Capturing SDR (N/60)…" while it captures the SDR baseline
3. Finally show "Done. HDR=N SDR=N · Folder: /sdcard/Android/data/eu.hyperhdr.android.probe/files/probe/<timestamp>/"

If the probe shows "Failed: …" capture the message and treat it as a partial result — the failure mode itself is data.

- [ ] **Step 3: Pull the dump folder via adb**

```bash
DEVICE=192.168.50.102:5555
RUN_FOLDER=$(adb -s $DEVICE shell ls /sdcard/Android/data/eu.hyperhdr.android.probe/files/probe/ | tail -1 | tr -d '\r')
echo "Pulling probe run: $RUN_FOLDER"
mkdir -p /tmp/probe-run
adb -s $DEVICE pull "/sdcard/Android/data/eu.hyperhdr.android.probe/files/probe/$RUN_FOLDER" /tmp/probe-run/
ls -la /tmp/probe-run/$RUN_FOLDER/ | head -10
```
Expected: a `metadata.jsonl` and ~120 `.raw` files (60 hdr + 60 sdr) in `/tmp/probe-run/<RUN_FOLDER>/`.

- [ ] **Step 4: Run the offline analyser**

```bash
pip3 install --user numpy matplotlib   # one-time, if not already installed
python3 probe/scripts/analyze.py /tmp/probe-run/$RUN_FOLDER
```
Expected output ends with one of:
- `Verdict: GO — 95% of HDR frames are PQ-shaped, …`
- `Verdict: PARTIAL — 50% PQ-shaped, HDR/SDR L1 = 0.05`
- `Verdict: NO-GO — 100% of HDR frames had wrong dataspace; …`

The script also writes `report.md` and `report-frames.html` into the run folder.

- [ ] **Step 5: Capture the verdict + evidence in a research note**

Create `docs/research/<run-date>-android-pq-probe-results.md` (use today's UTC date for `<run-date>`, e.g. `2026-05-04`). Template:

```markdown
# Android HDR PQ Probe Results

**Date:** <run-date>
**Device:** Google TV Streamer 4K (192.168.50.102:5555, Android 14, API 34)
**HDR test content:** YouTube "<clip title>" (or Netflix / VLC source)

## Verdict

**<GO | PARTIAL | NO-GO>** — <verdict_reason from analyze.py>

## Probe configuration

- Capture resolution: 1920×1080
- Frames captured: HDR=<N>, SDR=<N>
- ImageReader.Builder: `setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_P010)`,
  `setDefaultDataSpace(DataSpace.DATASPACE_BT2020_PQ)`,
  `setUsage(USAGE_GPU_SAMPLED_IMAGE | USAGE_CPU_READ_OFTEN)`
- VirtualDisplay flag: `VIRTUAL_DISPLAY_FLAG_PUBLIC`

## Evidence summary

(Paste the per-frame classification table from `report.md`.)

HDR mode dataspace returned by `Image.getDataSpace()`: `<DATASPACE_…>`
HDR mode hardware buffer format: `<YCBCR_P010 | other>`
HDR/SDR histogram L1 distance: `<value>`

## Raw artifacts

- Probe-run folder pulled to: `/tmp/probe-run/<run-folder>/`
- Per-frame interactive histograms: `report-frames.html`
- Sample HDR raw frame: `frame-hdr-0030.raw` (Y plane bytes followed by UV plane bytes,
  native byte order, 1920×1080, P010 layout)

## Decision and next steps

Per the spec at `docs/superpowers/specs/2026-05-03-android-pq-delivery-probe-design.md`:

- **GO** → Open follow-up brainstorm to integrate `ImageReader`-based HDR capture into
  production `EglP010Pipeline`; replace the RGB→YCbCr shader with a passthrough.
- **PARTIAL** → Stop integration work. Document this device behaviour as a vendor
  limitation. Keep the upstream HyperHDR schema contribution alive for hardware-grabber
  clients.
- **NO-GO** → Treat Android-side PQ as out-of-scope. Open a separate change to remove the
  experimental P010 toggle from the production app. Keep the upstream HyperHDR contribution
  for HW-grabber clients only.

Picked: **<GO | PARTIAL | NO-GO>** → next action is **<…>**.
```

Fill in the template with the actual values from the run.

- [ ] **Step 6: Commit the research note**

```bash
git add docs/research/<run-date>-android-pq-probe-results.md
git commit -m "docs(research): Android HDR PQ probe results — <verdict>

Probe ran on Google TV Streamer 4K against <test content>. <N> HDR
frames + <N> SDR frames captured. Verdict: <GO|PARTIAL|NO-GO>.

Detailed evidence in the research note. Next step: <…>."
```

---

## Self-Review

**Spec coverage:**

| Spec section | Implemented in |
|---|---|
| `:probe` Gradle module, applicationId `eu.hyperhdr.android.probe`, minSdk 34 | Task 1 |
| Compose-for-TV UI, single Activity, three-state machine | Tasks 6 + 7 |
| HDR ImageReader (YCBCR_P010 + DATASPACE_BT2020_PQ + USAGE_GPU_SAMPLED_IMAGE\|USAGE_CPU_READ_OFTEN + maxImages 3) | Task 4 |
| SDR ImageReader (RGBA_8888, no DataSpace) | Task 5 |
| 60 frames per mode, sequential HDR → SDR | Task 6 |
| Per-frame raw output `frame-<mode>-NNNN.raw` (Y plane then UV plane) | Tasks 4 + 5 |
| `metadata.jsonl` with mode, index, dataspace_int, dataspace_name, hardware_buffer_format_*, plane strides, y_min/max/mean, uv_u_mean/uv_v_mean, y_histogram_32bin | Task 3 (writer) + Tasks 4, 5 (callers) |
| On-device 32-bin Y histogram + min/max/mean | Task 2 (HistogramComputer) + Task 4 (HDR caller) + Task 5 (SDR luma proxy) |
| ConstantNameResolver for symbolic DataSpace/HardwareBuffer names via reflection | Task 4 |
| Output folder `<external-files>/probe/<UTC-timestamp>/` | Task 6 |
| Capture resolution fixed at 1920×1080 | Task 6 (default `width=1920, height=1080`) |
| Offline `analyze.py` reading `metadata.jsonl` + raw planes | Task 8 |
| Analysis: Y range usage, PQ curve fit, chroma centering, DataSpace check, HDR-vs-SDR comparison | Task 8 (`classify_frame` + `aggregate`) |
| Outputs `report.md` + `report-frames.html` | Task 8 (`write_report`, `write_html`) |
| Self-test for analyze.py | Task 8 (`test_analyze.py`) |
| Go / Partial / No-Go criteria | Task 8 (`aggregate`'s verdict logic), Task 10 (research note documenting outcome) |
| Run on Google TV Streamer 4K (192.168.50.102) | Tasks 9 + 10 |
| Research notes deliverable | Task 10 |

**Placeholder scan:**

- "TBD" / "TODO" / "implement later" — not present in the plan.
- "Add appropriate error handling" — not used; specific error paths are spelled out (try/catch on Builder, timeout Runnable, etc.).
- "Similar to Task N" — SdrProbe duplicates the `cleanup` and `writeBufferTo` helpers from HdrProbe; this is intentional duplication for clarity (the probe is throwaway and shared base classes are over-abstraction for ~2 callers).
- "Write tests for the above" without test code — every test step has a complete test body.
- One reference to `<run-date>` in Task 10's deliverable filename — that's an intentional placeholder for the date when the probe is actually executed; the template explicitly says to use today's UTC date.

**Type consistency:**

- `FrameMetadata` data class signature defined in Task 3, used identically in Tasks 4 + 5.
- `MetadataWriter(out: Writer)` constructor used identically in Tasks 4, 5, 6.
- `HistogramComputer.compute(plane, width, height, rowStride, pixelStride)` signature defined in Task 2, called in Task 4 with same arg order.
- `ProbeOrchestrator.State` sealed interface variants (`Idle`, `CapturingHdr`, `CapturingSdr`, `Done`, `Failed`) defined in Task 6, consumed in Task 7 with full coverage of all 5 cases.
- `HdrProbe(projection, width, height, density, outputDir, metadataWriter, targetFrames, timeoutMs)` matches `SdrProbe` constructor signature for symmetry.
- `analyze.py`'s `FrameRecord` field names (`y_min`, `y_max`, `y_mean`, `uv_u_mean`, `uv_v_mean`, `y_histogram_32bin`, `dataspace_int`, `dataspace_name`) match the JSON keys produced by `MetadataWriter` in Task 3 (`y_min`, `y_max`, `y_mean`, `uv_u_mean`, `uv_v_mean`, `y_histogram_32bin`, `dataspace`, `dataspace_name`). Note: `MetadataWriter` writes `"dataspace"` (no `_int` suffix) — `analyze.py`'s `load_records` reads from `obj["dataspace"]` and stores as `dataspace_int`. Verified consistent across Tasks 3 + 8.
