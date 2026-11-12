# Decision Log

Lightweight ADRs. Status: **Proposed** until you approve Phase 1. Items marked ⚠️ deviate from the
original brief and need your explicit OK.

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
