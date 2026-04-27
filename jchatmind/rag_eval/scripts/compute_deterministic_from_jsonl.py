#!/usr/bin/env python3
"""Deterministic IR metrics from a retrieval export JSONL (no LLM / no RAGAS)."""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import pandas as pd


def parse_ks(raw: str) -> list[int]:
    ks: list[int] = []
    for part in raw.split(","):
        stripped = part.strip()
        if not stripped:
            continue
        v = int(stripped)
        if v <= 0:
            raise ValueError("All ks must be > 0")
        ks.append(v)
    return sorted(set(ks))


def dcg(binary_relevances: list[int]) -> float:
    score = 0.0
    for index, rel in enumerate(binary_relevances, start=1):
        if rel:
            score += rel / math.log2(index + 1)
    return score


def read_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                rows.append(json.loads(text))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at line {line_number}: {exc}") from exc
    return rows


def build_metadata_df(rows: list[dict]) -> pd.DataFrame:
    metadata_rows: list[dict] = []
    for row in rows:
        retrieved = row.get("retrieved", [])
        metadata_rows.append(
            {
                "sample_id": row.get("sample_id"),
                "topic": row.get("topic"),
                "difficulty": row.get("difficulty"),
                "user_input": row.get("user_input"),
                "reference": row.get("reference"),
                "reference_context_ids": row.get("reference_context_ids", []),
                "retrieved_chunk_ids": [h.get("chunk_id") for h in retrieved if h.get("chunk_id")],
            }
        )
    return pd.DataFrame(metadata_rows)


def compute_deterministic_metrics(metadata_df: pd.DataFrame, ks: list[int]) -> pd.DataFrame:
    rows: list[dict] = []
    for _, row in metadata_df.iterrows():
        retrieved_ids = row["retrieved_chunk_ids"] or []
        reference_ids = row["reference_context_ids"] or []
        reference_set = set(reference_ids)
        metric_row: dict = {}
        if not reference_set:
            for k in ks:
                metric_row[f"hit_rate_at_{k}"] = None
                metric_row[f"recall_at_{k}"] = None
                metric_row[f"ndcg_at_{k}"] = None
            metric_row["mrr"] = None
            rows.append(metric_row)
            continue
        first_relevant_rank = None
        for idx, chunk_id in enumerate(retrieved_ids, start=1):
            if chunk_id in reference_set:
                first_relevant_rank = idx
                break
        metric_row["mrr"] = 0.0 if first_relevant_rank is None else 1.0 / first_relevant_rank
        for k in ks:
            top_k = retrieved_ids[:k]
            hits = sum(1 for cid in top_k if cid in reference_set)
            metric_row[f"hit_rate_at_{k}"] = 1.0 if hits > 0 else 0.0
            metric_row[f"recall_at_{k}"] = hits / len(reference_set)
            binary_relevances = [1 if cid in reference_set else 0 for cid in top_k]
            actual_dcg = dcg(binary_relevances)
            ideal_len = min(len(reference_set), k)
            ideal_binary = [1] * ideal_len
            ideal_dcg = dcg(ideal_binary)
            metric_row[f"ndcg_at_{k}"] = 0.0 if ideal_dcg == 0 else actual_dcg / ideal_dcg
        rows.append(metric_row)
    return pd.DataFrame(rows)


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    evaluation_dir = script_dir.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        default=str(evaluation_dir / "artifacts" / "retrieval_export_eval.jsonl"),
        help="Retrieval export JSONL.",
    )
    parser.add_argument(
        "--output",
        default="",
        help="Output CSV (default: reports/deterministic_<run>.csv).",
    )
    parser.add_argument("--ks", default="1,3,5", help="Comma-separated k cutoffs.")
    args = parser.parse_args()
    input_path = Path(args.input).resolve()
    ks = parse_ks(args.ks)
    run_name = __import__("datetime").datetime.now().strftime("%Y%m%d_%H%M%S")
    output_path = (
        Path(args.output).resolve()
        if args.output
        else (evaluation_dir / "reports" / f"deterministic_{run_name}.csv")
    )
    rows = read_jsonl(input_path)
    if not rows:
        print("Input JSONL is empty.", file=sys.stderr)
        return 1
    meta = build_metadata_df(rows)
    det = compute_deterministic_metrics(meta, ks).reset_index(drop=True)
    out = pd.concat([meta.reset_index(drop=True), det], axis=1)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(output_path, index=False)
    print(f"[done] rows={len(out)} output={output_path.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
