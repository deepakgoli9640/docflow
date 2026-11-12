# 04 — Transaction & Ledger Service

## 1. Why a ledger at all

Payment and refund tables describe *operational state* ("what is this payment doing?"). They change.
The ledger describes *money* ("who owes whom, and why?"). It never changes — it only grows.
Finance, merchant statements and reconciliation read the ledger, not the payment tables.

## 2. Double-entry model

Every financial event is a **transaction** (journal header) made of two or more **entries**.
Each entry debits or credits one **account**. For every transaction:

```
sum(debit amounts) == sum(credit amounts)      (per currency)
```

### 2.1 Accounts

| Account code | Type | Scope | Meaning |
|---|---|---|---|
| `PROVIDER_CLEARING` | Asset | platform | Money collected and held by the provider, not yet settled to our bank |
| `SETTLEMENT_BANK` | Asset | platform | Money the provider has settled to our bank account |
| `MERCHANT_PAYABLE:{merchantId}` | Liability | per merchant | What we owe each hospital |
| `PROVIDER_FEES` | Expense | platform | Provider fees + tax deducted at settlement |

Normal balances: assets and expenses are debit-normal; liabilities are credit-normal.

### 2.2 Postings

**Payment captured — ₹20,000**

| Account | Debit | Credit |
|---|---:|---:|
| PROVIDER_CLEARING | 20,000.00 | |
| MERCHANT_PAYABLE:hospital-A | | 20,000.00 |

**Partial refund completed — ₹5,000**

| Account | Debit | Credit |
|---|---:|---:|
| MERCHANT_PAYABLE:hospital-A | 5,000.00 | |
| PROVIDER_CLEARING | | 5,000.00 |

**Settlement matched by reconciliation — gross ₹15,000, fee ₹354 (optional, Phase 6 stretch)**

| Account | Debit | Credit |
|---|---:|---:|
| SETTLEMENT_BANK | 14,646.00 | |
| PROVIDER_FEES | 354.00 | |
| PROVIDER_CLEARING | | 15,000.00 |

Who bears provider fees (platform or hospital) is a commercial decision; this design books them as a
platform expense and keeps the hospital payable at gross. That choice is isolated to one posting rule.

**Correction (never update, never delete) — reverse a wrongly posted transaction**

A new transaction of type `REVERSAL` with `reverses_transaction_id` pointing at the original and
each entry mirrored (debits become credits). Then, if needed, a new correct transaction.
The original stays visible forever. This is the rule every auditor expects.

## 3. Immutability — enforced in three layers

1. **Application:** repositories expose only `save` for new rows; entities have no setters for
   financial fields; there is no update/delete use case.
2. **Database permissions:** the `ledger_app` role is granted `INSERT, SELECT` on `transactions`
   and `ledger_entries` — **no `UPDATE`, no `DELETE`**.
3. **Database triggers:** a `BEFORE UPDATE OR DELETE` trigger raises an exception, so even a
   superuser mistake through the app connection fails loudly.

Balance is enforced by a **deferred constraint trigger** that, at commit, checks that the
transaction's entries balance. An unbalanced transaction cannot be committed even if a bug skips
the Java check.

## 4. Idempotent posting

- `transactions.source_event_id` is `UNIQUE`. Posting the same `PaymentCaptured` twice violates
  the constraint; the consumer treats that as "already done" and acknowledges.
- Consumer-side dedupe (`processed_events`) is also used, but the unique constraint is the real
  guarantee: it lives in the same transaction as the postings.

## 5. Ordering across topics

`RefundCompleted` (from `refund.events.v1`) can be consumed before `PaymentCaptured`
(from `payment.events.v1`) because they are on different topics with different lag. Ledger checks
that the capture transaction for that payment exists; if not, it throws a
`PrerequisiteNotMetException`, which the Kafka error handler retries **in place** (blocking retry,
exponential backoff) — preserving partition order — and dead-letters after the retry budget
(~5 minutes), raising an alert. In practice the capture arrives within milliseconds.

## 6. Balances

Balances are computed as `SUM` over entries per account, served by an index on
`(account_id, created_at)`. This is simple and always correct. When entry volume makes it slow, the
production pattern is a periodic `account_balance_snapshots` table (balance as of entry id N) plus
the delta since the snapshot. Not built now — documented as the scaling path.

## 7. Integrity check

`GET /api/v1/admin/ledger/integrity` (OPS role) verifies:

- every transaction balances,
- total debits == total credits across the ledger,
- every `PaymentCaptured` / `RefundCompleted` consumed has exactly one transaction,
- no negative `MERCHANT_PAYABLE` balance (a sign of a refund posted without a capture).

Results are exposed as metrics so violations alert.

## 8. Merchant transaction history and reports

- `GET /api/v1/transactions` — the merchant's own transactions, filter by date range/type,
  paginated and sorted. Scoped by the `merchant_id` from the JWT.
- `GET /api/v1/reports/summary?from=&to=` — counts and totals of captures, refunds and net
  payable for the period.
- Each transaction has a human-friendly, unique `transaction_ref` (e.g. `TXN-20261112-000123`)
  that hospitals can quote to support.
