# Load test report (Phase 8)

**Scope:** payment creation and payment reads through the API gateway, on one machine, with the simulated
provider. These numbers describe *this* setup. They say nothing about production capacity, which needs real
hardware, several replicas and the real provider's latency.

## Setup

| | |
|---|---|
| Machine | 4 vCPU (Intel Xeon 2.1 GHz), 16 GB RAM, Linux; load generator on the same machine |
| Stack | Every Compose profile: 7 Spring services, Postgres, Kafka, Redis, Mailpit, dashboard, Prometheus, Grafana, Jaeger (15 containers) |
| JVM | The local container settings: SerialGC, `-XX:TieredStopAtLevel=1` (C1 JIT only), 512 MB limit per service |
| Observability | On, worst case: 100 % trace sampling exported to Jaeger, JSON logs, Prometheus scraping every 15 s |
| Provider | Simulated (in-memory, no network latency) |
| Scenario | Each iteration: `POST /api/v1/payments` (new Idempotency-Key and reference, ₹10), then `GET /api/v1/payments/{id}`, with a hospital token |
| Load model | Open (constant arrival rate): new iterations start on schedule even when the system slows down, as real hospitals would |
| Tenancy | 8 hospitals, round-robin: the gateway limits each hospital to 50 requests/s, so the limit is not what is measured |
| Tool | `java scripts/load/LoadTest.java` (JDK only). `scripts/load/payments.js` is the same scenario for k6 |

Each step ran for 60 s (repeat runs: 30-40 s) against a warmed-up stack.

## Results

Latency is measured at the client, through the gateway (token check, rate limiter in Redis, routing), the
service (JWT check, merchant replica, idempotency, daily-limit check under the per-merchant lock, payment row,
status history and outbox row in one transaction) and back.

| Iterations/s | Requests/s | POST p50 | POST p95 | POST p99 | GET p50 | GET p95 | GET p99 | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 40 | 27 ms | 39 ms | 49 ms | 11 ms | 18 ms | 26 ms | 0 % |
| 40 | 80 | 26 ms | 56 ms | 95 ms | 11 ms | 24 ms | 43 ms | 0 % |
| 60 | 120 | 35 ms | 104 ms | 143 ms | 14 ms | 49 ms | 78 ms | 0 % |
| 60 (repeat) | 120 | 57 ms | 3.1 s | 4.5 s | 22 ms | 1.8 s | 3.1 s | 1.6 % (503) |
| 80 | 160 | 0.7 s | 2.7 s | 4.1 s | 0.35 s | 1.7 s | 2.9 s | 0.5 % (503) |
| 80 (first run) | 160 | 2.0 s | 4.1 s | 6.9 s | 0.34 s | 3.3 s | 4.8 s | 45 % (503, 502) |
| 120 | 240 | 1.0 s | 6.1 s | 8.3 s | n/a | n/a | n/a | 97 % (503, timeouts) |

After every run the event pipeline had caught up within seconds: no pending outbox rows in any service, no
dead-letter records, ledger integrity check 0, and transaction-service held exactly one record per payment
(14,657 payments, 14,657 payment transactions across all runs).

## What limits it

**CPU.** At 80 iterations/s the 4 vCPUs were saturated (load average 7): payment-service ~135 % of a core,
gateway ~65 %, Postgres ~55 %, transaction-service ~25 %, Kafka 10-80 %. The services run C1-compiled code
with full tracing on, and the load generator shares the machine.

**How it fails.** Past about 60 iterations/s, latency rises, requests pile up (up to 900 in flight at 120/s),
each holds a Tomcat thread while waiting for one of payment-service's 10 database connections, and after 3 s
the wait ends in **`503 SERVICE_UNAVAILABLE` with `Retry-After`** (and some `502` from the gateway when the
service did not answer in time). Nothing is half-written: the idempotency key makes the hospital's retry safe,
and no event is lost or duplicated.

The 60 and 80 iterations/s steps were not stable from run to run: this is the knee, where a short pause (GC,
a burst of Kafka consumer work, the relay) is enough to tip the queue over.

## What would raise it (not done; recorded for production planning)

1. **More replicas.** payment-service is stateless apart from the database; the relay already elects one
   publisher per database (advisory lock). Two or three replicas behind the gateway is the first lever.
2. **Shed load early.** Cap Tomcat's threads near the connection pool size and return 503 at once when the
   queue is full, instead of after a 3 s pool wait. Overload would then fail fast with a short tail.
3. **Production JVM settings.** C2 JIT (drop `TieredStopAtLevel=1`), G1, and a larger heap.
4. **Sample traces** (`HPG_TRACE_SAMPLING=0.1` or tail sampling in a collector) instead of 100 %.
5. **Separate the load generator** from the system under test.

## Reproduce

```bash
# Stack with the simulated provider (default in deploy/env/.env)
docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile refund \
  --profile transactions --profile notify --profile recon --profile obs up -d --build

java scripts/load/LoadTest.java --rate 40 --duration 60 --merchants 8
# or with k6:
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-) \
  k6 run -e OPS_SECRET=$OPS_SECRET -e RATE=40 -e MERCHANTS=8 scripts/load/payments.js
```

Watch it on Grafana (http://localhost:3000): *HPG Payments* (rate, latency per service, 5xx) and *HPG Event
pipeline* (outbox backlog, consumer lag).
