# Agent design: what's already there, what's worth adding

Written for a whiteboard defense of the LangGraph design (per `CLAUDE.md`'s "explain
non-obvious decisions" rule). Covers the 5 nodes / 3 graphs in `graph/` as they exist today,
not aspirational scope — see root `README.md`'s Phase status for what's deliberately deferred.

## Patterns already in use

| Pattern | Where | Why it fits |
|---|---|---|
| **Router** (deterministic) | `builder.py`: `_route_after_classify`, `_route_after_extract` | Routing key (`document_type`, `hitl_required`) is a plain field the graph itself set two steps earlier — a conditional edge on that field *is* the router. No LLM needed to decide it. |
| **Router** (LLM-driven) | `query.py`: the `_ROUTE_SYSTEM` call inside `query_node` | Natural-language questions don't carry a structured routing key — an LLM has to read intent first (`summary` vs `search`). Correctly the one place an LLM makes a routing decision. |
| **Model tiering** | `classify.py` (`gpt-5.4-nano`) vs `extract.py`/`query.py` (`gpt-5.5`) | Classification is a binary, low-stakes decision; extraction/synthesis is where correctness actually matters. Cost/latency scale with task difficulty. |
| **HITL interrupt + resume** | `hitl.py`, `interrupt()` / `Command(resume=...)` | `extract_node`'s self-reported `confidence` is the only signal gating trust — below threshold, a human must look at the source document before the graph completes. |
| **Checkpointing** | `main.py` (`PostgresSaver` prod / `InMemorySaver` local), all three `build_*_graph` functions accept it | Required for HITL to actually work — the graph has to survive a real pause (document sits `awaiting-review` for however long) and resume from the exact node, not restart from `classify`. |
| **Graceful degradation** | every node's `except Exception` branch | An LLM/httpx failure returns a valid state update (`document_type: "unknown"`, `hitl_required: True`, populated `error`) instead of raising — the graph always reaches `END` with something the caller can act on. |
| **Deterministic-over-LLM for structured output** | `query.py`'s `_format_summary_answer`, `insight.py`'s whole node | Both produce arithmetic + templated text. Routing a well-defined computation through an LLM adds latency, cost, and a new failure mode (empty-content responses — see the `reasoning_effort` comments in `classify.py`/`extract.py`/`query.py`) for zero benefit over a Python function. |

## Gaps worth naming

**Retry is missing — CLAUDE.md asks for "retry / graceful degradation," we only have the second
half.** Every `_llm.invoke()` call fails straight to the except branch on the first error. For
`classify_node` that's fine (worst case: one document falls through to `unknown`, cheap to
re-run). For `extract_node` it's a worse trade: a single transient timeout sends a perfectly
extractable receipt to HITL, costing a human's attention that a silent retry would have avoided.
**Recommendation:** wrap `_llm.invoke()` in `extract_node` (and only there — it's the one node
where a retry is cheaper than the fallback) with 1–2 retries on transient errors before falling
through. Small, targeted, doesn't touch the graph topology.

**`extract_node`'s confidence is self-graded — nothing checks the LLM's homework.** The same
call that extracts `{merchant, amount, currency, date}` also assigns its own `confidence`, which
is the sole input to the HITL gate. A model can return `confidence: 0.9` with `amount: null` (or
a plausible-looking but wrong ISO date) and nothing catches it — the self-report is trusted
as-is. **Recommendation:** a small deterministic critic step, either inline in `extract_node` or
as its own node between `extract` and the conditional edge, that clamps `confidence` down (and
forces `hitl_required = True`) when required fields are missing, `amount` isn't numeric/positive,
or `date` doesn't parse. This is the one addition here with real payoff for the effort — it's a
handful of `if` statements, not a new call to anything, and it closes an actual blind spot in
the one node whose output gets persisted to Postgres.

**`query_node`'s router and its two branches are one function, not one node per branch.** The
LLM routing call, the `summary` branch, and the `search` branch all live inside `query_node` —
compare to `build_process_graph`, where `classify` and `extract` are separate nodes joined by a
conditional edge. Collapsing query's router+branches into one function means LangSmith traces
show one big "query" span instead of three, and the branches can't be retried or tested
independently of the routing call. **Recommendation, lower priority than the two above:** split
into `route_query_node` (sets `state["intent"]`) → conditional edge → `summarize_node` /
`search_node`, mirroring the existing `process_graph` shape. Worth doing if this project grows
more query intents; not urgent at two branches.

## Patterns deliberately not recommended here

**Planner.** `insight_node` runs two fixed checks (`_merchant_outliers`, `_spend_spike`) every
time, unconditionally. A planner would decide *which* checks to run based on the data — but with
two checks and no plans to add more (renewals/warranty tracking is explicitly out of scope for
this demo, see README), a planner is a dispatch mechanism with nothing to dispatch between. Add
one if a third or fourth heterogeneous check shows up; not before.

**Supervisor (LLM-driven).** `process_graph`'s classify → extract → hitl sequence is already a
supervisor in the structural sense — a central point deciding what runs next — just implemented
as LangGraph conditional edges on plain state fields instead of an LLM call. The routing logic
(`document_type in (receipt, invoice)`, `confidence < threshold`) is simple enough that giving it
to an LLM would add cost and a new failure mode for a decision a two-line `if` already makes
correctly. Reach for an LLM supervisor when the routing decision itself needs judgment the state
fields can't express — that isn't true here.

**Reflection loop on `query_node`'s search-synthesis step.** The synthesis call already degrades
to raw JSON on failure (see the `except` branch in `query.py`). Adding a critique-and-retry loop
on top would mostly be spending tokens to second-guess a 1–3 sentence summary of data the user
can already see raw. Not worth it at this scope.
