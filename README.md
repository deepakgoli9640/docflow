# Healthcare Payment Gateway (HPG)

A multi-tenant payment gateway that hospitals integrate with to collect the patient-responsibility
part of a bill. It handles payments, refunds, the ledger, notifications and reconciliation. It is
**not** a billing or insurance system.

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway · Spring Security / Authorization Server ·
PostgreSQL · Flyway · Kafka (Phase 4) · Redis · Testcontainers · Docker Compose

> **Status: Phase 2 of 8 complete** — project skeleton, shared libraries, API gateway and
> merchant-service. See [what is implemented](docs/README.md#implemented-vs-simulated) and the
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
services/merchant-service/   Hospital onboarding, API keys, configuration, OAuth2 token issuer
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

# 3a. Run everything in Docker (~550 MB RAM in total)
./mvnw -DskipTests package
docker compose -f deploy/docker-compose.yml --profile infra --profile core up -d --build

# 3b. ...or only infrastructure in Docker and the service from your IDE / terminal
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl services/merchant-service spring-boot:run -Dspring-boot.run.profiles=local
./mvnw -pl services/api-gateway      spring-boot:run -Dspring-boot.run.profiles=local
#    (in the IDE: run MerchantServiceApplication / ApiGatewayApplication with active profile "local")

# 4. Exercise the whole Phase 2 flow through the gateway
./scripts/smoke-test-phase2.sh

# Stop (add -v to also delete the database volume)
docker compose -f deploy/docker-compose.yml --profile '*' down
```

| URL | What |
|---|---|
| http://localhost:8080 | API gateway (the only entry point clients use) |
| http://localhost:8081/swagger-ui.html | merchant-service OpenAPI / Swagger UI |
| http://localhost:8081/actuator/health | merchant-service health |

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

More requests: [samples/http/merchant.http](samples/http/merchant.http).
