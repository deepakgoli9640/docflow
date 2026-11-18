# Interview guide

How to explain this project: a short pitch, the deep dives interviewers usually pick, likely questions with
answers, and honest limitations. Decision numbers (D1…) point to [decisions.md](decisions.md); every claim
here has a test or a document behind it.

---

## 1. The pitch (about 60 seconds)

> "It's a multi-tenant payment gateway for hospitals: they collect the patient's share of a bill through it.
> Seven Spring Boot services behind a Spring Cloud Gateway: merchants (hospitals, API keys, OAuth2 tokens),
> payments, refunds, transactions with a double-entry ledger, notifications, and reconciliation against the
> provider's settlement report. Razorpay is the provider.
>
> The interesting part is correctness under failure. Every money-moving request is idempotent, the payment
> state machine converges on what the provider confirms, events go through a transactional outbox to Kafka
> so a database change and its event can never disagree, consumers deduplicate, and reconciliation catches
> whatever still slips through. It has tenant isolation, PII masking, metrics, alerts and distributed traces
> across HTTP and Kafka, about 550 tests (85 % line coverage) against real Postgres and Kafka in containers, and I load-tested it."

Then offer a deep dive: *"Happy to go into idempotency, the outbox, the ledger or reconciliation."*

## 2. The architecture in one picture

```
Hospital app ──> API Gateway (JWT check, per-hospital rate limit, deny-list, routing)
                   ├─> merchant-service       hospitals, config, API keys, OAuth2 token issuer
                   ├─> payment-service        payments, checkout, provider calls, recovery jobs ──> Razorpay
                   ├─> refund-service         refunds, atomic reservation                       ──> Razorpay
                   ├─> transaction-service    history + double-entry ledger
                   ├─> notification-service   signed hospital webhooks, patient emails
                   └─> reconciliation-service ledger vs settlement report (ops only)
Kafka topics: merchant.snapshots (compacted), payment / refund / ledger / reconciliation / notification events
One Postgres database (and DB user) per service. Redis only for the gateway.
```

Why this split (D1, D2): each service owns one kind of record. Services talk asynchronously, so the ledger
or notifications being down never blocks a payment. The price is eventual consistency, handled explicitly:
local replicas (merchant snapshots) on the hot path, and "not yet" answers such as
`409 PAYMENT_NOT_YET_REFUNDABLE` instead of guesses.

## 3. Deep dives

### Idempotency (failure scenario 1)
- Hospitals send an `Idempotency-Key`. The key, a hash of the request and the response are stored **in the
  same Postgres transaction** as the payment (D9). The same key and body replays the response; the same key
  with a different body is a 422. A crash before commit leaves nothing, so the retry simply works.
- Redis was rejected for this: a key stored outside the transaction can disagree with the payment.
- Different keys for the same bill are stopped by a partial unique index on (merchant, merchant reference)
  for live payments: `409 DUPLICATE_PAYMENT`.
- Proven by `PaymentApiIT#concurrentRequestsWithTheSameKeyCreateExactlyOnePayment` (12 threads, one payment).

### The payment state machine and "provider truth"
- `CREATED → PENDING → AUTHORIZED → CAPTURED`, plus `FAILED / CANCELLED / EXPIRED / UNKNOWN`. All 243
  transitions are tested (`PaymentStateMachineTest`).
- Forward-only: a late `authorized` webhook after `captured` is recorded as `STALE_IGNORED` and answered 200
  (a 4xx would make the provider retry forever).
- The one exception is **provider evidence** (D6): if the provider confirms a capture on a payment we had
  expired, we move it to `CAPTURED` and flag it as late, because our state must match where the money is.
- Three signals converge: the checkout callback, the webhook and recovery polling. A timeout on capture
  becomes `UNKNOWN` and is **verified before any retry**, never blindly retried (no double charge).

### Transactional outbox (failure scenarios 6 and 7)
- Problem: "update the DB, then publish to Kafka" can crash in between. Publishing first can announce
  something that then rolls back.
- Fix (D7): write the event to an `outbox_events` table in the same transaction; a relay publishes it with
  `acks=all` and marks it published. At least once; consumers deduplicate by `eventId` in `processed_events`,
  in the same transaction as their own write.
- Order: one topic per aggregate, keyed by payment id (D4), so a payment's events stay in one partition. Only
  one relay per service publishes, via a Postgres advisory lock (D41), and a failed send stops the batch so
  nothing overtakes it.
- Kafka down: the API keeps working (it only writes to Postgres), the backlog drains when Kafka is back
  (`OutboxSurvivesKafkaOutageIT` pauses the Kafka container mid-test).
- Production upgrade: CDC with Debezium instead of polling.
- The trace context rides in the outbox too (W3C `traceparent`, D56), so one trace spans
  request → outbox → Kafka → consumers.

### Refunds can never exceed the captured amount (scenario 9)
- A single conditional statement reserves the amount atomically, backed by a `CHECK` constraint (D16):
  `UPDATE refundable_payments SET reserved_amount = reserved_amount + :amt WHERE … AND captured_amount -
  refunded_amount - reserved_amount >= :amt`. One row updated means reserved; zero means "exceeds". No lock is
  held across the provider call; a rejected refund releases its reservation.
- `concurrentRefundsNeverExceedTheCapturedAmount`: parallel partial refunds, exactly the right number win.
- A timed-out refund is looked up at the provider by our refund id before any retry.

### Double-entry ledger
- Every capture and refund books balanced postings (debit = credit). Append-only, enforced three ways: the
  application, database grants and triggers, including a deferred trigger that checks each transaction
  balances at commit (D17). An integrity check runs on demand and is exported as a metric with an alert.

### Reconciliation (scenario 10)
- Internal side: the ledger's `TransactionCreated` events (D52), because the ledger is the record of money.
  Provider side: the settlement CSV (streamed, SHA-256 dedupe of whole files) or the Razorpay settlement API.
- The matching engine is pure Java with table-driven tests: `MATCHED`, `AMOUNT_MISMATCH`,
  `MISSING_IN_PROVIDER`, `MISSING_IN_INTERNAL_SYSTEM`, `DUPLICATE` (including settled twice across days).
- Nothing is corrected automatically (D19); operations review and resolve. Resolving changes the
  reconciliation record only, never money.

### Security and multi-tenancy
- OAuth2 client credentials: the hospital trades an API key for a 15-minute JWT carrying its merchant id
  (`mid`) and roles (D11). Every query is scoped by `mid` from the token, never from the request. Another
  hospital's id returns 404, not 403, so ids cannot be probed.
- The gateway checks tokens, rate-limits per hospital, and refuses suspended hospitals within seconds
  through a Redis deny-list fed by merchant snapshots (D50).
- Provider webhooks: HMAC over the raw body, inbox dedupe, 48-hour replay window (D62). Hospital webhooks
  are signed (`timestamp.body`); the URL is re-checked against private addresses at every delivery (SSRF,
  D44). One classifier serves both checks and also catches IPv6 forms that hide an internal IPv4 address
  (NAT64 `64:ff9b::a9fe:a9fe` is the cloud metadata endpoint).
- Card data never touches the system: Razorpay's hosted checkout (D12). Patient emails are encrypted at rest
  (AES-256-GCM), and logs mask emails, phones and card numbers (D58).

### Disputes and chargebacks (D79–D81, D83)
- A dispute is its own aggregate and topic (`dispute.events.v1`). The payment stays `CAPTURED`: it did happen,
  and the 243-transition state machine stays untouched.
- Ledger postings follow the money the provider actually moved (`amount_deducted`), not the dispute status:
  Razorpay usually deducts when the dispute opens, and the ledger must match the provider balance at every
  moment or reconciliation breaks. Winning books a reversal.
- Refunds are refused while a dispute is open: a refund plus a lost chargeback would pay the patient twice.
- Accept/contest need an Idempotency-Key, like capture and cancel: a client whose first call timed out gets
  the original answer, and the provider never receives the decision twice.

### Resilience between services (D84–D89)
- **Load shedding** in every service (a semaphore with a short wait, then a fast `503` + `Retry-After`) runs
  before security. Without it, queues grow until every request is slow and fails anyway.
- **Database statement timeouts**, so one slow query cannot hold a connection forever.
- **Gateway circuit breaker per service** that counts outages only (refused, reset, timeout), not 5xx answers:
  a business error is not "down", and the client keeps the service's problem details.
- **The gateway retries GETs only.** Writes are retried by the client with its Idempotency-Key; a gateway that
  retried a POST after a timeout could double a payment.
- **Dead letters** can be inspected and replayed from one place; consumer deduplication makes replay safe, and
  every replay is recorded.
- **Chaos test** (`scripts/chaos-test.sh`) kills containers under load and then checks money invariants, not
  uptime: every capture booked exactly once, every refund booked once, hospital owed = captured − refunded,
  ledger balanced. It found a real bug: the in-memory simulated provider lost orders on restart (D88).

### People sign-in and key rotation (D73–D77)
- Machines (hospital systems) use API keys → client credentials → JWT. People (hospital staff, operations)
  sign in through Keycloak with OIDC Authorization Code + PKCE; HPG keeps only authorization.
- Services trust the second issuer with stricter rules (audience `hpg-api`, roles MERCHANT/OPS only,
  MERCHANT needs `mid`), so a misconfigured identity provider cannot mint service tokens.
- Access token in sessionStorage, refresh token in memory only, strict CSP: an XSS could steal at most a
  5-minute token. A backend-for-frontend with cookies is the stronger option if the dashboard grows.
- Signing keys rotate by publishing retired public keys next to the active one (`kid` in every token header),
  so tokens in flight stay valid.

### Deployment
- Docker Compose with profiles for local work, a lite mode for 8 GB laptops, and a one-command public demo
  server (`scripts/cloud/setup-server.sh`, Caddy with automatic Let's Encrypt HTTPS, Oracle Cloud Always Free).
- Helm chart for Kubernetes with Postgres and Kafka left to operators (CloudNativePG, Strimzi) or managed
  services (D68). Each pod gets only the secrets it uses (D69); `preStop` sleep and readiness without the
  database (D72).
- HA was verified, not just claimed: `docker-compose.ha.yml` runs two replicas of everything and the smoke
  tests pass (one outbox relay via advisory lock, one scheduler via ShedLock, shared signing key) (D71).

### Observability
- JSON logs with trace id, span id and correlation id. Domain metrics (payments by outcome, stuck payments,
  outbox age, dead letters, ledger integrity). 4 Grafana dashboards generated as code, 10 alert rules, Jaeger
  traces across HTTP and Kafka.
- A war story worth telling: counters named `payments.created` showed up empty in Prometheus because
  OpenMetrics reserves the `_created` suffix. Renamed to `payments.initiated` (D59).

### Testing strategy
- Unit tests for pure logic (state machine, matching, money, masking). Integration tests boot the real
  service against **real** Postgres, Kafka and Redis in Testcontainers, and WireMock for Razorpay.
- Every failure scenario in [11](11-failure-scenarios.md) has a test, including stopping the database
  (`DatabaseDownIT`) and pausing Kafka (`OutboxSurvivesKafkaOutageIT`).
- Smoke scripts drive the whole stack through the gateway; the dashboard was tested in a real browser.
- CI: build, all tests, coverage summary, secret scan, and images pushed to GHCR from master.
- Story: `DatabaseDownIT` found a real bug. A stopped database surfaced as "Unable to rollback" from a dead
  pooled connection and became a 500. The handler now recognises connection failures anywhere in the cause
  chain and answers 503 with Retry-After.

### Performance
- [Load test](load-test-report.md) on a 4-vCPU machine running all 15 containers: up to 60 create+read
  iterations/s (120 requests/s) with p95 around 100 ms and no errors. Beyond that, CPU saturates and the
  service answers 503 with Retry-After; nothing is lost or half-written.
- Know the levers: replicas (the service is stateless; the relay already elects one publisher), shedding load
  early, production JVM flags, trace sampling.

## 4. Likely questions

**Why microservices for this?** Separate failure domains and ownership of money records: a notification or
ledger outage must not stop payments. I kept it to six services plus a gateway, with no config server or
service discovery, because Docker/Kubernetes DNS and environment variables do that job (D1).

**How do you avoid a distributed transaction?** I don't use one. Each service commits locally; the outbox
makes "commit and announce" atomic; consumers are idempotent; recovery jobs and reconciliation converge the
rest. Compensation is an explicit, audited business action (a refund, a ledger reversal), never a hidden
rollback ([11, "Compensation"](11-failure-scenarios.md)).

**What happens if Razorpay charges the patient but your response times out?** The payment is `PROCESSING`
or `UNKNOWN`. Recovery polls the provider, the webhook may arrive, the checkout callback may arrive; all three
converge. Expiry asks the provider first. A capture found after expiry is applied and flagged as late.

**Exactly-once delivery?** Not end to end. At-least-once publishing plus idempotent consumers (dedupe table and
natural unique keys) gives exactly-once *effects*, which is what matters.

**Why not Kafka transactions?** They cover Kafka-to-Kafka, not "Postgres row plus Kafka message". The outbox
covers the real boundary.

**How do you keep event order?** One topic per aggregate, keyed by aggregate id, a single active relay, a
batch that stops on the first failure, and blocking retries in consumers where order matters (D18, D42).

**Why `BigDecimal` and `NUMERIC`?** Exact decimal amounts, no floating point. Converting to paise happens only
in the provider adapter (D15).

**How would you scale it?** Replicas of each service (stateless), separate Postgres instances per service,
partitions per topic, CDC instead of polling, Kafka and Postgres in high-availability setups, and shedding
load at the gateway. The load test shows where one box tops out.

**What about PCI DSS?** The hosted checkout keeps card data out of scope (SAQ A-style). The rest is
access control, audit logs, encryption at rest for PII, secrets out of git (gitleaks in CI) and TLS at the
ingress ([09](09-security.md)).

**What would you do differently?** Start with Testcontainers-based failure tests even earlier; they found
real bugs (the 500 on a database outage, a stale DNS cache in the gateway). Use a schema registry for events
once there are several teams.

## 5. Honest limitations (say them before you are asked)

- The Razorpay adapters are tested against WireMock only; they have **not** run against the real sandbox
  (see [razorpay-sandbox-checklist.md](razorpay-sandbox-checklist.md) for what would be checked).
- HA has been tested with two replicas in Compose and the Helm chart is validated, but it has not run on a real
  multi-node cluster; no multi-region, no managed Kafka or Postgres.
- No Alertmanager routing: alerts are defined and visible in Prometheus, but nobody gets paged.
- The webhook SSRF guard resolves the hostname, then the HTTP client resolves it again; a DNS answer that
  changes in between (rebinding) is a narrow remaining gap. The fix is to connect to the checked IP.
- Load numbers come from a shared 4-vCPU box with debug-friendly JVM settings; they are a floor, not a capacity.
- Events have no schema registry; contracts are Java records in `platform/event-contracts`. Fine for one
  team, the first thing to add with several.
