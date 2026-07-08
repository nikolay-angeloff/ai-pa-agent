const http = require('http');
const express = require('express');
const { WebSocketServer } = require('ws');

const app = express();
const PORT = process.env.PORT || 3000;
// Optional — Telegram echoes this back in the X-Telegram-Bot-Api-Secret-Token
// header on every webhook call, so we can reject requests that didn't come
// from the webhook we registered via setWebhook. Skipped if unset.
const WEBHOOK_SECRET = process.env.TELEGRAM_WEBHOOK_SECRET;
const BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
// Routed through Gateway (not straight to facade) so the cockpit stays behind
// the same single entry point every other client goes through.
const GATEWAY_URL = process.env.GATEWAY_URL || 'http://gateway:8080';
// Optional — checked on POST /events/* so only facade (the single writer)
// can trigger a broadcast. Skipped if unset, same pattern as WEBHOOK_SECRET.
const EVENTS_SECRET = process.env.EVENTS_SECRET;
// Where scheduled insight pushes go — the private chat found via the webhook
// logs (chat.id from a real /start interaction), not the bot's own account id.
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID;

app.use(express.json());

app.get('/health', (_req, res) => {
  res.json({ status: 'ok' });
});

// ── WebSocket channel (Phase 4 prep) ─────────────────────────────────────
// No dashboard consumes this yet — this is just the channel + a real trigger
// (POST /events/expense-created, called by facade after it persists a new
// expense) so the plumbing is actually exercised end-to-end before Phase 4
// builds a UI on top of it.
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws) => {
  console.log('[ws] dashboard client connected —', wss.clients.size, 'total');
  ws.on('close', () => console.log('[ws] client disconnected —', wss.clients.size, 'total'));
});

function broadcast(event) {
  const payload = JSON.stringify(event);
  for (const client of wss.clients) {
    if (client.readyState === client.OPEN) client.send(payload);
  }
}

app.post('/events/expense-created', (req, res) => {
  if (EVENTS_SECRET && req.get('X-Internal-Secret') !== EVENTS_SECRET) {
    return res.sendStatus(401);
  }
  console.log('[ws] broadcasting expense_created:', req.body);
  broadcast({ type: 'expense_created', data: req.body, at: new Date().toISOString() });
  res.sendStatus(202);
});

// Called by facade's InsightScheduler (Phase 4 Insight agent) after a scan
// finds anomalies. Broadcasts to the dashboard WS and, if TELEGRAM_CHAT_ID is
// configured, pushes each insight as a Telegram message too — best-effort,
// same as the rest of this file's outbound calls.
app.post('/events/insight', (req, res) => {
  if (EVENTS_SECRET && req.get('X-Internal-Secret') !== EVENTS_SECRET) {
    return res.sendStatus(401);
  }
  const insights = Array.isArray(req.body?.insights) ? req.body.insights : [];
  console.log('[ws] broadcasting insight:', insights);
  broadcast({ type: 'insight', data: insights, at: new Date().toISOString() });
  res.sendStatus(202);

  if (TELEGRAM_CHAT_ID) {
    for (const text of insights) {
      sendTelegramMessage(TELEGRAM_CHAT_ID, text).catch((err) =>
        console.error('[telegram] insight push failed:', err),
      );
    }
  }
});

async function sendTelegramMessage(chatId, text) {
  const resp = await fetch(`https://api.telegram.org/bot${BOT_TOKEN}/sendMessage`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ chat_id: chatId, text }),
  });
  if (!resp.ok) {
    console.error('[telegram] sendMessage failed:', resp.status, await resp.text());
  }
}

// Gateway strips the /api prefix and forwards to facade's POST /expenses/query,
// which delegates to the agent svc's existing query_graph (Phase 2) — no new
// agent logic lives here, this is transport only.
async function answerQuery(userId, question) {
  try {
    const resp = await fetch(`${GATEWAY_URL}/api/expenses/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    });
    const data = await resp.json();
    const reply = data.error ? `Error: ${data.error}` : (data.answer || 'No answer.');
    await sendTelegramMessage(userId, reply);
  } catch (err) {
    console.error('[query] failed:', err);
    await sendTelegramMessage(userId, 'Something went wrong answering that — try again in a moment.');
  }
}

/**
 * Telegram POSTs a JSON-serialized Update here for every event the bot is
 * subscribed to. Must ack with 2xx quickly — Telegram retries (then drops)
 * webhooks that are slow or error out.
 */
app.post('/webhook/telegram', (req, res) => {
  if (WEBHOOK_SECRET && req.get('X-Telegram-Bot-Api-Secret-Token') !== WEBHOOK_SECRET) {
    return res.sendStatus(401);
  }

  const message = req.body?.message;
  if (!message?.text) {
    // Non-text updates (stickers, edits, joins, ...) — ack and ignore for now.
    return res.sendStatus(200);
  }

  // chat.id (not from.id) is what sendMessage needs to reply — for a private
  // chat with the bot the two are numerically the same, but chat.id is the
  // one that's actually correct to carry forward.
  const normalized = {
    userId: message.chat.id,
    text: message.text,
  };

  console.log('[telegram] normalized update:', normalized);

  // Ack immediately — Telegram retries webhooks that are slow or non-2xx,
  // and the agent query (LLM calls) can take a few seconds. The answer goes
  // back on a separate sendMessage call once it's ready, not in this response.
  res.sendStatus(200);

  if (normalized.text.startsWith('/start')) {
    sendTelegramMessage(normalized.userId, 'Hi! Ask me about your expenses, e.g. "how much did I spend last month?"')
      .catch((err) => console.error('[telegram] /start reply failed:', err));
    return;
  }

  answerQuery(normalized.userId, normalized.text)
    .catch((err) => console.error('[query] unhandled error:', err));
});

server.listen(PORT, () => {
  console.log(`webhook listening on :${PORT} (HTTP + WS at /ws)`);
});
