# 11 — Failure Scenarios & Recovery

Each of the ten required scenarios: what happens, which mechanism handles it, and which automated
test proves it (test names are the plan for Phase 8; many are written earlier with the feature).

| # | Scenario | What could go wrong | Mechanism | Test |
|---|---|---|---|---|
| 1 | **Patient clicks Pay twice** | Two charges for one bill | (a) Hospital retries with same `Idempotency-Key` → replay. (b) Different keys, same bill → partial unique index on `(merchant_id, merchant_reference)` for live payments → `409 DUPLICATE_PAYMENT`. (c) Double submit on checkout → one provider order; a second capture is flagged `DUPLICATE_CAPTURE` and surfaces in recon. | `IdempotencyConcurrencyIT`: 20 threads, same key → 1 payment, 19 replays/409s. `DuplicateBillReferenceIT`. |
| 2 | **Provider times out** | Hung threads; unknown outcome | Connect/read timeouts; circuit breaker; bulkhead. Order creation: payment stays `CREATED`, retried later (harmless). Capture: `UNKNOWN`, verified by polling before any retry. Refund: `UNKNOWN`, provider queried for our `refund_id` before any retry. | WireMock `withFixedDelay` > read timeout: `CreateOrderTimeoutIT`, `CaptureTimeoutGoesUnknownIT`, `RefundTimeoutNoDoubleRefundIT`. |
| 3 | **Provider succeeds, we never get the response** | Patient charged, we think unpaid | Three convergent signals: checkout callback, webhook, recovery polling. Expiry **asks the provider before expiring**. Late capture after expiry → provider-evidence override to `CAPTURED`, flagged. | `LostResponseRecoveryIT`: WireMock returns success after delay > timeout; recovery job converges to `CAPTURED`. `ExpiryChecksProviderFirstIT`. |
| 4 | **Webhook arrives twice** | Double ledger posting, double notification | `webhook_inbox` unique on provider event id; state machine no-op for same-state; downstream idempotent consumers. | `DuplicateWebhookIT`: same signed body posted twice → 1 transition, 1 outbox event. |
| 5 | **Webhook arrives out of order** | `authorized` overwriting `captured` | Forward-only state machine; stale events recorded as `STALE_IGNORED`, acknowledged `200`. | `OutOfOrderWebhookIT`: captured then authorized → stays `CAPTURED`. Unit tests on `PaymentStateMachine` for every illegal edge. |
| 6 | **Kafka unavailable** | Events lost; API failing | API writes only to Postgres (outbox); relay retries; backlog drains when Kafka returns. Consumers simply pause. | `OutboxSurvivesKafkaOutageIT`: pause Kafka container, create payments (201), unpause, assert all events delivered in order. |
| 7 | **Consumer processes same event twice** | Double posting/notification | `processed_events` in same transaction + natural unique constraints (`transactions.source_event_id`, `notifications (source_event_id, channel, recipient_type)`). | `LedgerIdempotentConsumerIT`, `NotificationDedupIT`: publish same event twice → one row. |
| 8 | **Database unavailable** | Partial writes; cascading failures | All writes in transactions → no partial state. Hikari connection timeout (3 s) → `503 SERVICE_UNAVAILABLE`. Readiness fails → removed from LB. Webhooks get `503` → Razorpay retries later. Kafka consumers fail → blocking retry, offsets not committed → reprocessed after recovery. | `DatabaseDownIT`: stop Postgres container → API returns 503 problem detail; restart → succeeds. |
| 9 | **Refund exceeds captured amount** | Over-refund, including via concurrent partial refunds | Atomic conditional `UPDATE` reservation + DB `CHECK` constraint. | `RefundValidationTest` (unit), `ConcurrentPartialRefundsIT`: 10 parallel refunds of 30% each on one payment → exactly 3 succeed. |
| 10 | **Settlement report has missing or duplicate transactions** | Silent money leakage | Matching engine statuses `MISSING_IN_PROVIDER`, `MISSING_IN_INTERNAL_SYSTEM`, `DUPLICATE`; duplicate file upload rejected by checksum; nothing auto-corrected. | `ReconciliationMatcherTest` (table-driven unit tests), `SettlementCsvIT` with sample files. |

## Recovery workflows (summary)

| Stuck condition | Detected by | Recovery |
|---|---|---|
| Payment `CREATED` (order not created) | payment recovery job | create order |
| Payment `PROCESSING` > 2 min / `UNKNOWN` | payment recovery job | fetch provider status, apply |
| Payment past TTL | expiry job | verify with provider, then expire or capture |
| Refund `UNKNOWN` | refund recovery job | look up provider refunds by our `refund_id`; adopt or retry |
| Refund `PROCESSING` > 1 h | refund recovery job | fetch refund status |
| Outbox rows unpublished | relay + alert | automatic once Kafka returns |
| Event in DLT | alert | fix cause, replay (consumers are idempotent) |
| Notification `DEAD` | alert + `NotificationFailed` | hospital fixes endpoint; ops re-queues |
| Ledger/provider mismatch | reconciliation | manual resolution, optional reversal |

## Compensation, stated honestly

There is no distributed transaction across services. Instead:

- **Forward recovery** is preferred: retry until the provider-confirmed truth is reached.
- **Compensating actions** are explicit, audited business operations: a refund compensates an
  unwanted capture; a ledger reversal compensates a wrong posting. Neither is ever done silently
  by a background job in v1 — the system surfaces the condition and a merchant or ops user decides.

## Load testing (Phase 8)

A k6 script (lightweight, runs outside Docker) for `POST /payments` + `GET /payments/{id}` against
the `provider-sim` profile, reporting p50/p95/p99 and error rate. Results will be reported as
measured on your laptop, with the caveat that laptop numbers say nothing about production capacity.
