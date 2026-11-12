# Razorpay Sandbox Verification Checklist

**Status: not yet run — no Razorpay test keys available.**

The Razorpay adapter (`services/payment-service/.../provider/razorpay/RazorpayPaymentProvider.java`) was
written from Razorpay's public API documentation and tested against **WireMock stubs of those documented
shapes**. That proves our request building, parsing, signature checks, error mapping and timeout
behaviour — it does **not** prove that Razorpay behaves exactly as documented. Each item below is
marked *verify* in the code. Run through this list once you have test-mode keys.

## Setup

1. Razorpay Dashboard → switch to **Test Mode** → Settings → API Keys → generate `rzp_test_...` keys.
2. Settings → Webhooks → add `https://<public-tunnel>/webhooks/razorpay/payments`, choose a secret,
   select events `payment.authorized`, `payment.captured`, `payment.failed`, `order.paid`.
   A public HTTPS URL to your laptop needs a tunnel, e.g. `cloudflared tunnel --url http://localhost:8080`.
   (Without a tunnel the flow still completes: the recovery job polls Razorpay.)
3. In `deploy/env/.env`: `HPG_PAYMENT_PROVIDER=razorpay`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`,
   `RAZORPAY_WEBHOOK_SECRET`. Restart payment-service.

## Items to verify

| # | Assumption in our code | How to verify | Result |
|---|---|---|---|
| 1 | `POST /v1/orders` accepts `payment.capture = automatic / manual` at order level | Create a MANUAL-mode payment; pay with a test card; it should stop at `authorized` | ☐ |
| 2 | Order response has `id`, `amount` (paise), `currency`, `status` | Inspect payment-service logs / `payment_orders` | ☐ |
| 3 | Checkout `handler` returns `razorpay_payment_id`, `razorpay_order_id`, `razorpay_signature`; signature = hex HMAC-SHA256(`order_id|payment_id`, key secret) | Pay on the hosted page; callback must be accepted (not 400) | ☐ |
| 4 | Checkout option `method: {card, upi, netbanking, wallet}` restricts shown methods | Disable NETBANKING in merchant config; it should not be offered | ☐ |
| 5 | `GET /v1/orders/{id}/payments` returns `items[]` with `status` in created/authorized/captured/refunded/failed | Trigger recovery with a lost callback (close the tab after paying) | ☐ |
| 6 | `POST /v1/payments/{id}/capture` with `amount` = authorized amount; repeating it returns a 400 we can recover from by fetching the payment | Capture twice with different Idempotency-Keys; second must still answer CAPTURED | ☐ |
| 7 | Partial capture is not supported | Not offered by our API; confirm in docs | ☐ |
| 8 | Webhook header `X-Razorpay-Signature` = hex HMAC-SHA256(raw body, webhook secret); `X-Razorpay-Event-Id` present | Webhook must be APPLIED (not 401) | ☐ |
| 9 | Webhook body: `event`, `payload.payment.entity`, `created_at` (epoch seconds) | `webhook_inbox.payload` | ☐ |
| 10 | Failed-delivery retries last about 24 h (our replay window is 48 h) | Razorpay docs / dashboard | ☐ |
| 11 | Uncaptured authorizations are auto-refunded after a capture window (ours: `hpg.payment.capture-window`, 5 days) | Razorpay docs / account settings | ☐ |
| 12 | Payment pages may load `checkout.razorpay.com` and frame/connect to `api.razorpay.com` (our CSP) | Browser console shows no CSP violations | ☐ |
| 13 | Error body shape `{"error":{"code","description"}}` | Create an order below the minimum amount | ☐ |

Record findings here and in the decision log; fix the adapter (and its WireMock stubs) where reality differs.
