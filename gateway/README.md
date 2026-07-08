# gateway

Spring Cloud Gateway. Single entry point for the dashboard and webhook svc — no business logic
lives here.

- Route: `/api/**` → `${FACADE_URI}` with `StripPrefix=1` (`/api/health` → facade `/health`).
- CORS: allows `http://localhost:4200` (dashboard dev origin) explicitly, not `*`, since
  requests may carry a JWT header later.
- `JWT_SECRET` is read from env but **not enforced yet** — auth filter isn't implemented, only
  scaffolded for when Phase 5 needs it.

Run standalone (needs facade reachable at `FACADE_URI`, default `http://localhost:8081`):

```bash
./gradlew bootRun
```

Config: `src/main/resources/application.yml` (routes, CORS), `application-local.yml` (profile
overrides, currently empty besides a comment).
