#!/usr/bin/env bash
# Installs HPG on the current kubectl context as a single-node DEMO (one replica each, simulated provider):
# demo infrastructure, secrets from deploy/env/.env, a fresh JWT signing key, then the Helm chart.
# Needs kubectl, helm, openssl, an ingress-nginx controller for the *.localtest.me hosts, and the images
# (CI pushes them to ghcr.io; for a local build load hpg/*:local into the cluster and override image.*).
set -euo pipefail
cd "$(dirname "$0")/.."
NS=${NS:-hpg}
[[ -f deploy/env/.env ]] || ./scripts/init-env.sh

kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"

# Secret from the same variables Compose uses (only KEY=VALUE lines with a value).
grep -E '^[A-Z_]+=.+' deploy/env/.env > /tmp/hpg-demo.env
kubectl -n "$NS" create secret generic hpg-secrets --from-env-file=/tmp/hpg-demo.env \
  --dry-run=client -o yaml | kubectl apply -f -
rm -f /tmp/hpg-demo.env

# One signing key shared by every merchant-service replica.
if ! kubectl -n "$NS" get secret hpg-jwt >/dev/null 2>&1; then
  tmp=$(mktemp -d)
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/private.pem" 2>/dev/null
  openssl pkey -in "$tmp/private.pem" -pubout -out "$tmp/public.pem"
  kubectl -n "$NS" create secret generic hpg-jwt --from-file="$tmp/private.pem" --from-file="$tmp/public.pem"
  rm -rf "$tmp"
fi

kubectl -n "$NS" create configmap hpg-postgres-init --from-file=deploy/postgres/init/ \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" apply -f deploy/k8s/demo-infra.yaml
kubectl -n "$NS" rollout status statefulset/hpg-postgres --timeout=180s
kubectl -n "$NS" rollout status deployment/hpg-kafka --timeout=180s

helm upgrade --install hpg deploy/helm/hpg -n "$NS" -f deploy/helm/hpg/values-demo.yaml "$@"
kubectl -n "$NS" rollout status deployment -l app.kubernetes.io/part-of=hpg --timeout=600s
helm test hpg -n "$NS"
echo "API: http://api.hpg.localtest.me   Dashboard: http://app.hpg.localtest.me"
