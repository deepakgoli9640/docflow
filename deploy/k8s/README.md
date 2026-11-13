# Running HPG on Kubernetes

```
            Ingress (TLS, cert-manager)
           ┌──────────────┴──────────────┐
   api.<domain>                     app.<domain>
   api-gateway (2-6 pods, HPA)      dashboard (2 pods)
           │  NetworkPolicy: business services accept traffic only from the gateway
   merchant · payment · refund · transaction · notification · reconciliation   (2+ pods each, HPA, PDB)
           │                                │                          │
   PostgreSQL (CloudNativePG,       Kafka (Strimzi, 3 brokers,   Redis (primary + 2 replicas,
   3 instances, sync replica,       RF 3, min ISR 2, KRaft)      gateway only, fails open)
   PITR backups)
```

| Piece | Where | Notes |
|---|---|---|
| Services + dashboard | [`deploy/helm/hpg`](../helm/hpg) | Helm chart; images from CI (`ghcr.io/<owner>/hpg-<name>:<sha>`) |
| PostgreSQL | [`infra/postgres.yaml`](infra/postgres.yaml) | CloudNativePG `Cluster` + one `Database`/role per service |
| Kafka | [`infra/kafka.yaml`](infra/kafka.yaml) | Strimzi `Kafka` + `KafkaNodePool` |
| Redis | [`infra/redis-values.yaml`](infra/redis-values.yaml) | Bitnami chart values |
| Demo (one node) | [`demo-infra.yaml`](demo-infra.yaml), [`../helm/hpg/values-demo.yaml`](../helm/hpg/values-demo.yaml), [`scripts/k8s-demo-up.sh`](../../scripts/k8s-demo-up.sh) | Single instances, simulated provider |

## What makes it highly available

- **Every service runs 2+ replicas** (HPA up to 6, payment-service 10), spread over zones and nodes, with a
  PodDisruptionBudget (`minAvailable: 1`) so node drains and upgrades never take a service down.
- **Rolling updates never reduce capacity** (`maxUnavailable: 0`). Pods get a 10 s `preStop` pause so the
  ingress stops routing to them before Spring's graceful shutdown finishes in-flight requests.
- **Probes**: startup (up to 3 min), liveness (the JVM is not stuck) and readiness (ready for traffic) use
  Spring Boot's probe groups.
- **Replicas coordinate through the database**, so any number can run:
  - only one outbox relay per database publishes, through a Postgres advisory lock (D41);
  - scheduled jobs run on one replica (ShedLock);
  - notification delivery uses `SKIP LOCKED` leases;
  - Kafka consumer groups split partitions;
  - all merchant-service replicas sign tokens with one key, mounted from a Secret.

  `scripts/ha-check.sh` proves this with two replicas of every service. It runs the end-to-end smoke tests
  and checks for duplicates (none).
- **Data**: Postgres has a synchronous replica and automatic failover; Kafka keeps 3 copies of every event,
  and producers wait for 2 (`acks=all`, `min.insync.replicas=2`).

## Security defaults in the chart

Non-root (UID 10001), read-only root filesystem, all Linux capabilities dropped, `RuntimeDefault` seccomp, no
service-account token. NetworkPolicies deny all ingress except:
- ingress controller → gateway and dashboard;
- gateway → services;
- any HPG pod → merchant-service (token keys);
- monitoring → metrics.

Each service receives **only its own secrets** (`services.<name>.secrets`). Secrets are never in values:
create `hpg-secrets` and `hpg-jwt` with External Secrets, Sealed Secrets or your vault. Database connections
use TLS (`sslmode=require`).

## Install

```bash
# Operators (once per cluster): CloudNativePG, Strimzi, cert-manager, ingress-nginx, (Prometheus Operator)
kubectl create namespace hpg
kubectl -n hpg apply -f deploy/k8s/infra/postgres.yaml -f deploy/k8s/infra/kafka.yaml
helm -n hpg install hpg-redis oci://registry-1.docker.io/bitnamicharts/redis -f deploy/k8s/infra/redis-values.yaml
# Secrets hpg-secrets, hpg-jwt, hpg-db-<service>, hpg-redis from your secret store, then:
helm -n hpg install hpg deploy/helm/hpg --set image.tag=<commit-sha> \
  --set ingress.apiHost=api.example.com --set ingress.dashboardHost=app.example.com
helm -n hpg test hpg
```

A single-node demo (kind / k3d / minikube / Docker Desktop with ingress-nginx): `./scripts/k8s-demo-up.sh`.

## What was verified, and what was not

- **Verified**:
  - `helm lint`; the rendered manifests (production and demo values) and the operator resources validated
    against the Kubernetes 1.30 and CRD schemas (`kubeconform`, also in CI);
  - every service image run the way the chart runs it (non-root 10001, read-only root, no capabilities,
    `prod` profile, production JVM flags);
  - two replicas of every service passing the smoke tests (`scripts/ha-check.sh`).
- **Not verified here**: a live Kubernetes install. The build environment cannot run nested containers, so
  kind could not start. Run `scripts/k8s-demo-up.sh` on a laptop cluster first.
