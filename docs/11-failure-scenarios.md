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

### Automated tests (complete as of Phase 8)

The planned test names above were the design; these are the tests that exist and prove each scenario.

| # | Automated test(s) |
|---|---|
| 1 | `PaymentApiIT.Creation#concurrentRequestsWithTheSameKeyCreateExactlyOnePayment` (12 threads), `#sameIdempotencyKeyReplays…`, `#secondLivePaymentForTheSameBillIsRejected…`, `Webhooks#secondCaptureOnTheSameOrderIsFlaggedAsDuplicateCharge` |
| 2 | `RazorpayPaymentProviderTest#slowResponseIsATimeoutWithUnknownOutcome`, `#timedOutCaptureIsNeverRetried`, `#circuitOpensAfterRepeatedFailures…`; `PaymentApiIT.Lifecycle#captureTimeoutBecomesUnknownAndIsResolvedByVerification`, `Creation#providerOutageAtCreationLeavesPaymentCreatedAndRecoveryCompletesIt`; `RefundFlowIT#timedOutRefundIsLookedUpBeforeAnyRetry` |
| 3 | `PaymentApiIT.Checkout#lostProviderResponseIsRecoveredByPolling`, `#paymentWithNoCallbackAndNoWebhookIsFoundByPolling`, `Lifecycle#expiryAsksTheProviderFirst`, `#captureAfterExpiryIsAppliedAndFlaggedAsLate`; `SimulatedProviderIT#lostCallbackIsRecoveredByPolling` |
| 4 | `PaymentApiIT.Webhooks#duplicateWebhookIsAcknowledgedButProcessedOnce`; `RefundFlowIT#webhooksOlderThan48HoursAreAcknowledgedButNotApplied` (replay window) |
| 5 | `PaymentApiIT.Webhooks#outOfOrderWebhookDoesNotMoveThePaymentBackwards`; `PaymentStateMachineTest` (all 243 transitions) |
| 6 | `OutboxSurvivesKafkaOutageIT` (pauses the Kafka container: payments still `201`, nothing published or lost, every event delivered in order per payment after Kafka returns); `OutboxRelayIT` |
| 7 | `TransactionFlowIT#duplicateEventsAreBookedOnce`, `NotificationFlowIT#completedRefundNotifiesTheHospitalOnce` (same event delivered twice) |
| 8 | `DatabaseDownIT` (stops the Postgres container: `503` + `Retry-After` within the 3 s pool timeout, the retried request with the same Idempotency-Key creates exactly one payment once the database is back, no restart needed); `GlobalExceptionHandlerTest` |
| 9 | `RefundFlowIT#concurrentRefundsNeverExceedTheCapturedAmount`, `#partialRefundIsProcessedByWebhookAndCannotExceedTheCapturedAmount`, `#rejectedRefundReleasesTheReservation` |
| 10 | `MatchingEngineTest` (table-driven, every status incl. cross-day duplicates), `SettlementCsvParserTest`, `ReconciliationFlowIT` (sample files, duplicate upload `409`) |

`DatabaseDownIT` found a real bug: a stopped database surfaced as "Unable to rollback" on a dead pooled
connection and was answered `500`. Connection failures anywhere in the cause chain are now `503`.

### Beyond the ten scenarios (Phase 10: resilience between services)

| Failure | Mechanism | Proof |
|---|---|---|
| A service is overloaded | Load shedding in every service: at most 40 requests in flight per instance, then `503 SERVICE_BUSY` + `Retry-After` within 250 ms, before any work (health checks never shed) | `LoadSheddingFilterTest`; load test: no 500s at 3× capacity |
| A service is down or hangs | Gateway circuit breaker per service: half of the last 20 calls failed (refused, dropped, timed out) → fail fast with 503 for 10 s, then probe. A service's own error answers pass through unchanged | `ApiGatewayIT#aFailingServiceTripsItsCircuitBreaker…` |
| A replica dies mid-request | Gateway retries GETs on connection errors and 502/503 (another replica); writes are never repeated by the gateway, clients retry them with their Idempotency-Key | `ApiGatewayIT#safeReadsAreRetried…`; chaos test |
| A query hangs (lock, overloaded database) | Postgres `statement_timeout` (30 s) on every connection; a cancelled statement is `503` | `GlobalExceptionHandlerTest#statementTimeoutIs503` |
| An event fails every retry | Dead-letter topic, then inspection and replay by operations (`/api/v1/admin/dead-letters`); consumers deduplicate, so a replay acts once | `TransactionFlowIT#deadLettersCanBeInspectedAndReplayedOnce` |
| Everything at once | `scripts/chaos-test.sh` while 6 clients pay and refund: kills replicas of each service, pauses Kafka 20 s, stops Postgres 15 s, kills the gateway, kills all payment-service replicas | Measured: 102 failed requests retried with the same key, 0 lost, 236 payments and 115 refunds each booked exactly once, ledger balanced |

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

`scripts/load/payments.js` (k6) and `scripts/load/LoadTest.java` (JDK only) run `POST /payments` +
`GET /payments/{id}` at a fixed arrival rate against the simulated provider. Measured results and the
bottleneck analysis: [load-test-report.md](load-test-report.md). Under overload the service answers `503`
with `Retry-After`; nothing is lost or half-written.
