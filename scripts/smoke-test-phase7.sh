#!/usr/bin/env bash
# Security smoke test through the API gateway (docs/09-security.md):
#   1. tenant isolation: hospital B probes every merchant endpoint with hospital A's ids
#   2. role matrix: merchant vs operations vs no token on protected routes
#   3. log hygiene: a patient's email never appears unmasked in any service's logs; log lines carry trace ids
# Requires curl, jq, docker and the full stack (see README).
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
FAILS=0
check() { # check <expected codes regex> <actual> <description>
  if [[ "$2" =~ ^($1)$ ]]; then echo "ok: $3 -> $2"; else echo "FAIL: $3 (expected $1, got $2)"; FAILS=$((FAILS + 1)); fi
}
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

new_hospital() { # prints "merchantId keyId keySecret"
  local reg; reg=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
    -d "{\"legalName\":\"$1\",\"displayName\":\"$1\",\"contactEmail\":\"sec+$(date +%s%N)@$1.example\"}")
  echo "$(jq -r .merchant.id <<<"$reg") $(jq -r .apiKey.keyId <<<"$reg") $(jq -r .apiKey.keySecret <<<"$reg")"
}
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
activate() { curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"reason":"ok"}' \
  "$GW/api/v1/admin/merchants/$1/activate"; }
token() { curl -s -u "$1:$2" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token; }

step "Setup: hospital A with a captured payment, a refund and notifications; hospital B empty"
read -r MID_A KEY_A SECRET_A <<<"$(new_hospital alpha)"; read -r MID_B KEY_B SECRET_B <<<"$(new_hospital beta)"
activate "$MID_A"; activate "$MID_B"
TOKEN=$(token "$KEY_A" "$SECRET_A"); A=$TOKEN; B=$(token "$KEY_B" "$SECRET_B")
source scripts/lib/merchant.sh
wait_until_payable AUTO
PATIENT="leak.check.$(date +%s)@patients.example"
PAY=$(curl -s -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $A" -H "Idempotency-Key: $(uuid)" \
  -H 'Content-Type: application/json' -d "{\"merchantReference\":\"SEC-$RANDOM\",\"amount\":{\"amount\":\"900.00\",\"currency\":\"INR\"},
  \"customer\":{\"name\":\"Leak Check\",\"email\":\"$PATIENT\",\"phone\":\"+919811122233\"}}")
PID=$(jq -r .id <<<"$PAY"); CO=$(jq -r .checkoutUrl <<<"$PAY")
curl -s -o /dev/null -X POST "$GW/${CO#*://*/}/simulate" -d outcome=SUCCESS
for _ in $(seq 1 30); do
  R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/refunds" -H "Authorization: Bearer $A" -H "Idempotency-Key: sec-$PID" \
    -H 'Content-Type: application/json' -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}")
  [[ $(tail -1 <<<"$R") == 201 ]] && break; sleep 1
done
RID=$(sed '$d' <<<"$R" | jq -r .id)
for _ in $(seq 1 30); do
  TXN=$(curl -s -H "Authorization: Bearer $A" "$GW/api/v1/transactions?paymentId=$PID&type=PAYMENT" | jq -r '.content[0].transactionRef // empty')
  NID=$(curl -s -H "Authorization: Bearer $A" "$GW/api/v1/notifications?paymentId=$PID" | jq -r '.content[0].id // empty')
  [[ -n $TXN && -n $NID ]] && break; sleep 1
done
KEYS_A=$(curl -s -H "Authorization: Bearer $A" "$GW/api/v1/merchants/me/api-keys" | jq -r '.[0].keyId')
echo "A: payment $PID, refund $RID, transaction $TXN, notification $NID"

step "1. Tenant isolation: hospital B uses hospital A's ids"
BH=(-H "Authorization: Bearer $B")
check 404 "$(code "${BH[@]}" "$GW/api/v1/payments/$PID")" "B reads A's payment"
check 404 "$(code "${BH[@]}" -X POST -H "Idempotency-Key: $(uuid)" "$GW/api/v1/payments/$PID/capture")" "B captures A's payment"
check 404 "$(code "${BH[@]}" -X POST -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' -d '{}' "$GW/api/v1/payments/$PID/cancel")" "B cancels A's payment"
check 409 "$(code "${BH[@]}" -X POST -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}" "$GW/api/v1/refunds")" "B refunds A's payment (not refundable for B)"
check 404 "$(code "${BH[@]}" "$GW/api/v1/refunds/$RID")" "B reads A's refund"
check 404 "$(code "${BH[@]}" "$GW/api/v1/transactions/$TXN")" "B reads A's transaction"
check 404 "$(code "${BH[@]}" "$GW/api/v1/notifications/$NID")" "B reads A's notification"
check 0 "$(curl -s "${BH[@]}" "$GW/api/v1/payments?merchantReference=$(jq -r .merchantReference <<<"$PAY")" | jq '.content | length')" "B lists payments by A's reference"
check 0 "$(curl -s "${BH[@]}" "$GW/api/v1/refunds?paymentId=$PID" | jq '.content | length')" "B lists A's refunds"
check 0 "$(curl -s "${BH[@]}" "$GW/api/v1/transactions?paymentId=$PID" | jq '.content | length')" "B lists A's transactions"
check 0 "$(curl -s "${BH[@]}" "$GW/api/v1/notifications?paymentId=$PID" | jq '.content | length')" "B lists A's notifications"
check 0 "$(curl -s "${BH[@]}" "$GW/api/v1/reports/summary?from=$(date -u -d '-1 day' +%FT%TZ)&to=$(date -u -d '+1 day' +%FT%TZ)" | jq 'length')" "B's summary contains nothing of A"
check "$MID_B" "$(curl -s "${BH[@]}" "$GW/api/v1/merchants/me" | jq -r .id)" "B's profile is B's own"
check 404 "$(code "${BH[@]}" -X DELETE "$GW/api/v1/merchants/me/api-keys/$KEYS_A")" "B revokes A's API key"

step "2. Role matrix"
AH=(-H "Authorization: Bearer $A"); OH=(-H "Authorization: Bearer $OPS")
for path in /api/v1/admin/merchants /api/v1/admin/notifications /api/v1/admin/ledger/integrity /api/v1/reconciliation/batches; do
  check 403 "$(code "${AH[@]}" "$GW$path")" "hospital token on $path"
  check 401 "$(code "$GW$path")" "no token on $path"
  check 200 "$(code "${OH[@]}" "$GW$path")" "operations token on $path"
done
for path in /api/v1/payments /api/v1/refunds /api/v1/transactions /api/v1/notifications /api/v1/merchants/me; do
  check 403 "$(code "${OH[@]}" "$GW$path")" "operations token on hospital route $path"
  check 401 "$(code "$GW$path")" "no token on $path"
done
check "403|404" "$(code "${OH[@]}" "$GW/internal/v1/merchants/$MID_A/webhook-config")" "internal endpoint through the gateway"
check 401 "$(code -H 'Authorization: Bearer not.a.jwt' "$GW/api/v1/payments")" "forged token"

step "3. Log hygiene"
sleep 2
LEAKS=$(docker compose -f deploy/docker-compose.yml --profile '*' logs --no-color 2>/dev/null | grep -c "$PATIENT" || true)
check 0 "$LEAKS" "log lines containing the patient's email unmasked"
check 0 "$(docker compose -f deploy/docker-compose.yml --profile '*' logs --no-color 2>/dev/null | grep -c '9811122233' || true)" "log lines containing the patient's phone"
TRACED=$(docker logs --since 2m hpg-payment-service-1 2>&1 | grep -c '"traceId"\|"trace.id"\|"trace":{' || true)
check '[1-9][0-9]*' "$TRACED" "payment-service log lines carrying a trace id"

if (( FAILS > 0 )); then printf '\n\033[31m%d check(s) failed.\033[0m\n' "$FAILS"; exit 1; fi
printf '\n\033[32mPhase 7 security smoke test passed.\033[0m\n'
