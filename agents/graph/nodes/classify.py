"""
classify_node — cheap model (gpt-5.4-nano) classifies a document
from its email subject, sender, and filename alone.

No file I/O here — the raw bytes stay on disk until extract_node needs them.
Model tiering: nano is fast and cheap for this binary routing decision.
"""

import json
import os

from langchain_core.messages import HumanMessage, SystemMessage

from graph.llm import build_chat_model, response_text
from graph.state import AgentState

# cheap tier: gpt-5.4-nano (local/vps, default) or Claude 3.5 Haiku on Bedrock
# (aws profile, LLM_PROVIDER=bedrock) — see graph/llm.py. Fast and cheap either
# way, perfect for this binary routing decision.
_llm = build_chat_model("cheap", max_tokens=256, temperature=0)

_SYSTEM = """You classify email attachments as expense documents.
Reply with ONLY valid JSON — no markdown, no explanation:
{"document_type": "receipt" | "invoice" | "unknown"}

Definitions:
- receipt  : retail/restaurant purchase confirmation, payment receipt, supermarket bill
- invoice  : business billing document requesting payment, utility bill, subscription invoice
- unknown  : promotional email, newsletter, boarding pass, travel ticket, warranty, anything else
"""


def classify_node(state: AgentState) -> dict:
    subject  = state.get("subject")  or "(no subject)"
    sender   = state.get("sender")   or "(unknown sender)"
    filename = os.path.basename(state.get("raw_storage_path") or "")

    user_msg = f"Subject: {subject}\nFrom: {sender}\nFilename: {filename}"

    try:
        response = _llm.invoke([
            SystemMessage(content=_SYSTEM),
            HumanMessage(content=user_msg),
        ])
        parsed   = json.loads(response_text(response))
        doc_type = parsed.get("document_type", "unknown")
        if doc_type not in ("receipt", "invoice", "unknown"):
            doc_type = "unknown"
    except Exception as exc:
        return {
            "document_type": "unknown",
            "error": f"classify failed: {exc}",
            "messages": [{"role": "assistant", "content": f"[classify] error: {exc}"}],
        }

    return {
        "document_type": doc_type,
        "messages": [{"role": "assistant", "content": f"[classify] → {doc_type}"}],
    }
