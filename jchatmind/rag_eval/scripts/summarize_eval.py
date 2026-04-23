#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    evaluation_dir = script_dir.parent
    parser = argparse.ArgumentParser(
        description="Generate a markdown summary from the row-level retrieval evaluation CSV."
    )
    parser.add_argument(
        "--input",
        required=True,
        help="CSV file produced by run_ragas_retrieval_eval.py",
    )
    parser.add_argument(
        "--output",
        default=str(evaluation_dir / "reports" / "summary.md"),
        help="Markdown output path.",
    )
    return parser.parse_args()


def format_mean(series: pd.Series) -> str:
    valid = series.dropna()
    if valid.empty:
        return "n/a"
    return f"{valid.mean():.4f}"


def build_metric_table(df: pd.DataFrame, columns: list[str]) -> str:
    lines = ["| Metric | Mean |", "| --- | ---: |"]
    for column in columns:
        lines.append(f"| `{column}` | {format_mean(df[column])} |")
    return "\n".join(lines)


def build_group_table(df: pd.DataFrame, group_col: str, score_cols: list[str]) -> str:
    if group_col not in df.columns:
        return ""

    grouped = df.dropna(subset=[group_col]).groupby(group_col, dropna=True)
    if grouped.ngroups == 0:
        return ""

    lines = [f"| {group_col} | Samples | " + " | ".join(score_cols) + " |"]
    lines.append("| --- | ---: | " + " | ".join(["---:"] * len(score_cols)) + " |")
    for group_name, group_df in grouped:
        values = [format_mean(group_df[col]) for col in score_cols]
        lines.append(
            f"| {group_name} | {len(group_df)} | " + " | ".join(values) + " |"
        )
    return "\n".join(lines)


def build_lowest_samples(df: pd.DataFrame, score_col: str, limit: int = 5) -> str:
    if score_col not in df.columns:
        return ""
    subset = df.dropna(subset=[score_col]).sort_values(score_col, ascending=True).head(limit)
    if subset.empty:
        return ""

    lines = ["| sample_id | topic | score | user_input |", "| --- | --- | ---: | --- |"]
    for _, row in subset.iterrows():
        user_input = str(row.get("user_input", "")).replace("\n", " ")
        lines.append(
            f"| {row.get('sample_id', '')} | {row.get('topic', '')} | {row[score_col]:.4f} | {user_input} |"
        )
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    input_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve()

    df = pd.read_csv(input_path)
    metric_columns = [
        column
        for column in df.columns
        if column.startswith("context_")
        or column.startswith("hit_rate_at_")
        or column.startswith("recall_at_")
        or column.startswith("ndcg_at_")
        or column == "mrr"
    ]

    score_cols_for_grouping = [
        column for column in ["context_precision", "context_recall", "context_relevance", "mrr"] if column in df.columns
    ]

    parts = [
        "# Retrieval Evaluation Summary",
        "",
        f"- Samples: {len(df)}",
        f"- Source CSV: `{input_path.as_posix()}`",
        "",
        "## Overall",
        build_metric_table(df, metric_columns),
    ]

    topic_table = build_group_table(df, "topic", score_cols_for_grouping)
    if topic_table:
        parts.extend(["", "## By Topic", topic_table])

    difficulty_table = build_group_table(df, "difficulty", score_cols_for_grouping)
    if difficulty_table:
        parts.extend(["", "## By Difficulty", difficulty_table])

    weakest = build_lowest_samples(df, "context_recall")
    if weakest:
        parts.extend(["", "## Lowest Context Recall Samples", weakest])

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(parts).strip() + "\n", encoding="utf-8")
    print(f"[done] summary={output_path.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
