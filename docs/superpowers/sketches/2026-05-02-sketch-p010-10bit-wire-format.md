# Sketch — 10-bit P010 wire format

**Status:** Pre-plan brainstorming brief. Do **not** start implementing from this document. The wire format isn't decided.

**Why this is a sketch and not a plan:** changing the FlatBuffer schema is a coordinated change that affects HyperHDR upstream. There are real design choices to make (schema shape, endianness, who owns the tonemap), and choosing wrong locks us into a wire format that's painful to undo. Brainstorm first, then split into:
1. A HyperHDR upstream PR for the schema + server-side parser.
2. A client-side plan in this repo, gated on the upstream PR landing in a HyperHDR release.

---

## Goal

Stream 10-bit-per-channel HDR pixels (P010 / similar) from the Android grabber to HyperHDR. Today, Plan 4 ships **Tier B** — HDR-aware capture but 8-bit NV12 transport (we tone-map PQ→SDR on the device). Tier C would let HyperHDR do the tone-map in its existing LUT pipeline, matching what its DirectX/V4L2 grabbers do. The visible benefit is that HyperHDR's calibrated LUTs (which a user can tune for their TV's color profile) drive the LED output, instead of our generic Hable curve.

## Open design questions for brainstorming

### 1. Schema shape

Options:
- **(a) New table type `P010Image`** parallel to `RawImage` and `NV12Image`. Cleanest: every existing client keeps working unchanged. New clients/servers opt in.
- **(b) Extend `NV12Image` with a `bits_per_channel` field.** Smaller schema but breaks "what does NV12 actually mean?" — NV12 is by definition 8-bit. Forces every parser to special-case the field.
- **(c) Generic `YuvImage` with format enum.** Cleanest for future formats (P016, YUV420P10LE, etc.) but the biggest schema change.

Recommendation to take into the brainstorm: (a). It mirrors how `RawImage` and `NV12Image` already coexist.

### 2. Endianness and packing

P010 is "10-bit luma in the high 10 bits of a 16-bit word, padding zero in the low 6." But the layout in memory is platform-dependent:
- **Microsoft P010** (Windows DirectX): high-bits-MSB, little-endian word order.
- **Linux V4L2 P010_10LE** (HyperHDR's V4L2 path): same but the `_10LE` suffix is explicit.
- **Android `ImageFormat.YCBCR_P010` (API 33+)**: explicitly Microsoft P010.

If we pick "Microsoft P010" we line up with HyperHDR's DirectX grabber and Android's native format. Recommended.

But there's a wrinkle: the NV12Image schema today uses `[ubyte]` for plane data (raw bytes, no endianness). If the new schema uses `[ushort]` we have to commit to a byte order on the wire. Options:
- (i) `[ushort]` little-endian (FlatBuffers default for scalars).
- (ii) `[ubyte]` (raw bytes, byte order baked into the format spec).

Option (ii) is what NV12 does and is simpler; the format spec just says "Microsoft P010 byte layout."

### 3. Who owns the tonemap?

Three possible split points:
- **(A) Tier-B-as-today** — Android tonemaps PQ→SDR; HyperHDR sees SDR. v0.x ships this. No P010 needed.
- **(B) HyperHDR tonemaps** — Android sends P010; HyperHDR uses its existing DirectX-grabber LUT pipeline to map to SDR LED colors.
- **(C) Hybrid** — Android tonemaps to a wide-gamut linear intermediate (P010 or higher); HyperHDR applies user-calibrated color curves on top.

(B) is the natural fit for a 10-bit transport and is what users probably mean when they ask for "real HDR."

### 4. Bandwidth budget

At 192×108 @ 60 fps:
- 8-bit NV12: 192·108·1.5 bytes/frame · 60 = ~1.9 MB/s
- 10-bit P010: 192·108·3 bytes/frame · 60 = ~3.7 MB/s

Doubling. Not a concern over LAN; would matter only if we ever streamed remotely.

### 5. Tone-mapping metadata

HyperHDR's external-tonemap mode currently takes a binary HDR=0/1 flag (Plan 4). For P010 to be useful, HyperHDR needs to know:
- Which transfer function the source is in (PQ vs HLG)
- Which color primaries (BT.2020 vs DCI-P3)
- Optionally, the master display nits (for accurate tonemap)

Android exposes some of this via `HardwareBuffer.getDataSpace()` (API 33+); HyperHDR would need a JSON-API extension or new flatbuffer field to receive it.

### 6. Backwards compatibility

The new schema must coexist with existing clients (NV12, RawImage). HyperHDR's parser already handles `union ImageType {RawImage, NV12Image}` — adding `P010Image` to the union is a non-breaking change as long as existing-client serialization is unaffected (which `union` semantics guarantee in FlatBuffers).

### 7. Negotiation

How does the client know whether the server understands P010?
- **Option A:** the client always tries P010 first; on a parse error response, fall back to NV12 with on-device tonemap. Robust but adds a round-trip on every connect.
- **Option B:** the client probes via JSON-API serverinfo (HyperHDR could add a `supported_image_types` field). Cleaner, requires server-side cooperation.
- **Option C:** the client requires HyperHDR ≥ vX.Y; below that, NV12 always. Simplest but brittle.

(B) is the right answer if we're already PR-ing HyperHDR.

## What "the upstream PR" looks like

1. Open a HyperHDR GitHub issue describing the use case ("Android grabber wants to send P010 instead of NV12+local-tonemap").
2. Get author input on the schema shape (probably (a), but the maintainer might prefer (c)).
3. PR the schema change to `include/flatbuffers/parser/hyperhdr_request.fbs`.
4. PR the server-side parser change in `sources/flatbuffers/parser/FlatBuffersParser.cpp` and `sources/flatbuffers/server/FlatBuffersServer.cpp`. The existing NV12 path is the closest template — copy and adapt for the new format.
5. Add `supported_image_types` to the JSON `serverinfo` response if doing negotiation option (B).
6. Wait for a HyperHDR release with the change.

## What the client-side plan looks like (sketch only)

Once upstream lands:

1. **Schema sync** — `flatc 25.2.10 --java -o common/src/main/java common/src/main/flatbuffers/hyperhdr_request.fbs` regenerates the client code.
2. **Capture** — extend `EglNv12Pipeline` (or split out an `EglP010Pipeline`?) so the GPU writes 10-bit Y plus packed UV. This requires `GLES30.GL_R16` / `GL_RG16` formats and `glReadPixels` with `GL_UNSIGNED_SHORT`. Already supported on most Mali/Adreno; verify on the AM6 floor.
3. **Tier selection** — add `CaptureTier.HDR_P010` alongside `SDR` and `HDR_AWARE`. Plan 4's tier-aware pipeline already has a clean seam.
4. **Negotiation** — at connect time, query `supported_image_types`; if absent or doesn't include `P010`, fall back to current behavior.
5. **JSON-API metadata** — wire HDR transfer function + primaries into the JSON-API alongside `videomode`.
6. **Settings UI** — third option in the HDR group: "On the server (10-bit transport)" alongside "On the device" (Tier B) and "Off" (SDR).

Estimated client-side: ~10 tasks, similar shape to Plan 4. **Don't write this plan until upstream merges.**

## Risks

- **Driver compat for `GL_R16`/`GL_RG16`** — common on flagship phones, less reliable on older Android TV boxes. Need an instrumented test on the floor device before committing the architecture.
- **Maintainer alignment** — if HyperHDR upstream prefers a different schema (say (c) over (a)), all client work is gated on that decision. Talk first.
- **Color management is hard** — even with a "correct" P010 transport, getting LEDs to look right requires LUT calibration the user has to perform. The benefit is real but not automatic.

## Pre-plan checklist

Before writing the implementation plan:

- [ ] HyperHDR maintainer alignment on schema shape (issue + thread).
- [ ] HyperHDR PR merged.
- [ ] HyperHDR release containing the schema published.
- [ ] AM6 (API 28) confirmed to support `GL_R16`/`GL_RG16` via a small instrumented test.
- [ ] Confirmed which Android API exposes `Display.getDataSpace()` and whether MediaProjection's VirtualDisplay propagates it.

When all five pass, brainstorm-then-write the client-side plan.
