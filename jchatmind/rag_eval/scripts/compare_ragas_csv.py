#!/usr/bin/env python3
"""Compare two rag_eval row-level CSVs on overlapping numeric columns (RAGAS + deterministic)."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd


def numeric_columns(df: pd.DataFrame) -> list[str]:
    cols: list[str] = []
    for c in df.columns:
        if c in ("sample_id", "topic", "difficulty"):
            continue
        if pd.api.types.is_numeric_dtype(df[c]):
            cols.append(c)
    return sorted(cols)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="Earlier / reference CSV")
    parser.add_argument("current", type=Path, help="New CSV")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("rag_eval/reports/comparison_baseline_vs_current.md"),
        help="Markdown report path",
    )
    args = parser.parse_args()
    b = pd.read_csv(args.baseline)
    c = pd.read_csv(args.current)
    key = "sample_id"
    if key not in b.columns or key not in c.columns:
        print("Both CSVs must have sample_id.", file=sys.stderr)
        return 1
    b_idx = b.set_index(key, drop=False)
    c_idx = c.set_index(key, drop=False)
    common_ids = sorted(set(b_idx.index) & set(c_idx.index))
    only_b = sorted(set(b_idx.index) - set(c_idx.index))
    only_c = sorted(set(c_idx.index) - set(b_idx.index))
    overlap = sorted(set(numeric_columns(b)) & set(numeric_columns(c)))

    lines: list[str] = [
        "# RAG eval CSV comparison",
        "",
        f"- Baseline: `{args.baseline.as_posix()}` (rows={len(b)})",
        f"- Current: `{args.current.as_posix()}` (rows={len(c)})",
        f"- Matched `sample_id`: {len(common_ids)}",
        "",
    ]
    if only_b:
        lines.append(f"- Only in baseline: {only_b}")
    if only_c:
        lines.append(f"- Only in current: {only_c}")
    lines.append("")

    lines.append("## Mean (matched rows)")
    lines.append("")
    lines.append("| column | baseline_mean | current_mean | delta |")
    lines.append("| --- | ---: | ---: | ---: |")
    for col in overlap:
        mb = b_idx.loc[common_ids, col]
        mc = c_idx.loc[common_ids, col]
        vb = float(mb.mean()) if mb.notna().any() else float("nan")
        vc = float(mc.mean()) if mc.notna().any() else float("nan")
        if pd.isna(vb) and pd.isna(vc):
            d = 0.0
        elif pd.isna(vb) or pd.isna(vc):
            d = float("nan")
        else:
            d = vc - vb
        lines.append(
            f"| `{col}` | {vb:.6f} | {vc:.6f} | {d:.6f} |"
        )
    lines.append("")

    lines.append("## Per-sample max abs delta (Top 15 columns by mean |delta|)")
    line_deltas: list[tuple[str, float]] = []
    for col in overlap:
        diffs = []
        for sid in common_ids:
            xb, xc = b_idx.loc[sid, col], c_idx.loc[sid, col]
            if pd.isna(xb) and pd.isna(xc):
                continue
            if pd.isna(xb) or pd.isna(xc):
                diffs.append(float("inf"))
            else:
                diffs.append(abs(float(xc) - float(xb)))
        if not diffs:
            continue
        line_deltas.append((col, sum(diffs) / max(len(diffs), 1)))
    line_deltas.sort(key=lambda t: -abs(t[1]))
    top_cols = [t[0] for t in line_deltas[:15]]

    for col in top_cols:
        line_deltas2: list[tuple[str, float, float, float]] = []
        for sid in common_ids:
            xb, xc = b_idx.loc[sid, col], c_idx.loc[sid, col]
            if pd.isna(xb) and pd.isna(xc):
                continue
            if pd.isna(xb) or pd.isna(xc):
                line_deltas2.append((str(sid), float("nan"), float("nan"), float("nan")))
            else:
                line_deltas2.append(
                    (str(sid), float(xb), float(xc), float(xc) - float(xb))
                )
        line_deltas2.sort(key=lambda t: -abs(t[3]) if not pd.isna(t[3]) else 0)
        worst = line_deltas2[:8]
        if not worst or all(abs(t[3]) < 1e-9 for t in worst if not pd.isna(t[3])):
            continue
        lines.append(f"### `{col}`")
        lines.append("")
        lines.append("| sample_id | baseline | current | delta |")
        lines.append("| --- | ---: | ---: | ---: |")
        for sid, xb, xc, d in worst:
            if abs(d) < 1e-12 and not pd.isna(d):
                continue
            lines.append(f"| {sid} | {xb} | {xc} | {d} |")
        lines.append("")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines).strip() + "\n", encoding="utf-8")
    print(f"[done] report={args.output.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
