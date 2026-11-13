# Healthcare Payment Gateway (HPG) — Architecture Documentation

> Status: **Phase 4 complete** (Kafka, transactional outbox relay, merchant replication,
> notification-service), plus a web console over the same public APIs. Phases 5–8 are still design. The table at the bottom says exactly what runs today.
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
| 14 | [Web console](14-console.md) | The hospital / operations UI: backend-for-frontend, sign-in, pages, limits |
| — | [Decision log](decisions.md) | Every significant decision, the alternatives, and why |
| — | [Event catalogue](events/README.md) | Every Kafka event, with examples captured from the running system |
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
| Gateway routing, correlation IDs, Problem Details errors, Redis rate limiting | **Real** | Routes exist only for merchant-service so far |
| Gateway deny-list for suspended merchants | **Not yet** | Phase 4 (needs merchant events). Until then a suspended merchant's existing token works for ≤ 15 min; new tokens are refused immediately. |
| Internal webhook-config endpoint for notification-service | **Real** (endpoint) | Its consumer arrives in Phase 4 |
| Docker images, Compose profiles `infra` / `core`, per-service DB users | **Real** | Kafka and observability profiles in Phases 4 / 7 |
| CI (GitHub Actions: build + all tests) | **Real** | Image publishing, coverage, secret scanning in Phase 8 |
| Payment initiation, status, list, capture, cancel; state machine (all 243 transitions tested) | **Real** | Phase 3 |
| Idempotency-Key (Postgres, same transaction), duplicate-bill protection, daily limit with advisory lock | **Real** | Concurrency-tested |
| Optimistic locking with transaction re-run (callback / webhook / poll races) | **Real** | |
| Hosted checkout page (strict CSP), signed callback + immediate provider verification | **Real** | |
| Webhook endpoint: raw-body HMAC verification, inbox dedupe, out-of-order handling, replay window | **Real** | |
| Recovery jobs: order-creation retry, PROCESSING/UNKNOWN/PENDING verification, provider-checked expiry (ShedLock) | **Real** | |
| Razorpay adapter (orders, payments, capture, signatures, error mapping, timeouts) | **Real code, tested against WireMock only** | **Not yet run against the Razorpay sandbox** (no test keys). See [checklist](razorpay-sandbox-checklist.md). |
| Resilience4j: circuit breaker, retry with jittered backoff, separate live/recovery bulkheads | **Real** | |
| Simulated payment provider (`HPG_PAYMENT_PROVIDER=simulated`) | **Simulated** | In-memory, for local demos only; clearly labelled on the checkout page and in logs |
| Outbox events (PaymentInitiated/Authorized/Captured/Failed/Cancelled/Expired) | **Real** | Written in the business transaction and published to Kafka by the relay |
| Transactional outbox relay: ordered publishing, backlog metrics, cleanup, single runner (ShedLock) | **Real** | Phase 4 |
| Merchant snapshots published by merchant-service on a compacted topic | **Real** | The Phase 3 seeding endpoint is gone |
| Merchant config replicas in payment-service and notification-service | **Real** | Built from events; version-ordered so replays cannot move them backwards |
| Gateway deny-list: a suspended merchant's existing token is refused at the edge | **Real** | Fed by the same snapshots, kept in Redis, fail-open ([09 §2.1](09-security.md)) |
| Consumer deduplication (`processed_events`), dead-letter topics with original bytes, jittered blocking retries | **Real** | Phase 4 |
| notification-service: intake, delivery tracking, retries over ~a day, dead notifications, ops requeue | **Real** | Phase 4 |
| Hospital webhooks: HMAC-SHA256 over `timestamp.body`, replay window, sequence number, SSRF guard at delivery | **Real** | `scripts/webhook-receiver.py` verifies them the way a hospital would |
| Patient email | **Real (SMTP)** | Mailpit locally — a real SMTP server with a web inbox |
| Patient SMS | **Simulated** | `LoggingSmsSender`: logs, never sends; says SIMULATED at startup and per message |
| `NotificationFailed` events for alerting | **Real** | Published through the outbox when a notification is given up on |
| Web console (hospital: payments, settings, API keys; operations: hospitals, notification delivery) | **Real** | A backend-for-frontend over the public APIs, through the gateway ([14](14-console.md)) |
| Console security: tokens never reach the browser, `HttpOnly` session cookie, CSRF, session fixation, strict CSP | **Real** | Asserted by `ConsoleIT` and `scripts/smoke-test-console.sh` |
| Console sign-in from an API key id and secret (or the ops client credentials) | **Real, demo-grade** | Deliberately not how a product would do it; real deployments use an identity provider with MFA ([14 §3](14-console.md)) |
| Console pages for refunds, ledger, reconciliation; a dashboard; multi-instance sessions | **Not yet** | They follow the services ([14 §7](14-console.md)) |
| Refunds, ledger, reconciliation | **Not yet** | Phases 5–6 |
