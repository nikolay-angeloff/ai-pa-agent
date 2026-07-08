"""
hitl_node — pauses the graph when extraction confidence is below threshold.

interrupt() suspends execution here; on resume it returns whatever value
was passed via Command(resume=...) from the /agent/resume endpoint.

Keeping this in its own node (not inside extract_node) means extract_node
only ever calls the LLM once — the second graph invocation on resume
re-enters at hitl_node, not at extract_node.

Expected resume payload:
  {"approved": true}                          — accept extracted_fields as-is
  {"approved": true, "fields": {...}}         — accept with human corrections
  {"approved": false}                         — reject; facade should skip persistence
"""

from langgraph.types import interrupt

from graph.state import AgentState


def hitl_node(state: AgentState) -> dict:
    # Suspend here. The interrupt value is surfaced to the dashboard reviewer.
    human_review = interrupt({
        "document_id":      state.get("document_id"),
        "extracted_fields": state.get("extracted_fields"),
        "confidence":       state.get("confidence"),
    })

    # Reached only after Command(resume=...) is sent.
    approved = human_review.get("approved", False)

    if approved:
        fields = human_review.get("fields") or state.get("extracted_fields")
        return {
            "extracted_fields": fields,
            "confidence":       1.0,   # human-verified
            "hitl_approved":    True,
            "hitl_required":    False,
            "messages": [{"role": "assistant", "content": "[hitl] approved by human"}],
        }

    return {
        "hitl_approved": False,
        "hitl_required": False,
        "messages": [{"role": "assistant", "content": "[hitl] rejected by human"}],
    }
