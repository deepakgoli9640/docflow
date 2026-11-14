#!/usr/bin/env bash
# Runs the stack with TWO replicas of every service (deploy/docker-compose.ha.yml) and the end-to-end smoke
# tests against it: the same coordination Kubernetes relies on (outbox relay lock, ShedLock, consumer groups,
# a signing key shared by all merchant-service replicas). Then checks that no event was published twice and
# the ledger balances.
set -euo pipefail
cd "$(dirname "$0")/.."
export HPG_HA_JWT_DIR="$PWD/deploy/env/jwt"      # git-ignored (*.pem)
mkdir -p "$HPG_HA_JWT_DIR"
if [[ ! -f "$HPG_HA_JWT_DIR/private.pem" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$HPG_HA_JWT_DIR/private.pem" 2>/dev/null
  openssl pkey -in "$HPG_HA_JWT_DIR/private.pem" -pubout -out "$HPG_HA_JWT_DIR/public.pem"
  chmod 644 "$HPG_HA_JWT_DIR"/*.pem   # read by the container's non-root user; local demo key only
fi
C="docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.ha.yml"
P="--profile infra --profile core --profile refund --profile transactions --profile notify --profile recon"
$C $P up -d --wait --wait-timeout 300
$C $P ps --format '{{.Name}} {{.Status}}'

./scripts/smoke-test-phase4.sh
./scripts/smoke-test-phase6.sh
./scripts/smoke-test-phase7.sh

PGU=$(grep '^POSTGRES_USER=' deploy/env/.env | cut -d= -f2-)
q() { docker exec "$($C ps -q postgres)" psql -U "$PGU" -d "$1" -Atc "$2"; }
echo "== Checks across replicas"
for db in merchant_db payment_db refund_db transaction_db notification_db recon_db; do
  echo "$db: unpublished outbox rows = $(q $db 'SELECT count(*) FROM outbox_events WHERE published_at IS NULL')"
done
echo "transaction_db: events recorded twice = $(q transaction_db 'SELECT count(*) FROM (SELECT event_id FROM transaction_events GROUP BY 1 HAVING count(*) > 1) d')"
echo "transaction_db: payments with two transactions = $(q transaction_db "SELECT count(*) FROM (SELECT payment_id FROM transactions WHERE type = 'PAYMENT' GROUP BY 1 HAVING count(*) > 1) d")"
echo "transaction_db: unbalanced ledger postings = $(q transaction_db "SELECT count(*) FROM (SELECT posting_id FROM ledger_entries GROUP BY 1 HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0) d")"
