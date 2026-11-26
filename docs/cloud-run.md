# Deploying to Google Cloud Run

One script deploys the whole Healthcare Payment Gateway to Google Cloud: `scripts/cloud/deploy-cloud-run.sh`.

```bash
PROJECT_ID=my-gcp-project ./scripts/cloud/deploy-cloud-run.sh
```

At the end it prints the dashboard and API gateway URLs and runs a smoke test. Running it again updates
everything (new images, new revisions); it never recreates what already exists.

| Command | What it does |
|---|---|
| `./scripts/cloud/deploy-cloud-run.sh` | Deploy or update everything |
| `./scripts/cloud/deploy-cloud-run.sh status` | Services, database, VM and URLs |
| `./scripts/cloud/deploy-cloud-run.sh destroy --yes` | Delete everything it created, **including the database** |

## Before you start

1. A Google Cloud project **with billing enabled**, and permission to enable APIs and create resources
   (Owner or Editor on a personal project is enough).
2. Run it from **Google Cloud Shell** (shell.cloud.google.com): gcloud, git and openssl are already there.
   ```bash
   git clone https://github.com/deepakgoli9640/docflow.git && cd docflow
   PROJECT_ID=my-gcp-project ./scripts/cloud/deploy-cloud-run.sh
   ```
   From your own machine you need the gcloud CLI (`gcloud auth login`), bash and openssl. On Windows use
   Cloud Shell or WSL. No Java, Maven or Docker is needed locally: Cloud Build builds the images.
3. The first run takes **25–40 minutes** (Cloud SQL alone is about 10). Later runs take about 15 minutes.

## What it creates

```
                       Internet
                          │
        ┌─────────────────┴──────────────────┐
        ▼                                    ▼
  hpg-dashboard (public)  ──/api──►  hpg-api-gateway (public)
                                              │  HTTPS, internal ingress only
     ┌────────────┬─────────────┬─────────────┼─────────────┬───────────────┬─────────────────┐
     ▼            ▼             ▼             ▼             ▼               ▼                 ▼
  merchant     payment       refund      transaction   notification   reconciliation   (Kafka consumer
  -service     -service      -service    -service      -service       -service          in the gateway)
     └────────────┴─────────────┴──── VPC (Direct VPC egress) ───┴───────────────┴─────────────┘
                     │                          │                                    │
          Cloud SQL PostgreSQL 16        hpg-infra VM (private)                  Cloud NAT
          private IP, SSL only,          Kafka (KRaft) + Redis                   (Razorpay, SMTP,
          one database per service                                                MSG91, image pulls)
```

| Resource | Details |
|---|---|
| Artifact Registry `hpg` | 8 images, tagged with the git commit |
| Cloud Build | Builds the jars and images in the cloud |
| Cloud Run services | `hpg-<service>` for the 7 services and `hpg-dashboard`. Only the **gateway and dashboard are public**; the other services accept **internal traffic only**. |
| Cloud SQL `hpg-postgres` | PostgreSQL 16, private IP only, SSL required, daily backups. One database and one login role per service, each locked to its own database (the same rule as locally). |
| VM `hpg-infra` | Container-Optimized OS running Kafka (one broker) and Redis, no public IP. A firewall rule limits them to the subnet. |
| Secret Manager `hpg-*` | Generated passwords, encryption key, operations client secret, JWT signing key; your provider keys if you pass them |
| Service account `hpg-run` | Used by every service; can read the secrets |
| Network | Private Google Access, Cloud NAT `hpg-nat`, private service access for Cloud SQL |
| Cloud Run job `hpg-db-init` | Creates the per-service databases and roles; safe to run again |

### Why each Java service keeps one warm instance

The services do work **between requests**: the outbox relay sends events to Kafka every 500 ms, Kafka
consumers update ledgers and replicas, and scheduled jobs recover lost payments and expire links. On Cloud
Run that needs `--min-instances 1` and `--no-cpu-throttling` (CPU always allocated). With the defaults,
an idle service would stop sending and receiving events.

### Settings that differ from local Docker

| Setting | Cloud Run value | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | JSON logs, no Swagger UI, JWT key must be configured |
| JWT signing key | From Secret Manager, mounted as files | All instances of merchant-service sign with the same key |
| `*_DB_SSLMODE` | `require` | Cloud SQL only accepts encrypted connections |
| Connection pool | 5 per instance (`DB_POOL_SIZE`) | 6 services × 3 instances × 5 stays under the database's limit |
| `HPG_ALLOW_INSECURE_WEBHOOKS` | `false` | Hospital webhook URLs must be `https://` and public |
| `HPG_CLIENT_IP_TRUSTED_PROXIES` | `1` (gateway) | Google's front end adds the caller's IP to `X-Forwarded-For`; per-IP rate limits use it |
| Dashboard `GATEWAY_HOST_HEADER` | `$proxy_host` | Cloud Run routes by host name, so the dashboard sends the gateway's own host |

## Configuration

Set any of these as environment variables in front of the command.

| Variable | Default | Meaning |
|---|---|---|
| `PROJECT_ID` | current gcloud project | Where to deploy |
| `REGION` | `asia-south1` (Mumbai) | Region for everything |
| `PREFIX` | `hpg` | Prefix for every resource name (deploy twice with different prefixes) |
| `DB_TIER` | `db-custom-1-3840` | Cloud SQL machine (1 vCPU, 3.75 GB) |
| `MAX_INSTANCES` | `3` | Upper limit per service |
| `JAVA_CPU`, `JAVA_MEMORY` | `1`, `1Gi` | Per Java service instance |
| `INFRA_MACHINE` | `e2-medium` | VM for Kafka and Redis |
| `IMAGE_TAG` / `SKIP_BUILD=true` | git commit / `false` | Reuse images already built |
| `HPG_PAYMENT_PROVIDER` | `simulated` | `razorpay` for real payments (see below) |
| `HPG_SMS_PROVIDER` | `log` | `msg91` for real SMS |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | not set, `587` | Email provider. Cloud Run blocks port 25; use 587 (SendGrid, Mailgun, Gmail SMTP relay…) |
| `HPG_EMAIL_FROM` | `payments@hpg.example` | Sender address |

### Real Razorpay, SMS and email

```bash
PROJECT_ID=my-gcp-project HPG_PAYMENT_PROVIDER=razorpay \
RAZORPAY_KEY_ID=rzp_test_xxx RAZORPAY_KEY_SECRET=xxx RAZORPAY_WEBHOOK_SECRET=xxx \
SMTP_HOST=smtp.sendgrid.net SMTP_USERNAME=apikey SMTP_PASSWORD=xxx HPG_EMAIL_FROM=billing@your-domain \
HPG_SMS_PROVIDER=msg91 MSG91_AUTH_KEY=xxx \
./scripts/cloud/deploy-cloud-run.sh
```

Keys go to Secret Manager, never into plain environment variables. Then in the Razorpay dashboard set the
webhook URL to `https://<api-gateway URL>/webhooks/razorpay/payments` (and `/webhooks/razorpay/refunds`).

### Your own Kafka and Redis (production)

The infra VM is one Kafka broker: fine for a demo, a single point of failure for production. Set both
variables to skip the VM and use managed services, for example Google Managed Service for Apache Kafka,
Confluent Cloud and Memorystore for Redis, reachable from the VPC:

```bash
KAFKA_BOOTSTRAP_SERVERS=broker1:9092,broker2:9092 KAFKA_SASL_USERNAME=... KAFKA_SASL_PASSWORD=... \
REDIS_HOST=10.0.0.12 REDIS_PASSWORD=... \
PROJECT_ID=my-gcp-project ./scripts/cloud/deploy-cloud-run.sh
```

With SASL credentials the services connect with `SASL_SSL` + `PLAIN` (passed as `SPRING_APPLICATION_JSON`
from Secret Manager). Topics are created with replication factor 3 (`KAFKA_TOPIC_REPLICAS`).

## After deploying

- **Dashboard:** open the printed URL. Sign in as operations with client id `ops-console` and the secret
  from `gcloud secrets versions access latest --secret hpg-ops-client-secret`.
- **Postman:** in the *HPG local* environment set `baseUrl` to the API gateway URL and `opsClientSecret` to
  that secret, and set `webhookUrl` to an `https://` address (production mode refuses `http://`).
- **Logs:** `gcloud run services logs read hpg-payment-service --region asia-south1`, or Logs Explorer.
  Logs are JSON (ECS) with trace and correlation ids.
- **Update:** pull the latest code and run the script again.

## Cost

Roughly, with the defaults and very little traffic (check with the
[Google Cloud pricing calculator](https://cloud.google.com/products/calculator) for your region):

| Item | Why it costs | Rough monthly |
|---|---|---|
| 7 Java services, 1 warm instance each, CPU always allocated | Background work needs it (see above) | The largest share: about USD 300–400 |
| Cloud SQL `db-custom-1-3840` | Always on | about USD 50 |
| `hpg-infra` VM (`e2-medium`) | Kafka + Redis | about USD 25 |
| Cloud NAT, Secret Manager, Artifact Registry, Cloud Build, logs | Usage-based | about USD 10–40 |

To save money while learning: deploy, use it, then `destroy --yes`. Or lower `JAVA_MEMORY` and
`MAX_INSTANCES`. Without warm instances events would stall, so `--min-instances 0` isn't an option.

## Troubleshooting

| Problem | Fix |
|---|---|
| `--allow-unauthenticated` refused (organisation policy "Domain restricted sharing") | Use a personal project, or ask your admin to allow `allUsers` for Cloud Run in this project |
| Cloud Build cannot push images | The script grants Cloud Build's service account Artifact Registry access; if your organisation blocks that, grant `roles/artifactregistry.writer` to `<project-number>-compute@developer.gserviceaccount.com` |
| A service shows *not ready* | `gcloud run services logs read hpg-<service> --region <region>`. A database or Kafka connection error usually means the infra VM is still booting; wait 2 minutes and run the script again |
| `hpg-db-init` job failed | `gcloud run jobs executions list --job hpg-db-init --region <region>` and read its logs; usually Cloud SQL was still starting |
| Payments say `MERCHANT_NOT_ACTIVE` right after activation | Normal for a second or two while the activation reaches payment-service through Kafka |

## What was tested, and what wasn't

Tested here, without a Google Cloud account:
- **Script:** bash syntax and shellcheck pass.
- **Generated configuration:** produced with `gcloud` stubbed out, and checked.
- **Database setup:** the init job's script ran against PostgreSQL 16 as a non-superuser admin with only
  CREATEROLE and CREATEDB, as Cloud SQL's `postgres` user is. It is safe to run twice, and each service's
  role can create tables in its own database but can't connect to the others.
- **Services:** all 7 started with exactly the generated Cloud Run settings (`prod` profile, SSL-only
  database, JWT key mounted from files). The full Postman collection passed through that gateway: 92
  requests, 128 checks.
- **Dashboard:** its nginx configuration renders and validates with the Cloud Run settings, and still
  works unchanged locally.
- **Gateway rate limits:** the client-IP handling behind a proxy is unit-tested, and was checked by hand
  (forged `X-Forwarded-For` entries are ignored).

Not tested: the actual `gcloud` calls against a real project (Cloud SQL creation, VPC peering, Direct VPC
egress, Cloud Run internal ingress, Cloud Build). Report any step that fails with its error message.

## Known limitations

- **Kafka has no high availability:** the infra VM is one broker and one Redis. Use managed Kafka and
  Memorystore for production (see above).
- **Single sign-on is off:** Keycloak isn't deployed, so sign in with API keys and the operations client.
- **No Grafana, Prometheus or Jaeger:** use Cloud Logging and Cloud Monitoring, or deploy the
  observability stack separately.
- **Dashboard users share one rate-limit bucket:** token requests made through the dashboard are counted
  per dashboard instance, not per end user (direct API clients are limited per IP).
