"""
Graph assembly.

Three build functions — all accept a checkpointer so main.py can swap
InMemorySaver (tests) for PostgresSaver (production) without touching
the graph topology.

process_graph  — classify → extract → hitl (if low confidence) → END
query_graph    — query_node → END
insight_graph  — insight_node → END
"""

from langgraph.graph import END, START, StateGraph

from graph.nodes.classify import classify_node
from graph.nodes.extract import extract_node
from graph.nodes.hitl import hitl_node
from graph.nodes.insight import insight_node
from graph.nodes.query import query_node
from graph.state import AgentState


def _route_after_classify(state: AgentState) -> str:
    """Only extract for receipt/invoice; skip unknown documents."""
    if state.get("document_type") in ("receipt", "invoice"):
        return "extract"
    return END


def _route_after_extract(state: AgentState) -> str:
    """Route to HITL when confidence is below threshold; otherwise done."""
    if state.get("hitl_required"):
        return "hitl"
    return END


def build_process_graph(checkpointer):
    """
    classify → [receipt|invoice] → extract → [low-conf] → hitl → END
             → [unknown]         → END              → [ok]    → END
    """
    g = StateGraph(AgentState)

    g.add_node("classify", classify_node)
    g.add_node("extract", extract_node)
    g.add_node("hitl", hitl_node)

    g.add_edge(START, "classify")
    g.add_conditional_edges(
        "classify",
        _route_after_classify,
        {"extract": "extract", END: END},
    )
    g.add_conditional_edges(
        "extract",
        _route_after_extract,
        {"hitl": "hitl", END: END},
    )
    g.add_edge("hitl", END)

    return g.compile(checkpointer=checkpointer)


def build_query_graph(checkpointer):
    """Natural-language query → answer."""
    g = StateGraph(AgentState)
    g.add_node("query", query_node)
    g.add_edge(START, "query")
    g.add_edge("query", END)
    return g.compile(checkpointer=checkpointer)


def build_insight_graph(checkpointer):
    """Scheduled anomaly scan → list of insight messages."""
    g = StateGraph(AgentState)
    g.add_node("insight", insight_node)
    g.add_edge(START, "insight")
    g.add_edge("insight", END)
    return g.compile(checkpointer=checkpointer)
