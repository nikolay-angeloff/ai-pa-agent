"""
extract_node — stronger model (gpt-5.5, or Claude on Bedrock for the aws
profile) reads the raw file and returns structured extracted_fields +
confidence.

PDF and images are base64-encoded and sent as content blocks — no
server-side PDF parsing needed, leverages the model's native document
understanding. _file_content_block branches on LLM_PROVIDER because the
two providers' converters expect different block shapes for the same
data: OpenAI/langchain-openai accepts LangChain's newer standard
{"type": "file"/"image", "base64": ..., "mime_type": ...} shape unchanged,
but langchain-aws's Bedrock Converse block converter (verified against its
source, not memory) wants camelCase "mimeType" for file blocks and its own
native {"type": "image", "source": {"type": "base64", "media_type": ...,
"data": ...}} shape for images — passing the OpenAI-shaped blocks straight
through would silently mis-parse on Bedrock.
"""

import base64
import json
import mimetypes
import os

from langchain_core.messages import HumanMessage, SystemMessage

from graph.llm import PROVIDER, build_chat_model, response_text
from graph.state import AgentState

CONFIDENCE_THRESHOLD = 0.75  # below this → HITL interrupt (Step 4)

# strong tier: gpt-5.5 (local/vps, default) or Claude 3.5 Sonnet on Bedrock (aws
# profile) — see graph/llm.py. Worth the cost for accurate structured extraction
# on both providers.
_llm = build_chat_model("strong", max_tokens=1024, temperature=0)

_SYSTEM = """You extract expense fields from receipts and invoices.
Reply with ONLY valid JSON — no markdown, no explanation:
{
  "merchant": "string or null",
  "amount": number or null,
  "currency": "ISO 4217 code or null",
  "date": "YYYY-MM-DD or null",
  "confidence": 0.0 to 1.0
}

confidence guide:
- 1.0  all four fields clearly visible and unambiguous
- 0.8  three fields found or one is slightly ambiguous
- 0.5  two or fewer fields found
- 0.3  document is unreadable, corrupted, or irrelevant
"""

_SUPPORTED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/gif", "image/webp"}


def _file_content_block(path: str) -> dict:
    """Return a content block for the raw file, shaped for the active provider
    (see module docstring for why the shapes differ)."""
    mime_type, _ = mimetypes.guess_type(path)
    if mime_type is None:
        mime_type = "application/octet-stream"

    if mime_type == "application/pdf":
        with open(path, "rb") as fh:
            data = base64.standard_b64encode(fh.read()).decode()
        if PROVIDER == "bedrock":
            return {
                "type": "file",
                "base64": data,
                "mimeType": "application/pdf",
                "name": os.path.basename(path),
            }
        return {"type": "file", "base64": data, "mime_type": "application/pdf"}

    if mime_type in _SUPPORTED_IMAGE_TYPES:
        with open(path, "rb") as fh:
            data = base64.standard_b64encode(fh.read()).decode()
        if PROVIDER == "bedrock":
            return {
                "type": "image",
                "source": {"type": "base64", "media_type": mime_type, "data": data},
            }
        return {"type": "image", "base64": data, "mime_type": mime_type}

    # Plain text fallback (.txt, .eml, unknown) — identical on both providers.
    with open(path, "r", errors="replace") as fh:
        text = fh.read(8000)
    return {"type": "text", "text": text}


def extract_node(state: AgentState) -> dict:
    path = state.get("raw_storage_path")

    if not path or not os.path.exists(path):
        return {
            "extracted_fields": {},
            "confidence": 0.0,
            "hitl_required": True,
            "error": f"extract: file not found: {path}",
            "messages": [{"role": "assistant", "content": "[extract] file not found"}],
        }

    try:
        content_block = _file_content_block(path)
        response = _llm.invoke([
            SystemMessage(content=_SYSTEM),
            HumanMessage(content=[
                {"type": "text", "text": "Extract expense fields from this document."},
                content_block,
            ]),
        ])
        parsed = json.loads(response_text(response))

        fields = {
            "merchant":  parsed.get("merchant"),
            "amount":    parsed.get("amount"),
            "currency":  parsed.get("currency"),
            "date":      parsed.get("date"),
        }
        confidence = float(parsed.get("confidence", 0.0))
        confidence = max(0.0, min(1.0, confidence))

    except Exception as exc:
        return {
            "extracted_fields": {},
            "confidence": 0.0,
            "hitl_required": True,
            "error": f"extract failed: {exc}",
            "messages": [{"role": "assistant", "content": f"[extract] error: {exc}"}],
        }

    return {
        "extracted_fields": fields,
        "confidence": confidence,
        "hitl_required": confidence < CONFIDENCE_THRESHOLD,
        "messages": [{
            "role": "assistant",
            "content": (
                f"[extract] merchant={fields['merchant']} "
                f"amount={fields['amount']} {fields['currency']} "
                f"date={fields['date']} conf={confidence:.2f}"
            ),
        }],
    }
