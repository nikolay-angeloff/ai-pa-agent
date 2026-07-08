"""
insight_node — anomaly detection over the expense history facade already
persisted (Phase 4 Step 3). No LLM call: outlier detection is arithmetic
(z-score against each merchant's own history, plus a week-over-trailing-
average spend check), and templated Bulgarian text is more reliable than a
synthesis call for something this structured — same reasoning query.py's
_format_summary_answer uses to skip the LLM for summaries.

Facade has no /tools/* API (see CLAUDE.md's aspirational contract) — the
established pattern, set by query_node, is to call facade's real domain
endpoints directly over httpx. This node follows the same pattern.
"""

import os
import statistics
from datetime import date, timedelta

import httpx

from graph.state import AgentState

_FACADE_URL = os.environ.get("FACADE_URL", "http://facade:8081")

_LOOKBACK_DAYS = 90       # history window used to build each merchant's baseline
_RECENT_DAYS = 7          # window checked for anomalies
_MERCHANT_Z_THRESHOLD = 2.0
_SPEND_SPIKE_RATIO = 1.5


def _list_expenses(from_date: str, to_date: str) -> list[dict]:
    resp = httpx.get(
        f"{_FACADE_URL}/expenses",
        params={"from": from_date, "to": to_date, "page": 0, "size": 1000},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def _merchant_outliers(expenses: list[dict], recent_cutoff: date) -> list[str]:
    """Flag recent expenses that are a z-score outlier against that merchant's
    own history. Requires >=3 prior points — not enough signal below that."""
    by_merchant: dict[str, list[float]] = {}
    for e in expenses:
        amount = e.get("amount")
        if amount is None:
            continue
        by_merchant.setdefault(e.get("merchant") or "Unknown", []).append(float(amount))

    insights = []
    for e in expenses:
        expense_date = date.fromisoformat(e["expenseDate"])
        if expense_date < recent_cutoff:
            continue
        amount = e.get("amount")
        merchant = e.get("merchant") or "Unknown"
        history = by_merchant.get(merchant, [])
        if amount is None or len(history) < 3:
            continue
        mean = statistics.mean(history)
        stdev = statistics.pstdev(history)
        if stdev == 0:
            continue
        z = (float(amount) - mean) / stdev
        if z >= _MERCHANT_Z_THRESHOLD:
            insights.append(
                f"⚠️ {merchant}: {float(amount):.2f} {e.get('currency') or ''} "
                f"на {expense_date.isoformat()} е необичайно високо спрямо обичайните "
                f"ти разходи там (средно {mean:.2f})."
            )
    return insights


def _spend_spike(expenses: list[dict], today: date) -> list[str]:
    """Flag if total spend in the last _RECENT_DAYS is well above the average
    weekly spend over the trailing baseline window."""
    recent_cutoff = today - timedelta(days=_RECENT_DAYS)
    baseline_cutoff = today - timedelta(days=_LOOKBACK_DAYS)

    recent_total = sum(
        float(e["amount"]) for e in expenses
        if e.get("amount") is not None and date.fromisoformat(e["expenseDate"]) >= recent_cutoff
    )
    baseline_total = sum(
        float(e["amount"]) for e in expenses
        if e.get("amount") is not None
        and baseline_cutoff <= date.fromisoformat(e["expenseDate"]) < recent_cutoff
    )
    weeks_in_baseline = max((recent_cutoff - baseline_cutoff).days / 7, 1)
    avg_weekly = baseline_total / weeks_in_baseline

    if avg_weekly > 0 and recent_total >= avg_weekly * _SPEND_SPIKE_RATIO:
        return [
            f"\U0001F4C8 Похарчи {recent_total:.2f} през последните {_RECENT_DAYS} дни — "
            f"около {recent_total / avg_weekly:.1f}x над обичайното си седмично ниво "
            f"({avg_weekly:.2f})."
        ]
    return []


def insight_node(state: AgentState) -> dict:
    today = date.today()
    from_date = (today - timedelta(days=_LOOKBACK_DAYS)).isoformat()
    to_date = today.isoformat()

    try:
        expenses = _list_expenses(from_date, to_date)
    except httpx.HTTPError as exc:
        return {
            "insights": None,
            "error": f"insight: facade unreachable: {exc}",
            "messages": [{"role": "assistant", "content": f"[insight] facade error: {exc}"}],
        }

    recent_cutoff = today - timedelta(days=_RECENT_DAYS)
    insights = _merchant_outliers(expenses, recent_cutoff) + _spend_spike(expenses, today)

    return {
        "insights": insights,
        "error": None,
        "messages": [{"role": "assistant", "content": f"[insight] found {len(insights)} insight(s)"}],
    }
