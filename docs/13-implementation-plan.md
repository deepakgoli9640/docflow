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

## Phase 6 — reconciliation-service ✅ done

Delivered, with these differences:
- transaction-service now publishes `TransactionCreated` (with the provider reference) for every ledger
  posting; that was the missing input.
- Internal-only items are flagged `MISSING_IN_PROVIDER` once older than the settlement window (T+N, default 3,
  `0` in local Compose) and keep being flagged in later batches until matched or resolved. A reference matched
  in another day's report is a cross-day `DUPLICATE` (settled twice). "Near the day boundary → PENDING_REVIEW"
  is not implemented; the window covers it.
- Batches run in the background (202 + poll); a RUNNING batch older than 30 min is marked FAILED.
- API runs need Razorpay credentials; with the simulated provider only CSV uploads are available.
- Not built: settlement postings to the ledger (stretch), finance email on `ReconciliationCompleted`.
- Gateway: request-size limits moved from a default filter to each route (1 MB), so the upload route can
  allow 21 MB.
- Tests: table-driven matching and CSV parser unit tests, Razorpay settlement source (WireMock),
  ReconciliationFlowIT (every status, duplicate file, workflow, daily report, roles), TransactionCreated
  publishing, gateway size limits; `scripts/smoke-test-phase6.sh`.

Planned:

- Internal transaction replica from `ledger.events.v1`.
- CSV ingestion (streaming, checksum dedupe, malformed-row capture), Razorpay settlement API
  source.
- Matching engine (pure Java, table-driven unit tests), batch lifecycle, discrepancy resolution
  API, reports (JSON/CSV), `ReconciliationCompleted` events.
- Sample settlement files in `samples/settlement/`.
- Stretch: settlement postings to ledger on match.

## Phase 7 — Security hardening and observability ✅ done

Delivered, with these differences:
- Tenant isolation and the role matrix are checked end to end through the gateway by
  `scripts/smoke-test-phase7.sh` (two hospitals, ops and forged tokens, 41 checks) on top of each service's
  integration tests. Hospital B gets 404 (not 403) for A's resources, so ids cannot be probed.
- Logging: Spring Boot structured logging (ECS JSON) everywhere, with a shared customizer that masks emails,
  phone numbers, Luhn-valid card numbers and credentials in every string value. There is no Logback converter
  for plain-text console logs; the masking applies to JSON logs, which is what Compose and production use.
- Metrics: domain counters/gauges (payments initiated/terminal/captured amount/stuck, refunds, notifications,
  provider outcomes, outbox pending + oldest age, DLT records, ledger integrity, webhook signature failures)
  and HTTP latency histograms with SLO buckets. Counters ending in `.created` were renamed (`payments.initiated`,
  `refunds.initiated`, `notifications.queued`): Prometheus/OpenMetrics drops a `_created` suffix.
- Tracing: Micrometer Tracing with the OpenTelemetry bridge, OTLP to Jaeger. Spans cross the outbox: the
  writer stores the W3C `traceparent` in the envelope and the relay sends it as a Kafka header, so a trace goes
  gateway → payment-service → Kafka → refund/transaction/notification → reconciliation. Export is off unless
  `HPG_TRACE_EXPORT=true`.
- `obs` profile: Prometheus (scrapes all 7 apps, 10 alert rules), Grafana (4 dashboards generated by
  `deploy/grafana/dashboards/generate.py`), Jaeger. No Alertmanager: alerts are visible in Prometheus.
- Replay protection: provider webhooks older than 48 h are recorded as `STALE`, acknowledged (200) and not
  applied (refund-service; payment-service already had it).
- The gateway now resolves downstream hosts with the JVM resolver (30 s cache) instead of Netty's DNS cache,
  so a recreated service container is reachable again at once.
- Not built: optional local TLS (TLS belongs at the ingress), rate-limit tuning beyond the existing
  per-merchant limits.

Planned:


- Roles and method security across services, tenant-isolation tests (merchant A cannot see B).
- PII encryption converter, log masking, audit logging completeness, replay protection checks,
  rate-limit tuning, optional local TLS.
- Structured JSON logging, Micrometer custom metrics, OpenTelemetry tracing across HTTP and Kafka,
  `obs` profile with Prometheus, Grafana dashboards-as-code, Jaeger, alert rules.

## Phase 8 — Tests, Docker, CI/CD, failure and load testing ✅ done

Delivered, with these differences:
- Failure scenarios: the two missing tests were added, `OutboxSurvivesKafkaOutageIT` (pauses Kafka) and
  `DatabaseDownIT` (stops Postgres, on a fixed host port so it comes back on the same address). The latter
  found a bug (database outage answered 500), fixed in the shared exception handler. The full map is in
  [11](11-failure-scenarios.md).
- Dockerfiles, Compose profiles and `.env.example` already existed from earlier phases.
- CI: JaCoCo coverage (unit + integration tests in one report per module, summarised in the job summary),
  a gitleaks secret scan, and images for the 7 services and the dashboard pushed to GHCR on master
  (`ghcr.io/<owner>/hpg-<name>:<sha>` and `:latest`). The jars are built once, in the test job.
- Load testing: a k6 script and a JDK-only runner of the same scenario (no install needed); measured in
  [load-test-report.md](load-test-report.md). k6 itself was not run in the build environment.
- [interview-guide.md](interview-guide.md), reconciliation HTTP samples, final README.
- Not built: Alertmanager routing, local TLS, a Kubernetes deployment.

Planned:


- Fill test gaps so every scenario in [11](11-failure-scenarios.md) has an automated test.
- Dockerfiles for all services, full Compose profiles, `.env.example`.
- GitHub Actions CI (build, test, reports, gitleaks, image build/push).
- k6 load test script and a short report of measured results.
- Final README, sample requests (HTTP files / curl), and `docs/interview-guide.md` (how to explain
  each design decision, likely questions and trade-offs).

## Phase 9 — Production readiness

Four parts, in order: Kubernetes + HA, production security, business features, a real Razorpay sandbox run.

### 9a — Kubernetes and high availability ✅ done (validated statically)
- Helm chart for the gateway, six services and the dashboard:
  - 2+ replicas, autoscaling on CPU, disruption budgets, zone/node spreading;
  - startup, liveness and readiness probes, `preStop` pause and graceful shutdown;
  - NetworkPolicies, hardened pods, per-service secrets, ingress with TLS (cert-manager), ServiceMonitors, a
    `helm test`.
- CloudNativePG (3 instances, synchronous replica, PITR backups), Strimzi Kafka (3 KRaft nodes, RF 3, min ISR 2),
  Redis values. Topic replication and `min.insync.replicas` are now configurable in the services.
- Production profile for the gateway (JSON logs), Redis password support, Postgres `sslmode` per service.
- Verified:
  - schemas (also in CI);
  - every image run non-root with a read-only root filesystem and the production profile;
  - two replicas of every service passing the smoke tests with no duplicates (`scripts/ha-check.sh`).
- Not verified: an install on a live cluster (kind cannot run in the build environment).

### 9b — Production security ✅ done
- **Single sign-on** for dashboard users with Keycloak (OIDC Authorization Code + PKCE):
  - realm as code (`deploy/keycloak/hpg-realm.json`) with password policy, brute-force lockout and optional
    TOTP;
  - the `mid` user attribute and an `hpg-api` audience;
  - a Compose profile `auth`, user provisioning (`scripts/keycloak-users.sh`) and a Keycloak Operator manifest.
- **The gateway and all six services trust two issuers.** Identity-provider tokens may only grant people's
  roles (never SERVICE; MERCHANT needs a hospital).
- Browser-tested: an operations user and a hospital user signed in through Keycloak, with the right view,
  tenant isolation and a full sign-out.
- **Signing-key rotation:** the JWKS publishes the active key and retired public keys; tokens name their
  `kid`; production refuses to start without a configured key.
- **Secrets and TLS:**
  - External Secrets manifest (secret manager → Kubernetes Secrets);
  - HTTPS-only ingress with HSTS, Postgres TLS;
  - a TLS map per hop in [09 §8](09-security.md).
- Not done: service-mesh mTLS between services, a penetration test.

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
