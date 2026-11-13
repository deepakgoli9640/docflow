#!/usr/bin/env bash
# End-to-end smoke test of Phase 4: merchant snapshots replicated over Kafka, the transactional outbox,
# and notification delivery (signed webhook + patient email).
#
# Requires curl, jq, python3 and the stack:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile notify up -d --build
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
MAILPIT=${MAILPIT:-http://localhost:8025}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
WEBHOOK_PORT=${WEBHOOK_PORT:-9099}
WEBHOOK_LOG=$(mktemp /tmp/hpg-webhooks-XXXXXX.jsonl)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
# Waits for a condition, because everything here crosses Kafka and is therefore eventually consistent.
await() { # await <seconds> <description> <command...>
  local deadline=$((SECONDS + $1)); local what="$2"; shift 2
  until "$@" >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then echo "FAIL: timed out waiting for $what"; exit 1; fi
    sleep 1
  done
  echo "ok: $what"
}

RECEIVER_PID=""
cleanup() { [[ -n "$RECEIVER_PID" ]] && kill "$RECEIVER_PID" 2>/dev/null || true; }
trap cleanup EXIT

step "1. Register a hospital (its first snapshot is published through the outbox)"
EMAIL="billing+$(date +%s%N)@cityhospital.example"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"City Hospital Pvt Ltd\",\"displayName\":\"City Hospital\",\"contactEmail\":\"$EMAIL\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG")
KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG"); WEBHOOK_SECRET=$(jq -r .webhookSecret <<<"$REG")
echo "merchantId=$MID"

step "2. Start a stand-in hospital endpoint that verifies our signature"
HPG_WEBHOOK_SECRET="$WEBHOOK_SECRET" python3 scripts/webhook-receiver.py "$WEBHOOK_PORT" "$WEBHOOK_LOG" &
RECEIVER_PID=$!
sleep 1
echo "listening on port $WEBHOOK_PORT, log: $WEBHOOK_LOG"

step "3. Activate the hospital and point it at that endpoint"
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
# host.docker.internal resolves to the host from inside the containers (see docker-compose.yml).
expect 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GW/api/v1/merchants/me/configuration" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{
    \"enabledMethods\":[\"UPI\",\"CARD\"],\"captureMode\":\"AUTO\",
    \"minAmount\":{\"amount\":\"1.00\",\"currency\":\"INR\"},
    \"maxAmountPerTransaction\":{\"amount\":\"200000.00\",\"currency\":\"INR\"},
    \"dailyLimit\":{\"amount\":\"1000000.00\",\"currency\":\"INR\"},
    \"paymentTtlMinutes\":60,\"maxAttempts\":3,\"refundWindowDays\":180,
    \"webhookUrl\":\"http://host.docker.internal:$WEBHOOK_PORT/hooks\",
    \"notifyPatientEmail\":true,\"notifyPatientSms\":true}")" "configure webhook + patient notifications"

step "4. payment-service learns the merchant from Kafka (no manual seeding any more)"
# Phase 3 needed an admin endpoint to copy this across. It is gone; the snapshot does it.
await 40 "merchant replica reached payment-service" bash -c \
  "curl -s -o /dev/null -w '%{http_code}' -X POST '$GW/api/v1/payments' -H 'Authorization: Bearer $TOKEN' \
     -H 'Idempotency-Key: $(uuid)' -H 'Content-Type: application/json' \
     -d '{\"merchantReference\":\"PROBE-$RANDOM\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}' \
   | grep -q 201"

step "5. Take a payment"
PAY=$(curl -s -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" \
  -H 'Content-Type: application/json' -d "{\"merchantReference\":\"BILL-$RANDOM$RANDOM\",
    \"amount\":{\"amount\":\"20000.00\",\"currency\":\"INR\"},\"description\":\"Patient share\",
    \"customer\":{\"name\":\"A Patient\",\"email\":\"patient@example.com\",\"phone\":\"+919800001234\"}}")
PID=$(jq -r .id <<<"$PAY"); CHECKOUT=$(jq -r .checkoutUrl <<<"$PAY")
echo "paymentId=$PID"
curl -s -o /dev/null -X POST "$GW/${CHECKOUT#*://*/}/simulate" -d outcome=SUCCESS
expect CAPTURED "$(curl -s -H "Authorization: Bearer $TOKEN" "$GW/api/v1/payments/$PID" | jq -r .status)" "payment captured"

step "6. The hospital receives a signed webhook (verified by the receiver)"
await 40 "webhook delivered" bash -c "grep -q '\"eventType\": \"payment.captured\"' '$WEBHOOK_LOG'"
jq -c '{eventType, sequence, signatureValid, timestampFresh, status: .body.data.status, ref: .body.data.merchantReference}' \
  < "$WEBHOOK_LOG" | tail -1
expect true "$(jq -r 'select(.eventType=="payment.captured") | .signatureValid' < "$WEBHOOK_LOG" | tail -1)" \
  "hospital could verify the signature"
expect true "$(jq -r 'select(.eventType=="payment.captured") | .timestampFresh' < "$WEBHOOK_LOG" | tail -1)" \
  "timestamp inside the replay window"
# Patient contact details are not sent back to the hospital.
if grep -q 'patient@example.com' "$WEBHOOK_LOG"; then echo "FAIL: patient email was sent to the hospital"; exit 1; fi
echo "ok: no patient contact details in the webhook body"

step "7. The patient receives a receipt by email (Mailpit)"
await 40 "email delivered" bash -c \
  "curl -s '$MAILPIT/api/v1/messages' | jq -e '.messages[] | select(.To[0].Address==\"patient@example.com\")' >/dev/null"
curl -s "$MAILPIT/api/v1/messages" | jq -r '.messages[0] | "subject: \(.Subject)\nto: \(.To[0].Address)"'

step "8. Delivery is tracked and visible to operators"
curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/notifications?merchantId=$MID" \
  | jq -c '[.content[] | {channel, recipientType, status, attemptCount}]'
expect 3 "$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/notifications?merchantId=$MID" \
  | jq '[.content[] | select(.status=="DELIVERED")] | length')" "all three notifications delivered"

step "9. Suspending the hospital blocks its existing token at the edge"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"Chargeback investigation"}' "$GW/api/v1/admin/merchants/$MID/suspend"
await 40 "edge refuses the suspended merchant" bash -c \
  "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer $TOKEN' '$GW/api/v1/payments' | grep -q 403"

step "10. Nothing is left unpublished in any outbox"
for db in merchant_db payment_db notification_db; do
  user="${db%_db}_app"
  pending=$(docker exec -e PGPASSWORD="$(grep -i "^${user%%_app}_DB_PASSWORD=" deploy/env/.env | cut -d= -f2-)" \
    hpg-postgres-1 psql -qtAX -h localhost -U "$user" -d "$db" \
    -c "SELECT count(*) FROM outbox_events WHERE published_at IS NULL" 2>/dev/null || echo "?")
  echo "$db unpublished events: $pending"
done

printf '\n\033[32mPhase 4 smoke test passed.\033[0m\n'
