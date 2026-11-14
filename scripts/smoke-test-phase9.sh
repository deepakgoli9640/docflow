#!/usr/bin/env bash
# End-to-end check of the Phase 9c business features through the API gateway, with the SIMULATED provider:
#   1. Patient SMS (receipt) next to the email: logged with a masked number by the local "log" SMS provider.
#   2. Settlement posting: reconciliation matches the payment, the ledger books bank + fees.
#   3. Dispute: chargeback webhook, refunds paused, the hospital contests, the dispute is won, money back.
# Needs the stack: --profile infra --profile core --profile refund --profile transactions --profile notify --profile recon
set -euo pipefail
cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
PGU=$(grep '^POSTGRES_USER=' deploy/env/.env | cut -d= -f2-)
SIM_KEY=simulated-provider-signing-key   # the simulator's webhook key (local demos only)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
eventually() {
  local timeout=$1 what=$2; shift 2
  for ((i = 0; i < timeout; i++)); do "$@" >/dev/null 2>&1 && { echo "ok: $what"; return 0; }; sleep 1; done
  echo "FAIL: $what (not within ${timeout}s)"; exit 1
}
sql() { docker exec "$(docker ps -qf name=hpg-postgres)" psql -U "$PGU" -d "$1" -Atc "$2"; }

step "Setup: hospital, tokens"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Lotus Hospital Pvt Ltd\",\"displayName\":\"Lotus Hospital\",\"contactEmail\":\"billing+$(date +%s%N)@lotus.example\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
source scripts/lib/merchant.sh
wait_until_payable AUTO
AUTH=(-H "Authorization: Bearer $TOKEN")

step "1. A captured payment sends the patient an SMS receipt"
REF="BILL-$RANDOM$RANDOM"
PAY=$(curl -s -X POST "$GW/api/v1/payments" "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"$REF\",\"amount\":{\"amount\":\"1500.00\",\"currency\":\"INR\"},
       \"customer\":{\"name\":\"A Patient\",\"email\":\"p$(date +%s)@example.com\",\"phone\":\"+919811122233\"}}")
PID=$(jq -r .id <<<"$PAY"); CHECKOUT=$(jq -r .checkoutUrl <<<"$PAY")
curl -s -o /dev/null -X POST "$GW/${CHECKOUT#*://*/}/simulate" -d outcome=SUCCESS
expect CAPTURED "$(curl -s "${AUTH[@]}" "$GW/api/v1/payments/$PID" | jq -r .status)" "payment captured"
PPAY=$(curl -s "${AUTH[@]}" "$GW/api/v1/payments/$PID" | jq -r '.attempts[0].providerPaymentId')
sms_sent() { [[ $(sql notification_db "SELECT count(*) FROM notifications WHERE payment_id = '$PID' AND channel = 'SMS' AND status = 'DELIVERED'") == 1 ]]; }
eventually 30 "SMS notification delivered (local provider: logged)" sms_sent
LOGLINE=$(docker logs "$(docker ps -qf name=hpg-notification-service)" 2>&1 | grep "SMS (not sent" | grep -c "$REF" || true)
[[ $LOGLINE -ge 1 ]] && echo "ok: SMS text logged for bill $REF"
docker logs "$(docker ps -qf name=hpg-notification-service)" 2>&1 | grep -q "9811122233" \
  && { echo "FAIL: phone number appears unmasked in the logs"; exit 1; } || echo "ok: phone number masked in the logs"

step "2. Settlement: reconciliation matches the payment, the ledger books bank + fees"
booked() { [[ -n $(settled_rows | grep "$PPAY") ]]; }
eventually 30 "capture booked in the ledger" booked
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
CSV=$(mktemp --suffix=.csv)
printf 'entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at\n%s,payment,,1500.00,30.00,5.40,INR,setl_p9,%s,%s\n' \
  "$PPAY" "$NOW" "$NOW" > "$CSV"
BATCH=$(upload_settlement "$CSV" "$(date -u +%Y-%m-%d)"); rm -f "$CSV"
await_batch "$(jq -r .id <<<"$BATCH")" > /dev/null
settled() { [[ $(sql transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id = '$PID' AND e.posting_type = 'SETTLEMENT'") == 3 ]]; }
eventually 30 "settlement posted (SETTLEMENT_BANK, PROVIDER_FEES, PROVIDER_CLEARING)" settled
expect "1464.60" "$(sql transaction_db "SELECT e.amount FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id = '$PID' AND e.account_code = 'SETTLEMENT_BANK'")" "net to the bank (1500 - 30 - 5.40)"

step "3. Dispute: chargeback, refunds paused, contested, won"
DISP="disp_$RANDOM$RANDOM"
webhook() {   # webhook <event> <status> <amount_deducted_paise>
  local body sig respond
  respond=$(( $(date +%s) + 7 * 86400 ))
  body=$(printf '{"entity":"event","event":"%s","contains":["dispute"],"payload":{"dispute":{"entity":{"id":"%s","entity":"dispute","payment_id":"%s","amount":150000,"currency":"INR","amount_deducted":%s,"reason_code":"chargeback_unauthorised","respond_by":%s,"status":"%s","phase":"chargeback","created_at":%s}}},"created_at":%s}' \
    "$1" "$DISP" "$PPAY" "$3" "$respond" "$2" "$(date +%s)" "$(date +%s)")
  sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$SIM_KEY" | awk '{print $NF}')
  curl -s -X POST "$GW/webhooks/razorpay/payments" -H 'Content-Type: application/json' \
    -H "X-Razorpay-Signature: $sig" -H "X-Razorpay-Event-Id: evt_$(uuid)" -d "$body" | jq -r .outcome
}
expect APPLIED "$(webhook payment.dispute.created open 150000)" "chargeback webhook applied"
DID=$(curl -s "${AUTH[@]}" "$GW/api/v1/disputes" | jq -r '.[0].id')
expect OPEN "$(curl -s "${AUTH[@]}" "$GW/api/v1/disputes/$DID" | jq -r .status)" "dispute visible to the hospital"
refund_blocked() {
  [[ $(curl -s -X POST "$GW/api/v1/refunds" "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
    -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}" | jq -r .errorCode) == PAYMENT_DISPUTED ]]
}
eventually 30 "refund refused while the dispute is open (PAYMENT_DISPUTED)" refund_blocked
charged() { [[ $(sql transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id = '$PID' AND e.posting_type = 'CHARGEBACK'") == 2 ]]; }
eventually 30 "chargeback booked in the ledger" charged
expect UNDER_REVIEW "$(curl -s -X POST "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d '{"summary":"Consultation on file, signed consent and invoice attached"}' "$GW/api/v1/disputes/$DID/contest" | jq -r .status)" \
  "hospital contested with evidence"
expect APPLIED "$(webhook payment.dispute.won won 0)" "dispute won"
reversed() { [[ $(sql transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id = '$PID' AND e.posting_type = 'CHARGEBACK_REVERSAL'") == 2 ]]; }
eventually 30 "chargeback reversed in the ledger" reversed
hooks() { [[ $(sql notification_db "SELECT count(*) FROM notifications WHERE payment_id = '$PID' AND public_event_type LIKE 'dispute.%'") -ge 4 ]]; }
eventually 30 "hospital notified of every dispute step" hooks
expect true "$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/ledger/integrity" | jq -r .healthy)" "ledger integrity"

printf '\n\033[32mPhase 9c smoke test passed: SMS, settlement postings, disputes.\033[0m\n'
