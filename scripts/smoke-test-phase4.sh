#!/usr/bin/env bash
# End-to-end smoke test of the full flow through the API gateway:
#   hospital -> gateway -> payment-service -> Kafka -> refund / transaction / notification services.
# Uses the SIMULATED provider (HPG_PAYMENT_PROVIDER=simulated in deploy/env/.env). Requires curl + jq and:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core \
#     --profile refund --profile transactions --profile notify up -d --build
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
MAILPIT=${MAILPIT:-http://localhost:8025}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
# eventually <seconds> <description> <command...>: retries until the command succeeds (events are asynchronous)
eventually() {
  local timeout=$1 what=$2; shift 2
  for ((i = 0; i < timeout; i++)); do "$@" >/dev/null 2>&1 && { echo "ok: $what"; return 0; }; sleep 1; done
  echo "FAIL: $what (not within ${timeout}s)"; exit 1
}

step "Setup: register + activate a hospital, get tokens"
PATIENT="patient+$(date +%s%N)@example.com"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"City Hospital Pvt Ltd\",\"displayName\":\"City Hospital\",\"contactEmail\":\"billing+$(date +%s%N)@city.example\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
source scripts/lib/merchant.sh
wait_until_payable AUTO && echo "ok: merchant settings replicated to payment-service via merchant.snapshots.v1"
AUTH=(-H "Authorization: Bearer $TOKEN")

step "1. Payment Service: hospital creates a payment, patient pays (SIMULATED provider)"
PAY=$(curl -s -X POST "$GW/api/v1/payments" "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"BILL-$RANDOM$RANDOM\",\"amount\":{\"amount\":\"20000.00\",\"currency\":\"INR\"},
       \"description\":\"Patient share\",\"customer\":{\"name\":\"A Patient\",\"email\":\"$PATIENT\"}}")
PID=$(jq -r .id <<<"$PAY"); CHECKOUT=$(jq -r .checkoutUrl <<<"$PAY"); TOKEN_PATH=${CHECKOUT#*://*/}
curl -s -o /dev/null -X POST "$GW/$TOKEN_PATH/simulate" -d outcome=SUCCESS
expect CAPTURED "$(curl -s "${AUTH[@]}" "$GW/api/v1/payments/$PID" | jq -r .status)" "payment captured"

step "2. Refund Service: partial refund once PaymentCaptured has arrived through Kafka"
refund() { # refund <amount> <key> -> body + status line
  curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/refunds" "${AUTH[@]}" -H "Idempotency-Key: $2" \
    -H 'Content-Type: application/json' \
    -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"$1\",\"currency\":\"INR\"},\"reason\":\"Insurance re-adjudication\"}"
}
REFUND_KEY=$(uuid)
refundable() { [[ "$(refund 5000.00 "$REFUND_KEY" | tail -1)" == 201 ]]; }
eventually 60 "refund accepted (payment became refundable via payment.events.v1)" refundable
OUT=$(refund 5000.00 "$REFUND_KEY"); RID=$(sed '$d' <<<"$OUT" | jq -r .id)
expect COMPLETED "$(sed '$d' <<<"$OUT" | jq -r .status)" "refund status (idempotent replay of the same request)"
OUT=$(refund 16000.00 "$(uuid)")
expect 422 "$(tail -1 <<<"$OUT")" "refund above the remaining 15000.00 is rejected"
expect 15000.00 "$(sed '$d' <<<"$OUT" | jq -r .remainingRefundable.amount)" "remainingRefundable"

step "3. Transaction Service: history, status, ledger and summary built from events"
two_txns() { [[ "$(curl -s "${AUTH[@]}" "$GW/api/v1/transactions?paymentId=$PID" | jq -r '[.content[] | .type + ":" + .status] | sort | join(",")')" == "PAYMENT:CAPTURED,REFUND:COMPLETED" ]]; }
eventually 60 "transactions: PAYMENT CAPTURED + REFUND COMPLETED" two_txns
REF=$(curl -s "${AUTH[@]}" "$GW/api/v1/transactions?paymentId=$PID&type=PAYMENT" | jq -r '.content[0].transactionRef')
curl -s "${AUTH[@]}" "$GW/api/v1/transactions/$REF" | jq -c '{transactionRef, status, timeline: [.timeline[].status], ledger: [.ledgerEntries[] | "\(.direction) \(.accountCode | split(":")[0]) \(.amount.amount)"]}'
FROM=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ); TO=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)
expect 15000.00 "$(curl -s "${AUTH[@]}" "$GW/api/v1/reports/summary?from=$FROM&to=$TO" | jq -r '.[0].netAmount.amount')" "net amount in summary report"
expect true "$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/ledger/integrity" | jq -r .healthy)" "ledger integrity (OPS)"

step "4. Notification Service: patient receipt by email; hospital webhook status"
delivered() { [[ "$(curl -s "${AUTH[@]}" "$GW/api/v1/notifications?paymentId=$PID&channel=EMAIL" | jq -r '.content[0].status')" == DELIVERED ]]; }
eventually 60 "patient email DELIVERED" delivered
in_mailpit() { curl -s "$MAILPIT/api/v1/search?query=to:$PATIENT" | jq -e '.messages | length > 0'; }
eventually 30 "email visible in Mailpit ($MAILPIT)" in_mailpit
curl -s "${AUTH[@]}" "$GW/api/v1/notifications?paymentId=$PID" | jq -c '[.content[] | {channel, eventType, status, recipient}]'
echo "(webhooks are SKIPPED until the hospital configures a webhook URL: PUT /api/v1/merchants/me/configuration)"

step "5. Isolation: another hospital sees none of it"
REG2=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Other Hospital\",\"displayName\":\"Other\",\"contactEmail\":\"other+$(date +%s%N)@x.example\"}")
T2=$(curl -s -u "$(jq -r .apiKey.keyId <<<"$REG2"):$(jq -r .apiKey.keySecret <<<"$REG2")" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T2" "$GW/api/v1/refunds/$RID")" "cross-tenant refund read"
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T2" "$GW/api/v1/transactions/$REF")" "cross-tenant transaction read"

step "6. Suspension: the hospital's existing token is refused at the gateway within seconds (merchant.snapshots.v1)"
status_change() { curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d "{\"reason\":\"$2\"}" "$GW/api/v1/admin/merchants/$MID/$1"; }
code_with_old_token() { curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$GW/api/v1/payments?size=1"; }
status_change suspend "Smoke test: suspension"
refused() { [[ "$(code_with_old_token)" == 403 ]]; }
eventually 30 "token issued before the suspension is refused (403 MERCHANT_SUSPENDED)" refused
expect MERCHANT_SUSPENDED "$(curl -s "${AUTH[@]}" "$GW/api/v1/payments?size=1" | jq -r .errorCode)" "error code"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token")" "no new token while suspended"
status_change activate "Smoke test: reactivation"
accepted() { [[ "$(code_with_old_token)" == 200 ]]; }
eventually 30 "the same token works again after reactivation" accepted

printf '\n\033[32mPhase 4 smoke test passed: payment -> Kafka -> refund, transactions, notifications.\033[0m\n'
