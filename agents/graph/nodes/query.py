"""
query_node — NL question → intent routing → facade call → synthesised answer.

Two facade endpoints:
  summary : GET /expenses/summary?from=...&to=...  → totals by category
  search  : GET /expenses/search?q=...&limit=...   → semantically similar expenses

Flow:
  1. LLM classifies intent and extracts call parameters (structured JSON).
  2. httpx calls the facade.
  3. LLM synthesises a plain-language answer from the returned data.
"""

import json
import os
from datetime import date

import httpx
from langchain_core.messages import HumanMessage, SystemMessage

from graph.llm import build_chat_model, response_text
from graph.state import AgentState

_FACADE_URL = os.environ.get("FACADE_URL", "http://facade:8081")

# strong tier: gpt-5.5 (local/vps, default) or Claude 3.5 Sonnet on Bedrock (aws
# profile) — see graph/llm.py. Used for both calls below (routing, synthesis).
# reasoning_effort="none" on the OpenAI path — routing/synthesis here are short,
# simple tasks; without it, gpt-5.x models can burn the whole max_tokens budget
# on hidden reasoning and return empty content, which then fails json.loads() at
# the routing step (this caused "query routing failed: Expecting value: line 1
# column 1 (char 0)" — an empty-string JSON parse error).
_llm = build_chat_model("strong", max_tokens=512, temperature=0)

_ROUTE_SYSTEM = """\
You route expense queries to the right API call.
Today's date: {today}

Reply with ONLY valid JSON — no markdown, no explanation:
{{
  "intent": "summary" | "search",
  "from_date": "YYYY-MM-DD or null",
  "to_date": "YYYY-MM-DD or null",
  "search_query": "string or null",
  "limit": 10
}}

Rules:
- Use "summary" for totals, spending amounts, budgets, how-much, per-category breakdowns.
  Set from_date/to_date to cover the period the user mentions.
  Default to the current calendar month when no period is specified.
- Use "search" for specific merchants, items, or fuzzy document lookup.
  Put the relevant terms in search_query.
"""

_SYNTH_SYSTEM = """\
You are a concise personal finance assistant.
Answer the user's question in 1–3 sentences using only the expense data provided.
If the data is empty say so clearly — do not invent numbers.
"""


def _summary(from_date: str, to_date: str) -> dict:
    resp = httpx.get(
        f"{_FACADE_URL}/expenses/summary",
        params={"from": from_date, "to": to_date},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def _search(q: str, limit: int) -> list:
    resp = httpx.get(
        f"{_FACADE_URL}/expenses/search",
        params={"q": q, "limit": limit},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def _fmt_amount(value) -> str:
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return str(value)


def _format_summary_answer(data: dict, from_date: str, to_date: str) -> str:
    """Deterministic total + breakdown, no LLM call — short, consistent, and
    can't fail the way free-form synthesis can (see reasoning_effort note
    above: an LLM call here would be one more place an empty response could
    slip through)."""
    by_category = data.get("byCategory") or []
    if not by_category:
        return f"Няма разходи за периода {from_date} → {to_date}."

    lines = [f"\U0001F4B0 Общо ({from_date} → {to_date}): {_fmt_amount(data.get('grandTotal', 0))}"]
    lines.append("")
    lines.append("Разбивка:")
    for row in sorted(by_category, key=lambda r: r.get("total") or 0, reverse=True):
        category = row.get("category") or "некатегоризирано"
        lines.append(f"• {category}: {_fmt_amount(row.get('total', 0))} ({row.get('count', 0)} бр.)")
    return "\n".join(lines)


def query_node(state: AgentState) -> dict:
    question = (state.get("question") or "").strip()
    if not question:
        return {
            "answer": "No question provided.",
            "error": None,
            "messages": [{"role": "assistant", "content": "[query] empty question"}],
        }

    today = date.today().isoformat()

    # ── 1. Route ─────────────────────────────────────────────────────────────
    try:
        route_resp = _llm.invoke([
            SystemMessage(content=_ROUTE_SYSTEM.format(today=today)),
            HumanMessage(content=question),
        ])
        routing = json.loads(response_text(route_resp))
        intent = routing.get("intent", "search")
    except Exception as exc:
        return {
            "answer": None,
            "error": f"query routing failed: {exc}",
            "messages": [{"role": "assistant", "content": f"[query] routing error: {exc}"}],
        }

    # ── 2. Call facade ────────────────────────────────────────────────────────
    try:
        if intent == "summary":
            from_date = routing.get("from_date") or f"{today[:7]}-01"
            to_date   = routing.get("to_date")   or today
            data      = _summary(from_date, to_date)
            data_ctx  = f"Expense summary {from_date} → {to_date}"
        else:
            q     = routing.get("search_query") or question
            limit = int(routing.get("limit") or 10)
            data  = _search(q, limit)
            data_ctx = f"Top {limit} matches for '{q}'"
    except httpx.HTTPStatusError as exc:
        return {
            "answer": None,
            "error": f"facade returned {exc.response.status_code}: {exc.response.text[:200]}",
            "messages": [{"role": "assistant", "content": f"[query] HTTP {exc.response.status_code}"}],
        }
    except httpx.HTTPError as exc:
        return {
            "answer": None,
            "error": f"facade unreachable: {exc}",
            "messages": [{"role": "assistant", "content": f"[query] facade error: {exc}"}],
        }

    # ── 3. Format / synthesise ───────────────────────────────────────────────
    if intent == "summary":
        # Deterministic total + breakdown — no LLM round-trip needed, the
        # facade already returns exactly the numbers we want to show.
        answer = _format_summary_answer(data, from_date, to_date)
    else:
        data_text = json.dumps(data, default=str, ensure_ascii=False)
        try:
            synth_resp = _llm.invoke([
                SystemMessage(content=_SYNTH_SYSTEM),
                HumanMessage(content=f"Question: {question}\n\n{data_ctx}:\n{data_text}"),
            ])
            answer = response_text(synth_resp)
        except Exception as exc:
            # Degrade gracefully: return raw data if synthesis fails
            answer = f"(synthesis failed: {exc})\n\nRaw data: {data_text[:600]}"

    return {
        "answer": answer,
        "error": None,
        "messages": [{
            "role": "assistant",
            "content": f"[query] intent={intent} → {answer[:100]}",
        }],
    }
