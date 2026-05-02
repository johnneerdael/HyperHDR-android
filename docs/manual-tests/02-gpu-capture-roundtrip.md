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
- "Connection refused": same as Plan 1 manual test (wrong port or HyperHDR's flatbuffer server isn't enabled).

**Cleanup:** before merging Plan 3, **delete `DebugCaptureActivity` and `tv/src/debug/AndroidManifest.xml`** — the production service replaces them.
