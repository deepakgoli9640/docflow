#!/usr/bin/env bash
# End-to-end smoke test of Phase 2 through the API gateway (http://localhost:8080).
# Requires: curl, jq, and the stack running:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core up -d
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
# shellcheck disable=SC1091
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { # expect <expected-status> <actual-status> <label>
    if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi
    echo "ok: $3 -> $2"
}

step "1. Register a hospital (public endpoint)"
EMAIL="billing+$(date +%s)@cityhospital.example"
REG=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: smoke-register-1' \
  -d "{\"legalName\":\"City Hospital Pvt Ltd\",\"displayName\":\"City Hospital\",\"contactEmail\":\"$EMAIL\",\"contactPhone\":\"+919800001234\"}")
expect 201 "$(tail -1 <<<"$REG")" "register"
BODY=$(sed '$d' <<<"$REG")
MERCHANT_ID=$(jq -r .merchant.id <<<"$BODY")
KEY_ID=$(jq -r .apiKey.keyId <<<"$BODY")
KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$BODY")
echo "merchantId=$MERCHANT_ID status=$(jq -r .merchant.status <<<"$BODY") keyId=$KEY_ID"

step "2. Exchange the API key for an access token (OAuth2 client credentials)"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
[[ "$TOKEN" != "null" && -n "$TOKEN" ]] || { echo "FAIL: no token"; exit 1; }
echo "token claims: $(cut -d. -f2 <<<"$TOKEN" | tr '_-' '/+' | base64 -d 2>/dev/null | jq -c '{sub,mid,roles,iss}' || true)"

step "3. Read own profile"
expect 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$GW/api/v1/merchants/me")" "GET /me"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/merchants/me")" "GET /me without token"

step "4. Merchant cannot activate itself"
expect 403 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"self"}' "$GW/api/v1/admin/merchants/$MERCHANT_ID/activate")" "self-activation"

step "5. Ops activates the merchant"
OPS_TOKEN=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
STATUS=$(curl -s -X POST -H "Authorization: Bearer $OPS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC documents verified"}' "$GW/api/v1/admin/merchants/$MERCHANT_ID/activate" | jq -r .status)
expect ACTIVE "$STATUS" "activate"

step "6. Configure payments (limits above platform maximum are rejected)"
CONFIG='{"enabledMethods":["UPI","CARD"],"captureMode":"MANUAL","minAmount":{"amount":"10.00","currency":"INR"},
 "maxAmountPerTransaction":{"amount":"150000.00","currency":"INR"},"dailyLimit":{"amount":"2000000.00","currency":"INR"},
 "paymentTtlMinutes":30,"maxAttempts":3,"refundWindowDays":90,"webhookUrl":"https://his.cityhospital.example/hpg/webhooks",
 "notifyPatientEmail":true,"notifyPatientSms":false}'
expect 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$CONFIG" "$GW/api/v1/merchants/me/configuration")" "valid configuration"
expect 422 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "${CONFIG/150000.00/900000.00}" "$GW/api/v1/merchants/me/configuration")" "limit above platform maximum"

step "7. Suspended merchant cannot get new tokens"
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Chargeback investigation"}' "$GW/api/v1/admin/merchants/$MERCHANT_ID/suspend"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token")" "token while suspended"

step "8. Internal endpoints are not reachable through the gateway"
expect 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $OPS_TOKEN" \
  "$GW/internal/v1/merchants/$MERCHANT_ID/webhook-config")" "internal via gateway"

step "9. Error format (Problem Details with correlation id)"
curl -s -H 'X-Correlation-Id: smoke-err-1' "$GW/api/v1/merchants/me" | jq -c '{status,errorCode,correlationId}'

printf '\n\033[32mPhase 2 smoke test passed.\033[0m\n'
