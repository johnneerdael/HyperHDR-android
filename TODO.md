# TODO — watch-and-wait items

Items that don't yet need a plan because they're gated on external events. Re-evaluate when triggers fire.

---

## 10-bit P010 wire format (Tier C HDR)

**Status:** Blocked on HyperHDR upstream — schema design conversation needed before any client work.

v1.0.0 ships Tier B HDR: HDR-aware capture on Android 13+, on-device PQ→SDR Hable tone-map, 8-bit NV12 transport. Tier C would let HyperHDR's existing LUT pipeline handle the tone-map by accepting 10-bit P010 frames directly — visible benefit is that the user's calibrated TV LUT drives the LED color, not our generic Hable curve.

**Trigger:** HyperHDR upstream agrees on a schema shape (a new `P010Image` table parallel to `RawImage` and `NV12Image`, or alternative) and ships a release containing the schema.

**Action when triggered:**

1. File the upstream issue using the draft at `docs/superpowers/proposals/2026-05-02-hyperhdr-upstream-p010-wire-format.md` (one-shot — once filed, link from the sketch).
2. Once HyperHDR ships the schema, regenerate the FlatBuffers Java in `:common` from the new `.fbs`.
3. Add `CaptureTier.HDR_P010` enum value alongside `SDR` and `HDR_AWARE`.
4. Extend `EglNv12Pipeline` (or carve out an `EglP010Pipeline`) for `GLES30.GL_R16` / `GL_RG16` 16-bit textures and `glReadPixels` with `GL_UNSIGNED_SHORT`.
5. Wire the new tier through `HyperHdrFlatBufferClient.sendP010(...)` and the service's tier selection.
6. Verify `GL_R16`/`GL_RG16` render-target support on the AM6 (API 28) floor before committing the architecture.

Estimated work post-upstream: ~10 client-side tasks, similar shape to Plan 4. Don't write the plan until upstream merges.

**References:**
- Sketch: `docs/superpowers/sketches/2026-05-02-sketch-p010-10bit-wire-format.md`
- Upstream issue draft: `docs/superpowers/proposals/2026-05-02-hyperhdr-upstream-p010-wire-format.md`

**Last checked:** 2026-05-03 (issue not yet filed; awaiting maintainer's go-ahead).

---

## Backlog history (v2 backlog, all shipped except the above)

For reference. Everything below has been completed since v0.1.0:

| # | Item | Shipped in |
|---|---|---|
| 2 | WebSocket live JSON-API events | v0.3.0 (Plan 6) |
| 3 | Per-frame FlatBufferBuilder reuse | v0.2.0 (Plan 5) |
| 4 | FrameClock rounding for 90 Hz panels | v0.2.0 (Plan 5) |
| 5 | EglNv12Pipeline.start() partial-init cleanup | v0.2.0 (Plan 5) |
| 6 | OES texture detach before delete | v0.2.0 (Plan 5) |
| 7 | LeanbackPreferenceFragmentCompat migration | **Obsolete** — v1.0.0 dropped Leanback entirely |
| 8 | Compose-for-TV rewrite | v1.0.0 (Plan 7) |

Plans, specs, sketches, and proposals all live under `../docs/superpowers/`. Manual test recipes are in `docs/manual-tests/`.
