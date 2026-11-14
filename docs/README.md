# Healthcare Payment Gateway (HPG) — Architecture Documentation

> Status: **all phases complete (2–8)**: every service runs end to end behind the gateway, with security
> hardening, observability, a test for every failure scenario, CI/CD and a measured load test. Remaining
> gaps are listed in the table below (*Not yet*).
> Note: the "ledger-service" of the design documents is implemented as **transaction-service**. The table at the bottom says exactly what runs today.
> Where a statement depends on Razorpay behaviour, it is marked
> *(verify in Phase 3)* and will be checked against current Razorpay documentation and the sandbox
> before code relies on it.

## What this system is

A multi-tenant **payment gateway** that hospitals (merchants) integrate with to collect the
patient-responsibility portion of a bill. The hospital has already worked out what the patient owes
(after insurance). We only move money and account for it: payments, refunds, the ledger,
notifications and reconciliation.

It is **not** a billing system, a claims system or an insurance adjudication engine. We never see
diagnoses, line items or claim data. We see an amount, a hospital bill reference and patient
contact details.

## Reading order

| # | Document | What it answers |
|---|----------|-----------------|
| 01 | [Architecture overview](01-architecture-overview.md) | Scope, actors, services, boundaries, communication patterns, high-level diagram |
| 02 | [Payment lifecycle](02-payment-lifecycle.md) | State machine, idempotency, provider integration, recovery |
| 03 | [Refund lifecycle](03-refund-lifecycle.md) | Refund states, cumulative-amount guarantee, provider recovery |
| 04 | [Ledger](04-ledger.md) | Double-entry model, immutability, reversals |
| 05 | [Reconciliation](05-reconciliation.md) | Matching rules, statuses, discrepancy workflow |
| 06 | [Kafka & events](06-kafka-events.md) | Topics, envelope, outbox, consumers, retries, DLQ, ordering |
| 07 | [Data model](07-data-model.md) | Database ownership, schemas, ER diagrams |
| 08 | [API contracts](08-api-contracts.md) | REST endpoints, headers, errors, pagination, samples |
| 09 | [Security](09-security.md) | AuthN/AuthZ, secrets, webhooks, PCI DSS considerations |
| 10 | [Observability](10-observability.md) | Logs, metrics, traces, dashboards, alerts |
| 11 | [Failure scenarios](11-failure-scenarios.md) | The 10 required failure scenarios and how each is handled and tested |
| 12 | [Deployment & local dev](12-deployment-local-dev.md) | Docker Compose profiles for an 8 GB laptop, CI/CD |
| 13 | [Implementation plan](13-implementation-plan.md) | Phases 2–8, deliverables, exit criteria |
| — | [Load test report](load-test-report.md) | Measured latency and throughput, bottleneck, how to raise it |
| — | [Interview guide](interview-guide.md) | Pitch, deep dives, likely questions, honest limitations |
| — | [Decision log](decisions.md) | Every significant decision, the alternatives, and why |
| — | [Razorpay sandbox checklist](razorpay-sandbox-checklist.md) | What still has to be verified against the real Razorpay sandbox |

## Implemented vs simulated

Updated every phase. *Real* = runs and is covered by automated tests against real dependencies
(PostgreSQL / Redis via Testcontainers). *Simulated* = a stand-in, clearly labelled. *Not yet* =
designed but not built.

| Capability | Status | Notes |
|---|---|---|
| Maven multi-module build, shared `common` / `web-support` libraries | **Real** | Phase 2 |
| Merchant registration, profile, activate / suspend / deactivate lifecycle | **Real** | Phase 2 |
| Merchant configuration (methods, limits vs platform maximums, capture mode, TTL, webhook URL) | **Real** | Phase 2 |
| API keys: create (max 2 active), list, revoke, bcrypt-hashed secrets | **Real** | Phase 2 |
| OAuth2 client-credentials token issuance (Spring Authorization Server), JWKS | **Real** | Signing key is **ephemeral** unless PEM files are configured |
| JWT validation at gateway and in merchant-service, role-based access, tenant isolation | **Real** | |
| Audit log (append-only, DB trigger) | **Real** | merchant-service |
| Webhook secret encryption at rest (AES-256-GCM) | **Real** | |
| Gateway routing, correlation IDs, Problem Details errors, Redis rate limiting | **Real** | Routes to merchant, payment, refund, transaction and notification services |
| Gateway deny-list for suspended merchants | **Real** | Redis, fed by `merchant.snapshots.v1`: a suspended merchant's existing tokens are refused within seconds (fails open if Redis is down; payment/refund replicas still refuse). |
| Internal webhook-config endpoint for notification-service | **Real** | Called with a client-credentials token (SERVICE role), cached 60 s |
| Docker images, Compose profiles `infra` (+Kafka) / `core` / `refund` / `transactions` / `notify` / `recon` / `dashboard` / `obs`, per-service DB users | **Real** | |
| CI/CD (GitHub Actions: build + all tests, JaCoCo coverage summary, gitleaks, images to GHCR on master) | **Real** | |
| Payment initiation, status, list, capture, cancel; state machine (all 243 transitions tested) | **Real** | Phase 3 |
| Idempotency-Key (Postgres, same transaction), duplicate-bill protection, daily limit with advisory lock | **Real** | Concurrency-tested |
| Optimistic locking with transaction re-run (callback / webhook / poll races) | **Real** | |
| Hosted checkout page (strict CSP), signed callback + immediate provider verification | **Real** | |
| Webhook endpoint: raw-body HMAC verification, inbox dedupe, out-of-order handling, replay window | **Real** | |
| Recovery jobs: order-creation retry, PROCESSING/UNKNOWN/PENDING verification, provider-checked expiry (ShedLock) | **Real** | |
| Razorpay adapter (orders, payments, capture, signatures, error mapping, timeouts) | **Real code, tested against WireMock only** | **Not yet run against the Razorpay sandbox** (no test keys). See [checklist](razorpay-sandbox-checklist.md). |
| Resilience4j: circuit breaker, retry with jittered backoff, separate live/recovery bulkheads | **Real** | |
| Simulated payment provider (`HPG_PAYMENT_PROVIDER=simulated`) | **Simulated** | In-memory, for local demos only; clearly labelled on the checkout page and in logs |
| Outbox → Kafka relay (in order, one active relay per service via advisory lock, `acks=all`) | **Real** | payment-, refund- and notification-service; tested against Testcontainers Kafka |
| Consumers: envelope parsing, `processed_events` dedupe, blocking retry with backoff, `<topic>.dlt` | **Real** | Shared in `platform/messaging` |
| Merchant snapshots (`merchant.snapshots.v1`, compacted): published on every change via the outbox, backfilled at startup, OPS republish | **Real** | Feeds payment-service's merchant replica, refund-service (status, refund window) and the gateway deny-list; the temporary seeding endpoint is gone |
| Refunds: full/partial, atomic reservation (concurrency-tested), idempotency, state machine, webhooks, lookup-before-retry recovery | **Real** | refund-service; Razorpay refund adapter tested against WireMock only |
| Simulated refund provider | **Simulated** | Same `HPG_PAYMENT_PROVIDER=simulated` switch; refunds complete immediately |
| Transaction history and status (payments + refunds), stale-event protection, timeline | **Real** | transaction-service, built from events |
| Double-entry ledger: capture/refund postings, append-only + deferred balance triggers, integrity check, balances, summary report | **Real** | Reversals and settlement postings not yet |
| Hospital webhooks: HMAC-signed (`timestamp.body`), SSRF guard at delivery, retries with backoff + jitter, DEAD + `NotificationFailed` | **Real** | notification-service |
| Patient emails (receipt / failed / expired) via SMTP | **Real** | Mailpit locally; addresses encrypted at rest |
| Patient SMS (receipt, failed, expired), phone encrypted at rest | **Real** | Local provider logs a masked number; MSG91 adapter (DLT templates) tested against WireMock only |
| Web dashboard: hospital view (payments, refunds, transactions, notifications, settings) and operations view (hospitals, failed notifications, ledger) | **Real** | `ui/dashboard`, Compose profile `dashboard`; browser-tested end to end. Sign-in uses client credentials (production would add OIDC user login) |
| Ledger events (`TransactionCreated` on `ledger.events.v1`) for every capture and refund posting | **Real** | transaction-service, through its outbox |
| Reconciliation: internal replica from ledger events, streaming CSV upload (SHA-256 dedupe, malformed-row capture), matching engine (all statuses incl. cross-day duplicates), batches, discrepancy workflow, daily report JSON/CSV, `ReconciliationCompleted` | **Real** | reconciliation-service; dashboard page for OPS |
| Reconciliation from the Razorpay settlement API (scheduled 06:00 IST + on demand) | **Real code, tested against WireMock only** | Needs Razorpay keys; with the simulated provider use CSV uploads |
| Settlement postings to the ledger (net to `SETTLEMENT_BANK`, fees to `PROVIDER_FEES`, once per payment) | **Real** | reconciliation `SettlementConfirmed` → transaction-service |
| Disputes / chargebacks: Razorpay `payment.dispute.*` webhooks, hospital accept/contest (API + dashboard), chargeback and reversal postings, refunds paused while open, hospital webhooks | **Real** | Razorpay dispute endpoints tested against WireMock only |
| Finance email on `ReconciliationCompleted` | **Not yet** | |
| Tenant isolation and role matrix across every service, through the gateway | **Real** | `scripts/smoke-test-phase7.sh` (41 checks) plus per-service integration tests |
| Structured JSON logs (ECS) with trace id, span id, correlation id; PII masking (emails, phones, card numbers, credentials) | **Real** | `platform/observability`; masking applies to structured (JSON) logs only |
| Metrics: payments, refunds, provider outcomes, outbox backlog, DLT records, stuck payments, ledger integrity, webhook signature failures | **Real** | Prometheus scrape of every service and the gateway |
| Distributed tracing (OpenTelemetry, OTLP → Jaeger) across HTTP, the outbox and Kafka consumers | **Real** | Export off by default (`HPG_TRACE_EXPORT=true` turns it on); W3C `traceparent` carried in the outbox |
| Grafana dashboards as code (4), Prometheus alert rules (10) | **Real** | Compose profile `obs`; no Alertmanager routing (alerts visible in Prometheus) |
| Webhook replay protection: events older than 48 h acknowledged but not applied | **Real** | payment- and refund-service |
| Local TLS | **Not yet** | Plain HTTP locally; TLS terminates at the ingress in production |
| Failure-scenario tests: Kafka outage (container paused), database outage (container stopped) | **Real** | Every scenario in [11](11-failure-scenarios.md) has an automated test |
| Load test (k6 script + JDK runner), measured report | **Real** | [load-test-report.md](load-test-report.md); single 4-vCPU machine |
| Kubernetes: Helm chart (replicas, HPA, PDB, probes, rolling updates, NetworkPolicies, ingress + TLS, least-privilege secrets), CloudNativePG / Strimzi / Redis manifests | **Real, validated statically** | Schema-validated in CI; every image run with the chart's security settings; **not yet installed on a live cluster** ([deploy/k8s](../deploy/k8s/README.md)) |
| High availability: two replicas of every service pass the end-to-end smoke tests, no duplicates | **Real** | `scripts/ha-check.sh` (Compose with 2 replicas each) |
| Alertmanager routing | **Not yet** | |
| Dashboard single sign-on (OIDC Authorization Code + PKCE) with Keycloak: password policy, lockout, optional TOTP; services and gateway trust it for MERCHANT/OPS only | **Real** | Compose profile `auth`; browser-tested for both kinds of user; Keycloak Operator manifest for Kubernetes |
| Token signing-key rotation (active + retired public keys in the JWKS), production refuses ephemeral keys | **Real** | `KeyRotationIT` |
| Secrets from a secret manager (External Secrets), TLS at the ingress with HSTS, Postgres TLS | **Real, validated statically** | Kubernetes manifests; not run on a live cluster |
| Service-to-service mTLS (service mesh), penetration test | **Not yet** | |
| Load shedding in every service (fast 503 + Retry-After), Postgres statement timeout | **Real** | |
| Gateway circuit breaker per service, GET retries on another replica, 503/504 with Retry-After | **Real** | |
| Dead-letter inspection and replay for operations | **Real** | `/api/v1/admin/dead-letters` (OPS) |
| Chaos test (replicas, Kafka, Postgres, gateway killed under load) | **Real** | `scripts/chaos-test.sh`: nothing lost or booked twice |
