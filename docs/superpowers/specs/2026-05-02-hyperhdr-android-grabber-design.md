# HyperHDR-Android Grabber — Design

**Date:** 2026-05-02
**Repo (target):** `github.com/johnneerdael/HyperHDR-android`
**Upstream of fork:** `github.com/haumlab/hyperion-android-reborn` (hard fork; no merges back)
**Status:** Approved design, ready for implementation planning

---

## 1. Goal

A dedicated Android TV screen-capture client for **HyperHDR**, derived from `hyperion-android-reborn` but reshaped around HyperHDR's preferred wire format (flatbuffer NV12) and feature surface (mDNS discovery, JSON-API control, HDR signaling). The user-facing promise: **smoothest, lowest-latency Android grabber for HyperHDR**, including HDR-aware capture on Android 13+.

LED chain: **Android (this app) → HyperHDR → Hyperk (or any HyperHDR-supported LED controller) → LEDs.** This app's job ends at HyperHDR.

## 2. Locked decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Fork relationship | Hard fork. New repo, new package, no upstream merges. |
| Capture pipeline | GLES shader: downscale + RGBA→NV12 pack into ImageReader. |
| HDR scope | **Tier B** — HDR-aware capture on Android 13+, Android-side PQ→SDR tone-map, 8-bit NV12 transport. (Tier C / 10-bit transport deferred until HyperHDR upstream extends the schema.) |
| Min API | **minSdk 28 (Android 9)**. HDR-aware tier gated to A13+ + capable display + user opt-in. |
| HyperHDR-unique features in v1 | mDNS discovery (always); JSON-API control (a); multi-instance picker (b); auth tokens (c); HDR-state signal (f). Excluded: audio capture, LUT upload. |
| Form factors | **TV-only.** Phone/tablet target dropped. |
| Performance target | Default **30 fps @ 160×90**; opt-in **60 fps @ 192×108**. End-to-end latency goal **< 80 ms**. |
| Approach | **Approach 1 — Rebrand and replace.** Preserve upstream's solid lifecycle/foreground-service skeleton; surgically replace capture, network, discovery, UI. |

## 3. Architecture & module layout

```
HyperHDR-android/
├── common/                    # protocol, capture, discovery — pure Android library
│   └── src/main/
│       ├── flatbuffers/       # hyperhdr_request.fbs, hyperhdr_reply.fbs (copied from HyperHDR)
│       └── java/eu/hyperhdr/android/
│           ├── capture/       # HyperHdrGpuEncoder, EglNv12Pipeline, HdrToneMapShader
│           ├── flatbuf/       # HyperHdrFlatBufferClient (regenerated hyperhdrnet.*)
│           ├── json/          # HyperHdrJsonApiClient (control channel)
│           ├── discovery/     # HyperHdrMdnsDiscovery
│           └── service/       # HyperHdrCaptureService, lifecycle glue
└── tv/                        # the only app module (replaces upstream app/)
    └── src/main/
        └── java/eu/hyperhdr/android/tv/
            ├── ui/            # leanback fragments / activities
            └── ...
```

- `app/` (upstream's phone variant) is deleted.
- Two modules instead of one so `common/` can be JVM/test-harness-driven without `androidx.leanback`.
- `minSdk 28`, `targetSdk 36`, AGP 8.x, Kotlin 2.x. New code in Kotlin; existing Java (boot receiver, tile service) kept as Java with package renames only.
- **Dependencies kept:** AndroidX Leanback, FlatBuffers Java runtime, OkHttp, Konfetti.
- **Dependencies removed:** Protobuf-javalite, all phone-only support libs, WLED/DDP code path.
- **Initial commit** imports upstream verbatim (NOTICE/LICENSE preserved); a single follow-up "fork: rebrand to HyperHDR" commit lands all the renames so the historical pivot is one readable diff.
- **Generated FlatBuffers:** regenerated from HyperHDR's `hyperhdr_request.fbs` / `hyperhdr_reply.fbs` to produce `hyperhdrnet.*` Java classes. Wire format is byte-identical to the old `hyperionnet.*` — pure rebranding at the code level.

## 4. Capture pipeline

```
[MediaProjection]
    │  (creates a VirtualDisplay backed by an EGL Surface, NOT an ImageReader)
    ▼
[EGL Surface]    ← GLES2/3 context owned by EglNv12Pipeline
    │
    ▼
[Pass 1 — Downscale shader]
   • Samples the source as a GLES external/2D texture
   • Bilinear/box downscale to capture target (e.g. 160×90)
   • On HDR-aware tier: simultaneously applies PQ→linear→BT.709 SDR tone-map
     using a Hable/Reinhard curve in the fragment shader (gated by setting +
     Display.HdrCapabilities.getSupportedHdrTypes())
   • Output: small RGBA texture in linear or sRGB
    │
    ▼
[Pass 2 — RGBA→NV12 packing shader]
   • Two FBO attachments: a Y plane (R8) and an interleaved UV plane (R8G8 at half res)
   • Single fragment shader writes both planes from the same RGBA input via MRT
     (or two passes on GLES2 devices that lack MRT)
    │
    ▼
[ImageReader (YUV_420_888, NV12-laid-out)]   ← HardwareBuffer-backed (minSdk 28 ⇒ always available)
    │  acquireLatestImage() — drops stale frames automatically
    ▼
[FlatBufferClient.sendNv12(yPlane, uvPlane, w, h, strides)]
```

**Two render tiers, one code path:**

| Tier | Trigger | Shader pass | What changes |
|---|---|---|---|
| **SDR** (default, all devices) | Always available | Plain bilinear downscale + RGBA→NV12 pack | Source sampled as standard sRGB |
| **HDR-aware** (Android 13+, capable display, user opt-in) | `Display.isHdr()` && setting on | Adds PQ→linear EOTF + tone-map curve + BT.2020→BT.709 matrix before pack | VirtualDisplayConfig requests HDR colorspace; source sampled as PQ |

Tier is selected at capture-start time; switching requires a service restart of the capture pipeline (cheap — sub-second).

**Frame pacing & latency control:**

- `Choreographer.postFrameCallback` drives scheduling, not fixed `postDelayed` (eliminates ±frame jitter from `1000/fps` arithmetic). Target fps runs on the nearest VSYNC divisor (60 → 30 fps = every 2nd frame).
- `acquireLatestImage()` (not `acquireNextImage()`) — drop stale frames on backpressure.
- Single-buffered NV12 byte arrays reused across frames (no per-frame allocation).
- Direct `ByteBuffer` view onto the HardwareBuffer where possible (Android 26+) to skip a copy before the FlatBuffer send.

**Defaults & limits:**

- Default capture: **160×90 @ 30 fps**.
- Opt-in "high mode": **192×108 @ 60 fps**.
- Resolution choices exposed via TV settings; capped to even dimensions (NV12 chroma).

**Removed from existing encoder:** `BorderProcessor` (HyperHDR does borders server-side), `mAvgColor` shortcut (HyperHDR does this server-side), `mRemoveBorders`. **Kept:** `VirtualDisplay.Callback` lifecycle, orientation handling (adapted to swap GLES viewport).

## 5. Network layer

Three logically distinct connections to HyperHDR; one responsibility each.

```
┌────────────────────── Android device ──────────────────────┐
│                                                            │
│  HyperHdrFlatBufferClient ────────────────► HyperHDR :19400│  pixels (NV12)
│  (TCP, fire-and-forget per frame)                          │
│                                                            │
│  HyperHdrJsonApiClient ────────────────────► HyperHDR :19444│  control + events
│  (HTTP for one-shots, WebSocket for live)                  │
│                                                            │
│  HyperHdrMdnsDiscovery (NsdManager) ──────► local network  │  service browse
│  Browses _hyperhdr-flatbuf._tcp + _hyperhdr-json._tcp      │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### 5.1 Flatbuffer client (data plane)

Replaces upstream `HyperionFlatBuffers.kt`. Same fire-and-forget TCP design with these changes:

- Protocol classes regenerated from `hyperhdr_request.fbs` / `hyperhdr_reply.fbs`. Wire format byte-identical to existing — no server-side change needed.
- **NV12 path is the only path.** `setImage()` builds an `NV12Image` with separate `data_y` / `data_uv` byte arrays + strides reported by `ImageReader`. Legacy `RawImage` path removed.
- **Single writer thread.** A dedicated `HandlerThread` owns the socket; the capture pipeline posts frames into a single-slot mailbox (latest wins). Frames are dropped, not queued, on backpressure.
- **Reconnection.** On `IOException`: close socket, exponential backoff (250 ms → 4 s capped), notify JSON client + UI.
- **Origin string** = `"HyperHDR-Android"` so the source is identified correctly in HyperHDR's Components panel.

### 5.2 JSON-API client (control plane)

New class. Two transport modes against the same server.

- **HTTP request/response** (OkHttp) for one-shot calls. **The only transport required for v1.**
- **WebSocket** for live state when settings UI is open. **Deferred to a later release.** Listed in §10. Public API of `HyperHdrJsonApiClient` is shaped now to admit it later without a breaking change (results returned as `Flow`s, not single suspends).

**Calls used in v1:**

| Purpose | API | When |
|---|---|---|
| List server instances | `serverinfo` | At connect time, populates instance picker |
| Authenticate | `authorize-login` (token) | First connect, if server requires auth |
| Switch active instance | `instance` `switchTo` | When user picks a different instance |
| Signal HDR state | `videomode` (HDR=on/off) | When capture-side detects HDR/SDR transition |
| Component status | `componentstate` (COMP_HDR) | Optional — surfaces server-side HDR-tonemap toggle |

**Auth model:** JSON client owns the token. Acquired via `authorize-login`, stored in `EncryptedSharedPreferences`, included as a JSON field on every subsequent JSON call. **Flatbuffer port does not require auth** (HyperHDR auth is JSON-API-only) — data plane is never blocked on token state.

**Decoupling:** flatbuffer client knows nothing about JSON client. JSON client publishes events (instance changed, auth expired) on a Kotlin `Flow` consumed by the service layer. JSON unreachable → frames still flow; auth/HDR-signal/instance-switching unavailable, surfaced as a non-blocking warning.

### 5.3 mDNS discovery

New class wrapping `NsdManager`. Browses two service types in parallel:

- `_hyperhdr-flatbuf._tcp` (data)
- `_hyperhdr-json._tcp` (control)

Pairs them by hostname/IP — emits a list of "discovered HyperHDR servers." Replaces the upstream Hyperion-port scanner. **Fallback:** manual host/port entry remains available for VPN'd / locked-down networks.

### 5.4 Removed

`WledDdpClient`, `NetworkScanner`, `HyperionScannerTask`.

## 6. TV UI & settings flow

Single Leanback app. Four user-visible surfaces.

### 6.1 First-run / setup wizard (`GuidedStepFragment`)

```
Step 1  ─ "Looking for HyperHDR servers…"  (mDNS browse, ~3s, live results)
Step 2  ─ "Pick a server"                  (discovered list + "Enter manually…")
Step 3  ─ (only if auth required)          (token paste UI)
Step 4  ─ "Pick the instance to drive"     (populated from `serverinfo`)
Step 5  ─ Done → Main screen
```

Reachable any time via "Reconfigure server."

### 6.2 Main screen

D-pad-friendly, three regions:

- **Connection card** — server name, instance, status badge (green=streaming / amber=connected,paused / red=error). Pressing opens the wizard.
- **Big toggle button** — start/stop capture.
- **Live stats footer** — fps, bytes/sec, current resolution.

### 6.3 Settings screen (`LeanbackPreferenceFragment`)

| Group | Settings |
|---|---|
| **Capture** | Quality preset: **Standard** (160×90 @ 30 fps) or **High** (192×108 @ 60 fps); priority (default 100) |
| **HDR** | "HDR-aware capture" (gated to A13+, greyed out otherwise); "Signal HDR to HyperHDR" (default on) |
| **Connection** | Reconfigure server… ; Manual host/port; Auth token (re-paste); Reconnect-on-failure timeout |
| **Behavior** | Start on boot; Foreground notification style; Quick Settings tile |

### 6.4 Quick Settings tile + boot receiver

Both kept (renamed): `HyperHdrTileService`, `HyperHdrBootReceiver`.

### 6.5 Removed

Phone `MainActivity` and `app/src/main/res/layout/activity_*.xml`; Hyperion-port scanner UI (replaced by mDNS); update checker URL repointed at `github.com/johnneerdael/HyperHDR-android` releases.

## 7. Lifecycle & foreground service

### 7.1 Service shape

```
HyperHdrCaptureService  : Service
   ├── foregroundServiceType = mediaProjection | dataSync
   ├── owns: MediaProjection, EglNv12Pipeline, FlatBufferClient, JsonApiClient
   ├── exposes: a Binder for the TV UI to start/stop/observe state
   └── persists: a single sticky notification with toggle + reconfigure actions
```

`mediaProjection` is required for screen capture on A12+; `dataSync` is declared as a fallback for the rare device that rejects projection type while idle. Active capture always runs under `mediaProjection`.

### 7.2 State machine

```
   ┌─────────────┐ user toggle on    ┌──────────────┐
   │   IDLE      │ ────────────────► │  CONNECTING  │
   └─────────────┘                   └──────┬───────┘
       ▲                                    │ flatbuf TCP up + register acked
       │ user toggle off                    ▼
       │                              ┌─────────────┐
       │                              │  STREAMING  │
       │                              └──────┬──────┘
       │                                     │ projection paused / display off
       │                                     ▼
       │                              ┌─────────────┐
       │ projection revoked /         │   PAUSED    │
       │ user toggle off              └──────┬──────┘
       │                                     │ resume callback
       │                                     ▼
       │                              ┌─────────────┐
       └────────────────────────────  │   ERROR     │ ◄── socket / auth / projection
                                      └─────────────┘
```

- `CONNECTING` → exponential backoff loop on flatbuffer socket; UI shows amber.
- `PAUSED` ≠ stopped: capture suspended (display off / app backgrounded), socket stays open, foreground service alive. Resume is sub-second.
- `ERROR` is sticky until user acks or underlying cause clears.

### 7.3 EGL & MediaProjection ownership

Both **process-globally singleton** — held by service for its full lifetime, not recreated per session.

- `MediaProjection` token persisted across app restarts where the platform allows (A14+: token reuse; A12–13: dialog every time, no platform workaround).
- EGL context created once on service start, torn down on service stop. The `VirtualDisplay` input surface is recreated per capture session.

### 7.4 Display / orientation

`DisplayManager.DisplayListener` watches HDR-mode changes (A13+) and resolution changes:

- HDR transition with HDR-aware tier active → re-issue `videomode` JSON call.
- Resolution change → swap GLES viewport (no pipeline restart).

### 7.5 Vendor watchdog mitigations

- Sticky notification with explicit user-readable text + stop action.
- Foreground-service start within 5 s of `startService()` (already correct upstream).
- First-run guidance card detects manufacturer (TCL / Hisense / Xiaomi) and surfaces direct-action buttons to OS auto-start / battery-optimization-exemption settings.
- Capture and network threads run at `THREAD_PRIORITY_DISPLAY`.

### 7.6 Boot start

`HyperHdrBootReceiver` starts the service on `BOOT_COMPLETED` if "Start on boot" is enabled — but **does not** auto-start capture (MediaProjection consent cannot be automated). Notification shows "Idle — tap to capture." JSON-API reconnects in background. Documented in settings.

## 8. Error handling, reconnection, observability

### 8.1 Principles

1. Never silently swallow.
2. Frames are disposable; control is not.
3. Trust the platform at boundaries; validate at seams between subsystems. No defensive null-checks within `common/`; explicit checks at service ↔ network and capture ↔ network seams.

### 8.2 Error catalogue

| Source | Error | Recovery | User-visible? |
|---|---|---|---|
| MediaProjection | Permission denied | Stop service, prompt re-grant on next user action | Yes — toast + state→IDLE |
| MediaProjection | Projection revoked mid-session | Stop capture, attempt re-grant via UI | Yes — sticky banner |
| EGL | Context lost (display switch, GPU reset) | Recreate context once; if it fails twice, fall back to CPU RGB path with warning | Yes if fallback engaged |
| ImageReader | `acquireLatestImage` returns null repeatedly (>5 frames) | Recreate ImageReader; if persists, restart capture | Diagnostic only |
| Flatbuffer socket | Connect timeout | Exponential backoff 250 ms → 4 s capped, indefinite | Status badge → amber |
| Flatbuffer socket | Write IOException mid-frame | Drop frame, close socket, reconnect | Diagnostic only |
| JSON API | HTTP 401 (auth) | Mark token invalid, prompt re-paste | Yes — banner with "Reconfigure" |
| JSON API | HTTP 5xx / unreachable | Backoff, retry; data plane unaffected | Diagnostic only unless persistent (>30 s) |
| JSON API | `instance` switchTo fails | Surface error, revert UI selector | Yes — toast |
| mDNS | Resolution failure | Fall back to manual entry | Implicit |
| Server | `Reply` with error string | Log; if register failure, treat as connect failure | Yes — banner if blocking |

**CPU RGB fallback** (~100 lines, NV12 packed in CPU): degraded mode for devices with broken EGL. HDR-aware capture and high-fps modes are unavailable in this mode; user sees a warning.

### 8.3 Reconnection policy

One policy, identical for flatbuffer and JSON, parameterized only by timeout cap:

- **Backoff schedule:** 250 ms, 500 ms, 1 s, 2 s, 4 s, then 4 s indefinitely.
- **Reset on success:** first successful frame send / API call resets to 250 ms.
- **No jitter** in v1.
- **Suspend on background:** when service is `PAUSED` (display off), reconnection attempts pause.

### 8.4 Observability

- **In-app live stats footer** — fps actual vs target, bytes/sec, resolution, last error timestamp. Always visible.
- **Logcat** — structured tags (`HyperHdr.Capture`, `HyperHdr.FlatBuf`, `HyperHdr.JsonApi`, `HyperHdr.Service`); one log line per state transition; per-frame logs gated behind `DEBUG` flag.
- **Optional debug-bundle export** — settings → "Export diagnostic bundle" → zip with last N kB of logcat, current settings (token redacted), discovered server list, and recent error catalogue events.

### 8.5 Out

No telemetry, no crash reporter, no remote config. Privacy-sensitive app; local-only diagnostics.

## 9. Testing strategy

### 9.1 JVM unit tests (`common/src/test/`)

- FlatBuffer encode/decode round-trip.
- Reconnection backoff schedule (pure logic, fake clock).
- mDNS service-pair joining (synthetic `NsdServiceInfo` event stream).
- JSON-API client request/response shapes (OkHttp `MockWebServer`).

### 9.2 Instrumented tests (`common/src/androidTest/`)

- EGL pipeline smoke test — push a known RGBA frame through downscale + NV12-pack; verify output bytes against CPU reference. Requires emulator with GLES 3.0.
- ImageReader → flatbuffer end-to-end against an in-test fake TCP server.

### 9.3 Manual / device test checklist (in repo)

- Real HyperHDR server reachability on real Android TV.
- Latency measurement procedure — flash white, measure cam-to-LED time.
- HDR-aware tier verification with known HDR clip.
- Vendor watchdog tests on TCL hardware (community-tested post-release if no in-house access).

### 9.4 No mocks of HyperHDR

When a real server is needed in tests, use a real (or trivially-faked) socket. Mocked client interfaces are forbidden — protocol drift is the bug class we're guarding against.

## 10. Out of scope for v1

| Deferred | Why |
|---|---|
| HDR transport (10-bit / P010 wire format) | Requires upstream HyperHDR schema change. Tier B (Android-side tone-map) ships first. |
| Protobuf transport | Flatbuffer preferred; protobuf adds maintenance with no upside. |
| WLED / DDP / direct-to-LED transports | Out of project goal. |
| Phone/tablet UI | TV-only by decision. |
| Compose-for-TV rewrite | Future v2. |
| Audio capture / music mode | Niche; fights with MediaProjection audio routing on TVs. |
| LUT upload | Duplicates HyperHDR's calibration wizard. |
| Auth token refresh / OAuth | HyperHDR uses static tokens. |
| Live WebSocket subscription to JSON-API events | Polling on screen-open is fine for v1; live push is a UX nicety, not a correctness need. Client API is pre-shaped for it. |
| Telemetry / crash reporter / analytics | Privacy-sensitive app. |
| iOS / Apple TV port | Different platform. |
| Sideload-over-old-app package compatibility | Hard fork; users install fresh. |

## 11. Done definition for v1

1. mDNS discovers a real HyperHDR v22+ instance on the network.
2. Flatbuffer NV12 pipeline streams frames at configured fps with measured latency under 80 ms on a reference Android TV (Pixel-class, Shield TV, or ~2022+ Sony/Bravia).
3. Auth-required servers connect cleanly with token paste; auth-disabled servers connect without prompting.
4. HDR-state signal (`videomode`) toggles correctly when content transitions on at least one Android 13+ test device with an HDR-capable display.
5. Foreground service survives background ↔ foreground cycles and at least 8 hours of continuous capture without reconnect-storms or memory growth.
6. APK signed; first GitHub Release published with build artifacts and SHA256.
