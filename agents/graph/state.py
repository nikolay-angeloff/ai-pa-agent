from __future__ import annotations

import operator
from typing import Annotated, Optional
from typing_extensions import TypedDict


class AgentState(TypedDict):
    """
    Shared state threaded through every node in the expense agent graph.

    Document-processing flow:   classify → extract → (hitl_check) → done
    Query flow:                 query_node → done

    Only one flow is active per invocation; unused fields stay None.
    """

    # ── Document processing ────────────────────────────────────────────────
    # Provided by the caller (facade POST /agent/process).
    document_id: Optional[str]       # facade UUID of the Document row
    raw_storage_path: Optional[str]  # absolute path to raw file on disk
    subject: Optional[str]           # email subject line
    sender: Optional[str]            # email From header

    # Set by the classify node.
    document_type: Optional[str]     # "receipt" | "invoice" | "unknown"

    # Set by the extract node.
    extracted_fields: Optional[dict] # {merchant, amount, currency, date, …}
    confidence: Optional[float]      # 0.0 – 1.0; drives HITL threshold

    # Set by hitl_check; cleared once a human resumes the graph.
    hitl_required: bool              # True  → graph interrupts before extract
    hitl_approved: Optional[bool]    # written by the resume endpoint (Step 4)

    # ── Query flow ─────────────────────────────────────────────────────────
    # Provided by the caller (facade POST /agent/query).
    question: Optional[str]          # natural-language question from user
    answer: Optional[str]            # synthesized answer written by query node

    # ── Insight flow ──────────────────────────────────────────────────────
    # Set by insight_node; no caller-provided input (facade POST /agent/insights
    # takes no body — the node pulls its own window of expense history).
    insights: Optional[list]         # list[str] of Bulgarian-language insight messages

    # ── Shared ─────────────────────────────────────────────────────────────
    error: Optional[str]             # last error message; set on node failure

    # Accumulating list — operator.add means each node appends, never overwrites.
    # Used to carry LLM message history across nodes without clobbering.
    messages: Annotated[list, operator.add]
