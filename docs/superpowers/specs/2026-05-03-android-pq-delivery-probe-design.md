# Android HDR PQ Delivery Feasibility Probe — Design

## Status

Design approved 2026-05-03. Awaiting implementation plan via `superpowers:writing-plans`.

## Problem

The HyperHDR-android v1.2.x client ships an experimental opt-in P010 transport mode. The bytes on the wire have the correct Microsoft P010 byte layout (16-bit little-endian, 10 high bits + 6 zero pad), but the *values* are SDR-derived: the fragment shader does `RGB → BT.601 limited-range YCbCr → 10-bit` from whatever the OES sampler returns. The OES sampler in turn receives whatever Android's `MediaProjection` decides to deliver to the `SurfaceTexture` — and in practice that's an SDR-tone-mapped frame regardless of source HDR-ness.

The upstream HyperHDR P010 LUT pipeline expects PQ-encoded values (BT.2020 primaries, ST.2084 transfer). Sending SDR-derived bytes through the P010 byte layout produces wrong colours after the LUT. So our P010 transport is structurally correct on the wire but semantically wrong upstream of the wire.

## Goal

**Decide whether Android's MediaProjection on a Google TV Streamer 4K (Android 14, API 34) can deliver real PQ-encoded HDR frames into an `ImageReader` configured for `HardwareBuffer.YCBCR_P010` + `DataSpace.DATASPACE_BT2020_PQ`.**

This is a feasibility decision, not an integration. The probe runs once on real hardware, captures a representative dump, and produces a Go / Partial / No-Go outcome that drives the next decision (integrate, document gap, or remove the toggle).

## Non-goals

- **Not** integrating an HDR capture path into the production app.
- **Not** validating other devices, other Android versions, or other HDR types (Dolby Vision, HDR10+).
- **Not** designing the production HDR pipeline. That gets its own brainstorm + spec if the probe says GO.
- **Not** synthesising HDR test content. The probe captures whatever HDR content the user plays (YouTube HDR demo, Netflix HDR, local HEVC).

## Architecture

A new Gradle module `:probe` in the `HyperHDR-android` repo. Standalone APK with its own application ID `eu.hyperhdr.android.probe` so it installs alongside the production app and can be uninstalled trivially. **Min SDK 34** — the probe explicitly only targets devices that have the `ImageReader.Builder().setDataSpace()` API. Compose-for-TV UI, single Activity, Kotlin only, ~700 LOC including offline analysis script.

Module owns:
- One Activity (capture flow + status display + share-results button)
- One probe coroutine (capture loop)
- One offline Python analysis script (`analyze.py`)

No dependency on the production `:common` or `:tv` modules. Throwaway by design — even if it stays in the repo, it's quarantined.

## Probe activity

Single screen with three states:

```
Idle  →  [Start]  →  Capturing (HDR mode)  →  Capturing (SDR mode)  →  Done
```

User presses **Start**, the system MediaProjection permission prompt appears, user grants. The probe then:

1. **HDR-mode capture** — runs for `N=60` frames. Configured via `ImageReader.Builder(W, H)`:
   - `setHardwareBufferFormat(HardwareBuffer.YCBCR_P010)`
   - `setDataSpace(DataSpace.DATASPACE_BT2020_PQ)`
   - `setUsage(USAGE_GPU_SAMPLED_IMAGE or USAGE_CPU_READ_OFTEN)`
   - `setMaxImages(3)`
2. **SDR-mode capture** — runs for `N=60` frames immediately after, against the same content. Configured via the simpler `ImageReader.newInstance(W, H, ImageFormat.RGBA_8888, 3)`. No dataspace request. This gives us a baseline for comparison.
3. **Done** — UI shows the output folder path + a Share button (system intent). User can pull files via adb (when set up) or share to cloud storage.

Capture resolution: `1920×1080` fixed. Source resolution doesn't matter for the question we're asking; this is a stable size that's easy to reason about.

Both captures run against whatever HDR content the user is playing. The probe doesn't drive playback — that's manual.

## Per-frame data captured

For each frame, the probe writes:

1. **Raw bytes** to `frame-<mode>-NNNN.raw`:
   - HDR mode: Y plane (W×H×2 bytes) followed by UV plane (W×H bytes), native byte order
   - SDR mode: RGBA_8888 buffer (W×H×4 bytes)
2. **Metadata line** appended to `metadata.jsonl`:
   ```json
   {"mode":"hdr","index":0,"capture_ts_ns":1234567890,
    "dataspace":<int from Image.getDataSpace()>,
    "dataspace_name":"DATASPACE_BT2020_PQ",
    "hardware_buffer_format":<int from HardwareBuffer.getFormat()>,
    "hardware_buffer_format_name":"YCBCR_P010",
    "y_pixel_stride":2,"y_row_stride":7680,
    "uv_pixel_stride":4,"uv_row_stride":7680,
    "y_min":196,"y_max":904,"y_mean":428,
    "uv_u_mean":510,"uv_v_mean":516,
    "y_histogram_32bin":[0,0,12,89,...]}
   ```

   The probe records both the integer value (for diffing against the system constants table) AND the symbolic name resolved via reflection on `android.hardware.DataSpace` and `android.hardware.HardwareBuffer` — that way the dump is self-describing even if Android's encoding of the constant changes between API levels.

The on-device histogram + range stats let the user see the answer immediately on screen (e.g., "Y range 196–904 looks PQ-shaped" vs. "Y range 64–235 looks SDR-mapped"). The raw dump lets the analysis script recompute anything.

Output folder path: `/sdcard/Android/data/eu.hyperhdr.android.probe/files/probe/<ISO-timestamp>/`.

## Offline analysis (`analyze.py`)

Ships in the `:probe` module. Pure-Python, only needs `numpy` + `matplotlib`. Run as:

```bash
python3 analyze.py /path/to/pulled-dump-folder
```

For each captured frame, the script:

1. Reads `metadata.jsonl` line.
2. Reads raw `.p010` bytes; unpacks 10-bit values via `(uint16_word >> 6) & 0x3FF`.
3. Computes:
   - **Y range usage** — does the Y plane use the full 64–940 limited-range 10-bit space (PQ-typical) or just 64–235 (SDR-mapped to 10-bit)?
   - **PQ curve fit** — does brightness cluster mid-range with bright highlights extending into 800-940 (PQ characteristic) or distribute linearly (SDR-stretched)?
   - **Chroma centering** — `mean(U)` and `mean(V)` near 512 ± 50 (centered 10-bit)?
   - **DataSpace check** — does `Image.getDataSpace()` (recorded in metadata, with both integer value and resolved symbolic name) actually return `DATASPACE_BT2020_PQ` as the probe requested, or did the system override (e.g. to `DATASPACE_BT709`, `DATASPACE_SRGB`, `DATASPACE_UNKNOWN`)?
   - **HDR-vs-SDR comparison** — overlay HDR-mode histograms against SDR-mode histograms.

Outputs to the dump folder:

- `report.md` — human-readable summary with classification + recommendation.
- `report-frames.html` — interactive histograms per frame for visual inspection.

## Test content

User plays HDR content during the probe run. Recommended on Google TV Streamer 4K:

1. **YouTube HDR demo clips** — search "4K HDR 60FPS demo", play full-screen. Easiest, no auth.
2. **Netflix / Disney+ / Prime HDR titles** — if subscribed.
3. **Local HEVC HDR10 file via VLC** — most controlled.
4. **System HDR test pattern** — if Google TV Streamer surfaces one in Settings → Display.

The probe doesn't synthesise test patterns. The point is to capture what MediaProjection actually delivers from real HDR sources.

## Go / No-Go criteria

After running the probe and the analysis script, exactly one of three rows applies:

| Outcome | Criteria | Action |
|---|---|---|
| **🟢 GO** | `Image.getDataSpace() == DATASPACE_BT2020_PQ` AND HDR-mode Y histograms differ measurably from SDR-mode histograms (KS test or visual diff) AND Y range extends past ~800 with bright HDR content | Open a follow-up brainstorm + spec to integrate `ImageReader`-based HDR capture into the production `EglP010Pipeline`. Replace the RGB→YCbCr shader with a passthrough that delivers real PQ to the FlatBuffer wire. |
| **🟡 PARTIAL** | DataSpace returned matches request but histograms are similar to SDR (system silently tone-maps) | Stop the integration project. Document as a known-broken vendor pipeline. Keep upstream HyperHDR schema contribution alive for HW-grabber clients. Leave the v1.2.x toggle as-is with the existing limitation note in the manual recipe. |
| **🔴 NO-GO** | `Image.getDataSpace() != DATASPACE_BT2020_PQ`, OR frames are zero/black, OR HDR mode crashes | Treat Android-side PQ as out-of-scope. Open a separate issue/PR to remove the experimental P010 toggle from the production app. Keep upstream HyperHDR contribution alive for HW-grabber clients only. |

The probe + analysis output decides which row applies. We do not have to commit to any row in advance.

## Deliverables

1. **`:probe` Gradle module** in the `HyperHDR-android` repo.
2. **`analyze.py`** offline analysis script in the same module's directory.
3. **`docs/research/<run-date>-android-pq-probe-results.md`** — research-notes markdown (filename uses the actual probe-run date) recording the actual measurements from the Google TV Streamer 4K, the histograms, the DataSpace value seen, and the chosen Go / Partial / No-Go outcome with evidence.

The integration phase (conditional on Go) is **out of scope** for this spec. It gets its own brainstorm + spec + plan cycle.

## Risks

| Risk | Mitigation |
|---|---|
| MediaProjection silently tone-maps to SDR despite our DataSpace request — the probe returns PARTIAL or NO-GO | Explicitly designed for: that's exactly what the probe answers. SDR baseline capture lets us tell the difference unambiguously. |
| Google TV Streamer's vendor implementation differs from stock Android 14 | Acceptable — the probe answers "does it work on this device", which is what the user actually wants to know. We don't claim universality. |
| `ImageReader.Builder().setDataSpace()` throws on the device despite being API 34 | The probe wraps the configuration in try/catch and reports the exception in metadata. If thrown, NO-GO without further work. |
| Probe captures HDR mode but never produces frames (Surface configured but no Image arrives) | The probe times out HDR capture at 10s; if no frames arrive it transitions to SDR mode and reports the timeout. NO-GO outcome. |
| `Image.getDataSpace()` API 34 returns 0 or unknown even for HDR-configured sources | Recorded in metadata; the analysis script reports it. Forces NO-GO classification. |

## Testing

The probe is itself the test. There are no unit tests for the probe — its purpose is exploratory measurement. The analysis script has a tiny self-test: synthesise a known PQ-encoded frame in numpy, run it through the classifier, assert "PQ-shaped" comes out. ~30 lines.

## Out-of-scope (explicitly)

- Integration into the production HyperHDR-android app
- The shader rewrite to remove the BT.601 conversion
- Settings UI changes for HDR-Native capture mode
- Other devices besides the Google TV Streamer 4K
- Other Android versions besides Android 14 (API 34)
- HDR types other than HDR10 PQ (no Dolby Vision, no HDR10+)
- Production-grade error handling, logging integration with the existing app, telemetry

All of these become candidates if the probe says GO. None are candidates of this spec.
