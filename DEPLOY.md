# Deploy runbook (VPS)

Production counterpart to the root `README.md`'s local-dev quickstart. Walks a clean VPS through
first deploy; the sections after the walkthrough go deeper on the *why* behind each piece.

## Prerequisites

- A VPS running a recent Ubuntu/Debian (commands below assume `apt`).
- A domain name with an A (and AAAA, if the VPS has IPv6) record pointed at the VPS's public IP
  — required for Caddy's automatic HTTPS (see "Reverse proxy, HTTPS, and network isolation"
  below). Point the DNS record *before* starting the stack; Let's Encrypt's HTTP-01 challenge
  needs it resolvable when Caddy first requests a cert.
- SSH access with a non-root sudo user.

**Sizing.** Realistically **8 GB RAM minimum**. Two JVM services (facade: Spring Boot + Spring
AI, gateway: Spring Cloud Gateway) each want headroom beyond their working set for JIT/GC, on top
of Postgres, Qdrant, and the Python/Node services all running concurrently on the same box:

| Service | Rough working set |
|---|---|
| facade (JVM) | 512 MB – 1 GB |
| gateway (JVM) | 256 – 512 MB |
| agents (Python/FastAPI) | 256 – 512 MB |
| webhook (Node) | 128 – 256 MB |
| dashboard | 128 – 256 MB |
| postgres | 256 – 512 MB (grows with data) |
| qdrant | 256 MB+ (grows with vector count) |
| caddy | ~50 MB |
| OS + Docker overhead | ~1 GB |

That totals comfortably under 8 GB even at the high end, but JVM containers without a memory
cap tend to grab more than their working set for GC headroom — 8 GB gives room for that without
tuning `-Xmx` per service. **2–4 vCPUs** — nothing here is CPU-bound except LLM calls, which are
mostly waiting on the network, not the VPS's CPU.

## 1. Install Docker

```bash
# Docker's official GPG key + apt repo
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# run docker without sudo (log out/in once for the group change to take effect)
sudo usermod -aG docker $USER
```

Then enable Docker on boot — see "Auto-start after reboot" below.

## 2. Clone and configure

```bash
git clone <this-repo-url> ai-pa-agent
cd ai-pa-agent
cp .env.example .env
chmod 600 .env
```

Fill in `.env`: at minimum `POSTGRES_*`, `OPENAI_API_KEY`, `JWT_SECRET`, and (for this step)
`DOMAIN` + `ACME_EMAIL`. See the root `README.md`'s "Environment variables" section for the
full list — everything under "Cockpit channels" is optional.

## 3. Firewall

Only SSH, HTTP, and HTTPS need to reach the box from the internet — everything else runs behind
Caddy on the internal Docker network (see "Reverse proxy..." below).

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

**Docker and `ufw` don't compose the way you'd expect.** Docker manipulates `iptables`
directly and routes published container ports through the `nat` table — *before* traffic
reaches the `INPUT` chain `ufw` filters on. In practice this means a `docker run -p` or a
compose `ports:` entry is reachable from the internet **even if `ufw` shows it denied**.
Today this isn't a live risk: `caddy` is the only service in `docker-compose.prod.yml` that
publishes a port, and it publishes exactly the ports (`80`/`443`) `ufw` already allows. But if
a port ever gets published on another service later, `ufw` alone won't stop it — the fix is a
rule on Docker's own `DOCKER-USER` chain, not `ufw`:

```bash
# example: only needed if you ever publish a port you want ufw-style restriction on
sudo iptables -I DOCKER-USER -p tcp --dport <port> -j DROP
```

Worth knowing about now, before it's a 2am surprise, even though nothing in this stack
triggers it today.

## 4. Bring the stack up

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

First boot takes a minute or two longer than usual — Caddy is requesting a Let's Encrypt cert
for `DOMAIN` on top of the usual image builds.

## 5. Verify

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps    # all services "Up", postgres/qdrant "healthy"
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs caddy --tail 30   # confirm cert obtained, no ACME errors
curl -I https://$DOMAIN/                 # dashboard, expect 200
curl -I https://$DOMAIN/api/health       # gateway → facade health, expect 200
```

Then confirm the restart policy actually landed on the running containers and that a reboot
would bring everything back — see "Auto-start after reboot" below.

## Backups

Not covered by this pass — deliberately deferred. When added, a cron-driven `pg_dump` and a
Qdrant snapshot script will live in `scripts/`, on a schedule set up alongside this runbook.
Until then, `pgdata`/`qdrant_data` are only as durable as the VPS's own disk — there is no
off-box copy of this data yet.

## Auto-start after reboot

Two independent layers have to survive a VPS reboot for the stack to come back on its own:

1. **Docker itself must start on boot.** A fresh `apt`/`dnf` install of Docker does not enable
   the systemd service by default on every distro — check and enable explicitly:

   ```bash
   sudo systemctl enable docker
   sudo systemctl status docker   # confirm "enabled" and "active (running)"
   ```

2. **Every container must restart when Docker (re)starts.** This is what `restart: unless-stopped`
   on each service in `docker-compose.yml` does — Docker's own restart policy, independent of
   `docker compose up` ever running again manually. `unless-stopped` restarts a container
   automatically on daemon/host restart *unless* someone explicitly ran `docker compose stop` on
   it before the reboot (as opposed to `always`, which would even restart a container you
   deliberately stopped — not what we want for a box you might SSH into and pause something on).

   Together: VPS reboots → systemd starts `docker.service` (step 1) → Docker's restart manager
   sees every `expense-agent-*` container was running and marked `unless-stopped`, and starts them
   all — no cron job, no manual `docker compose up` needed after a reboot.

   Verify the policy is actually set (don't just trust the compose file — confirm the *running*
   containers have it, since it only applies on container creation):

   ```bash
   docker inspect --format '{{.Name}}: {{.HostConfig.RestartPolicy.Name}}' $(docker compose ps -q)
   # every line should read "unless-stopped"
   ```

## Reverse proxy, HTTPS, and network isolation

Three files work together, on top of the base `docker-compose.yml`:

- **`docker-compose.override.yml`** — auto-loaded by plain `docker compose up` (no `-f`
  flags). Re-publishes every host port the stack used to expose unconditionally
  (postgres `5434`, qdrant `6333`/`6334`, facade `8081`, agents `8000`, gateway `8080`,
  webhook `3000`, dashboard `4200`) — local dev is unaffected by anything below.
- **`docker-compose.prod.yml`** — VPS-only, never auto-loaded. Adds the `caddy` service
  and re-points the dashboard's browser-facing `API_BASE_URL`/`WS_BASE_URL` at the public
  domain instead of `localhost`.
- **`Caddyfile`** — the reverse-proxy config Caddy reads.

Deploy with both files listed **explicitly**:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Passing `-f` explicitly is what skips the auto-load of `docker-compose.override.yml` — so
none of its host-port publications reach the VPS. The base `docker-compose.yml` itself
publishes **no ports on any service except through Caddy**: Caddy is the only container
binding to the host's `80`/`443`, and it reaches everything else over the internal
`expense-net` Docker network by container name (`gateway:8080`, `dashboard:4200`).
Postgres, Qdrant, facade, and agents were never meant to be public and now can't be —
there's no host port to hit even if the firewall were misconfigured.

**Gateway stays the single public entry point**, per CLAUDE.md's architecture rule — Caddy
only ever talks to `gateway` and `dashboard`. Telegram's inbound webhook (`POST
/webhook/telegram`) and the dashboard's live-update socket (`GET /ws`) both need a public
path, but instead of exposing the `webhook` service directly, gateway proxies those two
specific paths onward to it (`gateway/src/main/resources/application.yml`:
`webhook-telegram`, `webhook-ws` routes). `POST /events/*` (facade → webhook, purely
internal) has no route through gateway and stays unreachable from outside the Docker
network — deliberately, since it has no strong auth of its own beyond the optional
`EVENTS_SECRET` header.

Caddy gets automatic Let's Encrypt HTTPS for free from the site address in the Caddyfile —
no cert config needed, as long as:
- `DOMAIN` (in `.env`) is a real domain name with an A/AAAA record pointing at the VPS's
  public IP — Let's Encrypt's HTTP-01 challenge needs to reach the box on port 80 to verify
  ownership.
- `ACME_EMAIL` (in `.env`) is a real address you can receive cert-expiry/problem notices at.
- Ports `80` and `443` are reachable from the internet (see the firewall section below).

Caddy's own certificate/account state lives in the `caddy_data` named volume (mounted at
`/data`) — same persistence guarantee as `pgdata`/`qdrant_data` from the section above.
Losing it just means re-issuing a cert on next start (rate-limited by Let's Encrypt at
~5/week per domain), not a security problem, but there's no reason to lose it either.

## Secrets

Every credential (`POSTGRES_PASSWORD`, `OPENAI_API_KEY`, `JWT_SECRET`, `TELEGRAM_BOT_TOKEN`,
`GMAIL_CLIENT_SECRET`, etc.) is read from `.env` via `${VAR}` substitution — none are
hardcoded in `docker-compose*.yml` or in application code. `.env` and `credentials.json`
(the Gmail OAuth client file used by `scripts/get_gmail_token.py`) are both git-ignored;
neither has ever been committed.

On the VPS, lock `.env` down to the deploying user only — it's the one file on the box that
holds every credential in plaintext:

```bash
chmod 600 .env
```

Nothing else is needed: `.env` is never baked into an image (compose reads it from disk at
`up` time), so there's no secret-bearing layer to worry about in `docker history` either.
