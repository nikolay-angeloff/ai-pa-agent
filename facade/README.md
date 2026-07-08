# facade

Spring Boot + Spring AI. The expense domain — **the only service that writes to Postgres or
Qdrant.** Everything else (agent svc, webhook) goes through this service's REST API.

## Endpoints

| Method + path | Purpose |
|---|---|
| `POST /ingest/run` | Pull new Gmail messages, store raw attachments, create `Document` rows |
| `POST /extract/run` | Run vision extraction on pending documents (calls agent svc `/agent/process`) |
| `GET /documents/awaiting-review` | Documents where extraction confidence triggered HITL |
| `POST /documents/{id}/approve` | Resume a paused HITL graph, persist the (possibly corrected) expense |
| `POST /documents/{id}/reject` | Resume rejecting; document stays unpersisted |
| `POST /expenses/ingest` | Direct expense persistence (used by the approve flow) |
| `GET /expenses` | Paginated listing for the dashboard table |
| `GET /expenses/summary` | Totals by category for a date range |
| `GET /expenses/search` | Semantic search over Qdrant |
| `POST /expenses/query` | Natural-language question → delegates to agent svc `/agent/query` |
| `POST /insights/run` | Manual trigger for the anomaly scan (`InsightScheduler` also calls this on a cron) |

## Key packages

- `document/`, `extraction/` — Gmail ingestion, vision extraction, HITL review flow.
- `expense/` — persisted expense CRUD, summary/search queries, vector store writes.
- `insight/` — `InsightService` (triggers agent svc, fans out to webhook), `InsightScheduler`
  (`app.insight.cron`, default daily 08:00), `InsightController` (manual trigger).
- `agent/` — `AgentClient`, thin HTTP wrapper around the agent svc's `/agent/*` endpoints.

## Config

`src/main/resources/application.yml`. Notable: `app.insight.cron` (overridable via
`INSIGHT_CRON`), `qdrant.host/port`, `spring.ai.openai.*` (embeddings + chat model).

## Run standalone

Needs Postgres + Qdrant reachable (see root `docker-compose.yml` for the easiest way to get
those up), plus `SPRING_DATASOURCE_*`, `OPENAI_API_KEY` in the environment.

```bash
./gradlew bootRun
```

## Tests

```bash
./gradlew test
```
