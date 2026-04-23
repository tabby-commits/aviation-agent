#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from openai import OpenAI

DEFAULT_TEMPERATURE = 0.0


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    evaluation_dir = script_dir.parent
    parser = argparse.ArgumentParser(
        description="Draft a labeled eval_set.en.jsonl from a bootstrap retrieval export."
    )
    parser.add_argument(
        "--input",
        default=str(evaluation_dir / "artifacts" / "retrieval_export_bootstrap.jsonl"),
        help="Bootstrap retrieval export JSONL produced from question_bank.en.jsonl.",
    )
    parser.add_argument(
        "--output",
        default=str(evaluation_dir / "datasets" / "eval_set.en.jsonl"),
        help="Output JSONL path for the drafted evaluation set.",
    )
    parser.add_argument(
        "--max-contexts",
        type=int,
        default=5,
        help="Maximum retrieved contexts to show the judge model per sample.",
    )
    parser.add_argument(
        "--max-samples",
        type=int,
        default=0,
        help="Optional cap on the number of samples to process. 0 means all.",
    )
    parser.add_argument(
        "--model",
        default=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
        help="OpenAI-compatible model name.",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=float(os.getenv("OPENAI_TEMPERATURE", DEFAULT_TEMPERATURE)),
        help="Sampling temperature for the labeling model.",
    )
    return parser.parse_args()


def load_environment(env_path: Path) -> None:
    if env_path.exists():
        load_dotenv(env_path)


def make_client() -> OpenAI:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required. Create rag_eval/.env from .env.example first.")

    base_url = os.getenv("OPENAI_BASE_URL")
    if base_url:
        return OpenAI(api_key=api_key, base_url=base_url)
    return OpenAI(api_key=api_key)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(f"Input file does not exist: {path}")
    rows: list[dict[str, Any]] = []
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


def extract_json_block(content: str) -> dict[str, Any]:
    match = re.search(r"\{.*\}", content, re.DOTALL)
    if not match:
        raise ValueError(f"Model response did not contain JSON: {content}")
    return json.loads(match.group(0))


def build_prompt(sample: dict[str, Any], max_contexts: int) -> tuple[str, str]:
    query = sample["user_input"]
    retrieved = sample.get("retrieved", [])[:max_contexts]
    formatted_contexts = []
    for index, hit in enumerate(retrieved, start=1):
        formatted_contexts.append(
            f"[{index}] chunk_id={hit.get('chunk_id')} doc_id={hit.get('doc_id')}\n{hit.get('content', '').strip()}"
        )

    system_prompt = (
        "You are helping build a retrieval evaluation dataset for an English aerospace-news knowledge base. "
        "Use only the provided retrieved contexts. Do not invent facts. "
        "Return valid JSON with keys: answerable, reference, reference_context_ids, notes."
    )
    user_prompt = (
        f"Question:\n{query}\n\n"
        f"Retrieved contexts:\n\n{chr(10).join(formatted_contexts)}\n\n"
        "Tasks:\n"
        "1. Decide whether the retrieved contexts are sufficient to answer the question.\n"
        "2. If answerable, write a concise English reference answer grounded only in the contexts.\n"
        "3. Select up to 3 chunk_ids that best support the answer.\n"
        "4. Keep notes short and mention why the sample needs review if the evidence is weak.\n\n"
        "Return JSON only."
    )
    return system_prompt, user_prompt


def draft_sample(
    client: OpenAI,
    model: str,
    temperature: float,
    sample: dict[str, Any],
    max_contexts: int,
) -> dict[str, Any] | None:
    system_prompt, user_prompt = build_prompt(sample, max_contexts)
    response = client.chat.completions.create(
        model=model,
        temperature=temperature,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    )
    content = response.choices[0].message.content or ""
    draft = extract_json_block(content)

    if not draft.get("answerable"):
        return None

    retrieved = sample.get("retrieved", [])
    context_by_id = {hit.get("chunk_id"): hit.get("content", "") for hit in retrieved if hit.get("chunk_id")}
    selected_ids = [
        chunk_id
        for chunk_id in draft.get("reference_context_ids", [])
        if chunk_id in context_by_id
    ]
    if not selected_ids:
        return None

    return {
        "sample_id": sample.get("sample_id"),
        "user_input": sample.get("user_input"),
        "reference": str(draft.get("reference", "")).strip(),
        "reference_context_ids": selected_ids,
        "reference_contexts": [context_by_id[chunk_id] for chunk_id in selected_ids],
        "topic": sample.get("topic"),
        "difficulty": sample.get("difficulty"),
        "review_status": "draft",
        "review_notes": str(draft.get("notes", "")).strip(),
    }


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False))
            handle.write("\n")


def main() -> int:
    evaluation_dir = Path(__file__).resolve().parent.parent
    load_environment(evaluation_dir / ".env")
    args = parse_args()

    input_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve()

    if args.max_contexts <= 0:
        print("--max-contexts must be > 0", file=sys.stderr)
        return 1

    rows = read_jsonl(input_path)
    if args.max_samples:
        rows = rows[: args.max_samples]

    client = make_client()
    drafted_rows: list[dict[str, Any]] = []
    skipped = 0

    for index, sample in enumerate(rows, start=1):
        try:
            drafted = draft_sample(
                client=client,
                model=args.model,
                temperature=args.temperature,
                sample=sample,
                max_contexts=args.max_contexts,
            )
        except Exception as exc:  # noqa: BLE001
            print(f"[error] sample={sample.get('sample_id', index)} detail={exc}", file=sys.stderr)
            skipped += 1
            continue

        if drafted is None or not drafted.get("reference"):
            skipped += 1
            print(f"[skip] sample={sample.get('sample_id', index)} reason=insufficient_grounding")
            continue

        drafted_rows.append(drafted)
        print(f"[drafted] sample={drafted['sample_id']}")

    write_jsonl(output_path, drafted_rows)
    print(
        f"[done] drafted={len(drafted_rows)} skipped={skipped} output={output_path.as_posix()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
