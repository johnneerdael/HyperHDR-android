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
