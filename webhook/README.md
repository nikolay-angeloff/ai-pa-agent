# webhook

Node.js 20 + Express + `ws`. Thin transport layer — normalizes Telegram updates and hosts the
dashboard's WebSocket channel. No business logic; every real decision is delegated to the
gateway/facade/agent svc.

## Endpoints

| Method + path | Purpose |
|---|---|
| `POST /webhook/telegram` | Telegram sends updates here. Acks immediately (2xx), answers async via `sendMessage` so slow LLM calls don't trigger Telegram's retry/drop behavior. |
| `POST /events/expense-created` | Called by facade after persisting a new expense; broadcasts to WS clients. |
| `POST /events/insight` | Called by facade's `InsightScheduler`/`InsightController`; broadcasts to WS clients and, if `TELEGRAM_CHAT_ID` is set, pushes each insight as a Telegram message. |
| `GET /ws` | WebSocket upgrade — dashboard connects here for live pushes. |

`POST /events/*` is optionally gated by `X-Internal-Secret` (`EVENTS_SECRET` env var) so only
facade can trigger a broadcast.

## Finding your `TELEGRAM_CHAT_ID`

Message your bot once, then `docker compose logs webhook` — look for
`[telegram] normalized update: { userId: ... }`. That `userId` is `chat.id`, which for a private
chat is your own numeric Telegram id. (Don't use the number from a `web.telegram.org` URL
fragment without checking — it can be the bot's own account id instead.)

## Run standalone

```bash
npm install
TELEGRAM_BOT_TOKEN=... GATEWAY_URL=http://localhost:8080 node index.js
```
