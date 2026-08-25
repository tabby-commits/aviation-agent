# space-agent

FastAPI migration skeleton for the JChatMind backend contract.

## Run

Install dependencies in your preferred Python environment:

```powershell
pip install -e ".[dev]"
```

Copy `.env.example` to `.env` and set `SPACE_AGENT_DATABASE_URL` for the existing PostgreSQL database. Then start the API:

```powershell
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## Current Scope

- FastAPI route skeleton for `/api/*`, `/sse/connect/{chatSessionId}`, and `/health`.
- Existing Java response wrapper shape: `code`, `message`, `data`.
- PostgreSQL repository boundary for the existing schema, with no destructive migrations.
- Agent runtime ports plus a stub implementation that persists an assistant placeholder message and emits SSE events.

Full RAG, BM25, pgvector similarity search, Skill parsing, Hook recovery, Agentic Search, and chart tooling are intentionally left for later modules.
