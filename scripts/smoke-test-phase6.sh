#!/usr/bin/env bash
# End-to-end smoke test of reconciliation through the API gateway: a payment and a refund are booked in the
# ledger, reach reconciliation-service through ledger.events.v1, and are matched against a settlement CSV.
# Requires curl + jq and the stack with the "recon" profile (see README).
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

step "Setup: active hospital, a captured payment and a partial refund"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Recon Hospital\",\"displayName\":\"Recon Hospital\",\"contactEmail\":\"recon+$(date +%s%N)@x.example\"}")
MID=$(jq -r .merchant.id <<<"$REG")
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"reason":"ok"}' \
  "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$(jq -r .apiKey.keyId <<<"$REG"):$(jq -r .apiKey.keySecret <<<"$REG")" -d grant_type=client_credentials \
  "$GW/oauth2/token" | jq -r .access_token)
source scripts/lib/merchant.sh
wait_until_payable AUTO
PAY=$(curl -s -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuid)" \
  -H 'Content-Type: application/json' -d "{\"merchantReference\":\"RECON-$RANDOM\",\"amount\":{\"amount\":\"3000.00\",\"currency\":\"INR\"}}")
PID=$(jq -r .id <<<"$PAY"); CO=$(jq -r .checkoutUrl <<<"$PAY")
curl -s -o /dev/null -X POST "$GW/${CO#*://*/}/simulate" -d outcome=SUCCESS
for _ in $(seq 1 30); do
  [[ $(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/refunds" -H "Authorization: Bearer $TOKEN" \
    -H "Idempotency-Key: recon-refund-$PID" -H 'Content-Type: application/json' \
    -d "{\"paymentId\":\"$PID\",\"amount\":{\"amount\":\"500.00\",\"currency\":\"INR\"}}") == 201 ]] && break; sleep 1
done
for _ in $(seq 1 30); do [[ $(settled_rows | wc -l) -ge 2 ]] && break; sleep 1; done
ROWS=$(settled_rows); echo "$ROWS"
expect 2 "$(wc -l <<<"$ROWS" | tr -d ' ')" "ledger bookings visible (capture + refund)"
PAY_REF=$(grep '^payment' <<<"$ROWS" | cut -d, -f2); REFUND_REF=$(grep '^refund' <<<"$ROWS" | cut -d, -f2)
sleep 3   # ledger.events.v1 -> reconciliation-service
TODAY=$(date +%Y-%m-%d)

step "1. Settlement report that agrees with the ledger"
CSV=$(mktemp --suffix=.csv); settlement_csv "$ROWS" > "$CSV"
BATCH=$(upload_settlement "$CSV" "$TODAY"); BID=$(jq -r .id <<<"$BATCH")
expect COMPLETED "$(await_batch "$BID" | jq -r .status)" "batch"
result() { curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/reconciliation/batches/$1/records?size=200" \
  | jq -r --arg r "$2" '.content[] | select(.providerReference == $r) | .status'; }
expect MATCHED "$(result "$BID" "$PAY_REF")" "payment $PAY_REF"
expect MATCHED "$(result "$BID" "$REFUND_REF")" "refund $REFUND_REF"
expect 409 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/reconciliation/settlement-files?businessDate=$TODAY" \
  -H "Authorization: Bearer $OPS" -F "file=@$CSV;type=text/csv")" "same file twice is rejected"

step "2. A corrected report where the provider settled less: flagged, then resolved"
settlement_csv "$(sed "s/^payment,$PAY_REF,3000.00/payment,$PAY_REF,2990.00/" <<<"$ROWS")" > "$CSV"
BID2=$(upload_settlement "$CSV" "$TODAY" | jq -r .id); rm -f "$CSV"
await_batch "$BID2" >/dev/null
expect MISMATCHED "$(result "$BID2" "$PAY_REF")" "payment $PAY_REF"
RID=$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/reconciliation/batches/$BID2/records?status=MISMATCHED&size=200" \
  | jq -r --arg r "$PAY_REF" '.content[] | select(.providerReference == $r) | .id')
expect RESOLVED "$(curl -s -X POST "$GW/api/v1/reconciliation/records/$RID/resolve" -H "Authorization: Bearer $OPS" \
  -H 'Content-Type: application/json' -d '{"resolutionType":"PROVIDER_ERROR_RAISED","note":"Smoke test: raised with provider"}' \
  | jq -r .resolutionStatus)" "discrepancy resolved"
curl -s -H "Authorization: Bearer $OPS" -H 'Accept: text/csv' "$GW/api/v1/reconciliation/reports/daily?date=$TODAY" \
  | grep -q "MISMATCHED,payment,$PAY_REF" && echo "ok: daily CSV report lists the mismatch"
expect 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GW/api/v1/reconciliation/batches")" \
  "hospitals cannot see reconciliation"

printf '\n\033[32mPhase 6 smoke test passed: ledger -> ledger.events.v1 -> reconciliation, discrepancy workflow.\033[0m\n'
