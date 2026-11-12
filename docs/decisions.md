# Decision Log

Lightweight ADRs. D1–D22 were **accepted** at the end of Phase 1 (D22: replace the DocFlow stub at the
repository root). Items marked ⚠️ deviate from the original brief and were explicitly approved.
Later entries record decisions made while implementing.

| # | Decision | Alternatives considered | Why |
|---|---|---|---|
| D1 | Six business services + gateway; no auth/config/discovery servers | Separate auth service; Eureka; Config Server | Merchant-service owns credentials so it issues tokens; Docker/K8s DNS replaces Eureka; env vars + profiles replace Config Server. Fewer JVMs on 8 GB. |
| D2 | Async Kafka between services; local replicas for hot-path data | Sync REST between services | Availability isn't multiplied across services; ledger/notification outages don't block payments. Cost: eventual consistency, handled explicitly ([01 §4.3](01-architecture-overview.md)). |
| D3 | One sync internal call: notification → merchant for webhook secret | Put secret in events; asymmetric signing | Secrets must not sit in retained Kafka topics. Off the hot path, cached, retried. |
| D4 ⚠️ | **One Kafka topic per aggregate** (`payment.events.v1`) with event types inside, instead of one topic per event type | Topic per event type as suggested | Kafka orders only within one partition of one topic; per-type topics break per-payment ordering. The suggested names survive as event types. |
| D5 ⚠️ | **No separate `SUCCESS` state**; `CAPTURED` is success. `payment.success` folded into `PaymentCaptured`. | Keep both | Two states meaning "money collected" invite consumers to disagree. |
| D6 | Provider-evidence override: `EXPIRED`/`CANCELLED` → `CAPTURED` allowed only on provider-confirmed capture | Strictly terminal; auto-refund | Internal state must converge to where the money actually is; hospital decides on refund. |
| D7 | Transactional outbox with polling relay | Debezium CDC; publish after commit; Kafka transactions | Atomic with business write, no extra infra. CDC is the production upgrade (~1 GB for Kafka Connect). |
| D8 | JSON events with `eventVersion`, additive-only within a version | Avro/Protobuf + Schema Registry | Readable, one less container. Registry recommended for production. |
| D9 | Idempotency keys in Postgres | Redis | Must commit atomically with the business row. |
| D10 | Redis only for gateway rate limiting + suspended-merchant deny-list | No Redis; Redis caches everywhere | Both need shared, fast, TTL-based state across gateway replicas. Nothing else justifies it. |
| D11 | Merchant auth: OAuth2 client credentials → 15-min JWT (Spring Authorization Server in merchant-service) | Raw API key on every request (Razorpay style); Keycloak | Local validation, secret sent rarely, standard protocol; Keycloak costs ~500 MB. Deny-list closes the revocation gap. |
| D12 | Razorpay Orders + Standard Checkout on a minimal page we host | Payment Links; server-side card collection | Card data never touches us; supports manual capture. Payment Links are a valid later alternative. Server-side card collection is ruled out (PCI). |
| D13 | Raw `RestClient` adapter instead of Razorpay Java SDK | Official SDK | Explicit timeouts, Micrometer observation, WireMock-friendly base URL, our own error mapping. |
| D14 | One Postgres container, one database + one user per service | Container per service; shared schema | Enforces ownership with permissions while saving RAM. Production: separate instances. |
| D15 | `NUMERIC(19,2)` + `BigDecimal` `Money` value object | `BIGINT` paise | Matches the brief ("decimal types"); paise conversion confined to the adapter. |
| D16 | Optimistic locking + bounded re-apply; conditional `UPDATE` for refund reservation | Pessimistic locks everywhere | Low contention; the one real race (concurrent refunds) is solved atomically in SQL. |
| D17 | Ledger immutability enforced by app, DB grants and triggers; deferred balance trigger | App-only checks | Defence in depth for the one table that must never be wrong. |
| D18 | Blocking retries where order matters (ledger, replicas); retry topics for notifications; DB-scheduled retries for webhook delivery (hours) | One retry style everywhere | Each matches its ordering and delay needs. |
| D19 | Reconciliation reads internal data from ledger events; never writes to ledger | Read payment DB; auto-correct | Ownership rules; brief forbids automatic financial changes. |
| D20 | Jaeger for traces; Loki optional | Tempo; ELK | Simplest OTLP-native UI; ELK is far too heavy for 8 GB. |
| D21 ⚠️ | **Spring Boot 3.5.x** (as requested) | Spring Boot 4.x (the repo's current POM uses 4.1.0) | You asked for 3.x and most interview material targets 3.x. Note: 3.5 is the last 3.x line; check its open-source support window — moving to 4.x later is mostly dependency/namespace work. |
| D22 ⚠️ | **Repository layout**: the branch currently contains an unrelated "DocFlow" Spring Boot stub at the root | (a) Replace the root with the gateway project; (b) put the gateway in a sub-folder | Your call — see the Phase 1 summary. Docs are in `docs/` either way. |
| D23 | `platform/web-support` separate from `platform/common` | One shared module | `common` stays framework-free and usable by the reactive gateway; servlet plumbing (exception handler, filters) is auto-configured only in MVC services. |
| D24 | Spring Authorization Server with a **stateless** `OAuth2AuthorizationService` | Default in-memory or JDBC authorization store | The default in-memory store keeps every issued token forever (memory leak with client-credentials); JDBC adds a table we never read. Self-contained JWTs + no introspection endpoint = nothing to store. |
| D25 | JWT signing key from PEM files, else an **ephemeral** key with a loud warning | Commit a dev key; always require files | No key material in git; zero setup locally; production must mount real keys. |
| D26 | Max 2 active API keys; last active key cannot be revoked; merchant row locked (`SELECT … FOR UPDATE`) while checking the limit | Optimistic locking | Rotation without downtime; no self-lockout. The limit check reads child rows, so optimistic locking on the merchant would not detect the race — a concurrency test proves the lock works. |
| D27 | Gateway actuator on separate port 9080 | Same port with deny rules | Prometheus can scrape inside the network, while `/actuator` is not even served on the public listener. |
| D28 | Dockerfiles package a host-built jar | Maven build stage inside Docker | Avoids a second Maven cache on an 8 GB laptop; CI builds once. |
| D29 | `deploy/env/.env` is imported only under the `local` profile | Import unconditionally | Tests must not depend on a developer's machine (this bug actually happened, and a test caught it). |
| D30 | Pin `nimbus-jose-jwt` 9.47 in the parent POM; Maven enforcer `dependencyConvergence` on | Let Maven pick | Resource server and Authorization Server pulled different versions; the enforcer rule makes such conflicts a build error instead of a runtime surprise. |
| D31 | `Authority` (MERCHANT / PROVIDER / SYSTEM) separate from `TransitionSource` | One enum | Rules are about *who may*; history wants *where from*. Stale provider events are ignored, invalid merchant requests rejected. |
| D32 | Per-merchant PostgreSQL advisory lock around payment creation | Optimistic checks; SERIALIZABLE isolation | Daily-limit and key checks are check-then-act over rows that do not exist yet; row locks cannot cover them. Cost: one merchant's creations are serialised for a few ms. |
| D33 | After a signed checkout callback, fetch the payment from the provider immediately | Trust the callback; wait for webhook | The callback proves authenticity but not the final status (authorized vs captured); one cheap GET gives the patient a definite answer. |
| D34 | Provider order created outside the DB transaction; failure after commit still returns 201 CREATED | Hold the transaction open; return an error | Never hold DB connections across network calls; never tell a client "failed" about something that was committed. |
| D35 | JDK HttpClient forced to HTTP/1.1 for the provider | Default (attempts HTTP/2 upgrade) | The h2c upgrade broke POST bodies against a plain-HTTP server in tests; one predictable protocol beats multiplexing at our volume. |
| D36 | Simulated provider selected by property, logs a warning, labels the checkout page | Spring profile; no simulator | Lets the whole flow run without keys while making it impossible to mistake for a real integration. |
| D37 | Server-rendered checkout HTML with a CSP nonce, no template engine | Thymeleaf; SPA | Four tiny pages; fewer dependencies; strict CSP is easier to reason about. |
| D38 | `platform/persistence` shared module | Copy base entity/converter per service | Third service needing the same JPA plumbing; auditing and encryption behave identically everywhere. |
| D39 | Evidence ordering: successful attempts applied before failed ones | Arrival order | Prevents a spurious FAILED → "late capture" when a poll returns several attempts at once. |
