# Healthcare Payment Gateway (HPG) — Architecture Documentation

> Status: **Phase 1 — architecture proposal, awaiting approval.** No service code exists yet.
> Everything below is a design. Where a statement depends on Razorpay behaviour, it is marked
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
| — | [Decision log](decisions.md) | Every significant decision, the alternatives, and why |

## Implemented vs simulated (kept up to date every phase)

| Capability | Status |
|---|---|
| Everything | **Designed only** (Phase 1) |

This table will be maintained honestly as phases land: each row will say *Real*, *Real (sandbox)*,
*Simulated* or *Not implemented*.
