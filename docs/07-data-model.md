# 07 — Data Model & Database Ownership

## 1. Ownership rules

- **One logical database per service** (`merchant_db`, `payment_db`, `refund_db`, `ledger_db`,
  `notification_db`, `recon_db`), each with **its own database user** that has privileges only on
  its own database. A service physically cannot query another service's tables.
- Locally all six databases live in **one PostgreSQL container** to save RAM. In production they
  would be separate instances (or at least separate clusters for ledger/payment), so one service's
  load or outage cannot affect another.
- Data another service needs is **replicated through events** into tables that the consumer owns
  (`merchant_config_replica`, `refundable_payments`, `internal_transactions`). Replicas are
  read-only from the owner's point of view and are rebuilt from Kafka if lost.
- **No cross-database foreign keys.** IDs from other services are stored as plain UUID columns.

## 2. Conventions (all services)

| Concern | Convention |
|---|---|
| Primary keys | `UUID` generated in the application (UUIDv7, time-ordered → index-friendly). Ledger entries and outbox use `BIGINT` identity for ordering. |
| Money | `NUMERIC(19,2)` + `currency CHAR(3)`; Java `BigDecimal` wrapped in a `Money` value object. Never `double`/`float`. Paise conversion only inside the Razorpay adapter. |
| Timestamps | `TIMESTAMPTZ`, application and JVM run in UTC (`-Duser.timezone=UTC`, `hibernate.jdbc.time_zone=UTC`). |
| Audit fields | `created_at`, `updated_at`, `created_by`, `updated_by` (Spring Data JPA auditing) on mutable tables. |
| Optimistic locking | `version BIGINT NOT NULL` + `@Version` on every mutable aggregate. |
| Enums | `VARCHAR` + `CHECK` constraint (readable, easy to extend with a migration; Postgres enums are awkward to change). |
| Migrations | Flyway, `V{n}__description.sql`, forward-only. Never edit an applied migration. |
| Soft delete | Not used for financial data. Financial rows are immutable; corrections are new rows. |
| PII at rest | Patient email/phone and hospital webhook secrets encrypted with AES-GCM via a JPA `AttributeConverter`; key from env / secrets manager. |

## 3. merchant_db

```mermaid
erDiagram
    merchants ||--o{ merchant_credentials : has
    merchants ||--|| merchant_configurations : has
    merchants ||--o{ merchant_audit_log : has

    merchants {
        uuid id PK
        varchar legal_name
        varchar display_name
        varchar contact_email UK
        varchar status "PENDING_ACTIVATION ACTIVE SUSPENDED DEACTIVATED"
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    merchant_credentials {
        uuid id PK
        uuid merchant_id FK
        varchar key_id UK "public client id"
        varchar secret_hash "bcrypt - secret shown once"
        varchar status "ACTIVE REVOKED"
        timestamptz expires_at
        timestamptz last_used_at
        timestamptz revoked_at
    }
    merchant_configurations {
        uuid merchant_id PK
        jsonb enabled_methods "card upi netbanking"
        varchar capture_mode "AUTO MANUAL"
        numeric min_amount
        numeric max_amount_per_txn
        numeric daily_limit
        int payment_ttl_minutes
        int max_attempts
        int refund_window_days
        varchar webhook_url
        varchar webhook_secret_enc "AES-GCM"
        boolean notify_patient_email
        boolean notify_patient_sms
        bigint version
    }
    merchant_audit_log {
        bigint id PK
        uuid merchant_id FK
        varchar action
        varchar actor
        jsonb details "masked"
        timestamptz created_at
    }
```

Plus `outbox_events`, `shedlock`. Spring Authorization Server's registered-client lookup is backed by
`merchant_credentials` (custom `RegisteredClientRepository`), so we do not add its default tables.

## 4. payment_db

```mermaid
erDiagram
    payments ||--o{ payment_orders : "provider orders"
    payments ||--o{ payment_attempts : "patient attempts"
    payment_orders ||--o{ payment_attempts : "made on"
    payments ||--o{ payment_status_history : audit

    payments {
        uuid id PK
        uuid merchant_id "index"
        varchar merchant_reference "hospital bill ref"
        numeric amount
        char currency
        varchar status
        varchar capture_mode
        varchar customer_name
        varchar customer_email_enc
        varchar customer_phone_enc
        varchar description
        varchar checkout_token UK
        timestamptz expires_at
        timestamptz captured_at
        boolean late_capture
        varchar failure_code
        varchar failure_reason
        varchar correlation_id
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    payment_orders {
        uuid id PK
        uuid payment_id FK
        varchar provider "RAZORPAY"
        varchar provider_order_id UK
        numeric amount
        char currency
        varchar provider_status
        timestamptz created_at
    }
    payment_attempts {
        uuid id PK
        uuid payment_id FK
        uuid payment_order_id FK
        varchar provider_payment_id UK
        varchar method
        varchar status "AUTHORIZED CAPTURED FAILED"
        numeric amount
        varchar error_code
        varchar error_description
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    payment_status_history {
        bigint id PK
        uuid payment_id FK
        varchar from_status
        varchar to_status
        varchar trigger_source "API CALLBACK WEBHOOK RECOVERY"
        varchar reason
        timestamptz created_at
    }
```

Supporting tables in `payment_db`:

| Table | Key columns |
|---|---|
| `idempotency_keys` | PK `(merchant_id, operation, idempotency_key)`, `request_hash`, `status`, `resource_id`, `response_status`, `expires_at` |
| `outbox_events` | `id BIGINT identity`, `event_id UUID UK`, `aggregate_type`, `aggregate_id`, `event_type`, `topic`, `message_key`, `payload JSONB`, `headers JSONB`, `created_at`, `published_at`, `attempts`, `last_error` |
| `webhook_inbox` | `provider_event_id UK`, `event_type`, `payload JSONB`, `received_at`, `processed_at`, `status`, `outcome` (`APPLIED` / `STALE_IGNORED` / `FAILED`) |
| `merchant_config_replica` | `merchant_id PK`, status, limits, methods, capture mode, TTL, `snapshot_version` |
| `processed_events` | PK `(consumer_group, event_id)` |
| `shedlock` | ShedLock standard table |

Key indexes and constraints:

```sql
-- one live payment per hospital bill
CREATE UNIQUE INDEX ux_payments_active_reference ON payments (merchant_id, merchant_reference)
  WHERE status NOT IN ('FAILED','CANCELLED','EXPIRED');
-- merchant listing and recovery scans
CREATE INDEX ix_payments_merchant_created ON payments (merchant_id, created_at DESC);
CREATE INDEX ix_payments_recovery ON payments (status, updated_at)
  WHERE status IN ('CREATED','PENDING','PROCESSING','AUTHORIZED','UNKNOWN');
-- relay scan
CREATE INDEX ix_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount_positive CHECK (amount > 0);
```

## 5. refund_db

```mermaid
erDiagram
    refundable_payments ||--o{ refunds : "refunded by"
    refunds ||--o{ refund_attempts : "provider calls"

    refundable_payments {
        uuid payment_id PK
        uuid merchant_id
        varchar provider_payment_id
        numeric captured_amount
        numeric reserved_amount
        numeric refunded_amount
        char currency
        timestamptz captured_at
        bigint version
    }
    refunds {
        uuid id PK
        uuid merchant_id
        uuid payment_id FK
        numeric amount
        char currency
        varchar reason
        varchar status "CREATED PROCESSING COMPLETED FAILED UNKNOWN"
        varchar provider_refund_id UK
        varchar initiated_by "MERCHANT OPS SYSTEM"
        varchar failure_code
        varchar correlation_id
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    refund_attempts {
        uuid id PK
        uuid refund_id FK
        int attempt_no
        varchar operation "CREATE FETCH"
        varchar outcome "SUCCESS REJECTED TIMEOUT ERROR"
        int http_status
        varchar error_code
        bigint latency_ms
        timestamptz attempted_at
    }
```

```sql
ALTER TABLE refundable_payments ADD CONSTRAINT ck_refund_total
  CHECK (refunded_amount >= 0 AND reserved_amount >= 0
         AND refunded_amount + reserved_amount <= captured_amount);
```

Plus `idempotency_keys`, `refund_outbox_events` (same shape as `outbox_events`), `webhook_inbox`,
`merchant_status_replica`, `processed_events`, `shedlock`.

## 6. ledger_db

```mermaid
erDiagram
    transactions ||--|{ ledger_entries : "balanced set"
    ledger_accounts ||--o{ ledger_entries : posted_to
    transactions |o--o| transactions : reverses

    ledger_accounts {
        uuid id PK
        varchar code UK "e.g. MERCHANT_PAYABLE:uuid"
        varchar account_type "ASSET LIABILITY EXPENSE INCOME"
        uuid merchant_id "null for platform accounts"
        char currency
        timestamptz created_at
    }
    transactions {
        uuid id PK
        varchar transaction_ref UK
        varchar type "PAYMENT_CAPTURE REFUND SETTLEMENT REVERSAL"
        uuid merchant_id
        uuid payment_id
        uuid refund_id
        varchar provider_reference "pay_ or rfnd_ id"
        numeric amount
        char currency
        uuid source_event_id UK
        uuid reverses_transaction_id FK
        varchar description
        timestamptz occurred_at
        timestamptz created_at
    }
    ledger_entries {
        bigint id PK
        uuid transaction_id FK
        uuid account_id FK
        varchar direction "DEBIT CREDIT"
        numeric amount "always positive"
        char currency
        timestamptz created_at
    }
```

Plus `outbox_events`, `processed_events`. Immutability and balance enforcement: see [04 §3](04-ledger.md).

## 7. notification_db

```mermaid
erDiagram
    notifications ||--o{ notification_attempts : "delivery tries"

    notifications {
        uuid id PK
        uuid source_event_id
        varchar event_type
        varchar channel "WEBHOOK EMAIL SMS"
        varchar recipient_type "MERCHANT PATIENT OPS"
        varchar recipient_enc
        uuid merchant_id
        uuid payment_id
        jsonb payload
        varchar status "PENDING SENDING DELIVERED FAILED DEAD"
        int attempt_count
        timestamptz next_attempt_at
        varchar last_error
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }
    notification_attempts {
        uuid id PK
        uuid notification_id FK
        int attempt_no
        varchar outcome
        int response_code
        varchar error
        bigint duration_ms
        timestamptz attempted_at
    }
```

`UNIQUE (source_event_id, channel, recipient_type)` — the same event can never produce two webhooks
or two emails to the same party, even if the event is consumed twice. Plus
`merchant_notification_replica`, `processed_events`, `outbox_events`, `shedlock`.

## 8. recon_db

```mermaid
erDiagram
    reconciliation_batches ||--o{ settlement_records : contains
    reconciliation_batches ||--o{ reconciliation_records : produces

    reconciliation_batches {
        uuid id PK
        date business_date
        varchar source "FILE API"
        varchar provider
        varchar file_name
        varchar file_sha256 UK
        varchar status "RUNNING COMPLETED FAILED"
        int matched_count
        int mismatched_count
        int missing_in_provider_count
        int missing_internal_count
        int duplicate_count
        int pending_review_count
        int malformed_row_count
        varchar triggered_by
        timestamptz started_at
        timestamptz completed_at
    }
    settlement_records {
        uuid id PK
        uuid batch_id FK
        int row_number
        varchar provider_entity_id
        varchar entity_type "payment refund adjustment"
        varchar provider_order_id
        numeric amount
        numeric fee
        numeric tax
        char currency
        varchar settlement_id
        timestamptz settled_at
        jsonb raw_row
        varchar parse_error
    }
    reconciliation_records {
        uuid id PK
        uuid batch_id FK
        varchar provider_reference
        varchar entity_type
        varchar internal_transaction_ref
        numeric internal_amount
        numeric provider_amount
        varchar status "MATCHED MISMATCHED MISSING_IN_PROVIDER MISSING_IN_INTERNAL_SYSTEM DUPLICATE PENDING_REVIEW"
        varchar resolution_status "OPEN IN_REVIEW RESOLVED"
        varchar resolution_type
        varchar resolution_note
        varchar resolved_by
        timestamptz resolved_at
        bigint version
    }
```

Plus `internal_transactions` (replica of ledger transactions: `transaction_ref`, type,
`provider_reference`, amount, currency, `occurred_at`, `merchant_id`), `processed_events`,
`outbox_events`, `shedlock`.

## 9. Table list vs the requested list

| Requested | Present | Notes |
|---|---|---|
| merchants, merchant_credentials, merchant_configurations | ✅ | + `merchant_audit_log` |
| payment_orders, payments, payment_attempts, idempotency_keys, outbox_events | ✅ | + `payment_status_history`, `webhook_inbox`, `merchant_config_replica` |
| refunds, refund_attempts, refund_outbox_events | ✅ | + `refundable_payments`, `idempotency_keys`, `webhook_inbox` |
| transactions, ledger_accounts, ledger_entries | ✅ | |
| notifications, notification_attempts | ✅ | |
| reconciliation_batches, reconciliation_records, settlement_records | ✅ | + `internal_transactions` |

Every added table exists for a reason stated in the lifecycle documents (audit, dedupe, or a local
replica that avoids a synchronous call).
