#!/usr/bin/env bash
# Runs once, when the codespace is created: secrets file, jars and Docker images.
# Starting the stack is start.sh's job (it runs on every start).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> HPG codespace setup (first time only, about 8-12 minutes)"
command -v jq >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq jq >/dev/null; }

[ -f deploy/env/.env ] || ./scripts/init-env.sh

echo "==> Building the services (Maven)"
./mvnw -B -ntp -q -DskipTests package

echo "==> Building the Docker images"
# Docker-in-Docker starts with the container; give it a moment on the very first boot.
for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker compose -f deploy/docker-compose.yml \
  --profile infra --profile core --profile refund --profile transactions --profile notify \
  --profile recon --profile dashboard build

echo "==> Setup done. start.sh now starts HPG."
