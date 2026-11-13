# Kafka Event Catalogue

Every example below was **captured from the running system** (`outbox_events`) during
`scripts/smoke-test-phase4.sh`, then had its ids and patient details replaced with placeholders. The
envelope is described in [06-kafka-events.md](../06-kafka-events.md) §3; the Java records live in
`platform/event-contracts`.

| Topic | Key | Produced by | Consumed by |
|---|---|---|---|
| `merchant.snapshots.v1` (compacted) | `merchantId` | merchant-service | payment-service, notification-service, api-gateway |
| `payment.events.v1` | `paymentId` | payment-service | notification-service (ledger-service from Phase 5) |
| `notification.events.v1` | `notificationId` | notification-service | nobody yet: alerting and a future operations tool |

**Compatibility.** Within an `eventVersion`, only optional fields are added, and consumers are
configured to ignore fields they do not know. A change that is not additive gets a new
`eventVersion`; a consumer that meets a version newer than it understands dead-letters the record
rather than guessing (`MerchantSnapshotConsumer`), because guessing at payment limits is worse than
stopping.

**Personal data.** Payment events carry patient name, email and phone, because notification-service
needs them to send a receipt. Nothing else does: the hospital-facing webhook body leaves them out, and
`NotificationFailed` carries no recipient at all. No event ever carries card data, a webhook signing
secret, or anything clinical.

## `MerchantSnapshot`

**Topic:** `merchant.snapshots.v1`

The merchant's whole configuration, not a diff, because the topic is compacted: the newest record per merchant is kept for ever, so a consumer can rebuild its replica from the beginning of the topic. `payload.version` only ever increases; a consumer ignores anything not newer than what it holds, which makes redelivery and replay harmless. **No signing secret** — notification-service fetches that over an authenticated call.

```json
{
  "eventId": "01933f7e-0000-7000-8000-00000000000e",
  "eventType": "MerchantSnapshot",
  "eventVersion": 1,
  "occurredAt": "2026-11-13T05:58:21.513565436Z",
  "producer": "merchant-service",
  "aggregateType": "Merchant",
  "aggregateId": "b1e7c3d2-0000-7000-8000-000000000001",
  "aggregateVersion": 4,
  "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
  "correlationId": "49c0ea16-c917-455d-b2f8-396dd3636e95",
  "payload": {
    "status": "SUSPENDED",
    "version": 4,
    "legalName": "City Hospital Pvt Ltd",
    "minAmount": {
      "amount": "1.00",
      "currency": "INR"
    },
    "updatedAt": "2026-11-13T05:58:18.927206Z",
    "dailyLimit": {
      "amount": "1000000.00",
      "currency": "INR"
    },
    "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
    "webhookUrl": "http://host.docker.internal:9099/hooks",
    "captureMode": "AUTO",
    "displayName": "City Hospital",
    "maxAttempts": 3,
    "enabledMethods": [
      "CARD",
      "UPI"
    ],
    "notifyPatientSms": true,
    "refundWindowDays": 180,
    "paymentTtlMinutes": 60,
    "notifyPatientEmail": true,
    "maxAmountPerTransaction": {
      "amount": "200000.00",
      "currency": "INR"
    }
  }
}
```

## `PaymentInitiated`

**Topic:** `payment.events.v1`

A hospital asked us to collect an amount. Written in the same transaction as the payment row, so the event cannot exist without the payment or the payment without the event.

```json
{
  "eventId": "01933f7e-0000-7000-8000-00000000000e",
  "eventType": "PaymentInitiated",
  "eventVersion": 1,
  "occurredAt": "2026-11-13T05:58:20.166298354Z",
  "producer": "payment-service",
  "aggregateType": "Payment",
  "aggregateId": "3f9c2a51-0000-7000-8000-000000000002",
  "aggregateVersion": 1,
  "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
  "correlationId": "a16f5739-9e81-4518-8909-7aedf25d18e9",
  "causationId": "a16f5739-9e81-4518-8909-7aedf25d18e9",
  "payload": {
    "amount": {
      "amount": "20000.00",
      "currency": "INR"
    },
    "status": "CREATED",
    "paymentId": "3f9c2a51-0000-7000-8000-000000000002",
    "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
    "occurredAt": "2026-11-13T05:58:20.166174819Z",
    "captureMode": "AUTO",
    "lateCapture": false,
    "merchantReference": "BILL-174092777"
  }
}
```

## `PaymentCaptured`

**Topic:** `payment.events.v1`

The money was collected: this is the success event. `previousStatus` shows where it came from, and `lateCapture` is true when the provider confirmed a capture after we had already expired or cancelled the payment (see 02-payment-lifecycle.md §3.3). `aggregateVersion` is the payment's optimistic-lock version, passed on to hospitals as `X-HPG-Sequence` so they can ignore a stale update.

```json
{
  "eventId": "01933f7e-0000-7000-8000-00000000000e",
  "eventType": "PaymentCaptured",
  "eventVersion": 1,
  "occurredAt": "2026-11-13T05:58:20.277939224Z",
  "producer": "payment-service",
  "aggregateType": "Payment",
  "aggregateId": "3f9c2a51-0000-7000-8000-000000000002",
  "aggregateVersion": 4,
  "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
  "correlationId": "8525756b-8ec9-4687-9aa7-3b495aeff3bd",
  "causationId": "pay_sim_3UydGcUwK9GozY",
  "payload": {
    "amount": {
      "amount": "20000.00",
      "currency": "INR"
    },
    "status": "CAPTURED",
    "customer": {
      "name": "A Patient",
      "email": "patient@example.com",
      "phone": "+919800001234"
    },
    "provider": "SIMULATED",
    "paymentId": "3f9c2a51-0000-7000-8000-000000000002",
    "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
    "occurredAt": "2026-11-13T05:58:20.277819778Z",
    "captureMode": "AUTO",
    "lateCapture": false,
    "previousStatus": "PROCESSING",
    "providerOrderId": "order_sim_QNgfSI5bduCvyv",
    "merchantReference": "BILL-174092777",
    "providerPaymentId": "pay_sim_3UydGcUwK9GozY"
  }
}
```

## `NotificationFailed`

**Topic:** `notification.events.v1`

Published when a notification is given up on, so the failure can be alerted on and an operator can see
which hospital stopped accepting callbacks. There is no automatic consumer: the fix is always a human
one. It carries no recipient address — which merchant and which payment is what matters, and an email
address in a retained topic would be personal data nobody downstream needs.

*(Hand-written: the smoke test delivers everything successfully, so there was no real one to capture.
`NotificationDeliveryIT#anEndpointThatKeepsFailingIsRetriedThenDeclaredDeadAndPublished` asserts it.)*

```json
{
  "eventId": "01933f7e-0000-7000-8000-00000000001a",
  "eventType": "NotificationFailed",
  "eventVersion": 1,
  "occurredAt": "2026-11-13T06:10:00.000Z",
  "producer": "notification-service",
  "aggregateType": "Notification",
  "aggregateId": "7c1d2e3f-0000-7000-8000-000000000003",
  "aggregateVersion": 12,
  "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
  "correlationId": "his-req-000981",
  "causationId": "01933f7e-0000-7000-8000-00000000000e",
  "payload": {
    "notificationId": "7c1d2e3f-0000-7000-8000-000000000003",
    "merchantId": "b1e7c3d2-0000-7000-8000-000000000001",
    "paymentId": "3f9c2a51-0000-7000-8000-000000000002",
    "sourceEventId": "01933f7e-0000-7000-8000-00000000000e",
    "sourceEventType": "PaymentCaptured",
    "channel": "WEBHOOK",
    "recipientType": "MERCHANT",
    "attempts": 12,
    "lastError": "endpoint returned 503",
    "failedAt": "2026-11-13T06:10:00.000Z"
  }
}
```
