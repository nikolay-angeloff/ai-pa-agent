# infra/cdk — AWS profile (Phase 5)

**This is not an active deployment.** Everything under `infra/cdk/` is infrastructure-as-code
that exists in the repo, compiles, and synthesizes cleanly (`cdk synth` passes with zero AWS
credentials configured) — but nothing here has been deployed, and nothing here runs today. The
live, actually-running deployments are **local** (`docker compose up`) and **VPS**
(`docker-compose.prod.yml` + Caddy, see [`../DEPLOY.md`](../DEPLOY.md)). This directory is a
"someday" option: the code a real cloud cutover would start from, kept correct and current
rather than built the day it's needed.

The only differences between local/vps and aws are **config** (Spring profile, `LLM_PROVIDER`
env var, connection strings) — no application code branches on which environment it's running
in beyond that. See [`../CLAUDE.md`](../CLAUDE.md) for the engineering brief this was built
against.

## What's here

Five CDK stacks, fragmented by infrastructure type (not one monolith) so each is reviewable on
its own:

| Stack | File | Provisions |
|---|---|---|
| `ExpenseAgentNetwork` | `lib/network-stack.ts` | VPC (2 AZ, hardcoded — avoids a live `DescribeAvailabilityZones` lookup so `cdk synth` works offline), public + private subnets, 1 NAT gateway, ALB security group, one flat internal security group |
| `ExpenseAgentStorage` | `lib/storage-stack.ts` | RDS Postgres (`db.t4g.micro`, generated Secrets Manager credentials), EFS (2 access points: shared raw-attachment storage, Qdrant data), 5 ECR repos |
| `ExpenseAgentSecrets` | `lib/secrets-stack.ts` | JWT/events secrets (CDK-generated), Gmail/Telegram/LangSmith placeholders (filled by hand post-deploy) |
| `ExpenseAgentAi` | `lib/ai-stack.ts` | One IAM managed policy: `bedrock:InvokeModel*` scoped to the exact 3 model IDs the app uses — not a wildcard |
| `ExpenseAgentCompute` | `lib/compute-stack.ts` | ECS cluster (Fargate, Service Connect), ALB, all 6 services (gateway, facade, agents, webhook, dashboard, qdrant) |

`bin/app.ts` wires them together; `ComputeStack` depends on the other four via cross-stack
props, so `cdk deploy` (given multiple stack IDs) topologically orders the deploy automatically.

## Two decisions worth explaining

**ALB sits in front of Gateway — it does not replace it.** The ALB is a pure AWS-native L7/TLS
front door, the direct equivalent of Caddy's role on the VPS. Gateway keeps its job (JWT auth,
routing, rate limiting) per `CLAUDE.md`'s architecture boundary — "single entry point... no
business logic" describes what Gateway *is*, not a reason to move that logic into the ALB. The
ALB's listener rules are a 1:1 mirror of the root [`Caddyfile`](../Caddyfile): `/api/*`,
`/webhook/telegram`, `/ws` → gateway (path-based rule, priority 10); everything else (default
action, no conditions) → dashboard.

**EFS, not EBS, for Fargate's persistent storage.** Fargate can attach EBS volumes directly
since re:Invent 2023, but that path is single-task-attach — it can't do what this app needs,
which is facade (read-write) and agents (read-only) mounting the *same* raw-attachment volume
simultaneously. EFS supports that natively via access points, so it's used for both jobs:
facade/agents' shared raw storage, and Qdrant's own persistent volume. This superseded the
original Qdrant-on-EBS placeholder noted in `facade/src/main/resources/application-aws.yml`'s
TODO from Step 1 — that comment has been corrected to match.

**Service discovery** uses ECS Service Connect (not docker-compose's bare bridge-network DNS).
Every service is reachable at `<name>.expense.internal` from any other task — the one
systematic difference from local/vps, handled entirely by a small `dns()` helper in
`compute-stack.ts`, not by any application code change.

## local vs vps vs aws

| | local | vps | aws |
|---|---|---|---|
| LLM | OpenAI API key | OpenAI API key | Bedrock (Claude 3.5 Sonnet/Haiku, Titan Embed) |
| Vector store | Qdrant container + Docker volume | Qdrant container + Docker volume | Qdrant container on Fargate + EFS *(S3 Vectors deferred — see below)* |
| Postgres | container + Docker volume | container + Docker volume | RDS (`db.t4g.micro`) |
| Reverse proxy / TLS | — (localhost, no TLS) | Caddy (auto Let's Encrypt) | ALB, **HTTP only** (no ACM cert/domain in scope) |
| Secrets | `.env` file | `.env` file on the VPS | AWS Secrets Manager |
| Persistent files | bind mounts | bind mounts | EFS |
| Service discovery | docker-compose bridge network (`http://facade:8081`) | same | ECS Service Connect (`http://facade.expense.internal:8081`) |
| Spring profile | `local` | `local` + `docker-compose.prod.yml` overrides | `aws` (gateway stays on `local` — no AWS-specific config exists for it) |
| Python `LLM_PROVIDER` | `openai` (default) | `openai` (default) | `bedrock` |
| Deploy mechanism | `docker compose up` | `docker compose -f ... -f docker-compose.prod.yml up -d` | `cdk deploy` (manual, see below) |
| Status | **active, daily driver** | **active** | **not deployed** — code only |

**Why S3 Vectors isn't used yet:** `CLAUDE.md`'s original plan called for S3 Vectors on AWS
behind Spring AI's `VectorStore` interface. Checked via Context7: the
`spring-ai-starter-vector-store-s3` starter only exists in the Spring AI `v2.0.0-M6` milestone
docs, not in this project's pinned GA BOM (`v1.0.3`) or even `v1.1.8`. Building a "someday" prod
profile on a pre-release milestone artifact isn't worth it, so `aws` runs the same self-hosted
Qdrant as local/vps (on Fargate + EFS instead of a bind-mounted volume). Separately worth
knowing before that migration ever happens: facade's Qdrant integration today talks to Qdrant's
raw gRPC client directly (`QdrantConfig`, `QdrantCollectionInitializer`) — it isn't behind
Spring AI's `VectorStore` interface at all yet. Moving to S3 Vectors means that refactor first,
independent of any CDK change. Revisit once the starter ships GA.

## How this would actually be deployed

Nothing below has been run against a real AWS account as part of this work — this is the
runbook for whenever this stack is actually activated.

### 1. Prerequisites

- An AWS account, with the CLI configured (`aws configure` or `AWS_PROFILE`)
- Node.js 20+, Docker
- One-time per account/region: `npx cdk bootstrap aws://<account-id>/<region>` (run from
  `infra/cdk/`)

### 2. Provision everything except compute

```bash
cd infra/cdk
npm install
npx cdk deploy ExpenseAgentNetwork ExpenseAgentStorage ExpenseAgentSecrets ExpenseAgentAi
```

Deployed in this order (or together — CDK resolves the dependency graph) because the ECR repos
created by `ExpenseAgentStorage` need to exist *before* images can be pushed to them, and
`ExpenseAgentCompute` (deployed last) needs an image already sitting in each repo for its
Fargate services to start successfully — an ECS service with no valid image to pull will fail
its circuit-breaker rollback rather than come up.

### 3. Build and push the 5 images

```bash
aws ecr get-login-password --region <region> | \
  docker login --username AWS --password-stdin <account-id>.dkr.ecr.<region>.amazonaws.com

for svc in gateway facade agents webhook dashboard; do
  docker build -t expense-agent/$svc:latest ./$svc
  docker tag expense-agent/$svc:latest \
    <account-id>.dkr.ecr.<region>.amazonaws.com/expense-agent/$svc:latest
  docker push <account-id>.dkr.ecr.<region>.amazonaws.com/expense-agent/$svc:latest
done
```

(`qdrant` is pulled straight from Docker Hub by the task definition — no push needed for it.)

### 4. Fill in the placeholder secrets

`ExpenseAgentSecrets` creates the Gmail/Telegram/LangSmith secrets empty (JWT/events secrets are
auto-generated and need no action). Fill them the same way you'd fill `.env` locally — see
[`.env.example`](../.env.example) for what each field means:

```bash
aws secretsmanager put-secret-value --secret-id GmailOAuth --secret-string \
  '{"clientId":"...","clientSecret":"...","refreshToken":"..."}'

aws secretsmanager put-secret-value --secret-id TelegramBot --secret-string \
  '{"botToken":"...","webhookSecret":"...","chatId":"..."}'

aws secretsmanager put-secret-value --secret-id MiscSecrets --secret-string \
  '{"langsmithApiKey":"..."}'
```

(Actual secret IDs get a CDK-generated suffix — find the exact names with
`aws secretsmanager list-secrets --query "SecretList[].Name"` after step 2.)

### 5. Deploy compute

```bash
npx cdk deploy ExpenseAgentCompute
```

### 6. Verify

```bash
aws elbv2 describe-load-balancers --query "LoadBalancers[?contains(LoadBalancerName,'Alb')].DNSName"
curl -I http://<alb-dns-name>/          # dashboard, expect 200
curl -I http://<alb-dns-name>/api/health # gateway -> facade health, expect 200
```

### Redeploying a new image

```bash
docker build -t ... && docker push ...
npx cdk deploy ExpenseAgentCompute -c imageTag=<new-tag>
```

## Rough cost estimate

**Order-of-magnitude only** — check the
[AWS Pricing Calculator](https://calculator.aws) for current numbers before relying on this.
Assumes `eu-central-1`, the whole stack running 24/7 for a month, light traffic, and excludes
Bedrock token usage (pay-per-request, highly workload-dependent — see the Bedrock pricing page
for the 3 models this app calls).

| Item | Approx. monthly |
|---|---|
| Fargate (6 services, 1.75 vCPU / 3.5 GB combined) | ~$60 |
| NAT Gateway (1, + light data processing) | ~$35 |
| Application Load Balancer | ~$20 |
| RDS `db.t4g.micro` + 20 GB storage | ~$15 |
| EFS (small volumes) | ~$3 |
| Secrets Manager (5 secrets) | ~$2 |
| ECR storage, CloudWatch Logs | ~$3 |
| **Total (excl. Bedrock usage, data transfer)** | **~$140/month** |

The single NAT gateway and ALB are close to half the bill for what is, traffic-wise, a
single-user portfolio project — the honest reason this isn't the active deployment: the $6/month
VPS does the same job for this project's actual load.

## What's deliberately not done here

This is a skeleton sized to prove the architecture, not to hold real financial data. Before ever
pointing this at real expenses:

- **HTTPS** — the ALB listener is HTTP-only. Needs an ACM certificate + a real domain
  (Route 53 record) + an HTTP→HTTPS redirect.
- **RDS/EFS retention** — both are `RemovalPolicy.DESTROY` with `deleteAutomatedBackups: true`,
  deliberately, so a test `cdk destroy` leaves nothing orphaned/billing behind. Flip both to
  `RETAIN`, enable RDS automated backups, and add an EFS backup plan before this holds anything
  real.
- **Security groups** — one flat `internalSecurityGroup` (self-referencing all-traffic) shared
  by every Fargate task, RDS, and the EFS mount targets, mirroring docker-compose's flat bridge
  network today. Fine for a single-tenant skeleton; a real hardening pass would split this into
  a per-service SG matrix (only facade↔RDS, only facade+agents↔EFS, etc.).
- **Multi-AZ RDS, ECS auto-scaling, container-level health checks on the 4 non-ALB'd
  services** (facade, agents, webhook, qdrant) — currently only the 2 ALB-fronted services
  (gateway, dashboard) get target-group health checks; the rest rely solely on the ECS
  deployment circuit breaker to catch a crash-looping task, not a live liveness probe.
- **S3 Vectors** — see above; blocked on both a GA Spring AI starter and a facade-side
  `VectorStore` refactor, neither in scope here.
- **EFS access point uid/gid** — hardcoded to a generic `1000:1000` placeholder, not verified
  against each container image's actual runtime user. Check with `docker run --rm <image> id`
  per image before trusting file permissions on a real deploy.

## Validation performed (Step 5)

- `npx cdk synth` — all 5 stacks synthesize with zero AWS credentials configured (94 resources
  total: Network 27, Storage 15, Secrets 6, Ai 2, Compute 44).
- `npx tsc --noEmit` — clean type-check.
- `docker compose up -d --build` (local, all 7 containers) — full rebuild from current source,
  all services reach a healthy state; smoke-tested every service's health endpoint
  (`gateway:8080/actuator/health`, `facade:8081/health`, `gateway:8080/api/health`,
  `agents:8000/health`, `webhook:3000/health`, `dashboard:4200/`, `qdrant:6333/healthz`) — all
  200.
- `agents` test suite (`pytest`, 22 tests) — all pass against the rebuilt image.
- `docker-compose.yml`, `docker-compose.prod.yml`, `Caddyfile`, `DEPLOY.md` — confirmed zero
  diff from this work (`git diff --stat`); local/vps remain exactly as they were.
- No actual `cdk deploy` was run — that's the point of this being a "someday" variant, not an
  active one.

**A real local-breaking bug was caught and fixed by this validation pass:** Step 1 added the
Bedrock Spring AI starters (`spring-ai-starter-model-bedrock-converse`,
`spring-ai-starter-model-bedrock`) to facade's `build.gradle.kts` for the `aws` profile. Spring
Boot autoconfigures every starter's beans regardless of active profile, so with the OpenAI
starter already on the classpath too, three `EmbeddingModel` beans and two `ChatModel` beans
all became candidates with nothing to disambiguate — facade failed to start under the `local`
profile (and would have under `vps` too, since both use the same base `application.yml`) with
`required a single bean, but 3 were found`. This went undetected until this step because the
facade container wasn't rebuilt with `--build` after Step 1 until now. **Fix:** `application.yml`
now explicitly pins `spring.ai.model.chat: openai` / `spring.ai.model.embedding: openai` as the
base default; `application-aws.yml`'s existing overrides to `bedrock-converse`/`bedrock-titan`
are unchanged. Config-only, verified via the full rebuild + health-check sweep above.
