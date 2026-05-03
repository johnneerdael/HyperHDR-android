# Manual test: P010 (10-bit) end-to-end against the HyperHDR-10bit fork

**Prereqs:**
- v1.1.0 APK installed on a real Android device whose GLES driver supports 16-bit
  unsigned-integer FBO color attachments (`GL_R16UI` / `GL_RG16UI`). Both UGOOS-AM6 (API 28)
  and AM9 PRO (API 34) — the project's reference test devices — pass this probe; most
  other Android TV devices with a core ES 3.0 driver will too. The capability check
  runs at app startup via `Egl16BitCapabilityProbe.supportsR16UiFboStandalone()`; if the
  driver fails the probe the settings toggle stays greyed out and Tier C is unavailable.
- HyperHDR running from the `johnneerdael/HyperHDR-10bit` fork (branch `p010-wire-format`)
  on the LAN. This is the server-side P010 prerequisite from Plan 8.
- The Android device is configured as a HyperHDR client (wizard completed; NV12 streaming
  works first as a regression check).

This is the only Plan 9 test that proves end-to-end behavior. The instrumented
`EglP010PipelineSmokeTest` verifies the GPU pipeline produces P010 bytes; the wire-format
unit test verifies the FlatBuffer encoding; this recipe confirms the bytes actually reach
HyperHDR's LUT pipeline and produce LED output.

## Regression: NV12 still works

1. With HDR_AWARE enabled, HDR_NATIVE off (the Plan 4 default), start capture.
2. Confirm LEDs follow content as in v1.0.0.

If NV12 stops working: bug in the Task 9 tier-dispatch refactor. Roll back.

## Enable Tier C

3. In HyperHDR-android → Settings → HDR group:
   - Confirm "HDR-aware capture" is on.
   - Confirm "10-bit HDR transport (experimental)" toggle is **enabled** (not greyed out).
     If it's greyed out on a device that should support it, run `adb logcat | grep HyperHdr.Probe`
     and look for the `GL_R16UI/GL_RG16UI FBO support: NO` line — that's the driver verdict.
   - Toggle it on.
4. Stop and restart capture (the service reads the new tier on `startCapture`, not live).

## Verify P010 reaches HyperHDR

5. In HyperHDR's logs (e.g. `journalctl -fu hyperhdr` on Linux, or Console.app on macOS),
   look for:

```
[FlatBufferServer] Debug: Received first P010 frame. First plane size: <N> (stride: <S>). Second plane size: <M> (stride: <S>). Image size: <T> (<W> x <H>)
```

Where the numeric values match: `width` and `height` are the configured capture
resolution (160×90 standard, 192×108 high), `stride` is `width * 2`, total size is
`width * height * 3`.

6. Open colourful HDR content on the Android device (e.g. a Dolby Vision YouTube video).
   LEDs should follow with HyperHDR's LUT-based tone-map applied (which may look subtly
   different from the v1.0.0 Hable curve depending on your LUT calibration).

## Pass criteria

- HyperHDR logs the "Received first P010 frame" debug line on Tier C startup.
- HyperHDR does NOT log "P010 image data size … does not match" — that means the
  Android-side stride/size math disagrees with the server-side validator from Plan 8.
- HyperHDR does NOT log "Unsupported flatbuffers image format" — that means the Android
  client is sending P010 to a server that doesn't have the Plan 8 patches. Verify the
  server is running the fork.
- LEDs follow content. Visible difference from Tier B (Hable on device) depends on your
  LUT calibration.

## Fail modes

- **Toggle stays disabled** despite HDR-aware on: capability probe returned false. Check
  `adb logcat | grep HyperHdr.Probe` for the reason. Common: the driver lacks core ES 3.0
  integer-format renderable attachments (rare). Tier C is unavailable here; nothing to fix
  on the app side.
- **"Unsupported flatbuffers image format"**: HyperHDR is on stock master, not the fork.
  Check the HyperHDR build banner / version output for the P010 changes.
- **"P010 image data size … does not match"**: Android-side stride math is off. The Task 8
  pipeline computes `strideY = width * 2`. If HyperHDR's server-side check uses a different
  formula (e.g. a per-row alignment), one side is wrong; reconcile against Plan 8 task 6's
  stride check (`width * 2`).
- **LEDs go black**: HyperHDR's LUT pipeline may not have a calibrated LUT for P010 input.
  Run HyperHDR's calibration wizard with a P010 source.
- **Frame rate drops on Tier C compared to Tier B**: 16-bit `glReadPixels` is 2× the data
  per frame. On a slow device this can be a real bottleneck. Drop to STANDARD quality
  (160×90) if HIGH (192×108) is too heavy.
- **LED colour looks wrong (washed / hue-shifted) but the frame rate is fine**: the
  Task 8 shader does BT.601 limited-range RGB → YCbCr conversion as a structurally-correct
  passthrough. The Android source frames are nominally PQ HDR (BT.2020 colour primaries),
  not BT.601 SDR. HyperHDR's LUT pipeline expects PQ-encoded input on its P010 path. If
  the LUT was calibrated against true PQ content, the BT.601-converted bytes from this
  client will look "off". A future v1.2.x can add a `MediaProjection`-side HDR colorspace
  flag to deliver real PQ to the OES sampler; for v1.1.0 this is documented as a known
  limitation of the experimental path.
