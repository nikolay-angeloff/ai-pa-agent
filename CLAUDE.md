# CLAUDE.md — Personal Expense Agent

Guidance for Claude Code working in this repo. Read this before generating code.

## What this project is
Personal AI expense assistant. **Input:** Gmail (receipts, invoices, tickets, promos) → classify + extract + index. **Cockpit:** Viber/Telegram bot for natural-language queries ("expenses last 3 months?", "when does the washing machine warranty expire?"). Non-commercial portfolio/learning project.

## Golden rules
1. **Use Context7 for all library/API docs, setup and config steps** (Spring AI, Spring Cloud Gateway, LangGraph, langchain-aws, Qdrant client, Viber/Telegram SDKs). Training data is outdated for these — they moved fast around/after their 1.0 releases. Never generate Spring AI / LangGraph config from memory; fetch current docs first.
2. **Build one service (or one agent) at a time, test it, then move on.** Do NOT scaffold every service at once. Follow the phase plan below.
3. **Explain non-obvious decisions in comments** (state reducers, checkpointing, HITL interrupts, vector config). The author must be able to defend every choice on a whiteboard.

## Architecture (respect these boundaries)
- **Gateway** (Spring Cloud Gateway): single entry point, JWT auth, routing, rate limit. No business logic.
- **Facade** (Spring Boot + Spring AI): expense domain, Postgres CRUD, **the ONLY writer of the vector store and Postgres**, embeddings, exposes tools (`/tools/search`, `/tools/aggregate`, `/tools/document/{id}`) for the agents.
- **Agent svc** (Python + LangGraph): orchestration only (classify → extract → query → insight). Calls LLM. Calls facade tools for retrieval — **never writes to the vector store or DB directly.**
- **Webhook svc** (Node.js): Viber/Telegram webhook receiver + WebSocket push to dashboard. Thin.
- **Dashboard** (Angular): expenses, charts, alerts, HITL approvals.

**Single-writer rule:** only the facade persists. Agents orchestrate and request data via facade tools. This avoids duplicating persistence logic across languages. Do not violate it.

## Service contracts (define via OpenAPI before coding a service)
- Gateway → Facade: REST, JWT in header.
- Facade → Agent svc: `POST /agent/process` (a document), `POST /agent/query` (a question). Versioned JSON.
- Agent svc → Facade tools: `POST /tools/search`, `POST /tools/aggregate`, `GET /tools/document/{id}`.
- Webhook → Gateway: normalize Viber/Telegram payload to `{ userId, text }` → facade `/query`.

## Tech stack (pin versions via Context7 at setup)
- Java 21, Spring Boot, Spring Cloud Gateway, Spring AI
- Python 3.12, LangGraph, langchain-aws (for Bedrock on AWS)
- Node.js 20 (webhook)
- Angular (dashboard)
- Postgres, Qdrant (local) / S3 Vectors (AWS)

## Environments (config, not code)
- **local:** LLM via OpenAI/Anthropic API key; vector store = Qdrant container (with a Docker volume — data must survive restarts). Spring profile `local`.
- **aws:** LLM via Bedrock (Spring AI profile + langchain-aws); vector store = S3 Vectors (or Qdrant + EBS). Spring profile `aws`.
- Abstract the vector store behind Spring AI's `VectorStore` interface so Qdrant/S3 swap by profile.
- ⚠️ Verify with Context7 whether the Spring AI S3 Vectors starter is a stable release or snapshot. If bleeding-edge, use Qdrant+volume on both envs first; add S3 Vectors as a later phase.

## Secrets
- API keys and credentials only in `.env` (local) / AWS Secrets Manager (cloud). **Never** hardcode secrets or commit `.env`. `.env.example` is the committed template.

## Agent patterns to implement explicitly (LangGraph)
- Shared graph **state** (document, extracted fields, confidence, answer).
- **HITL interrupt** when extraction confidence is low → wait for dashboard approval.
- **Checkpointing** so an interrupted flow resumes.
- **Retry / graceful degradation** — on LLM/tool failure, escalate, don't crash.
- **Model tiering** — cheap model for classify/routing, stronger for extraction/query.
- **Observability** — LangSmith tracing + structured logs.

## Phase plan (do in order; each phase must produce a working thing)
- **Phase 0** — Skeleton: docker-compose up with all services as hello-world; Gateway routes to facade; Postgres + Qdrant (with volume) come up healthy.
- **Phase 1** — Core vertical slice, no agents yet: Gmail ingestion (manual trigger) → facade does simple vision extraction (receipts/invoices only) → store Postgres + Qdrant → facade REST endpoint "how much did I spend in period". Local, API key only.
- **Phase 2** — LangGraph agents: move classify/extract/query into a Python graph (supervisor, state, HITL, checkpointing). Facade calls agent svc. LangSmith tracing.
- **Phase 3** — Cockpit: Node webhook + Telegram bot (Viber if HTTPS/public account is available; else Telegram). NL query → agent → answer. WebSocket to dashboard.
- **Phase 4** — Dashboard (Angular) + Insight agent (scheduled: renewals, expiring warranties/promos, anomalies → push).
- **Phase 5** — Cloud: AWS profile (Bedrock + S3 Vectors), ECS Fargate deploy via CDK. Two live deployment configs.
- **Phase 6 (optional)** — Second agent system with CrewAI sharing the same facade + data; README comparison LangGraph vs CrewAI.

**Discipline:** don't start a phase before the previous one works end-to-end. Don't add document types before receipts work cleanly.

## Definition of done per service
- Builds in its own container, has a healthcheck, respects its contract, has a short README section, secrets externalized, no direct cross-service DB access.
