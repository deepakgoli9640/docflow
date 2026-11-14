#!/usr/bin/env bash
# Chaos test: real failures while hospitals keep paying and refunding, then checks that no money was lost or
# booked twice. Runs against the two-replica stack (scripts/ha-check.sh starts it) or the normal one.
#
# Workload: WORKERS parallel clients, each looping (at least ITERATIONS times, and until the chaos is over): create a
# payment, the patient pays, and every other
# payment gets a partial refund. Like a real client, every request is retried on 5xx / connection errors with the
# SAME Idempotency-Key until it succeeds.
# Chaos, while that runs: kill service containers (one replica at a time, then all of one service), pause Kafka,
# restart PostgreSQL. Afterwards: wait for the system to converge, then check the invariants.
set -uo pipefail
cd "$(dirname "$0")/.."
GW=${GW:-http://localhost:8080}
WORKERS=${WORKERS:-6}
ITERATIONS=${ITERATIONS:-5}
OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-)
PGU=$(grep '^POSTGRES_USER=' deploy/env/.env | cut -d= -f2-)
OUT=$(mktemp -d)
command -v jq >/dev/null || { echo "jq is required"; exit 1; }
uuid() { cat /proc/sys/kernel/random/uuid; }
pg() { docker exec "$(docker ps -qf name=hpg-postgres)" psql -U "$PGU" -d "$1" -Atc "$2"; }
c() { docker ps -qf "name=hpg-$1" ; }   # container ids of a service (all replicas)

# call <file-for-body> curl-args...: retries until a non-5xx, non-connection-error answer (max ~3 min)
call() {
  local out=$1 code; shift
  for attempt in $(seq 1 90); do
    code=$(curl -s -o "$out" -w '%{http_code}' --max-time 15 "$@")
    [[ $code != 000 && $code != 5* ]] && { echo "$code"; return 0; }
    echo "retry $code" >> "$OUT/retries"
    sleep 2
  done
  echo "$code"
}

echo "== Setup"
REG=$(curl -s -X POST "$GW/api/v1/merchants" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Chaos Hospital Pvt Ltd\",\"displayName\":\"Chaos Hospital\",\"contactEmail\":\"chaos$(date +%s%N)@x.example\"}")
MID=$(jq -r .merchant.id <<<"$REG"); KEY_ID=$(jq -r .apiKey.keyId <<<"$REG"); KEY_SECRET=$(jq -r .apiKey.keySecret <<<"$REG")
OPS=$(curl -s -u "ops-console:$OPS_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
curl -s -o /dev/null -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"chaos"}' "$GW/api/v1/admin/merchants/$MID/activate"
TOKEN=$(curl -s -u "$KEY_ID:$KEY_SECRET" -d grant_type=client_credentials "$GW/oauth2/token" | jq -r .access_token)
GW=$GW TOKEN=$TOKEN OPS=$OPS source scripts/lib/merchant.sh
wait_until_payable AUTO
echo "hospital $MID"

worker() {
  local w=$1 body="$OUT/w$w.json"
  # At least ITERATIONS rounds, and until the chaos sequence has finished, so every failure meets live traffic.
  local i=0
  while (( i < ITERATIONS )) || [[ ! -f $OUT/chaos-done ]]; do
    i=$((i + 1))
    local key amount pid checkout code
    key=$(uuid); amount="$((100 + RANDOM % 900)).00"
    code=$(call "$body" -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $key" \
      -H 'Content-Type: application/json' -d "{\"merchantReference\":\"CHAOS-$w-$i-$RANDOM\",\"amount\":{\"amount\":\"$amount\",\"currency\":\"INR\"}}")
    [[ $code == 201 ]] || { echo "create $code $(cat "$body")" >> "$OUT/errors"; continue; }
    pid=$(jq -r .id "$body"); checkout=$(jq -r .checkoutUrl "$body")
    echo "$pid $amount" >> "$OUT/payments"
    call "$body" -X POST "$GW/${checkout#*://*/}/simulate" -d outcome=SUCCESS > /dev/null
    if (( i % 2 == 0 )); then
      # Retried refund: same key every time. 409 PAYMENT_NOT_YET_REFUNDABLE = capture still on its way: wait.
      local rkey; rkey=$(uuid)
      for _ in $(seq 1 60); do
        code=$(call "$body" -X POST "$GW/api/v1/refunds" -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $rkey" \
          -H 'Content-Type: application/json' -d "{\"paymentId\":\"$pid\",\"amount\":{\"amount\":\"50.00\",\"currency\":\"INR\"}}")
        [[ $code == 201 ]] && { echo "$pid" >> "$OUT/refunds"; break; }
        [[ $(jq -r .errorCode "$body" 2>/dev/null) == PAYMENT_NOT_YET_REFUNDABLE ]] || { echo "refund $code $(cat "$body")" >> "$OUT/errors"; break; }
        sleep 2
      done
    fi
  done
}

chaos() {
  sleep 5
  for svc in payment-service transaction-service refund-service notification-service; do
    local first; first=$(c "$svc" | head -1)
    [[ -n $first ]] || continue
    echo "chaos: kill one $svc"; docker kill "$first" > /dev/null; sleep 8; docker start "$first" > /dev/null; sleep 4
  done
  echo "chaos: pause Kafka 20 s"; docker pause "$(c kafka)" > /dev/null; sleep 20; docker unpause "$(c kafka)" > /dev/null
  sleep 5
  echo "chaos: PostgreSQL down for 15 s"; docker stop -t 1 "$(c postgres)" > /dev/null; sleep 15
  docker start "$(docker ps -aqf name=hpg-postgres)" > /dev/null; sleep 10
  echo "chaos: kill the API gateway"; docker kill "$(c api-gateway)" > /dev/null; sleep 5
  docker start "$(docker ps -aqf name=hpg-api-gateway)" > /dev/null; sleep 10
  echo "chaos: kill ALL payment-service replicas"; for id in $(c payment-service); do docker kill "$id" > /dev/null; done
  sleep 10; for id in $(docker ps -aqf name=hpg-payment-service); do docker start "$id" > /dev/null; done
  echo "chaos: done"
  sleep 5; touch "$OUT/chaos-done"
}

echo "== Workload ($WORKERS workers x $ITERATIONS) with chaos"
started=$(date +%s)
chaos > "$OUT/chaos.log" 2>&1 &
CHAOS=$!
for w in $(seq 1 "$WORKERS"); do worker "$w" & done
wait $(jobs -p | grep -v "^$CHAOS$") 2>/dev/null; wait "$CHAOS"
cat "$OUT/chaos.log"
touch "$OUT/retries" "$OUT/errors" "$OUT/refunds"
echo "workload done in $(( $(date +%s) - started )) s"
echo "requests that failed and were retried with the same Idempotency-Key: $(wc -l < "$OUT/retries")"
sort "$OUT/retries" | uniq -c | sed 's/^/   /'
echo "requests that never succeeded: $(wc -l < "$OUT/errors")"; head -5 "$OUT/errors"

echo "== Waiting for the system to converge"
ids=$(awk '{printf "%s'\''%s'\''", (NR>1?",":""), $1}' "$OUT/payments")
for _ in $(seq 1 120); do
  pending=0
  for db in payment_db refund_db transaction_db notification_db; do
    n=$(pg $db "SELECT count(*) FROM outbox_events WHERE published_at IS NULL" 2>/dev/null || echo 1); pending=$((pending + n))
  done
  captured=$(pg payment_db "SELECT count(*) FROM payments WHERE id IN ($ids) AND status = 'CAPTURED'" 2>/dev/null || echo -1)
  booked=$(pg transaction_db "SELECT count(DISTINCT t.payment_id) FROM transactions t JOIN ledger_entries e ON e.transaction_id = t.id AND e.posting_type = 'CAPTURE' WHERE t.payment_id IN ($ids)" 2>/dev/null || echo -2)
  [[ $pending == 0 && $captured == "$booked" ]] && break
  sleep 3
done

echo "== Invariants"
FAILS=0
check() { if [[ "$2" == "$3" ]]; then echo "ok: $1 ($3)"; else echo "FAIL: $1: expected $2, got $3"; FAILS=$((FAILS+1)); fi; }
total=$(wc -l < "$OUT/payments")
check "every payment the client created is captured" "$total" "$(pg payment_db "SELECT count(*) FROM payments WHERE id IN ($ids) AND status = 'CAPTURED'")"
check "no payment created twice (idempotency under retries)" "$total" "$(pg payment_db "SELECT count(*) FROM payments WHERE merchant_id = '$MID' AND merchant_reference LIKE 'CHAOS-%'")"
check "every capture booked exactly once in the ledger" "$((total * 2))" "$(pg transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id IN ($ids) AND e.posting_type = 'CAPTURE'")"
refunds=$(wc -l < "$OUT/refunds" 2>/dev/null || echo 0)
check "no refund created twice" "$refunds" "$(pg refund_db "SELECT count(*) FROM refunds WHERE merchant_id = '$MID'")"
for _ in $(seq 1 60); do
  done_refunds=$(pg refund_db "SELECT count(*) FROM refunds WHERE merchant_id = '$MID' AND status = 'COMPLETED'")
  [[ $done_refunds == "$refunds" && $(pg transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.merchant_id = '$MID' AND e.posting_type = 'REFUND'") == $((refunds * 2)) ]] && break
  sleep 3
done
check "every refund completed" "$refunds" "$(pg refund_db "SELECT count(*) FROM refunds WHERE merchant_id = '$MID' AND status = 'COMPLETED'")"
check "every refund booked exactly once" "$((refunds * 2))" "$(pg transaction_db "SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id WHERE t.merchant_id = '$MID' AND e.posting_type = 'REFUND'")"
check "no event recorded twice by transaction-service" "0" "$(pg transaction_db "SELECT count(*) FROM (SELECT event_id FROM transaction_events GROUP BY 1 HAVING count(*) > 1) d")"
check "no refund exceeds its payment" "0" "$(pg refund_db "SELECT count(*) FROM refundable_payments WHERE refunded_amount + reserved_amount > captured_amount")"
check "hospital owed = captured - refunded" \
  "$(awk '{s+=$2} END {printf "%.2f", s - 50 * '"$refunds"'}' "$OUT/payments")" \
  "$(pg transaction_db "SELECT COALESCE(sum(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_entries WHERE account_code = 'MERCHANT_PAYABLE:$MID'")"
check "ledger integrity" "true" "$(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/ledger/integrity" | jq -r .healthy)"
check "no unpublished events left" "0" "$(for db in payment_db refund_db transaction_db notification_db; do pg $db "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"; done | awk '{s+=$1} END {print s}')"
echo "dead letters: $(curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/admin/dead-letters?limit=200" | jq 'length')"
rm -rf "$OUT"
(( FAILS == 0 )) && printf '\n\033[32mChaos test passed: %s payments, %s refunds, nothing lost or booked twice.\033[0m\n' "$total" "$refunds" \
  || { printf '\n\033[31mChaos test: %s invariant(s) violated.\033[0m\n' "$FAILS"; exit 1; }
