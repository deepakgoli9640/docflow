# Healthcare Payment Gateway (HPG)

A multi-tenant payment gateway that hospitals integrate with to collect the patient-responsibility
part of a bill. It handles payments, refunds, the ledger, notifications and reconciliation. It is
**not** a billing or insurance system.

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway · Spring Security / Authorization Server ·
PostgreSQL · Flyway · Kafka · Redis · Testcontainers · Docker Compose

> **Status: Phases 2–7 complete**: the full service architecture runs end to end, with tenant-isolation
> checks, PII-masked JSON logs, metrics, distributed tracing across HTTP and Kafka, dashboards and alerts.
>
> ```
> Hospital application ──> API Gateway (auth, routing, rate limiting)
>                            ├─> payment-service       create and manage payments
>                            ├─> refund-service        full and partial refunds
>                            ├─> transaction-service   transaction history, status, ledger
>                            └─> notification-service  hospital webhooks, patient emails
>                            └─> reconciliation-service ledger vs provider settlement (operations)
>        Kafka (payment, refund, ledger, merchant events) connects them asynchronously;
>        payment- and refund-service talk to the external payment provider (Razorpay).
> ```
>
> A **web dashboard** for hospitals and for your operations team runs on http://localhost:8090
> ([ui/dashboard](ui/dashboard/README.md)).
>
> The Razorpay adapters are tested against WireMock; they have **not yet run against the real sandbox**
> (no test keys), so local demos use a clearly labelled simulated provider. See
> [what is implemented](docs/README.md#implemented-vs-simulated) and the
> [implementation plan](docs/13-implementation-plan.md).

## Documentation

Start with [docs/README.md](docs/README.md). Architecture, lifecycles, data model, API contracts,
security, failure handling and every design decision (with alternatives) are documented there.

## Repository layout

```
pom.xml                      parent POM: versions and plugins only
platform/common/             Money, UUIDv7 ids, error codes, correlation ids, masking, AES-GCM (no Spring)
platform/web-support/        Servlet plumbing: Problem Details errors, correlation filter, JWT roles, paging
services/api-gateway/        Spring Cloud Gateway: routing, JWT validation, Redis rate limiting
platform/persistence/        Shared JPA plumbing: audited base entity, column encryption, UTC clock
platform/event-contracts/    Event envelope, payment event types, topic names
platform/messaging/          Transactional outbox + Kafka relay; consumer helpers (dedupe, retry, dead-letter topics)
services/merchant-service/   Hospital onboarding, API keys, configuration, OAuth2 token issuer
services/payment-service/    Payments, state machine, idempotency, Razorpay adapter, checkout, webhooks, recovery
services/refund-service/     Full/partial refunds, atomic refundable-amount reservation, refund webhooks, recovery
services/transaction-service/ Transaction history and status, double-entry ledger, summary reports
services/notification-service/ Signed hospital webhooks and patient emails, retried with backoff
services/reconciliation-service/ Ledger vs provider settlement: CSV/API reports, matching, discrepancy workflow
ui/dashboard/                Web dashboard for hospitals and operations (static files + nginx, no build step)
deploy/docker-compose.yml    Local environment, split into profiles
deploy/postgres/init/        One database + one locked-down user per service
deploy/env/.env.example      Every setting/secret the stack reads (copy via scripts/init-env.sh)
scripts/                     init-env.sh, smoke tests
samples/http/                Ready-to-run HTTP requests (IntelliJ / VS Code REST Client)
docs/                        Architecture documentation
```

## Prerequisites

- JDK 21 (`java -version`), Docker with Docker Compose v2, `openssl`; `curl` + `jq` for the smoke test.
- No local Maven needed — use the wrapper `./mvnw`.
- Windows: run the scripts from Git Bash or WSL.

## Quick start

```bash
# 1. Generate deploy/env/.env with random secrets (git-ignored)
./scripts/init-env.sh

# 2. Build and run all tests (unit + Testcontainers integration tests; Docker must be running)
./mvnw verify

# 3a. Run everything in Docker (~2.1 GB RAM in total, measured)
./mvnw -DskipTests package
docker compose -f deploy/docker-compose.yml --profile infra --profile core \
  --profile refund --profile transactions --profile notify --profile recon --profile dashboard up -d --build
#     add `--profile obs` (and HPG_TRACE_EXPORT=true) for Prometheus, Grafana and Jaeger (~170 MB more)
./scripts/seed-demo-data.sh       # optional: demo data + sign-in details for the dashboard

# 3b. ...or only infrastructure in Docker and the service from your IDE / terminal
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl services/merchant-service spring-boot:run -Dspring-boot.run.profiles=local
./mvnw -pl services/api-gateway      spring-boot:run -Dspring-boot.run.profiles=local
#    (in the IDE: run MerchantServiceApplication / ApiGatewayApplication with active profile "local")

# 4. Exercise the flows through the gateway (uses the SIMULATED provider by default)
./scripts/smoke-test-phase2.sh
./scripts/smoke-test-phase3.sh
./scripts/smoke-test-phase4.sh    # payment -> Kafka -> refund, transactions, notifications
./scripts/smoke-test-phase6.sh    # ledger -> reconciliation against a settlement CSV
./scripts/smoke-test-phase7.sh    # tenant isolation, role matrix, PII-free logs with trace ids

# Stop (add -v to also delete the database volume)
docker compose -f deploy/docker-compose.yml --profile '*' down
```

| URL | What |
|---|---|
| http://localhost:8080 | API gateway (the only entry point clients use) |
| http://localhost:8090 | Dashboard for hospitals and operations |
| http://localhost:8081/swagger-ui.html | merchant-service OpenAPI / Swagger UI |
| http://localhost:8082/swagger-ui.html | payment-service OpenAPI / Swagger UI |
| http://localhost:8083/swagger-ui.html | refund-service OpenAPI / Swagger UI |
| http://localhost:8084/swagger-ui.html | transaction-service OpenAPI / Swagger UI |
| http://localhost:8085/swagger-ui.html | notification-service OpenAPI / Swagger UI |
| http://localhost:8086/swagger-ui.html | reconciliation-service OpenAPI / Swagger UI |
| http://localhost:8025 | Mailpit: every email the system sends locally |
| http://localhost:3000 | Grafana (`obs` profile): payments, provider, event pipeline, recon & ledger dashboards |
| http://localhost:9090 | Prometheus (`obs` profile): targets and alert rules |
| http://localhost:16686 | Jaeger (`obs` profile, `HPG_TRACE_EXPORT=true`): traces across gateway, services and Kafka |
| http://localhost:8080/checkout/{token} | Hosted checkout page (the `checkoutUrl` of a payment) |

Run a single module's tests: `./mvnw -pl services/merchant-service -am verify`.
Unit tests only (no Docker): `./mvnw test`.

## Try it by hand

```bash
GW=http://localhost:8080
# Register (public) - the response contains an API key secret and webhook secret, shown once
curl -s -X POST $GW/api/v1/merchants -H 'Content-Type: application/json' \
  -d '{"legalName":"City Hospital Pvt Ltd","displayName":"City Hospital","contactEmail":"billing@city.example"}' | jq

# Exchange the key for a 15-minute access token
TOKEN=$(curl -s -u "<keyId>:<keySecret>" -d grant_type=client_credentials $GW/oauth2/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" $GW/api/v1/merchants/me | jq
```

More requests: [samples/http/merchant.http](samples/http/merchant.http),
[samples/http/payment.http](samples/http/payment.http),
[samples/http/refund-transaction-notification.http](samples/http/refund-transaction-notification.http).

### Payment provider

`deploy/env/.env` → `HPG_PAYMENT_PROVIDER=simulated` (default from `init-env.sh`) runs an in-memory
**simulated** provider: no money moves, the checkout page says so. To use Razorpay, set
`HPG_PAYMENT_PROVIDER=razorpay` and the three `RAZORPAY_*` **test-mode** values, then follow
[docs/razorpay-sandbox-checklist.md](docs/razorpay-sandbox-checklist.md). If you created your `.env`
during Phase 2, add the line `HPG_PAYMENT_PROVIDER=simulated` to it (or re-run `init-env.sh --force`).
