#!/usr/bin/env bash
# Runs every time the codespace starts: starts HPG, seeds demo data the first time, starts a webhook receiver.
#   bash .devcontainer/start.sh            start (or restart) HPG
#   bash .devcontainer/start.sh --rebuild  after pulling new code: rebuild the jars and images first
set -euo pipefail
cd "$(dirname "$0")/.."
STATE=.devcontainer/.state
mkdir -p "$STATE"

for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker info >/dev/null 2>&1 || { echo "Docker is not running; open a terminal and run: bash .devcontainer/start.sh"; exit 1; }

build=()
if [ "${1:-}" = "--rebuild" ]; then
  echo "==> Rebuilding the services"
  ./mvnw -B -ntp -q -DskipTests package
  build=(--build)
fi

echo "==> Starting HPG (1-3 minutes)"
docker compose -f deploy/docker-compose.yml \
  --profile infra --profile core --profile refund --profile transactions --profile notify \
  --profile recon --profile dashboard up -d --wait "${build[@]}"

# A receiver for the hospital's webhooks (Postman's default webhookUrl): docker logs -f hook-receiver
docker start hook-receiver >/dev/null 2>&1 ||
  docker run -d --name hook-receiver --restart unless-stopped --network hpg_default \
    mendhak/http-https-echo:31 >/dev/null

if [ ! -f "$STATE/seeded" ]; then
  echo "==> Loading demo data (first start only)"
  ./scripts/seed-demo-data.sh | tee "$STATE/seed-output.txt"
  touch "$STATE/seeded"
fi

cat <<EOF

==> HPG is running.
    Ports tab: 8090 dashboard, 8080 API, 8025 patient emails (Mailpit), 3000 Grafana.
    Demo sign-in details:      cat $STATE/seed-output.txt
    Run every Postman request: ./scripts/run-postman.sh
    Guide:                     docs/codespaces.md
EOF
