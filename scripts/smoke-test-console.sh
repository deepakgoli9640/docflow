#!/usr/bin/env bash
# Checks the web console end to end with nothing but curl: sign in as a hospital and as operations,
# create a payment through the form, pay it on the simulated checkout, and confirm the console shows it.
#
# Requires curl and jq, and the stack with the ui profile:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile notify --profile ui up -d --build
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
CONSOLE=${CONSOLE:-http://localhost:8086}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
JAR=$(mktemp /tmp/hpg-console-cookies-XXXXXX)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
expect() { if [[ "$1" != "$2" ]]; then echo "FAIL: $3 (expected $1, got $2)"; exit 1; fi; echo "ok: $3 -> $2"; }
contains() { if ! grep -q "$2" <<<"$1"; then echo "FAIL: $3"; exit 1; fi; echo "ok: $3"; }
trap 'rm -f "$JAR"' EXIT

# The console uses a session cookie and CSRF tokens, exactly like a browser would.
console_login() {
    rm -f "$JAR"
    curl -s -c "$JAR" "$CONSOLE/login" >/dev/null
    local token; token=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}')
    curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -X POST "$CONSOLE/login" \
        -d "username=$1" -d "password=$2" -d "_csrf=$token"
}
csrf() { grep XSRF-TOKEN "$JAR" | awk '{print $7}'; }
page() { curl -s -b "$JAR" -c "$JAR" -L "$CONSOLE$1"; }
code() { curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' "$CONSOLE$1"; }

step "Set up a hospital through the API"
EMAIL="console+$(date +%s%N)@cityhospital.example"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
    -d "{\"legalName\":\"City Hospital Pvt Ltd\",\"displayName\":\"City Hospital\",\"contactEmail\":\"$EMAIL\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG")
KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
OPS_TOKEN=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS_TOKEN" -H 'Content-Type: application/json' \
    -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
echo "merchantId=$MID"

step "1. Everything needs a session"
expect 302 "$(code /payments)" "signed-out visitor is redirected"

step "2. A bad secret is refused"
expect 302 "$(console_login "$KEY_ID" wrong-secret)" "wrong secret is rejected"
expect 302 "$(code /payments)" "still signed out"

step "3. Hospital signs in with its API key"
expect 302 "$(console_login "$KEY_ID" "$KEY_SECRET")" "sign-in accepted"
expect 200 "$(code /payments)" "payments page"
contains "$(page /payments)" "Payments" "the payments page renders"

step "4. A hospital cannot reach the operations pages"
expect 403 "$(code /ops/merchants)" "hospital is refused /ops/merchants"

step "5. The browser is never given a token or the secret"
HTML=$(page /payments)
if grep -q "$KEY_SECRET" <<<"$HTML"; then echo "FAIL: the secret reached the browser"; exit 1; fi
if grep -qE 'eyJ[A-Za-z0-9_-]{10,}\.' <<<"$HTML"; then echo "FAIL: an access token reached the browser"; exit 1; fi
echo "ok: no secret and no access token in the HTML"
# curl marks HttpOnly cookies with a #HttpOnly_ prefix in its jar. The session cookie must be one:
# a cookie JavaScript can read is a cookie an injected script can steal.
contains "$(cat "$JAR")" "#HttpOnly_.*JSESSIONID" "the session cookie is HttpOnly"

step "6. A form post without a CSRF token is refused"
expect 403 "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$CONSOLE/payments" \
    -d "merchantReference=BILL-CSRF" -d "amount=100.00")" "post without a CSRF token"

step "7. Create a payment through the console form"
REF="BILL-CONSOLE-$RANDOM"
LOCATION=$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{redirect_url}' -X POST "$CONSOLE/payments" \
    -d "merchantReference=$REF" -d "amount=20000.00" -d "description=Patient share" \
    -d "customerName=A Patient" -d "customerEmail=patient@example.com" -d "_csrf=$(csrf)")
PAYMENT_PATH=${LOCATION#*"$CONSOLE"}
[[ -n "$PAYMENT_PATH" ]] || { echo "FAIL: no payment was created"; exit 1; }
echo "ok: created, console redirected to $PAYMENT_PATH"
DETAIL=$(page "$PAYMENT_PATH")
contains "$DETAIL" "$REF" "the detail page shows the bill reference"
contains "$DETAIL" "PENDING" "the payment is pending"
contains "$DETAIL" "p\*\*\*\*\*t@example.com" "the patient's email is masked"

step "8. The patient pays on the hosted checkout"
CHECKOUT=$(grep -oE 'data-copy="http[^"]+/checkout/[^"]+"' <<<"$DETAIL" | head -1 | cut -d'"' -f2)
[[ -n "$CHECKOUT" ]] || { echo "FAIL: no checkout link on the page"; exit 1; }
curl -s -o /dev/null -X POST "${CHECKOUT}/simulate" -d outcome=SUCCESS
contains "$(page "$PAYMENT_PATH")" "CAPTURED" "the console now shows it captured"

step "9. Operations sign in and see the hospital"
expect 302 "$(console_login ops-console "$OPS_SECRET")" "operations sign-in"
contains "$(page /ops/merchants)" "City Hospital" "the hospital is listed"
contains "$(page "/ops/merchants/$MID")" "Suspend" "the lifecycle actions are offered"
expect 403 "$(code /payments)" "operations are refused the hospital pages"

step "10. Notification delivery is visible to operations"
contains "$(page "/ops/notifications?merchantId=$MID")" "PaymentCaptured" "deliveries for this hospital"

step "11. Signing out ends the session"
curl -s -b "$JAR" -c "$JAR" -o /dev/null -X POST "$CONSOLE/logout" -d "_csrf=$(csrf)"
expect 302 "$(code /ops/merchants)" "signed out"

printf '\n\033[32mConsole smoke test passed.\033[0m\n'
