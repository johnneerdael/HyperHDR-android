# HyperHDR upstream issue draft — P010 (10-bit) wire format for the flatbuffer protocol

This is a draft of a GitHub issue to file at `https://github.com/awawa-dev/HyperHDR/issues`. The goal is to align on a schema shape *before* anyone writes code, on either side. Review and edit before filing.

---

## Suggested title

`Proposal: extend FlatBuffer protocol with a P010 (10-bit) image variant for HDR-capable clients`

## Suggested labels

`enhancement`, `flatbuffer`, `hdr`

## Issue body (paste below the line)

---

Hi, first off — thanks for HyperHDR. The existing FlatBuffer protocol with NV12 support has been a great foundation for client-side grabbers.

I'm working on a third-party Android TV grabber ([johnneerdael/HyperHDR-android](https://github.com/johnneerdael/HyperHDR-android)) that captures the screen and streams it to HyperHDR over the FlatBuffer protocol. v0.1.0–v0.3.0 ship 8-bit NV12 with on-device PQ→SDR tone-mapping (Hable curve). It works, but the result is a generic tone-map that ignores the user's calibrated LUTs on the HyperHDR side.

I'd like to extend the FlatBuffer protocol with a 10-bit (P010) image variant so that HDR-capable clients can ship the original PQ-encoded pixels and let HyperHDR's existing tone-mapping pipeline (the one your DirectX grabber path already drives) do the SDR conversion. I'd be happy to write the upstream PR myself, but the schema shape is a forever-decision, so I want to align with you on direction first.

### Use case

Android 13+ (`MediaProjection` + `Display.HdrCapabilities` + `ImageFormat.YCBCR_P010`) can deliver PQ-encoded 10-bit frames from screen capture. Today my client downconverts those to 8-bit NV12 + applies a generic Hable curve before sending. With a 10-bit transport, HyperHDR — which already has well-tested tone-mapping for its DirectX/V4L2 grabbers — could do the conversion using the user's own LUT. Visible benefit: LED color matches the user's calibrated TV output more closely than a generic curve can.

### Current state (citations from `master`)

- `include/flatbuffers/parser/hyperhdr_request.fbs`: `union ImageType { RawImage, NV12Image }` — extending the union is non-breaking.
- `sources/flatbuffers/parser/FlatBuffersParser.cpp`: parser dispatches on `ImageType`; the NV12 branch around line 193 is the closest template for a new variant.
- `sources/flatbuffers/server/FlatBuffersServer.cpp`: NV12 frames are routed through the existing tone-mapping path with `_lut.data()` already in scope; a P010 branch could reuse it.

### Proposed schema shape

```fbs
table P010Image {
  data_y: [ubyte];       // Microsoft P010 layout: 10 high bits + 6 zero pad per 16-bit word
  data_uv: [ubyte];      // interleaved Cb/Cr, half-height, same packing
  width: int;
  height: int;
  stride_y: int;
  stride_uv: int;
}

union ImageType { RawImage, NV12Image, P010Image }
```

Rationale for `[ubyte]` rather than `[ushort]`: the existing NV12 schema uses raw bytes and the FlatBuffers Java runtime treats `[ushort]` differently across versions — using bytes keeps wire format stable and lets clients write whatever endianness their platform produces. The format spec would lock "Microsoft P010 byte layout" so endianness is implicit.

### Open questions for maintainer input

These are the design choices I'd like your call on before any code lands:

1. **Schema shape — agree on `P010Image` as a parallel table, or prefer a different approach?** I considered (a) extending NV12Image with `bits_per_channel`, and (b) a generic `YuvImage` with a format enum. (a) breaks "what does NV12 mean," (b) is the biggest schema change. I lean parallel, but happy to defer to your view.

2. **Tone-mapping metadata.** P010 alone isn't enough — HyperHDR also needs to know transfer function (PQ vs HLG) and primaries (BT.2020 typical). Two options:
   - Extend the schema with optional fields (`transfer_function`, `primaries`).
   - Continue using the JSON-API `videomode` command, with a new field for the transfer function alongside the existing `HDR=0/1`.

3. **Negotiation.** How should clients discover that the server understands P010? I see three options:
   - Always try P010 first; fall back on parser-error response. Adds a round-trip.
   - Add `supported_image_types` to the JSON-API `serverinfo` response. Cleaner.
   - Require HyperHDR ≥ vX.Y; below that, NV12 always.

4. **Server-side reuse.** Can the existing `_lut.data()` tone-mapping path consume P010 directly, or would the parser need to convert to an internal HDR-aware format first? (I haven't read enough of the LUT pipeline to know.)

5. **Bandwidth note for context.** At 192×108 @ 60 fps, NV12 = 1.9 MB/s, P010 = 3.7 MB/s. Doubling. LAN is fine; would only matter for hypothetical remote streaming.

### What I'd like from this issue

Direction-level agreement on the four open questions above. Once we have alignment, I'll send a PR for:

- The schema change in `include/flatbuffers/parser/hyperhdr_request.fbs`.
- Server-side parser branch in `sources/flatbuffers/parser/FlatBuffersParser.cpp`.
- Server-side image-handling branch in `sources/flatbuffers/server/FlatBuffersServer.cpp`.
- Optional `serverinfo` extension if we go with negotiation option (b).

Happy to discuss either here or on the discussions board if you'd prefer that venue.

Thanks!

— John ([@johnneerdael](https://github.com/johnneerdael))

---

## Notes for filing

- **Venue:** issue, not discussion — this is a concrete proposal with a clear ask. If the HyperHDR repo prefers discussions for design topics, switch.
- **Filing command (when ready):**
  ```bash
  gh issue create \
    --repo awawa-dev/HyperHDR \
    --title "Proposal: extend FlatBuffer protocol with a P010 (10-bit) image variant for HDR-capable clients" \
    --label "enhancement" \
    --body-file /Users/jneerdael/Scripts/hyperhdr/docs/superpowers/proposals/2026-05-02-hyperhdr-upstream-p010-wire-format.md
  ```
  (You may need to drop labels if the maintainer hasn't created them, or strip the markdown header above the line; `--body-file` will include the whole file otherwise. Easier in practice: copy the issue-body section by hand into the GitHub web UI.)
- **After filing:** add a link from `docs/superpowers/sketches/2026-05-02-sketch-p010-10bit-wire-format.md` to the upstream issue, so future-you knows where the conversation lives.
