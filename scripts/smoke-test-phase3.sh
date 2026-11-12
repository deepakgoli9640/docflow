#!/usr/bin/env bash
# End-to-end smoke test of Phase 3 through the API gateway, using the SIMULATED provider
# (HPG_PAYMENT_PROVIDER=simulated in deploy/env/.env). Requires curl + jq and the stack:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core up -d --build
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

step "Setup: register + activate a hospital, get tokens"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"City Hospital Pvt Ltd\",\"displayName\":\"City Hospital\",\"contactEmail\":\"billing+$(date +%s%N)@city.example\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)

seed_replica() { # TEMPORARY (Phase 3): copy merchant config into payment-service; Phase 4 does this via Kafka
  curl -s -o /dev/null -w '%{http_code}' -X PUT "$GW/api/v1/admin/merchant-config-replicas/$MID" \
    -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d "{\"displayName\":\"City Hospital\",
    \"status\":\"ACTIVE\",\"enabledMethods\":[\"UPI\",\"CARD\"],\"captureMode\":\"$1\",
    \"minAmount\":{\"amount\":\"1.00\",\"currency\":\"INR\"},\"maxAmountPerTransaction\":{\"amount\":\"200000.00\",\"currency\":\"INR\"},
    \"dailyLimit\":{\"amount\":\"1000000.00\",\"currency\":\"INR\"},\"paymentTtlMinutes\":60,\"maxAttempts\":3}"
}
expect 200 "$(seed_replica AUTO)" "seed merchant replica (temporary endpoint)"

create() { # create <idempotency-key> <reference> <amount>
  curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
    -d "{\"merchantReference\":\"$2\",\"amount\":{\"amount\":\"$3\",\"currency\":\"INR\"},\"description\":\"Patient share\",
         \"customer\":{\"name\":\"A Patient\",\"email\":\"patient@example.com\",\"phone\":\"+919800001234\"}}"
}

step "1. Initiate a payment (AUTO capture)"
KEY=$(uuid); REF="BILL-$RANDOM$RANDOM"
OUT=$(create "$KEY" "$REF" "20000.00"); expect 201 "$(tail -1 <<<"$OUT")" "create payment"
PAY=$(sed '$d' <<<"$OUT"); PID=$(jq -r .id <<<"$PAY"); CHECKOUT=$(jq -r .checkoutUrl <<<"$PAY")
echo "paymentId=$PID status=$(jq -r .status <<<"$PAY") checkoutUrl=$CHECKOUT"

step "2. Same Idempotency-Key again -> replay, not a second payment"
REPLAY=$(curl -s -D - -o /tmp/hpg-replay.json -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"$REF\",\"amount\":{\"amount\":\"20000.00\",\"currency\":\"INR\"},\"description\":\"Patient share\",
       \"customer\":{\"name\":\"A Patient\",\"email\":\"patient@example.com\",\"phone\":\"+919800001234\"}}")
grep -qi '^idempotent-replayed: true' <<<"$REPLAY" && echo "ok: Idempotent-Replayed header present"
expect "$PID" "$(jq -r .id /tmp/hpg-replay.json)" "replay returns the same payment"

step "3. Second payment for the same bill with a new key -> 409"
expect 409 "$(tail -1 <<<"$(create "$(uuid)" "$REF" "20000.00")")" "duplicate bill"

step "4. Patient opens the checkout page and pays (SIMULATED provider)"
TOKEN_PATH=${CHECKOUT#*://*/}
curl -s "$GW/$TOKEN_PATH" | grep -q "SIMULATED PROVIDER" && echo "ok: checkout page served (clearly labelled simulated)"
curl -s -X POST "$GW/$TOKEN_PATH/simulate" -d outcome=SUCCESS | grep -q "Payment successful" && echo "ok: patient sees success"
expect CAPTURED "$(curl -s -H "Authorization: Bearer $TOKEN" "$GW/api/v1/payments/$PID" | jq -r .status)" "payment status"
curl -s -H "Authorization: Bearer $TOKEN" "$GW/api/v1/payments/$PID" | jq -c '[.statusHistory[] | "\(.from // "-")->\(.to) (\(.source))"]'

step "5. MANUAL capture: authorize at checkout, then the hospital captures"
expect 200 "$(seed_replica MANUAL)" "switch merchant to MANUAL capture (temporary endpoint)"
OUT=$(create "$(uuid)" "BILL-$RANDOM$RANDOM" "5000.00"); PID2=$(sed '$d' <<<"$OUT" | jq -r .id)
CO2=$(sed '$d' <<<"$OUT" | jq -r .checkoutUrl); P2=${CO2#*://*/}
curl -s -o /dev/null -X POST "$GW/$P2/simulate" -d outcome=SUCCESS
expect AUTHORIZED "$(curl -s -H "Authorization: Bearer $TOKEN" "$GW/api/v1/payments/$PID2" | jq -r .status)" "after checkout"
expect CAPTURED "$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" \
  "$GW/api/v1/payments/$PID2/capture" | jq -r .status)" "capture"

step "6. Cancel an unpaid payment"
OUT=$(create "$(uuid)" "BILL-$RANDOM$RANDOM" "750.00"); PID3=$(sed '$d' <<<"$OUT" | jq -r .id)
expect CANCELLED "$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" \
  -H 'Content-Type: application/json' -d '{"reason":"Bill re-issued"}' "$GW/api/v1/payments/$PID3/cancel" | jq -r .status)" "cancel"

step "7. Forged provider webhook is rejected"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/webhooks/razorpay/payments" \
  -H 'Content-Type: application/json' -H 'X-Razorpay-Signature: forged' -d '{"event":"payment.captured"}')" "forged webhook"

step "8. Another hospital cannot see this payment"
REG2=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Other Hospital\",\"displayName\":\"Other\",\"contactEmail\":\"other+$(date +%s%N)@x.example\"}")
T2=$(curl -s -u "$(jq -r .apiKey.keyId <<<"$REG2"):$(jq -r .apiKey.keySecret <<<"$REG2")" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T2" "$GW/api/v1/payments/$PID")" "cross-tenant read"

printf '\n\033[32mPhase 3 smoke test passed.\033[0m\n'
