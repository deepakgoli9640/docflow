# 10 — Observability

## 1. Three signals, one correlation

| Signal | Tooling | Where it goes |
|---|---|---|
| Logs | Spring Boot structured logging (JSON, ECS format) to stdout | `docker compose logs` locally; optional Loki + Grafana (`logging` profile) |
| Metrics | Micrometer → Actuator `/actuator/prometheus` | Prometheus → Grafana |
| Traces | Micrometer Tracing with the OpenTelemetry bridge, OTLP export | Jaeger (all-in-one) |

Every log line carries `traceId`, `spanId`, `correlationId`, `merchantId` (when known) via MDC.
Trace context crosses Kafka in the W3C `traceparent` header (Spring Kafka observation enabled), so a
single trace shows: hospital request → payment-service → outbox relay → Kafka → ledger posting →
notification delivery.

**Correlation ID vs trace ID:** the trace ID is technical and short-lived (one trace). The
correlation ID is a business handle the hospital can send and quote to support, and it survives
into events, webhooks and asynchronous retries hours later.

## 2. Metrics

### Business

| Metric | Type | Labels |
|---|---|---|
| `payments_initiated_total` | counter | `capture_mode` |
| `payments_transitions_total` | counter | `from`, `to`, `trigger` |
| `payments_terminal_total` | counter | `status` (CAPTURED/FAILED/EXPIRED/CANCELLED) |
| `payments_late_capture_total` | counter | — (alert on any) |
| `payment_amount_captured` | distribution summary | `currency` |
| `refunds_total` | counter | `status` |
| `ledger_postings_total` | counter | `type` |
| `ledger_integrity_violations` | gauge | — (alert if > 0) |
| `recon_records_total` | counter | `status` |
| `recon_open_discrepancies` | gauge | — |
| `notifications_delivery_total` | counter | `channel`, `outcome` |

`merchant_id` is **not** a metric label — unbounded label cardinality is the classic way to take
down Prometheus. Per-merchant analysis comes from the ledger reports and logs.

### Technical

| Metric | Source |
|---|---|
| HTTP latency/throughput by route & status | `http_server_requests_seconds` (Actuator) with SLO buckets 100/250/500/1000 ms |
| Provider call latency & outcome | `provider_requests_seconds{provider, operation, outcome}` (custom timer) |
| Provider availability | Resilience4j `resilience4j_circuitbreaker_state`, failure rate, `provider_requests` outcome ratio |
| Outbox backlog | `outbox_pending_events`, `outbox_oldest_pending_age_seconds` |
| Kafka consumer lag | Kafka client metric `kafka_consumer_fetch_manager_records_lag_max` (via Micrometer) per group; `kafka-exporter` container optional in `obs` profile for broker-side lag |
| DLT volume | `kafka_dlt_records_total{topic}` |
| JVM / Hikari pool | default Micrometer binders |

## 3. Dashboards (provisioned as code in `deploy/grafana/`)

1. **Payments overview** — creation rate, capture success rate, failure reasons, p95 latency,
   payments stuck by status.
2. **Provider health** — Razorpay latency, error rate by operation, circuit-breaker state.
3. **Event pipeline** — outbox backlog per service, consumer lag per group, DLT counts.
4. **Reconciliation & ledger** — open discrepancies by status, last successful run, integrity.

## 4. Alerts (Prometheus rules)

| Alert | Condition |
|---|---|
| `PaymentSuccessRateDrop` | capture success ratio < 70% over 15 min with ≥ 20 attempts |
| `ProviderCircuitOpen` | circuit breaker open > 1 min |
| `PaymentsStuck` | payments in PROCESSING/UNKNOWN older than 15 min > 0 |
| `OutboxBacklog` | oldest unpublished event > 2 min |
| `ConsumerLagHigh` | lag > 1000 for 5 min |
| `DeadLetters` | any new DLT record |
| `LedgerIntegrityViolation` | > 0 |
| `LateCapture` | any |
| `ReconNotRun` | last successful run > 26 h ago |

Alertmanager routing is out of scope locally; alerts are visible in Prometheus/Grafana.

## 5. Health checks

- `/actuator/health/liveness` — the process is alive (no dependency checks, so a DB outage does not
  cause restart loops).
- `/actuator/health/readiness` — DB reachable; Kafka is **not** part of readiness for API services
  (the outbox lets them keep serving while Kafka is down), but is for pure consumers.
- Provider health is **not** in readiness: Razorpay being down must not take our API out of the
  load balancer. It is visible as a metric/circuit state instead.
