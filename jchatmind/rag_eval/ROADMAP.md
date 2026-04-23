# Evaluation Roadmap

## Phase 1

- Keep the retrieval-export path low-coupled and evaluation-specific.
- Build a reusable English `question_bank.en.jsonl`.
- Export retrieval results from the Java backend as JSONL.
- Draft `eval_set.en.jsonl` with an OpenAI-compatible model.
- Run retrieval-only RAGAS metrics plus deterministic IR metrics.

## Phase 2

- Expand `eval_set.en.jsonl` after manual review.
- Add grouped analysis by topic, difficulty, and time period.
- Track additional debugging fields such as `vector_distance` and `bm25_score`.
- Compare multiple `topN` settings and hybrid-retrieval parameter choices.

## Phase 3

- Extend from retrieval-only evaluation to generation evaluation.
- Add A/B experiments for chunking strategies, embedding models, and hybrid fusion settings.
- Automate recurring benchmark runs for regression detection.
