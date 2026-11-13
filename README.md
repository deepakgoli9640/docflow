# Healthcare Payment Gateway (HPG)

A multi-tenant payment gateway that hospitals integrate with to collect the patient-responsibility
part of a bill. It handles payments, refunds, the ledger, notifications and reconciliation. It is
**not** a billing or insurance system.

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway · Spring Security / Authorization Server ·
PostgreSQL · Flyway · Kafka (Phase 4) · Redis · Testcontainers · Docker Compose

> **Status: Phase 4 of 8 complete** — gateway, merchant-service, payment-service and
> notification-service, connected by Kafka through a transactional outbox, plus a **web console**
> for hospital staff and operations, built on the same public APIs. The Razorpay adapter is
> tested against WireMock; it has **not yet run against the real sandbox** (no test keys), and local
> demos use a clearly labelled simulated provider. Patient SMS is simulated; patient email is real SMTP. See [what is implemented](docs/README.md#implemented-vs-simulated) and the
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
platform/messaging/          Transactional outbox writer (Kafka relay in Phase 4)
services/merchant-service/   Hospital onboarding, API keys, configuration, OAuth2 token issuer
services/payment-service/    Payments, state machine, idempotency, Razorpay adapter, checkout, webhooks, recovery
services/notification-service/ Hospital webhooks (signed), patient email/SMS, delivery tracking, retries
services/console-service/    Web console (Thymeleaf): backend-for-frontend over the public APIs — no DB of its own
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

# 3a. Run everything in Docker (~1.5 GB RAM in total; use `clean package`, see note below)
./mvnw -DskipTests clean package
docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile notify up -d --build
#     add --profile ui for the web console (~250 MB more)

# 3b. ...or only infrastructure in Docker and the service from your IDE / terminal
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl services/merchant-service spring-boot:run -Dspring-boot.run.profiles=local
./mvnw -pl services/api-gateway      spring-boot:run -Dspring-boot.run.profiles=local
#    (in the IDE: run MerchantServiceApplication / ApiGatewayApplication with active profile "local")

# 4. Exercise the flows through the gateway (uses the SIMULATED provider by default)
./scripts/smoke-test-phase2.sh    # onboarding, tokens, configuration, suspension
./scripts/smoke-test-phase3.sh    # payments, idempotency, checkout, capture, cancel
./scripts/smoke-test-phase4.sh    # events: snapshot replication, signed webhook, patient email
./scripts/smoke-test-console.sh   # the web console: sign-in, roles, CSRF, create + pay a payment

# Stop (add -v to also delete the database volume)
docker compose -f deploy/docker-compose.yml --profile '*' down
```

| URL | What |
|---|---|
| http://localhost:8080 | API gateway (the only entry point clients use) |
| http://localhost:8081/swagger-ui.html | merchant-service OpenAPI / Swagger UI |
| http://localhost:8082/swagger-ui.html | payment-service OpenAPI / Swagger UI |
| http://localhost:8025 | Mailpit inbox — patient email, as actually sent over SMTP |
| http://localhost:8085/swagger-ui.html | notification-service (operations API) |
| http://localhost:8080/checkout/{token} | Hosted checkout page (the `checkoutUrl` of a payment) |
| http://localhost:8086 | **Web console** — hospital staff and operations ([docs/14](docs/14-console.md)) |

Run a single module's tests: `./mvnw -pl services/merchant-service -am verify` (the `-am` matters: it
rebuilds the shared `platform/*` modules, which a bare `-pl` would take from your local Maven
repository). Unit tests only (no Docker): `./mvnw test`.

**Always `clean package` before building images.** When only a `platform/*` module changes, Maven
leaves the services' fat jars untouched and the image ships stale shared code.

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
[samples/http/payment.http](samples/http/payment.http).

### Payment provider

`deploy/env/.env` → `HPG_PAYMENT_PROVIDER=simulated` (default from `init-env.sh`) runs an in-memory
**simulated** provider: no money moves, the checkout page says so. To use Razorpay, set
`HPG_PAYMENT_PROVIDER=razorpay` and the three `RAZORPAY_*` **test-mode** values, then follow
[docs/razorpay-sandbox-checklist.md](docs/razorpay-sandbox-checklist.md). If you created your `.env`
during Phase 2, add the line `HPG_PAYMENT_PROVIDER=simulated` to it (or re-run `init-env.sh --force`).

## Web console

`--profile ui` adds a web UI at **http://localhost:8086** — [docs/14-console.md](docs/14-console.md).

Hospital staff sign in with an **API key id and secret** and get their payments, their configuration
and their keys; operations sign in with `HPG_OPS_CLIENT_SECRET` (user `ops-console`) and get merchant
onboarding, lifecycle actions and notification delivery. Both come from `./scripts/init-env.sh` or
the registration response.

It is a **backend-for-frontend**: it owns no database and no business rules, calls the services
through the gateway exactly as a hospital's own system would, and never hands the browser an access
token — the browser gets an `HttpOnly` session cookie and nothing else. Sign-in with a key secret
typed into a form is a deliberate demo-grade simplification, explained in the doc.
