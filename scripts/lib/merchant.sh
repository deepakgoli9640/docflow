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
