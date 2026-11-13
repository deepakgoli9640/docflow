# 13 — Implementation Plan

Each phase ends with: compile (`./mvnw -B verify`), all tests green, docs updated (including the
implemented-vs-simulated table in [README](README.md)), and the exact commands to run what was
built. No phase starts while the previous one has unresolved critical errors.

## Phase 2 — Foundation, gateway, merchant-service ✅ done

Delivered as planned, with these differences:
- Added `platform/web-support` (servlet-only plumbing) so the reactive gateway does not inherit
  Spring MVC classes from `common`.
- Spotless was not added (formatting rules can come with CI hardening in Phase 8).
- Gateway deny-list for suspended merchants moves to Phase 4 (it is fed by merchant events).
- A minimal CI workflow (build + all tests) was added now rather than waiting for Phase 8.
- Tests: 114 total — 69 unit, 45 integration (Testcontainers PostgreSQL and Redis, WireMock).

- Maven multi-module skeleton, parent POM with Spring Boot 3.5.x / Spring Cloud 2025.0.x BOMs,
  Java 21, enforcer plugin, Spotless formatting.
- `platform/common`: `Money`, Problem Details error model, base `GlobalExceptionHandler`,
  correlation-ID filter, masking utilities.
- `deploy/docker-compose.yml` with the `infra` profile; Postgres init script creating one database
  and one user per service.
- **merchant-service**: registration, activation/suspension state machine, profile, configuration,
  API key lifecycle (create/list/revoke, bcrypt), Spring Authorization Server client-credentials
  token endpoint + JWKS, audit log, Flyway migrations.
- **api-gateway**: routes, JWT validation, correlation ID, Redis rate limiting.
- Tests: unit (services, validators), `@DataJpaTest` + Testcontainers repository tests, MockMvc API
  tests, token issuance IT.
- **Exit:** register → activate → create key → get token → call `GET /merchants/me` through the
  gateway.

## Phase 3 — payment-service and Razorpay ✅ done (sandbox run pending keys)

Delivered as planned, with these differences:
- **Exit criterion partially met:** no Razorpay test keys yet, so the adapter is verified against WireMock
  stubs of Razorpay's documented API, and the end-to-end demo uses the clearly labelled simulated
  provider. The real sandbox run is tracked in [razorpay-sandbox-checklist.md](razorpay-sandbox-checklist.md).
- Added `platform/persistence` (shared JPA base entity, column encryption, auditing; merchant-service
  refactored onto it) and created `platform/event-contracts` + `platform/messaging` (outbox writer) now,
  because payment-service writes outbox events from day one. The Kafka relay is still Phase 4.
- The simulated provider is selected by property (`HPG_PAYMENT_PROVIDER`), not a Spring profile.
- `FAILED → CAPTURED` was added to the provider-evidence override (money moved after the attempt budget
  was used up).
- Tests: 429 in the repository (341 unit, 88 integration); payment-service alone 312.

- Entities/migrations for payment tables; `PaymentStateMachine` with exhaustive unit tests.
- Idempotency (`idempotency_keys`), duplicate-bill protection, optimistic locking with retry.
- `PaymentProvider` port; Razorpay adapter (RestClient, timeouts, error mapping, Resilience4j);
  `SimulatedPaymentProvider` (opt-in profile).
- Hosted checkout page + callback signature verification; payment webhook endpoint with
  raw-body signature verification and `webhook_inbox`.
- Capture, cancel, expiry and recovery jobs (ShedLock).
- Merchant config replica: table + temporary seeding until Kafka arrives in Phase 4.
- Outbox **table written** in this phase (events accumulate), relay arrives in Phase 4.
- Tests: WireMock-based adapter tests (success, 4xx, 5xx, timeout), webhook signature tests,
  idempotency concurrency IT, out-of-order/duplicate webhook ITs.
- **Exit:** a real Razorpay **test-mode** payment completed through the checkout page (if you
  provide sandbox keys), or the simulator flow, with the difference stated.

## Phase 4 — Kafka, outbox, notification-service ✅ done

Delivered, with these differences:
- Merchant snapshot replication (`merchant.snapshots.v1`) came in a follow-up: merchant-service publishes a
  full snapshot through its outbox on registration, status, profile and configuration changes (startup
  backfill + OPS republish); payment-service's replica, refund-service (status, refund window) and the
  gateway's Redis deny-list consume it. The temporary seeding endpoint was removed. Tests: MerchantSnapshotIT,
  MerchantReplicationIT, refund suspension/window ITs, gateway deny-list IT; `smoke-test-phase4.sh` step 6
  suspends a hospital mid-session.
- Notification retries are **database-driven** (`next_attempt_at`, exponential backoff + jitter, lease-based
  claiming with `SKIP LOCKED`) instead of Kafka retry topics: intake only stores rows, so the partition never
  waits on a hospital endpoint, and multi-hour retries do not belong in Kafka anyway (D18 already said so
  for webhook delivery).
- One active outbox relay per service is ensured with a transaction-scoped Postgres advisory lock rather
  than ShedLock, so `platform/messaging` does not depend on ShedLock.
- No SMS port yet; email goes over real SMTP (Mailpit locally).
- Tests: OutboxRelayIT (payment-service → Kafka), NotificationFlowIT (signed webhook, retry then success,
  retry budget → DEAD + `NotificationFailed`, skipped without URL, duplicate events), unit tests for
  backoff, signing and the SSRF guard. `scripts/smoke-test-phase4.sh` runs the whole flow in Docker.

Planned:

- `platform/event-contracts`, `platform/messaging` (outbox relay, idempotent consumer helper,
  error handlers, DLT config).
- Kafka in `infra` profile; topic creation via `NewTopic` beans.
- Outbox relay in merchant- and payment-service; merchant snapshot replication.
- **notification-service**: intake consumer, notification state machine, DB-driven retry with
  exponential backoff + jitter, hospital webhook delivery with HMAC signing + SSRF guard, email
  via SMTP (Mailpit locally — real SMTP protocol), `SmsSender` port with a logging implementation
  (**simulated**, clearly labelled), `NotificationFailed` events.
- Tests: Kafka Testcontainers producer/consumer tests, duplicate-event tests, Kafka-outage test.

## Phase 5 — refund-service and ledger-service ✅ done (as refund-service + transaction-service)

Delivered, with these differences:
- ledger-service is named **transaction-service** (database `transaction_db`): besides the ledger it owns the
  merchant-facing transaction history and status of every payment and refund.
- `TransactionCreated` / `ledger.events.v1`, reversals and the reversal API are not built yet (the outbox
  table exists for them). Refund-service has no merchant replica yet, so it does not check
  `MERCHANT_SUSPENDED` (a suspended merchant cannot get new tokens anyway).
- transaction-service uses two consumers (payment and refund topics): a RefundCompleted waiting in blocking
  retry for its capture must not also stop the topic that delivers that capture.
- Tests: RefundFlowIT (webhook completion, idempotent replay, 8 concurrent refunds vs. the captured amount,
  timeout → lookup → adopt without a second refund, rejection releases the reservation),
  TransactionFlowIT (refund before capture, stale event, duplicates, ledger immutability, reports, roles).

Planned:

- **refund-service**: `refundable_payments` replica, atomic reservation, refund state machine,
  Razorpay refund adapter, refund webhooks, recovery with lookup-before-retry.
- **ledger-service**: accounts, transactions, entries, posting rules, immutability triggers and
  grants, deferred balance check, reversals, transaction history API, summary report,
  integrity check, `TransactionCreated` events.
- Tests: concurrent partial refunds, over-refund, refund timeout; ledger balance/immutability
  tests against real Postgres; idempotent posting; out-of-order capture/refund.

## Phase 6 — reconciliation-service

- Internal transaction replica from `ledger.events.v1`.
- CSV ingestion (streaming, checksum dedupe, malformed-row capture), Razorpay settlement API
  source.
- Matching engine (pure Java, table-driven unit tests), batch lifecycle, discrepancy resolution
  API, reports (JSON/CSV), `ReconciliationCompleted` events.
- Sample settlement files in `samples/settlement/`.
- Stretch: settlement postings to ledger on match.

## Phase 7 — Security hardening and observability

- Roles and method security across services, tenant-isolation tests (merchant A cannot see B).
- PII encryption converter, log masking, audit logging completeness, replay protection checks,
  rate-limit tuning, optional local TLS.
- Structured JSON logging, Micrometer custom metrics, OpenTelemetry tracing across HTTP and Kafka,
  `obs` profile with Prometheus, Grafana dashboards-as-code, Jaeger, alert rules.

## Phase 8 — Tests, Docker, CI/CD, failure and load testing

- Fill test gaps so every scenario in [11](11-failure-scenarios.md) has an automated test.
- Dockerfiles for all services, full Compose profiles, `.env.example`.
- GitHub Actions CI (build, test, reports, gitleaks, image build/push).
- k6 load test script and a short report of measured results.
- Final README, sample requests (HTTP files / curl), and `docs/interview-guide.md` (how to explain
  each design decision, likely questions and trade-offs).

## Testing approach used throughout

| Level | Tools | Notes |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | State machines, validators, matching engine, posting rules — no Spring context |
| Repository | `@DataJpaTest` + Testcontainers Postgres | Real Postgres: constraints, partial indexes, triggers can't be tested on H2 |
| API | `@WebMvcTest` / MockMvc | Validation, error format, security rules |
| Integration | `@SpringBootTest` + Testcontainers (Postgres, Kafka) + WireMock | One shared container per JVM (singleton pattern) to keep the laptop responsive |
| Async assertions | Awaitility | Never `Thread.sleep` |

Integration tests are run per module (`./mvnw -pl services/payment-service verify`) so you never
need every container at once.
