# agents

Python 3.12 + LangGraph + FastAPI. Orchestration only — calls facade's real domain endpoints
over `httpx`, never touches Postgres/Qdrant directly (facade is the single writer).

## Graphs (`graph/builder.py`)

| Graph | Nodes | Entry point |
|---|---|---|
| `process_graph` | `classify` → `extract` → (`hitl` if confidence < 0.75) → END | `POST /agent/process` |
| `query_graph` | `query` → END | `POST /agent/query` |
| `insight_graph` | `insight` → END | `POST /agent/insights` |

Each node lives in `graph/nodes/`. State shape is `graph/state.py` — a single `AgentState`
TypedDict shared across all three graphs; unused fields stay `None` per invocation.

- **classify** (`gpt-5.4-nano`) — cheap binary/ternary routing (`receipt`/`invoice`/`unknown`)
  from subject/sender/filename alone, no file I/O.
- **extract** (`gpt-5.5`) — reads the raw file (PDF/image/text), returns structured fields +
  confidence. Confidence below `CONFIDENCE_THRESHOLD` (0.75) sets `hitl_required`.
- **hitl** — `interrupt()`s the graph; resumes via `POST /agent/resume/{thread_id}` with the
  human's approve/reject decision.
- **query** — LLM routes NL question → `summary` or `search` intent → calls the matching facade
  endpoint → deterministic formatting for summaries, LLM synthesis for search results.
- **insight** — no LLM call. Pulls 90 days of expense history from facade and runs two
  deterministic checks: per-merchant z-score outliers, and week-over-trailing-average spend
  spikes. See the module docstring in `graph/nodes/insight.py` for why this one skips the LLM.

Checkpointing: `PostgresSaver` when `POSTGRES_URL` is set (production), `InMemorySaver`
otherwise (tests / no-DB dev).

See [`PATTERNS.md`](./PATTERNS.md) for which agentic patterns (router, HITL, model tiering,
critic, planner, supervisor) apply here, which are already implemented, and which are
deliberately not — with reasoning for each.

## Run standalone

```bash
pip install -r requirements.txt
FACADE_URL=http://localhost:8081 OPENAI_API_KEY=... uvicorn main:app --reload
```

## Tests

```bash
pip install -r requirements.txt -r requirements-dev.txt
pytest
```

Tests target the deterministic logic (anomaly math, answer formatting, error paths) with LLM
calls mocked — see `tests/`.
