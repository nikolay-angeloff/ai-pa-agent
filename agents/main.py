import os
import uuid
from contextlib import ExitStack, asynccontextmanager

from fastapi import FastAPI, HTTPException
from langgraph.types import Command
from pydantic import BaseModel

from graph.builder import build_insight_graph, build_process_graph, build_query_graph

# Graphs are initialised in lifespan; None until startup completes.
process_graph = None
query_graph = None
insight_graph = None


# ── Lifespan ───────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Open the checkpointer once at startup and close it on shutdown.

    POSTGRES_URL present  → PostgresSaver  (durable, survives restarts).
    POSTGRES_URL absent   → InMemorySaver  (in-process, useful for unit tests).

    local/vps set POSTGRES_URL directly (one compose-interpolated string).
    The aws profile builds it from parts instead (POSTGRES_HOST/USER/PASSWORD/
    DB) because ECS task-definition `secrets` only inject one Secrets-Manager
    value per env var — there's no way to hand ECS a template to fill in, so
    the RDS-generated username/password (Secrets Manager) and host (plain,
    non-secret CFN output) arrive as separate env vars and get joined here
    instead of being composed into one plaintext DSN in the task definition.
    """
    global process_graph, query_graph, insight_graph

    postgres_url = os.environ.get("POSTGRES_URL")
    if not postgres_url and os.environ.get("POSTGRES_HOST"):
        from urllib.parse import quote

        user = quote(os.environ.get("POSTGRES_USER", ""), safe="")
        password = quote(os.environ.get("POSTGRES_PASSWORD", ""), safe="")
        host = os.environ["POSTGRES_HOST"]
        port = os.environ.get("POSTGRES_PORT", "5432")
        db = os.environ.get("POSTGRES_DB", "expense")
        postgres_url = f"postgresql://{user}:{password}@{host}:{port}/{db}"

    # ── LangSmith tracing ─────────────────────────────────────────────────────
    # Enabled automatically when LANGSMITH_API_KEY is set in the environment.
    # Both LANGSMITH_* (current SDK) and LANGCHAIN_* (legacy alias) env vars
    # are accepted — docker-compose sets both, so either SDK version works.
    tracing_on = os.environ.get("LANGSMITH_TRACING", "false").lower() == "true"
    project    = os.environ.get("LANGSMITH_PROJECT", "expense-agent")
    if tracing_on and os.environ.get("LANGSMITH_API_KEY"):
        print(f"[agents] LangSmith tracing ENABLED — project={project}", flush=True)
        print(f"[agents] View traces: https://smith.langchain.com → Projects → {project}", flush=True)
    else:
        print("[agents] LangSmith tracing DISABLED (set LANGSMITH_API_KEY + LANGSMITH_TRACING=true to enable)", flush=True)

    if postgres_url:
        from langgraph.checkpoint.postgres import PostgresSaver
        with ExitStack() as stack:
            checkpointer = stack.enter_context(
                PostgresSaver.from_conn_string(postgres_url)
            )
            # idempotent — creates checkpoint tables if they don't exist yet
            checkpointer.setup()
            process_graph = build_process_graph(checkpointer)
            query_graph = build_query_graph(checkpointer)
            insight_graph = build_insight_graph(checkpointer)
            yield
    else:
        from langgraph.checkpoint.memory import InMemorySaver
        checkpointer = InMemorySaver()
        process_graph = build_process_graph(checkpointer)
        query_graph = build_query_graph(checkpointer)
        insight_graph = build_insight_graph(checkpointer)
        yield


app = FastAPI(title="expense-agents", version="0.3.0", lifespan=lifespan)


# ── Request / Response models ──────────────────────────────────────────────

class ProcessRequest(BaseModel):
    document_id: str
    raw_storage_path: str
    subject: str | None = None
    sender: str | None = None


class ProcessResponse(BaseModel):
    thread_id: str
    document_type: str | None
    extracted_fields: dict | None
    confidence: float | None
    hitl_required: bool
    error: str | None


class ResumeRequest(BaseModel):
    approved: bool
    fields: dict | None = None  # human-corrected fields; None → keep extracted as-is


class QueryRequest(BaseModel):
    question: str


class QueryResponse(BaseModel):
    answer: str | None
    error: str | None


class InsightResponse(BaseModel):
    insights: list[str] | None
    error: str | None


# ── Endpoints ──────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "checkpointer": "postgres" if os.environ.get("POSTGRES_URL") else "memory"}


@app.post("/agent/process", response_model=ProcessResponse)
def process_document(req: ProcessRequest):
    """
    Facade calls this when a new document arrives.
    Each call gets its own thread_id so state is isolated per document.
    Returns hitl_required=True (+ thread_id) when the graph paused for review.
    """
    thread_id = str(uuid.uuid4())
    config = {"configurable": {"thread_id": thread_id}}

    initial_state = {
        "document_id":       req.document_id,
        "raw_storage_path":  req.raw_storage_path,
        "subject":           req.subject,
        "sender":            req.sender,
        "hitl_required":     False,
        "messages":          [],
    }

    result = process_graph.invoke(initial_state, config)

    return ProcessResponse(
        thread_id=thread_id,
        document_type=result.get("document_type"),
        extracted_fields=result.get("extracted_fields"),
        confidence=result.get("confidence"),
        hitl_required=result.get("hitl_required", False),
        error=result.get("error"),
    )


@app.post("/agent/resume/{thread_id}", response_model=ProcessResponse)
def resume_hitl(thread_id: str, req: ResumeRequest):
    """
    Resume a graph that paused at the HITL node.
    Pass approved=true to accept (with optional field corrections),
    or approved=false to reject (facade will skip persistence).
    """
    config = {"configurable": {"thread_id": thread_id}}
    try:
        result = process_graph.invoke(
            Command(resume={"approved": req.approved, "fields": req.fields}),
            config,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

    return ProcessResponse(
        thread_id=thread_id,
        document_type=result.get("document_type"),
        extracted_fields=result.get("extracted_fields"),
        confidence=result.get("confidence"),
        hitl_required=result.get("hitl_required", False),
        error=result.get("error"),
    )


@app.post("/agent/query", response_model=QueryResponse)
def query(req: QueryRequest):
    """Natural-language expense query."""
    thread_id = str(uuid.uuid4())
    config = {"configurable": {"thread_id": thread_id}}

    result = query_graph.invoke(
        {"question": req.question, "hitl_required": False, "messages": []},
        config,
    )

    return QueryResponse(
        answer=result.get("answer"),
        error=result.get("error"),
    )


@app.post("/agent/insights", response_model=InsightResponse)
def insights():
    """Scheduled anomaly scan — facade calls this on a cron, no request body."""
    thread_id = str(uuid.uuid4())
    config = {"configurable": {"thread_id": thread_id}}

    result = insight_graph.invoke(
        {"hitl_required": False, "messages": []},
        config,
    )

    return InsightResponse(
        insights=result.get("insights"),
        error=result.get("error"),
    )
