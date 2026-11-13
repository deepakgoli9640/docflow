# Shared helpers for the scripts in ./scripts (source after GW and TOKEN are set).
#
# Merchant settings reach payment-service asynchronously (merchant.snapshots.v1). After activating a
# merchant or changing its configuration, wait until payment-service accepts payments in the expected
# capture mode before relying on it.

# set_capture_mode <AUTO|MANUAL>: changes the merchant's own configuration (PUT replaces it whole)
set_capture_mode() {
  local cfg
  cfg=$(curl -sf -H "Authorization: Bearer $TOKEN" "$GW/api/v1/merchants/me/configuration" | jq --arg m "$1" '.captureMode = $m')
  curl -sf -o /dev/null -X PUT "$GW/api/v1/merchants/me/configuration" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$cfg"
}

# wait_until_payable <AUTO|MANUAL> [seconds]: probes with a throwaway payment (cancelled right away) until
# payment-service's copy of the merchant is active and in the expected capture mode
wait_until_payable() {
  local mode=$1 timeout=${2:-60} out code body
  for ((i = 0; i < timeout; i++)); do
    out=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" \
      -H "Idempotency-Key: probe-$(date +%s%N)" -H 'Content-Type: application/json' \
      -d "{\"merchantReference\":\"PROBE-$(date +%s%N)\",\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}")
    code=$(tail -1 <<<"$out"); body=$(sed '$d' <<<"$out")
    if [[ $code == 201 ]]; then
      curl -s -o /dev/null -X POST "$GW/api/v1/payments/$(jq -r .id <<<"$body")/cancel" -H "Authorization: Bearer $TOKEN" \
        -H "Idempotency-Key: probe-cancel-$(date +%s%N)" -H 'Content-Type: application/json' -d '{"reason":"readiness probe"}'
      [[ $(jq -r .captureMode <<<"$body") == "$mode" ]] && return 0
    fi
    sleep 1
  done
  echo "FAIL: payment-service did not pick up the merchant's settings ($mode) within ${timeout}s"; exit 1
}

# settled_rows: prints "type,provider_reference,amount" for the hospital's captured payments and completed
# refunds, once transaction-service has booked them (i.e. the ledger knows them)
settled_rows() {
  curl -sf -H "Authorization: Bearer $TOKEN" "$GW/api/v1/transactions?size=100" | jq -r '.content[]
    | select((.type == "PAYMENT" and .status == "CAPTURED") or (.type == "REFUND" and .status == "COMPLETED"))
    | select(.providerReference != null)
    | "\(if .type == "PAYMENT" then "payment" else "refund" end),\(.providerReference),\(.amount.amount)"'
}

# settlement_csv <rows>: a provider settlement report (docs/05-reconciliation.md §3) from "type,ref,amount" lines
settlement_csv() {
  echo "entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at"
  local now; now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  while IFS=, read -r type ref amount; do
    [[ -n $ref ]] && echo "$ref,$type,,$amount,0.00,0.00,INR,setl_demo,$now,$now"
  done <<<"$1"
}

# upload_settlement <file> <businessDate>: as OPS; prints the response body
upload_settlement() {
  curl -s -X POST "$GW/api/v1/reconciliation/settlement-files?businessDate=$2" -H "Authorization: Bearer $OPS" \
    -F "file=@$1;type=text/csv;filename=settlement-$2.csv"
}

# await_batch <batchId>: waits until the batch is no longer RUNNING; prints it
await_batch() {
  local b
  for _ in $(seq 1 60); do
    b=$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/reconciliation/batches/$1")
    [[ $(jq -r .status <<<"$b") != RUNNING ]] && { echo "$b"; return 0; }
    sleep 1
  done
  echo "$b"
}
