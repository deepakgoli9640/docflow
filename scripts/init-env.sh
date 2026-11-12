#!/usr/bin/env bash
# Creates deploy/env/.env with fresh random secrets. deploy/env/.env is git-ignored.
# Usage: ./scripts/init-env.sh [--force]
set -euo pipefail

cd "$(dirname "$0")/.."
target=deploy/env/.env

if [[ -f "$target" && "${1:-}" != "--force" ]]; then
    echo "$target already exists. Use --force to regenerate."
    echo "Note: an existing Postgres volume keeps the OLD passwords. After regenerating, recreate it with:"
    echo "  docker compose -f deploy/docker-compose.yml --profile '*' down -v"
    exit 1
fi

rand() { openssl rand -hex 24; }

sed -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(rand)|" \
    -e "s|^MERCHANT_DB_PASSWORD=.*|MERCHANT_DB_PASSWORD=$(rand)|" \
    -e "s|^PAYMENT_DB_PASSWORD=.*|PAYMENT_DB_PASSWORD=$(rand)|" \
    -e "s|^REFUND_DB_PASSWORD=.*|REFUND_DB_PASSWORD=$(rand)|" \
    -e "s|^LEDGER_DB_PASSWORD=.*|LEDGER_DB_PASSWORD=$(rand)|" \
    -e "s|^NOTIFICATION_DB_PASSWORD=.*|NOTIFICATION_DB_PASSWORD=$(rand)|" \
    -e "s|^RECON_DB_PASSWORD=.*|RECON_DB_PASSWORD=$(rand)|" \
    -e "s|^HPG_ENCRYPTION_KEY=.*|HPG_ENCRYPTION_KEY=$(openssl rand -base64 32)|" \
    -e "s|^HPG_OPS_CLIENT_SECRET=.*|HPG_OPS_CLIENT_SECRET=$(rand)|" \
    -e "s|^HPG_NOTIFICATION_CLIENT_SECRET=.*|HPG_NOTIFICATION_CLIENT_SECRET=$(rand)|" \
    deploy/env/.env.example > "$target"
chmod 600 "$target"
echo "Wrote $target with random secrets."
echo "Add your Razorpay TEST-mode keys to it when you reach Phase 3."
