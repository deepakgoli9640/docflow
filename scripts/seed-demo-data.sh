#!/usr/bin/env bash
# Fills a running local stack with demo data for the dashboard (http://localhost:8090) and prints the
# credentials to sign in. Uses the SIMULATED provider (HPG_PAYMENT_PROVIDER=simulated): no money moves.
# Requires curl + jq and:
#   docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile refund \
#     --profile transactions --profile notify --profile dashboard up -d --build
set -euo pipefail

cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
OPS=$(curl -sf -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)

register() { # register <display name> <legal name>
  curl -sf -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' -d "{\"legalName\":\"$2\",
    \"displayName\":\"$1\",\"contactEmail\":\"billing+$(date +%s%N)@$(tr -dc a-z <<<"${1,,}").example\",\"contactPhone\":\"+919800001234\"}"
}

echo "Registering hospitals..."
REG=$(register "City Hospital" "City Hospital Pvt Ltd")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"KYC verified"}' "$GW/api/v1/admin/merchants/$MID/activate"
register "Lakeview Clinic" "Lakeview Clinic LLP" >/dev/null   # left waiting for activation (operations view)
TOKEN=$(curl -sf -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
AUTH=(-H "Authorization: Bearer $TOKEN")
source scripts/lib/merchant.sh
echo "Waiting for payment-service to receive the hospital's settings..."
wait_until_payable AUTO

pay() { # pay <amount> <patient name> <email> <outcome: SUCCESS|FAILURE|OPEN|CANCEL> -> payment id
  local p id co
  p=$(curl -sf -X POST "$GW/api/v1/payments" "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
    -d "{\"merchantReference\":\"BILL-2026-$RANDOM\",\"amount\":{\"amount\":\"$1\",\"currency\":\"INR\"},
         \"description\":\"Patient share after insurance\",\"customer\":{\"name\":\"$2\",\"email\":\"$3\"}}")
  id=$(jq -r .id <<<"$p"); co=$(jq -r .checkoutUrl <<<"$p")
  case $4 in
    SUCCESS|FAILURE) curl -sf -o /dev/null -X POST "$GW/${co#*://*/}/simulate" -d "outcome=$4" ;;
    CANCEL) curl -sf -o /dev/null -X POST "$GW/api/v1/payments/$id/cancel" "${AUTH[@]}" -H "Idempotency-Key: $(uuid)" \
              -H 'Content-Type: application/json' -d '{"reason":"Bill re-issued"}' ;;
  esac
  echo "$id"
}

echo "Creating payments..."
P1=$(pay 20000.00 "Asha Rao" asha@example.com SUCCESS)
P2=$(pay 4500.00 "Vikram Iyer" vikram@example.com SUCCESS)
pay 12800.00 "Meera Nair" meera@example.com SUCCESS >/dev/null
pay 950.00 "Rahul Das" rahul@example.com SUCCESS >/dev/null
pay 3200.00 "Sana Khan" sana@example.com FAILURE >/dev/null
pay 7600.00 "Arjun Mehta" arjun@example.com OPEN >/dev/null
pay 1800.00 "Priya Shah" priya@example.com CANCEL >/dev/null
set_capture_mode MANUAL && wait_until_payable MANUAL
pay 15000.00 "Kiran Bose" kiran@example.com SUCCESS >/dev/null   # authorized, waiting for capture
set_capture_mode AUTO && wait_until_payable AUTO

refund() { # refund <paymentId> <amount> <reason>; retries (same key) while the capture event is on its way
  local key; key=$(uuid)
  for _ in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/refunds" "${AUTH[@]}" -H "Idempotency-Key: $key" \
      -H 'Content-Type: application/json' -d "{\"paymentId\":\"$1\",\"amount\":{\"amount\":\"$2\",\"currency\":\"INR\"},\"reason\":\"$3\"}")
    [[ $code == 201 ]] && return 0
    sleep 1
  done
  echo "refund of $1 did not succeed (last HTTP $code)"; return 1
}
echo "Creating refunds..."
refund "$P1" 5000.00 "Procedure partially covered on re-adjudication"
refund "$P2" 4500.00 "Duplicate payment at the front desk"

if curl -s -o /dev/null -f -H "Authorization: Bearer $OPS" "$GW/api/v1/reconciliation/batches?size=1"; then
  echo "Reconciling with a settlement report that has deliberate differences..."
  for _ in $(seq 1 30); do [[ $(settled_rows | grep -c refund) -ge 2 ]] && break; sleep 1; done
  sleep 3   # let reconciliation-service receive the ledger events
  ROWS=$(settled_rows)
  MISMATCH=$(awk -F, 'NR==1 {printf "%s,%s,%.2f\n", $1, $2, $3 - 10}' <<<"$ROWS")   # provider short by 10.00
  REPORT=$(printf '%s\n%s\n%s\npayment,pay_unknown_%s,999.00\n' "$MISMATCH" "$(sed -n '3,$p' <<<"$ROWS")" \
    "$(sed -n 3p <<<"$ROWS")" "$RANDOM")                    # row 2 left out, row 3 twice, one unknown payment
  CSV=$(mktemp --suffix=.csv); settlement_csv "$REPORT" > "$CSV"
  BATCH=$(upload_settlement "$CSV" "$(date +%Y-%m-%d)"); rm -f "$CSV"
  await_batch "$(jq -r .id <<<"$BATCH")" | jq -c '{businessDate, status, matched, mismatched, missingInProvider, missingInInternalSystem, duplicate}'
fi

# Single sign-on users when Keycloak runs (profile "auth"); names are made unique per run.
SSO=""
if curl -s -o /dev/null -f "http://localhost:8090/auth/realms/hpg/.well-known/openid-configuration"; then
  suffix=$RANDOM
  SSO=$(printf '  Single sign-on     %s\n                     %s\n' \
    "$(./scripts/keycloak-users.sh hospital "staff$suffix" "$MID")" "$(./scripts/keycloak-users.sh ops "ops$suffix")")
fi

cat <<INFO

Demo data ready. Open http://localhost:8090
$SSO

  Hospital sign-in   API key ID: $KEY_ID
                     Secret:     $KEY_SECRET
  Operations sign-in Client ID:  ops-console
                     Secret:     HPG_OPS_CLIENT_SECRET in deploy/env/.env

Patient emails: http://localhost:8025
INFO
