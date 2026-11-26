#!/usr/bin/env bash
# Runs the whole Postman collection with newman against a running stack (Codespaces, Linux, macOS, WSL).
#
#   ./scripts/run-postman.sh                                 # http://localhost:8080
#   BASE_URL=https://my-gateway.example ./scripts/run-postman.sh
#
# The operations secret comes from deploy/env/.env (or OPS_CLIENT_SECRET). Needs Node.js (npx).
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
OPS_CLIENT_SECRET="${OPS_CLIENT_SECRET:-$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env 2>/dev/null | cut -d= -f2- || true)}"
[ -n "$OPS_CLIENT_SECRET" ] || { echo "No HPG_OPS_CLIENT_SECRET in deploy/env/.env; start the stack first (or set OPS_CLIENT_SECRET)." >&2; exit 1; }
command -v npx >/dev/null || { echo "Node.js (npx) is needed: https://nodejs.org" >&2; exit 1; }

# The settlement upload (request H1) reads the sample CSV from this checkout.
collection="$(mktemp --suffix=.json)"
trap 'rm -f "$collection"' EXIT
sed "s#__REPO__#$PWD#g" postman/HPG.postman_collection.json >"$collection"

extra=()
[ -n "${WEBHOOK_URL:-}" ] && extra+=(--env-var "webhookUrl=${WEBHOOK_URL}")
npx --yes newman@6 run "$collection" \
  --env-var "baseUrl=${BASE_URL}" --env-var "opsClientSecret=${OPS_CLIENT_SECRET}" "${extra[@]}" \
  --delay-request 200
