# Testing guide: every service, every feature, end to end

A step-by-step plan for a tester. It touches every service and every feature, with the exact command to run and
the result to expect. Payments use the **simulated provider**, so no real money moves and no Razorpay account is
needed.

| Part | What | Time |
|---|---|---|
| [1](#1-set-up) | Set up and start everything | 20 min (first build) |
| [2](#2-quick-confidence-automated) | Quick confidence: run the automated suites | 15 min |
| [3](#3-session-setup) | Session setup (variables used by every test) | 2 min |
| [4](#4-feature-tests) | Feature tests, area by area (A–L) | 2–3 h |
| [5](#5-end-to-end-journeys) | End-to-end journeys across all services | 30 min |
| [6](#6-reporting-a-problem) | How to report a problem | – |

Each test has an ID (`B3`, `F2`, ...). Write down **pass / fail** and, for a failure, the `correlationId` from the
response (see [6](#6-reporting-a-problem)).

---

## 1. Set up

**You need:** Docker with Docker Compose v2, JDK 21, `curl`, `jq`, `openssl`, about **8 GB of free RAM** and a
browser. Linux, macOS, or Windows with WSL / Git Bash.

> **Windows, no terminal:** double-click `start-hpg.bat` (README, "Windows: double-click to run"). On an 8 GB
> laptop `start-hpg-lite.bat` runs only the payment flow; then parts C, D, E, G and most of F cannot be tested.
> **Cloud:** `scripts/cloud/setup-server.sh` deploys the whole stack on one server (README, "Public demo server").

```bash
git clone https://github.com/deepakgoli9640/docflow.git && cd docflow
./scripts/init-env.sh                       # random secrets in deploy/env/.env (git-ignored)
./mvnw -B -ntp -DskipTests package          # build every service
docker compose -f deploy/docker-compose.yml \
  --profile infra --profile core --profile refund --profile transactions --profile notify --profile recon \
  --profile dashboard --profile obs up -d --build --wait
./scripts/seed-demo-data.sh                 # demo hospital, payments, refunds, reconciliation; prints sign-in details
```

Keep the sign-in details the seed script prints. Optional: add `--profile auth` to the `up` command for single sign-on
(test I1), and `HPG_TRACE_EXPORT=true` in front of it for traces in Jaeger (test L3).

| URL | What |
|---|---|
| http://localhost:8080 | API gateway: the only entry point for API calls |
| http://localhost:8090 | Dashboard (hospital and operations) |
| http://localhost:8025 | Mailpit: every email the system sends |
| http://localhost:3000 | Grafana dashboards |
| http://localhost:9090 | Prometheus (targets, alert rules) |
| http://localhost:16686 | Jaeger traces |
| http://localhost:8081 … 8086 `/swagger-ui.html` | API documentation of each service |

**Ready when:** `docker compose -f deploy/docker-compose.yml --profile '*' ps` shows every service `healthy`.

---

## 2. Quick confidence (automated)

Run these first. If one fails, report it before testing by hand.

| Command | Covers | Expected last line |
|---|---|---|
| `./mvnw verify` | 580+ unit and integration tests (real Postgres/Kafka/Redis in containers) | `BUILD SUCCESS` |
| `./scripts/smoke-test-phase2.sh` | Hospitals, API keys, tokens, configuration, audit | `Phase 2 smoke test passed` |
| `./scripts/smoke-test-phase3.sh` | Payments: create, idempotency, checkout, capture, cancel | `Phase 3 smoke test passed` |
| `./scripts/smoke-test-phase4.sh` | Payment → Kafka → refunds, transactions, notifications | `Phase 4 smoke test passed` |
| `./scripts/smoke-test-phase6.sh` | Ledger → reconciliation, discrepancy workflow | `Phase 6 smoke test passed` |
| `./scripts/smoke-test-phase7.sh` | Tenant isolation, roles, PII-free logs | `Phase 7 security smoke test passed` |
| `./scripts/smoke-test-phase9.sh` | SMS, settlement postings, disputes | `Phase 9c smoke test passed` |
| `./scripts/ha-check.sh` | The smoke tests with **two replicas of every service** (needs ~4.5 GB) | `unbalanced ledger postings = 0` |
| `./scripts/chaos-test.sh` | Kills services, pauses Kafka, stops Postgres under load; money invariants | `Chaos test passed` |
| `java scripts/load/LoadTest.java --rate 40 --duration 60 --merchants 8` | Latency and errors under load | `Errors 0.00%` at 40/s |

---

> **Prefer Postman?** `import-postman.bat` (Windows) puts every endpoint into Postman, in the order of this guide,
> with tokens, Idempotency-Keys and expected-result tests built in. *Run collection* executes parts A–J
> automatically (92 requests). Parts K and L still need the commands below.

## 3. Session setup

Open one terminal in the repository folder and keep it for all of part 4 (the variables carry over).

```bash
export GW=http://localhost:8080
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
show() { jq . 2>/dev/null || cat; }      # pretty-print JSON responses

# Operations (OPS) token: valid 15 minutes. Re-run this line if a call returns 401 later.
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials $GW/oauth2/token | jq -r .access_token)
echo "OPS token: ${OPS:0:20}..."

# A receiver for the hospital's webhooks (prints every call it gets)
docker run -d --rm --name hook-receiver --network hpg_default mendhak/http-https-echo:31
```

---

## 4. Feature tests

### A. Hospitals, API keys, configuration (merchant-service)

**A1. Register a hospital** (public endpoint)
```bash
REG=$(curl -s -X POST $GW/api/v1/merchants -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Test Hospital Pvt Ltd\",\"displayName\":\"Test Hospital\",\"contactEmail\":\"billing+$(date +%s)@test.example\"}")
echo "$REG" | show
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
```
Expect: `201`, `merchant.status` = `PENDING_ACTIVATION`, an `apiKey` with `keyId` + `keySecret` and a
`webhookSecret` (secrets are shown only once).

**A2. A pending hospital cannot take payments**
```bash
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials $GW/oauth2/token | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' $GW/api/v1/merchants/me -H "Authorization: Bearer $TOKEN"
```
Expect: a token is issued, but the hospital is not active yet. Payments are refused until A3 (checked in B1).

**A3. Operations activates it**
```bash
curl -s -X POST $GW/api/v1/admin/merchants/$MID/activate -H "Authorization: Bearer $OPS" \
  -H 'Content-Type: application/json' -d '{"reason":"KYC verified"}' | jq '{status, statusReason}'
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials $GW/oauth2/token | jq -r .access_token)
```
Expect: `"status": "ACTIVE"`.

**A4. Profile and configuration**
```bash
curl -s $GW/api/v1/merchants/me -H "Authorization: Bearer $TOKEN" | jq '{id, displayName, status}'
curl -s -X PATCH $GW/api/v1/merchants/me -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"displayName":"Test Hospital - East Wing"}' | jq .displayName
curl -s $GW/api/v1/merchants/me/configuration -H "Authorization: Bearer $TOKEN" | show
```
Expect: the new display name; a configuration with defaults (`captureMode` `AUTO`, limits, `refundWindowDays`).

**A5. Set the configuration, including the webhook URL and patient notifications**
```bash
CONFIG='{"enabledMethods":["UPI","CARD","NETBANKING"],"captureMode":"AUTO",
  "minAmount":{"amount":"10.00","currency":"INR"},"maxAmountPerTransaction":{"amount":"150000.00","currency":"INR"},
  "dailyLimit":{"amount":"2000000.00","currency":"INR"},"paymentTtlMinutes":30,"maxAttempts":3,"refundWindowDays":90,
  "webhookUrl":"http://hook-receiver:8080/hpg/webhooks","notifyPatientEmail":true,"notifyPatientSms":true}'
curl -s -X PUT $GW/api/v1/merchants/me/configuration -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$CONFIG" | jq '{captureMode, webhookUrl}'
```
Expect: `200` with the new values.

**A6. Limits above the platform maximum are refused**
```bash
curl -s -X PUT $GW/api/v1/merchants/me/configuration -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq '.maxAmountPerTransaction.amount="99999999.00"' <<<"$CONFIG")" | jq '{status, errorCode, detail}'
```
Expect: `422 LIMIT_EXCEEDS_PLATFORM_MAXIMUM`.

**A7. API keys: at most two active, rotate, revoke**
```bash
K2=$(curl -s -X POST $GW/api/v1/merchants/me/api-keys -H "Authorization: Bearer $TOKEN"); echo "$K2" | jq '{keyId}'
curl -s -o /dev/null -w 'third key: %{http_code}\n' -X POST $GW/api/v1/merchants/me/api-keys -H "Authorization: Bearer $TOKEN"
curl -s $GW/api/v1/merchants/me/api-keys -H "Authorization: Bearer $TOKEN" | jq '[.[] | {keyId, status}]'
curl -s -o /dev/null -w 'revoke second: %{http_code}\n' -X DELETE $GW/api/v1/merchants/me/api-keys/$(jq -r .keyId <<<"$K2") \
  -H "Authorization: Bearer $TOKEN"
```
Expect: the second key is created; the third is refused with `409 API_KEY_LIMIT_REACHED`; revoking returns `204`.
Revoking the last active key is refused with `409 LAST_ACTIVE_API_KEY`.

**A8. Rotate the webhook signing secret**
```bash
curl -s -X POST $GW/api/v1/merchants/me/webhook-secret/rotate -H "Authorization: Bearer $TOKEN" | jq 'keys'
```
Expect: a new `webhookSecret`, shown once.

**A9. Operations views**
```bash
curl -s "$GW/api/v1/admin/merchants?status=ACTIVE&size=5" -H "Authorization: Bearer $OPS" | jq '.page'
curl -s $GW/api/v1/admin/merchants/$MID/configuration -H "Authorization: Bearer $OPS" | jq 'has("webhookSecret")'
```
Expect: a page of hospitals; the configuration **without** the webhook secret (`false`).

*Suspension is tested in H5.*

---

### B. Payments and checkout (payment-service)

**B1. Create a payment** (HPG also emails and texts the patient the checkout link)
```bash
BILL="BILL-$RANDOM$RANDOM"; KEY=$(uuid)
PAY=$(curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"merchantReference\":\"$BILL\",\"amount\":{\"amount\":\"2000.00\",\"currency\":\"INR\"},
  \"description\":\"Patient share\",\"customer\":{\"name\":\"Asha Rao\",\"email\":\"asha$RANDOM@example.com\",\"phone\":\"+919800012345\"}}")
echo "$PAY" | jq '{id, status, checkoutUrl}'
PID=$(jq -r .id <<<"$PAY"); CHECKOUT=$(jq -r .checkoutUrl <<<"$PAY")
```
Expect: `201`, status `PENDING`, a `checkoutUrl`. If you skipped A3, it is refused with `MERCHANT_NOT_ACTIVE`.

**B2. The patient receives the link**: open http://localhost:8025. Expect an email from the hospital with the
checkout link. SMS (local provider) is logged with a masked number:
`docker compose -f deploy/docker-compose.yml logs notification-service | grep "SMS (not sent"`. Expect
`+91******2345`, never the full number.

**B3. Retry with the same Idempotency-Key**: no second payment
```bash
curl -s -D - -o /dev/null -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"merchantReference\":\"$BILL\",\"amount\":{\"amount\":\"2000.00\",\"currency\":\"INR\"},
  \"description\":\"Patient share\",\"customer\":{\"name\":\"Asha Rao\",\"email\":\"asha@example.com\",\"phone\":\"+919800012345\"}}" \
  | grep -iE "^HTTP|idempotent-replayed"
```
Expect: `201` with `Idempotent-Replayed: true` and the same payment id. The body differs (email), so if your output is
`422 IDEMPOTENCY_KEY_REUSED`, that is also correct: **same key + different body = 422**. Try both variants.

**B4. Same bill, new key: duplicate refused**
```bash
curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"$BILL\",\"amount\":{\"amount\":\"2000.00\",\"currency\":\"INR\"}}" | jq '{status, errorCode}'
```
Expect: `409 DUPLICATE_PAYMENT`.

**B5. Validation**
```bash
curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"merchantReference":"X1","amount":{"amount":"5.00","currency":"INR"}}' | jq '{status, errorCode}'   # no key
curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d '{"merchantReference":"X2","amount":{"amount":"5.00","currency":"INR"}}' | jq '{status, errorCode}'   # below minimum
curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d '{"merchantReference":"bad ref!","amount":{"amount":"-1","currency":"INR"}}' | jq '{status, errorCode, errors}'
```
Expect: `400 MISSING_IDEMPOTENCY_KEY`; `422 AMOUNT_BELOW_MINIMUM`; `400 VALIDATION_FAILED` listing each field.

**B6. Patient pays (browser)**: open the `checkoutUrl` (`echo $CHECKOUT`). The simulated page shows *Pay (simulated
success)*, *Decline* and *Pay without returning*. Click **Pay**. Then:
```bash
curl -s $GW/api/v1/payments/$PID -H "Authorization: Bearer $TOKEN" | jq '{status, capturedAt, attempts: [.attempts[] | {providerPaymentId, status}]}'
```
Expect: `CAPTURED` (AUTO capture). Mailpit shows a **receipt** email; the hospital webhook receiver logs
`payment.captured` (`docker logs hook-receiver | grep payment.captured`).

> Without a browser: `curl -s -o /dev/null -X POST "$GW/${CHECKOUT#*://*/}/simulate" -d outcome=SUCCESS`

**B7. Payment declined**: create a payment (as in B1, new bill and key), open its link and click **Decline**.
Expect: the payment stays `PENDING` until `maxAttempts` (3) declines, then `FAILED`. The patient gets a
"payment not completed" email after the final failure.

**B8. Lost callback, recovered automatically**: create a payment and click **Pay without returning** (the provider
captured it but our callback never arrived). Expect `PROCESSING` or `PENDING` at first, then `CAPTURED` within about
2 minutes: the recovery job polls the provider.

**B9. Manual capture and cancel**
```bash
curl -s -X PUT $GW/api/v1/merchants/me/configuration -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq '.captureMode="MANUAL"' <<<"$CONFIG")" > /dev/null; sleep 3          # replicated to payment-service via Kafka
M=$(curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"MAN-$RANDOM\",\"amount\":{\"amount\":\"900.00\",\"currency\":\"INR\"},\"sendCheckoutLink\":false}")
MPID=$(jq -r .id <<<"$M"); curl -s -o /dev/null -X POST "$GW/$(jq -r .checkoutUrl <<<"$M" | sed 's#.*://[^/]*/##')/simulate" -d outcome=SUCCESS
curl -s $GW/api/v1/payments/$MPID -H "Authorization: Bearer $TOKEN" | jq .status                       # AUTHORIZED
curl -s -X POST $GW/api/v1/payments/$MPID/capture -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" | jq .status
# A second one, cancelled instead:
C=$(curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"CAN-$RANDOM\",\"amount\":{\"amount\":\"300.00\",\"currency\":\"INR\"},\"sendCheckoutLink\":false}")
curl -s -X POST $GW/api/v1/payments/$(jq -r .id <<<"$C")/cancel -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" \
  -H 'Content-Type: application/json' -d '{"reason":"Bill corrected"}' | jq .status
curl -s -X PUT $GW/api/v1/merchants/me/configuration -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$CONFIG" > /dev/null
```
Expect: `AUTHORIZED` → `CAPTURED`; the second payment `CANCELLED`. Capturing a cancelled payment is refused with
`409 INVALID_STATE_TRANSITION`. Configuration is set back to AUTO at the end.

**B10. Expiry**: create a payment with `"expiresInMinutes": 1` and do not pay. Expect `EXPIRED` within about
2 minutes (the expiry job checks with the provider first), and a "link expired" email.

**B11. List and filter**
```bash
curl -s "$GW/api/v1/payments?status=CAPTURED&size=5" -H "Authorization: Bearer $TOKEN" | jq '.page'
curl -s "$GW/api/v1/payments?merchantReference=$BILL" -H "Authorization: Bearer $TOKEN" | jq '[.content[] | {id, status}]'
```

---

### C. Refunds (refund-service)

**C1. Partial refund**
```bash
RKEY=$(uuid)
curl -s -X POST $GW/api/v1/refunds -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $RKEY" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"500.00\",\"currency\":\"INR\"},\"reason\":\"Insurance adjustment\"}" | jq '{id, status}'
```
Expect: `201`, status `COMPLETED` (simulated provider). A `409 PAYMENT_NOT_YET_REFUNDABLE` right after capture means
the capture event has not arrived yet: retry after 2 seconds with the **same** key.

**C2. Same key again**: run C1 again unchanged. Expect the same refund with `Idempotent-Replayed: true`.

**C3. More than what is left**
```bash
curl -s -X POST $GW/api/v1/refunds -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"1600.00\",\"currency\":\"INR\"}}" | jq '{status, errorCode, remainingRefundable}'
```
Expect: `422 REFUND_EXCEEDS_CAPTURED_AMOUNT` with `remainingRefundable` = `1500.00`.

**C4. List refunds**
```bash
curl -s "$GW/api/v1/refunds?paymentId=$PID" -H "Authorization: Bearer $TOKEN" | jq '[.content[] | {id, amount: .amount.amount, status}]'
```
Also check: the patient's bank refund shows on the dashboard Refunds page, and the hook receiver logs `refund.completed`.

---

### D. Transactions and ledger (transaction-service)

**D1. History and timeline**
```bash
curl -s "$GW/api/v1/transactions?paymentId=$PID" -H "Authorization: Bearer $TOKEN" | jq '[.content[] | {transactionRef, type, status}]'
REF=$(curl -s "$GW/api/v1/transactions?paymentId=$PID&type=PAYMENT" -H "Authorization: Bearer $TOKEN" | jq -r '.content[0].transactionRef')
curl -s $GW/api/v1/transactions/$REF -H "Authorization: Bearer $TOKEN" | jq '{status, timeline: [.timeline[] | .eventType], ledger: [.ledgerEntries[]? | {accountCode, direction, amount}]}'
```
Expect: a PAYMENT `CAPTURED` and a REFUND `COMPLETED`; the timeline in order (Initiated → … → Captured).

**D2. Summary report**
```bash
curl -s "$GW/api/v1/reports/summary?from=$(date -u -d '-1 day' +%FT%TZ)&to=$(date -u -d '+1 hour' +%FT%TZ)" \
  -H "Authorization: Bearer $TOKEN" | show
```
Expect: captured count and amount, refunded amount, and net = captured − refunded.

**D3. Ledger (operations)**
```bash
curl -s "$GW/api/v1/admin/ledger/accounts/MERCHANT_PAYABLE:$MID/balance" -H "Authorization: Bearer $OPS" | show
curl -s $GW/api/v1/admin/ledger/integrity -H "Authorization: Bearer $OPS" | show
```
Expect: the hospital is owed captured − refunded; integrity `"healthy": true`.

---

### E. Notifications (notification-service)

**E1. Hospital webhooks are signed**
```bash
docker logs hook-receiver 2>&1 | grep -A3 '"x-hpg-signature"' | head -8
curl -s "$GW/api/v1/notifications?paymentId=$PID" -H "Authorization: Bearer $TOKEN" | jq '[.content[] | {channel, type: .eventType, status}]'
```
Expect: requests with `X-HPG-Signature`, `X-HPG-Timestamp` and `X-HPG-Event-Type` headers. Notifications for the
payment: WEBHOOK, EMAIL and SMS, all `DELIVERED`. Hospital webhooks never contain the patient's email or phone.

**E2. Delivery failures are retried**
```bash
docker stop hook-receiver
# make a payment as in B1 and pay it (B6), then:
curl -s "$GW/api/v1/notifications?status=PENDING&channel=WEBHOOK" -H "Authorization: Bearer $TOKEN" | jq '[.content[] | {type: .eventType, attempts, nextAttemptAt}]'
docker run -d --rm --name hook-receiver --network hpg_default mendhak/http-https-echo:31
```
Expect: the webhook is retried with increasing delays (`attempts` grows) and becomes `DELIVERED` after the receiver
is back. One that gives up (`DEAD`) can be re-queued by operations:
`curl -s -X POST $GW/api/v1/admin/notifications/<id>/retry -H "Authorization: Bearer $OPS"`.

**E3. Webhook URL safety**: set `webhookUrl` to `https://user:password@hooks.example.com/hpg` with A5's command.
Expect `422` (no credentials in URLs). Locally `HPG_ALLOW_INSECURE_WEBHOOKS=true` allows plain http and private
addresses so the Docker receiver works. With `false` (production) `http://169.254.169.254/latest` and other
private, loopback or link-local addresses are refused with `422`, and delivery re-checks the resolved address
each time.

---

### F. Disputes / chargebacks (payment, refund, transaction, notification services)

The simulated provider accepts a dispute webhook in Razorpay's format, signed with its local key.

**F1. The patient's bank raises a chargeback**
```bash
PPAY=$(curl -s $GW/api/v1/payments/$PID -H "Authorization: Bearer $TOKEN" | jq -r '[.attempts[] | select(.status=="CAPTURED")][0].providerPaymentId')
dispute_webhook() {  # dispute_webhook <event> <status> <amount_deducted_paise>
  local body sig
  body=$(printf '{"entity":"event","event":"%s","payload":{"dispute":{"entity":{"id":"disp_test_%s","entity":"dispute","payment_id":"%s","amount":150000,"currency":"INR","amount_deducted":%s,"reason_code":"chargeback_unauthorised","respond_by":%s,"status":"%s","phase":"chargeback","created_at":%s}}},"created_at":%s}' \
    "$1" "$PID" "$PPAY" "$3" $(( $(date +%s) + 7*86400 )) "$2" $(date +%s) $(date +%s))
  sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac simulated-provider-signing-key | awk '{print $NF}')
  curl -s -X POST $GW/webhooks/razorpay/payments -H 'Content-Type: application/json' \
    -H "X-Razorpay-Signature: $sig" -H "X-Razorpay-Event-Id: evt_$(uuid)" -d "$body" | jq .outcome
}
dispute_webhook payment.dispute.created open 150000
DID=$(curl -s $GW/api/v1/disputes -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
curl -s $GW/api/v1/disputes/$DID -H "Authorization: Bearer $TOKEN" | jq '{status, amount: .amount.amount, amountDeducted: .amountDeducted.amount, respondBy}'
```
Expect: `"APPLIED"`; dispute `OPEN`, ₹1,500 disputed and deducted, a deadline. The hook receiver logs
`dispute.opened` and `dispute.funds_deducted`.

**F2. Refunds are paused while it is open**: repeat C1 with a new key. Expect `409 PAYMENT_DISPUTED`.

**F3. The hospital contests (with an Idempotency-Key)**
```bash
CKEY=$(uuid)
for i in 1 2; do curl -s -D - -X POST $GW/api/v1/disputes/$DID/contest -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $CKEY" \
  -H 'Content-Type: application/json' -d '{"summary":"Consultation on file; signed consent and invoice attached"}' \
  | grep -iE '^HTTP|idempotent-replayed|"status"' ; done
curl -s -X POST $GW/api/v1/disputes/$DID/accept -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" | jq '{status, errorCode}'
curl -s -X POST $GW/api/v1/disputes/$DID/accept -H "Authorization: Bearer $TOKEN" | jq '{status, errorCode}'
```
Expect: `UNDER_REVIEW`; the second call is replayed (`Idempotent-Replayed: true`). Accepting after contesting is
`409 DISPUTE_NOT_OPEN`; without a key it is `400 MISSING_IDEMPOTENCY_KEY`.

**F4. The bank decides for the hospital**
```bash
dispute_webhook payment.dispute.won won 0
curl -s $GW/api/v1/disputes/$DID -H "Authorization: Bearer $TOKEN" | jq '{status, amountDeducted: .amountDeducted.amount, resolvedAt}'
curl -s $GW/api/v1/transactions/$REF -H "Authorization: Bearer $TOKEN" | jq '[.ledgerEntries[]? | .postingType] | unique'
```
Expect: `WON`, nothing deducted any more. The ledger shows `CHARGEBACK` and `CHARGEBACK_REVERSAL` postings. Refunds
work again (C1 with a new key).

**F5. Dashboard**: sign in as the hospital → *Disputes*. Raise another dispute (F1 with a new payment) and use
**Accept** or **Contest with evidence** in the drawer.

---

### G. Reconciliation (reconciliation-service, operations only)

**G1. The seeded batch**: dashboard → sign in as Operations → *Reconciliation*. Expect one completed batch with
matched, mismatched, missing and duplicate rows (the seed script uploads a report with deliberate differences).

**G2. Upload a settlement file**
```bash
TODAY=$(date -u +%F)
B=$(curl -s -X POST "$GW/api/v1/reconciliation/settlement-files?businessDate=$TODAY" -H "Authorization: Bearer $OPS" \
  -F "file=@samples/settlement/discrepancies.csv;type=text/csv"); echo "$B" | jq '{id, status}'
sleep 3; curl -s $GW/api/v1/reconciliation/batches/$(jq -r .id <<<"$B") -H "Authorization: Bearer $OPS" | show
curl -s -o /dev/null -w 'same file again: %{http_code}\n' -X POST "$GW/api/v1/reconciliation/settlement-files?businessDate=$TODAY" \
  -H "Authorization: Bearer $OPS" -F "file=@samples/settlement/discrepancies.csv;type=text/csv"
```
Expect: `202` then `COMPLETED` with counts. The sample's ids are not in your ledger, so they show as *missing in
ledger*, plus duplicates, a pending-review adjustment and one malformed row. The same file again is `409`.

**G3. Work a discrepancy**
```bash
BID=$(jq -r .id <<<"$B")
RID=$(curl -s "$GW/api/v1/reconciliation/batches/$BID/records?resolutionStatus=OPEN" -H "Authorization: Bearer $OPS" | jq -r '.content[0].id')
curl -s -X POST $GW/api/v1/reconciliation/records/$RID/review -H "Authorization: Bearer $OPS" | jq .resolutionStatus
curl -s -X POST $GW/api/v1/reconciliation/records/$RID/resolve -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"resolutionType":"TIMING_DIFFERENCE","note":"Settled in the next report"}' | jq '{resolutionStatus, resolutionType}'
curl -s "$GW/api/v1/reconciliation/reports/daily?date=$TODAY" -H "Authorization: Bearer $OPS" -H 'Accept: text/csv' | head -5
```
Expect: `IN_REVIEW` → `RESOLVED`; the daily report as CSV.

**G4. Settlement posting**: `./scripts/smoke-test-phase9.sh` (step 2) matches a real payment. Its ledger then has
`SETTLEMENT` entries: net to `SETTLEMENT_BANK` and fees to `PROVIDER_FEES`, booked once even if reported twice.

**G5. Roles**: hospitals are refused.
```bash
curl -s -o /dev/null -w '%{http_code}\n' $GW/api/v1/reconciliation/batches -H "Authorization: Bearer $TOKEN"
```
Expect `403`.

---

### H. Security

| ID | Check | Command | Expect |
|---|---|---|---|
| H1 | No token | `curl -s -o /dev/null -w '%{http_code}\n' $GW/api/v1/payments` | `401` |
| H2 | Forged token | `curl -s -o /dev/null -w '%{http_code}\n' $GW/api/v1/payments -H 'Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln'` | `401` |
| H3 | Hospital on operations APIs | `curl -s -o /dev/null -w '%{http_code}\n' $GW/api/v1/admin/merchants -H "Authorization: Bearer $TOKEN"` | `403` |
| H4 | Tenant isolation | Register and activate a second hospital (A1, A3, other variable names), then read `$PID` with its token | `404`, never the other hospital's data |
| H5 | Suspension | `curl -s -X POST $GW/api/v1/admin/merchants/$MID/suspend -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"reason":"test"}'`, wait 5 s, call `$GW/api/v1/payments` with `$TOKEN` | `403 MERCHANT_SUSPENDED` (the existing token is blocked too). Activate again (A3) and it works |
| H6 | Rate limit | `for i in $(seq 1 25); do curl -s -o /dev/null -w '%{http_code} ' -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials $GW/oauth2/token; done; echo` | some `429` after ~10 calls |
| H7 | No PII in logs | `docker compose -f deploy/docker-compose.yml logs 2>&1 \| grep -c 9800012345` | `0` |
| H8 | Secrets never returned | A9, and `GET /api/v1/merchants/me/api-keys` | no `keySecret` / `webhookSecret` |

### I. Single sign-on (needs `--profile auth`)

**I1.** Create users and sign in through the browser:
```bash
./scripts/keycloak-users.sh ops tester.ops
./scripts/keycloak-users.sh hospital tester.hospital $MID
```
Open http://localhost:8090 → **Sign in with your organisation account** → use the printed password. Expect: the ops
user sees the Operations view; the hospital user sees only `Test Hospital`'s data. **Sign out** ends the Keycloak
session (the next sign-in asks for the password again). Five wrong passwords lock the account for a while.

### J. Dashboard walkthrough (http://localhost:8090)

Sign in with the seed script's **hospital** API key, then the **operations** client (`ops-console` +
`HPG_OPS_CLIENT_SECRET` from `deploy/env/.env`). Tick each item:

- **Hospital:**
  - **Overview:** totals and the *needs attention* list.
  - **Payments:**
    - filters, detail drawer, **New payment**, then open its link;
    - capture and cancel from the drawer, and refund from a captured payment.
  - **Refunds:** list and detail.
  - **Disputes:** deadline, contest and accept.
  - **Transactions:** filters, timeline, ledger lines.
  - **Notifications:** channel, status, attempts.
  - **Settings:** configuration, API keys (create and revoke), webhook secret rotation.
- **Operations:**
  - **Overview** and **Hospitals:** activate and suspend with a reason, view a hospital's configuration.
  - **Notifications:** failed deliveries, **Retry**.
  - **Reconciliation:** upload a file, batch drawer, review and resolve, CSV download.
  - **Ledger:** balances, integrity.
- **Both:** works on a phone-sized window; the browser console shows no errors.

### K. Failure handling between services

| ID | Do | Expect |
|---|---|---|
| K1 | `docker compose -f deploy/docker-compose.yml pause kafka`; create and pay a payment (B1, B6); `docker compose ... unpause kafka` | Payment `201` and `CAPTURED` while Kafka is down; refunds, transactions and notifications catch up within seconds after unpause, nothing lost |
| K2 | `docker compose -f deploy/docker-compose.yml stop postgres`; `curl -s -D - $GW/api/v1/payments -H "Authorization: Bearer $TOKEN"`; then `start postgres` | `503` with `Retry-After` in about 3 s (no hang, no 500); works again after Postgres is back, no restart needed |
| K3 | `docker compose -f deploy/docker-compose.yml stop refund-service`; call `$GW/api/v1/refunds` 15 times | First calls `503`; then the gateway's circuit is open and answers `503` immediately (`Circuit open` in the gateway log). Other services keep working. `start refund-service`: fine again within ~10 s |
| K4 | Dead letters: `curl -s "$GW/api/v1/admin/dead-letters" -H "Authorization: Bearer $OPS"` | `[]` normally. If any exist: each names the failed consumer and error; replay with `POST $GW/api/v1/admin/dead-letters/<dltTopic>/<partition>/<offset>/replay`; a second replay is `409` |
| K5 | `java scripts/load/LoadTest.java --rate 120 --duration 30 --merchants 8` | Many `503` (busy) with `Retry-After`, **no 500**; everything still consistent afterwards (D3 integrity healthy) |
| K6 | `./scripts/chaos-test.sh` | `Chaos test passed: … nothing lost or booked twice` |

### L. Observability

- **L1. Grafana** (http://localhost:3000): dashboards *HPG Payments*, *Provider*, *Event pipeline*, *Recon & ledger*
  show live data after the tests above. Outbox backlog returns to 0 after K1.
- **L2. Prometheus** (http://localhost:9090): *Status → Targets*: all 7 applications **UP**. *Alerts*: 10 rules;
  `ServiceDown` fires within about a minute when you stop a service (K3), then clears.
- **L3. Jaeger** (http://localhost:16686, stack started with `HPG_TRACE_EXPORT=true`): search service
  `payment-service`. A payment trace spans the gateway, payment, refund, notification and transaction services.
- **L4. Logs:** `docker compose -f deploy/docker-compose.yml logs payment-service | tail -3`. Each line is JSON with
  `traceId` and `correlationId`; emails and phones are masked.

---

## 5. End-to-end journeys

Each journey is one patient story across all services. Use a fresh payment for each.

**J1. Pay, refund, settle, reconcile**
1. Create a payment with an email and phone (B1). The patient gets the link by email and SMS (B2).
2. Pay it (B6): `CAPTURED`, receipt email, `payment.captured` webhook.
3. Partial refund (C1): `COMPLETED`, `refund.completed` webhook.
4. Ledger (D1, D3): capture and refund postings, the hospital is owed the difference, integrity healthy.
5. Settlement: run `./scripts/smoke-test-phase9.sh` or upload a report containing the payment's
   `providerPaymentId` (G2). Expect `MATCHED` and a `SETTLEMENT` posting.

**J2. Manual capture, cancel and expiry**: B9 and B10. Expect the right status, emails and webhooks for each, and no
ledger postings for cancelled or expired payments.

**J3. Chargeback**: F1 to F4 on a captured payment. Watch it end to end: webhooks to the hospital, refunds paused,
chargeback and reversal in the ledger, dashboard Disputes page.

**J4. Outage during business**: start J1, and between steps run K1 (Kafka down) and K2 (Postgres down). Retry
failed calls with the **same Idempotency-Key**. Expect the same end state as J1: one payment, one refund, ledger
balanced.

---

## 6. Reporting a problem

Every error response carries a `correlationId`. Include it, then find the related logs with:
```bash
docker compose -f deploy/docker-compose.yml logs --since 30m 2>&1 | grep <correlationId>
```

**Template**
```
Test ID:      (e.g. C3)
Steps:        (command or clicks)
Expected:     (from this guide)
Actual:       (status, errorCode, body)
correlationId:
Time (UTC):
Logs:         (lines from the command above)
```

**Known limitations (not bugs)**
- Payments use the **simulated provider**; Razorpay itself has only been tested against mocks.
- SMS is logged locally, not sent (the MSG91 adapter is for production).
- Kubernetes manifests are validated, but no live cluster install has been run.
- `start-hpg-lite.bat` runs only the payment flow; the Refunds, Transactions, Notifications and Reconciliation
  pages show errors there by design.

**Clean up:** `docker rm -f hook-receiver; docker compose -f deploy/docker-compose.yml --profile '*' down -v`
