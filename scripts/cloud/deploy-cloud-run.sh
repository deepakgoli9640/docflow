#!/usr/bin/env bash
# Deploys the whole Healthcare Payment Gateway to Google Cloud Run with one command.
#
#   PROJECT_ID=my-project ./scripts/cloud/deploy-cloud-run.sh            # deploy or update everything
#   PROJECT_ID=my-project ./scripts/cloud/deploy-cloud-run.sh status     # URLs and state
#   PROJECT_ID=my-project ./scripts/cloud/deploy-cloud-run.sh destroy --yes
#
# Run it from Google Cloud Shell (gcloud, openssl and the repository are all there) or any machine with
# the gcloud CLI logged in (gcloud auth login) on a project with billing enabled. Safe to run again: every
# step checks what already exists, so a second run only builds new images and rolls out new revisions.
#
# What it creates (all in REGION):
#   - Artifact Registry repo, images built by Cloud Build (no Java or Docker needed on your machine)
#   - Network: Private Google Access, Cloud NAT, private service access for Cloud SQL
#   - Cloud SQL for PostgreSQL 16 (private IP only), one database and login role per service
#   - One small VM running Kafka + Redis (skipped when you bring your own, see EXTERNAL INFRA below)
#   - Secret Manager secrets (generated passwords, encryption key, JWT signing key, optional provider keys)
#   - 7 Cloud Run services + the dashboard. Only the API gateway and the dashboard are public; the other
#     services accept internal traffic only. Java services keep 1 warm instance with CPU always allocated,
#     because outbox relays, Kafka consumers and scheduled jobs run in the background.
#
# docs/cloud-run.md explains every step, the costs and how to connect real Razorpay, SMTP and SMS.
set -euo pipefail

# ------------------------------------------------------------------ settings (override with env vars)
PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null || true)}"
REGION="${REGION:-asia-south1}"                  # Mumbai; any Cloud Run + Cloud SQL region works
ZONE="${ZONE:-${REGION}-a}"
PREFIX="${PREFIX:-hpg}"
NETWORK="${NETWORK:-default}"
SUBNET="${SUBNET:-default}"
DB_TIER="${DB_TIER:-db-custom-1-3840}"           # 1 vCPU, 3.75 GB, ~100 connections
DB_POOL_SIZE="${DB_POOL_SIZE:-5}"                # per instance and service; 6 services x MAX_INSTANCES x this < max connections
MAX_INSTANCES="${MAX_INSTANCES:-3}"
JAVA_CPU="${JAVA_CPU:-1}"
JAVA_MEMORY="${JAVA_MEMORY:-1Gi}"
INFRA_MACHINE="${INFRA_MACHINE:-e2-medium}"
IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
SKIP_BUILD="${SKIP_BUILD:-false}"                # true: reuse images with IMAGE_TAG already in Artifact Registry

# Business settings
HPG_PAYMENT_PROVIDER="${HPG_PAYMENT_PROVIDER:-simulated}"   # razorpay needs RAZORPAY_KEY_ID/SECRET/WEBHOOK_SECRET
HPG_SMS_PROVIDER="${HPG_SMS_PROVIDER:-log}"                 # msg91 needs MSG91_AUTH_KEY
HPG_EMAIL_FROM="${HPG_EMAIL_FROM:-payments@hpg.example}"
SMTP_HOST="${SMTP_HOST:-}"                                  # e.g. smtp.sendgrid.net (port 25 is blocked on Cloud Run)
SMTP_PORT="${SMTP_PORT:-587}"

# EXTERNAL INFRA (optional): set these to use your own Kafka / Redis instead of the infra VM, e.g. Google
# Managed Service for Apache Kafka, Confluent Cloud, Memorystore. SASL credentials go to Secret Manager.
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-}"
KAFKA_SASL_USERNAME="${KAFKA_SASL_USERNAME:-}"
KAFKA_SASL_PASSWORD="${KAFKA_SASL_PASSWORD:-}"
KAFKA_TOPIC_REPLICAS="${KAFKA_TOPIC_REPLICAS:-}"            # default: 1 on the infra VM, 3 on external Kafka
REDIS_HOST="${REDIS_HOST:-}"
REDIS_PORT="${REDIS_PORT:-6379}"

# Names
AR_REPO="${PREFIX}"
SQL_INSTANCE="${PREFIX}-postgres"
INFRA_VM="${PREFIX}-infra"
INFRA_IP_NAME="${PREFIX}-infra-ip"
RUN_SA_NAME="${PREFIX}-run"
ROUTER="${PREFIX}-router"
NAT="${PREFIX}-nat"
PSA_RANGE="${PREFIX}-psa-range"
FW_RULE="${PREFIX}-allow-infra"

# service name | port | database prefix (or -) | public (yes/no)
SERVICES=(
  "merchant-service|8081|MERCHANT|no"
  "payment-service|8082|PAYMENT|no"
  "refund-service|8083|REFUND|no"
  "transaction-service|8084|TRANSACTION|no"
  "notification-service|8085|NOTIFICATION|no"
  "reconciliation-service|8086|RECON|no"
  "api-gateway|8080|-|yes"
)
DB_ROLES=("MERCHANT|merchant_db|merchant_app" "PAYMENT|payment_db|payment_app" "REFUND|refund_db|refund_app"
          "TRANSACTION|transaction_db|transaction_app" "NOTIFICATION|notification_db|notification_app"
          "RECON|recon_db|recon_app")

# ------------------------------------------------------------------ helpers
log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '\033[33m    ! %s\033[0m\n' "$*"; }
die()  { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
exists() { "$@" >/dev/null 2>&1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

preflight() {
  command -v gcloud >/dev/null || die "gcloud CLI not found. Use Google Cloud Shell, or install https://cloud.google.com/sdk"
  command -v openssl >/dev/null || die "openssl not found"
  [ -n "$PROJECT_ID" ] || die "Set PROJECT_ID (or run: gcloud config set project <id>)"
  [ -f pom.xml ] && [ -d services ] || die "Run this from the repository root (the folder with pom.xml)"
  gcloud projects describe "$PROJECT_ID" >/dev/null || die "Project $PROJECT_ID not found or no access"
  PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
  RUN_SA="${RUN_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
  REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}"
  gcloud config set project "$PROJECT_ID" >/dev/null 2>&1
  # Cloud Run's deterministic URL (https://SERVICE-PROJECT_NUMBER.REGION.run.app), known before deploying,
  # so services that need each other's URLs can be configured in one pass.
  url_of() { printf 'https://%s-%s-%s.%s.run.app' "$PREFIX" "$1" "$PROJECT_NUMBER" "$REGION"; }
  info "Project $PROJECT_ID ($PROJECT_NUMBER), region $REGION, image tag $IMAGE_TAG"
}

# ------------------------------------------------------------------ 1. APIs and registry
enable_apis() {
  log "Enabling Google Cloud APIs (first run takes a minute)"
  gcloud services enable run.googleapis.com sqladmin.googleapis.com compute.googleapis.com \
    artifactregistry.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com \
    servicenetworking.googleapis.com iam.googleapis.com --quiet
}

ensure_registry() {
  log "Artifact Registry repository $AR_REPO"
  exists gcloud artifacts repositories describe "$AR_REPO" --location "$REGION" ||
    gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location "$REGION" \
      --description "Healthcare Payment Gateway images" --quiet
  # Cloud Build's default service account needs to push images and write logs.
  local build_sa="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
  for role in roles/artifactregistry.writer roles/logging.logWriter roles/storage.objectViewer; do
    gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:${build_sa}" --role "$role" \
      --condition=None --quiet >/dev/null 2>&1 || warn "could not grant $role to $build_sa (fine if Cloud Build already works)"
  done
}

# ------------------------------------------------------------------ 2. images
build_images() {
  if [ "$SKIP_BUILD" = "true" ]; then log "Skipping image build (SKIP_BUILD=true), using tag $IMAGE_TAG"; return; fi
  log "Building 8 images with Cloud Build (about 8-12 minutes)"
  local cfg="$WORK/cloudbuild.yaml" svc port rest
  {
    echo "steps:"
    echo "- id: jars"
    echo "  name: maven:3.9.9-eclipse-temurin-21"
    echo "  entrypoint: mvn"
    echo "  args: ['-B', '-ntp', '-DskipTests', 'package']"
    for entry in "${SERVICES[@]}"; do
      IFS='|' read -r svc port rest <<<"$entry"
      echo "- id: image-${svc}"
      echo "  name: gcr.io/cloud-builders/docker"
      echo "  args: ['build', '-t', '${REGISTRY}/${svc}:${IMAGE_TAG}', 'services/${svc}']"
      echo "  waitFor: ['jars']"
    done
    echo "- id: image-dashboard"
    echo "  name: gcr.io/cloud-builders/docker"
    echo "  args: ['build', '-t', '${REGISTRY}/dashboard:${IMAGE_TAG}', 'ui/dashboard']"
    echo "  waitFor: ['-']"
    echo "images:"
    for entry in "${SERVICES[@]}"; do IFS='|' read -r svc rest <<<"$entry"; echo "- '${REGISTRY}/${svc}:${IMAGE_TAG}'"; done
    echo "- '${REGISTRY}/dashboard:${IMAGE_TAG}'"
    echo "timeout: 2400s"
    echo "options:"
    echo "  machineType: E2_HIGHCPU_8"
    echo "  logging: CLOUD_LOGGING_ONLY"
  } >"$cfg"
  gcloud builds submit --region "$REGION" --config "$cfg" . --quiet
}

# ------------------------------------------------------------------ 3. network
ensure_network() {
  log "Network: Private Google Access, Cloud NAT, private service access for Cloud SQL"
  gcloud compute networks subnets update "$SUBNET" --region "$REGION" --enable-private-ip-google-access --quiet >/dev/null
  SUBNET_CIDR="$(gcloud compute networks subnets describe "$SUBNET" --region "$REGION" --format='value(ipCidrRange)')"

  # Cloud NAT: services send all egress through the VPC (to reach internal services, Cloud SQL and the
  # infra VM), so internet calls (Razorpay, SMTP, MSG91) and the VM's image pulls need NAT.
  exists gcloud compute routers describe "$ROUTER" --region "$REGION" ||
    gcloud compute routers create "$ROUTER" --network "$NETWORK" --region "$REGION" --quiet
  exists gcloud compute routers nats describe "$NAT" --router "$ROUTER" --region "$REGION" ||
    gcloud compute routers nats create "$NAT" --router "$ROUTER" --region "$REGION" \
      --auto-allocate-nat-external-ips --nat-all-subnet-ip-ranges --quiet

  # Private service access, so Cloud SQL gets a private IP inside the VPC.
  if ! gcloud services vpc-peerings list --network "$NETWORK" --format='value(peering)' 2>/dev/null | grep -q servicenetworking; then
    exists gcloud compute addresses describe "$PSA_RANGE" --global ||
      gcloud compute addresses create "$PSA_RANGE" --global --purpose VPC_PEERING --prefix-length 20 --network "$NETWORK" --quiet
    gcloud services vpc-peerings connect --service servicenetworking.googleapis.com --ranges "$PSA_RANGE" \
      --network "$NETWORK" --quiet
  else
    info "private service access already set up on $NETWORK"
  fi

  # Only the subnet (Cloud Run egress and the VM itself) may reach Kafka and Redis on the infra VM.
  exists gcloud compute firewall-rules describe "$FW_RULE" ||
    gcloud compute firewall-rules create "$FW_RULE" --network "$NETWORK" --direction INGRESS --action ALLOW \
      --rules tcp:9092,tcp:6379 --source-ranges "$SUBNET_CIDR" --target-tags "${PREFIX}-infra" --quiet
}

# ------------------------------------------------------------------ 4. secrets
secret_exists() { exists gcloud secrets describe "$1"; }
# ensure_secret NAME VALUE: creates it once; never overwrites a generated secret on later runs.
ensure_secret() {
  local name="$1" value="$2"
  secret_exists "$name" && return 0
  printf '%s' "$value" | gcloud secrets create "$name" --replication-policy automatic --data-file=- --quiet >/dev/null
  info "created secret $name"
}
# set_secret NAME VALUE: user-provided values (provider keys); adds a new version when the value changed.
set_secret() {
  local name="$1" value="$2"
  [ -n "$value" ] || return 0
  if secret_exists "$name"; then
    [ "$(gcloud secrets versions access latest --secret "$name" 2>/dev/null || true)" = "$value" ] && return 0
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- --quiet >/dev/null
    info "updated secret $name"
  else
    ensure_secret "$name" "$value"
  fi
}
rand_hex() { openssl rand -hex 24; }

ensure_secrets() {
  log "Secrets in Secret Manager"
  ensure_secret "${PREFIX}-postgres-password" "$(rand_hex)"
  local entry key db role
  for entry in "${DB_ROLES[@]}"; do
    IFS='|' read -r key db role <<<"$entry"
    ensure_secret "${PREFIX}-$(echo "$key" | tr 'A-Z' 'a-z')-db-password" "$(rand_hex)"
  done
  ensure_secret "${PREFIX}-encryption-key" "$(openssl rand -base64 32)"
  ensure_secret "${PREFIX}-ops-client-secret" "$(rand_hex)"
  ensure_secret "${PREFIX}-notification-client-secret" "$(rand_hex)"
  if ! secret_exists "${PREFIX}-jwt-private"; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/private.pem" 2>/dev/null
    openssl pkey -in "$WORK/private.pem" -pubout -out "$WORK/public.pem"
    ensure_secret "${PREFIX}-jwt-private" "$(cat "$WORK/private.pem")"
    ensure_secret "${PREFIX}-jwt-public" "$(cat "$WORK/public.pem")"
  fi
  set_secret "${PREFIX}-razorpay-key-id" "${RAZORPAY_KEY_ID:-}"
  set_secret "${PREFIX}-razorpay-key-secret" "${RAZORPAY_KEY_SECRET:-}"
  set_secret "${PREFIX}-razorpay-webhook-secret" "${RAZORPAY_WEBHOOK_SECRET:-}"
  set_secret "${PREFIX}-smtp-username" "${SMTP_USERNAME:-}"
  set_secret "${PREFIX}-smtp-password" "${SMTP_PASSWORD:-}"
  set_secret "${PREFIX}-msg91-auth-key" "${MSG91_AUTH_KEY:-}"
  set_secret "${PREFIX}-redis-password" "${REDIS_PASSWORD:-}"
  if [ -n "$KAFKA_SASL_USERNAME" ]; then
    # Exact Kafka client keys, passed as SPRING_APPLICATION_JSON (keeps the password out of plain env vars).
    local jaas json
    jaas="org.apache.kafka.common.security.plain.PlainLoginModule required username=\\\"${KAFKA_SASL_USERNAME}\\\" password=\\\"${KAFKA_SASL_PASSWORD}\\\";"
    json="{\"spring\":{\"kafka\":{\"properties\":{\"security.protocol\":\"SASL_SSL\",\"sasl.mechanism\":\"PLAIN\",\"sasl.jaas.config\":\"${jaas}\"}}}}"
    set_secret "${PREFIX}-kafka-client-json" "$json"
  fi

  log "Service account $RUN_SA for the Cloud Run services"
  exists gcloud iam service-accounts describe "$RUN_SA" ||
    gcloud iam service-accounts create "$RUN_SA_NAME" --display-name "HPG Cloud Run services" --quiet
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:${RUN_SA}" \
    --role roles/secretmanager.secretAccessor --condition=None --quiet >/dev/null
}

# ------------------------------------------------------------------ 5. Cloud SQL
ensure_database() {
  log "Cloud SQL for PostgreSQL 16 ($SQL_INSTANCE, private IP only; first creation takes about 10 minutes)"
  if ! exists gcloud sql instances describe "$SQL_INSTANCE"; then
    gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --edition ENTERPRISE \
      --tier "$DB_TIER" --region "$REGION" --network "projects/${PROJECT_ID}/global/networks/${NETWORK}" \
      --no-assign-ip --ssl-mode ENCRYPTED_ONLY --storage-size 10 --storage-auto-increase \
      --backup-start-time 20:30 --availability-type zonal --quiet
  fi
  gcloud sql users set-password postgres --instance "$SQL_INSTANCE" \
    --password "$(gcloud secrets versions access latest --secret "${PREFIX}-postgres-password")" --quiet >/dev/null
  DB_HOST="$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(ipAddresses.filter("type:PRIVATE").extract(ipAddress).flatten())')"
  [ -n "$DB_HOST" ] || DB_HOST="$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(ipAddresses[0].ipAddress)')"
  info "database private IP $DB_HOST"
}

# One database and one login role per service; each role owns only its database (same rules as
# deploy/postgres/init). Runs as a Cloud Run job inside the VPC; safe to run again.
init_databases() {
  log "Creating per-service databases and roles (Cloud Run job ${PREFIX}-db-init)"
  local script="$WORK/db-init.sh" envfile="$WORK/db-init.env.yaml" secrets="POSTGRES_PASSWORD=${PREFIX}-postgres-password:latest"
  local entry key db role
  cat >"$script" <<'INIT'
set -eu
export PGPASSWORD="$POSTGRES_PASSWORD" PGSSLMODE=require
create() {
  psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -U postgres -d postgres -v db="$1" -v role="$2" -v pw="$3" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'role', :'pw') \gexec
SELECT format('GRANT %I TO CURRENT_USER', :'role') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'role') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SELECT format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'role') \gexec
SQL
  echo "ready: $1 owned by $2"
}
INIT
  for entry in "${DB_ROLES[@]}"; do
    IFS='|' read -r key db role <<<"$entry"
    echo "create $db $role \"\$${key}_DB_PASSWORD\"" >>"$script"
    secrets="${secrets},${key}_DB_PASSWORD=${PREFIX}-$(echo "$key" | tr 'A-Z' 'a-z')-db-password:latest"
  done
  {
    echo "DB_HOST: '${DB_HOST}'"
    echo "DB_INIT_SH: |"
    sed 's/^/  /' "$script"
  } >"$envfile"
  gcloud run jobs deploy "${PREFIX}-db-init" --region "$REGION" --image docker.io/library/postgres:16-alpine \
    --service-account "$RUN_SA" --network "$NETWORK" --subnet "$SUBNET" --vpc-egress all-traffic \
    --env-vars-file "$envfile" --set-secrets "$secrets" \
    --command sh --args='-c,printf "%s" "$DB_INIT_SH" > /tmp/init.sh && sh /tmp/init.sh' \
    --task-timeout 600 --max-retries 1 --execute-now --wait --quiet
}

# ------------------------------------------------------------------ 6. Kafka + Redis
ensure_infra() {
  if [ -n "$KAFKA_BOOTSTRAP_SERVERS" ] && [ -n "$REDIS_HOST" ]; then
    log "Using your Kafka ($KAFKA_BOOTSTRAP_SERVERS) and Redis ($REDIS_HOST); no infra VM"
    KAFKA_TOPIC_REPLICAS="${KAFKA_TOPIC_REPLICAS:-3}"
    return
  fi
  [ -z "$KAFKA_BOOTSTRAP_SERVERS" ] && [ -z "$REDIS_HOST" ] ||
    die "Set both KAFKA_BOOTSTRAP_SERVERS and REDIS_HOST to use your own infra, or neither to use the infra VM"
  log "Infra VM $INFRA_VM: Kafka (KRaft, 1 broker) + Redis on Container-Optimized OS"
  exists gcloud compute addresses describe "$INFRA_IP_NAME" --region "$REGION" ||
    gcloud compute addresses create "$INFRA_IP_NAME" --region "$REGION" --subnet "$SUBNET" --quiet
  local ip; ip="$(gcloud compute addresses describe "$INFRA_IP_NAME" --region "$REGION" --format='value(address)')"
  cat >"$WORK/infra-startup.sh" <<STARTUP
#!/bin/bash
# Runs at every boot. Container-Optimized OS drops inbound traffic by default: open Kafka and Redis
# (the VPC firewall rule ${FW_RULE} still limits them to the subnet).
iptables -C INPUT -p tcp --dport 9092 -j ACCEPT 2>/dev/null || iptables -A INPUT -p tcp --dport 9092 -j ACCEPT
iptables -C INPUT -p tcp --dport 6379 -j ACCEPT 2>/dev/null || iptables -A INPUT -p tcp --dport 6379 -j ACCEPT
docker start kafka 2>/dev/null || docker run -d --name kafka --restart always --network host -u 0 \\
  -v kafka-data:/var/lib/kafka/data \\
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \\
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \\
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://${ip}:9092 \\
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \\
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \\
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e KAFKA_LOG_DIRS=/var/lib/kafka/data \\
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \\
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_NUM_PARTITIONS=3 -e KAFKA_LOG_RETENTION_HOURS=168 \\
  -e KAFKA_HEAP_OPTS="-Xmx768m -Xms768m" apache/kafka:3.9.1
docker start redis 2>/dev/null || docker run -d --name redis --restart always --network host \\
  -v redis-data:/data redis:7-alpine redis-server --appendonly yes --protected-mode no
STARTUP
  if ! exists gcloud compute instances describe "$INFRA_VM" --zone "$ZONE"; then
    gcloud compute instances create "$INFRA_VM" --zone "$ZONE" --machine-type "$INFRA_MACHINE" \
      --image-family cos-stable --image-project cos-cloud --boot-disk-size 30GB \
      --network "$NETWORK" --subnet "$SUBNET" --private-network-ip "$ip" --no-address \
      --tags "${PREFIX}-infra" --shielded-secure-boot \
      --metadata-from-file startup-script="$WORK/infra-startup.sh" --quiet
  else
    gcloud compute instances add-metadata "$INFRA_VM" --zone "$ZONE" \
      --metadata-from-file startup-script="$WORK/infra-startup.sh" --quiet >/dev/null
  fi
  KAFKA_BOOTSTRAP_SERVERS="${ip}:9092"
  REDIS_HOST="$ip"
  KAFKA_TOPIC_REPLICAS="${KAFKA_TOPIC_REPLICAS:-1}"
  info "Kafka ${KAFKA_BOOTSTRAP_SERVERS}, Redis ${REDIS_HOST}:6379 (private; first boot pulls images for ~1-2 minutes)"
}

# ------------------------------------------------------------------ 7. Cloud Run services
optional_secret() { secret_exists "$2" && printf ',%s=%s:latest' "$1" "$2" || true; }

deploy_service() {
  local svc="$1" port="$2" dbkey="$3" public="$4"
  local envfile="$WORK/${svc}.env.yaml" secrets="" name="${PREFIX}-${svc}"
  local gateway_url merchant_url
  gateway_url="$(url_of api-gateway)"
  merchant_url="$(url_of merchant-service)"
  {
    echo "SPRING_PROFILES_ACTIVE: prod"
    echo "JAVA_TOOL_OPTIONS: '-XX:+UseG1GC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC'"
    echo "KAFKA_BOOTSTRAP_SERVERS: '${KAFKA_BOOTSTRAP_SERVERS}'"
    echo "HPG_MESSAGING_CONSUMER_TOPIC_REPLICAS: '${KAFKA_TOPIC_REPLICAS}'"
    echo "HPG_TOKEN_ISSUER: '${merchant_url}'"
    echo "HPG_MERCHANT_SERVICE_URI: '${merchant_url}'"
    echo "HPG_TRACE_EXPORT: 'false'"
    echo "HPG_PAYMENT_PROVIDER: '${HPG_PAYMENT_PROVIDER}'"
    echo "HPG_SMS_PROVIDER: '${HPG_SMS_PROVIDER}'"
    echo "HPG_CHECKOUT_BASE_URL: '${gateway_url}/checkout'"
    echo "HPG_ALLOW_INSECURE_WEBHOOKS: 'false'"
    echo "HPG_EMAIL_FROM: '${HPG_EMAIL_FROM}'"
    echo "SMTP_HOST: '${SMTP_HOST:-localhost}'"
    echo "SMTP_PORT: '${SMTP_PORT}'"
    echo "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE: 'true'"
    if [ "$dbkey" != "-" ]; then
      echo "${dbkey}_DB_HOST: '${DB_HOST}'"
      echo "${dbkey}_DB_PORT: '5432'"
      echo "${dbkey}_DB_SSLMODE: 'require'"
      echo "SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: '${DB_POOL_SIZE}'"
      secrets="${dbkey}_DB_PASSWORD=${PREFIX}-$(echo "$dbkey" | tr 'A-Z' 'a-z')-db-password:latest"
    fi
  } >"$envfile"

  case "$svc" in
    merchant-service)
      secrets="${secrets},HPG_ENCRYPTION_KEY=${PREFIX}-encryption-key:latest,HPG_OPS_CLIENT_SECRET=${PREFIX}-ops-client-secret:latest"
      secrets="${secrets},HPG_NOTIFICATION_CLIENT_SECRET=${PREFIX}-notification-client-secret:latest"
      # Every instance signs with the same key (one secret per mount directory, a Cloud Run rule).
      secrets="${secrets},/etc/hpg/jwt-private/private.pem=${PREFIX}-jwt-private:latest,/etc/hpg/jwt-public/public.pem=${PREFIX}-jwt-public:latest"
      { echo "HPG_JWT_PRIVATE_KEY_LOCATION: 'file:/etc/hpg/jwt-private/private.pem'"
        echo "HPG_JWT_PUBLIC_KEY_LOCATION: 'file:/etc/hpg/jwt-public/public.pem'"; } >>"$envfile" ;;
    payment-service)
      secrets="${secrets},HPG_ENCRYPTION_KEY=${PREFIX}-encryption-key:latest"
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_ID "${PREFIX}-razorpay-key-id")"
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_SECRET "${PREFIX}-razorpay-key-secret")"
      secrets="${secrets}$(optional_secret RAZORPAY_WEBHOOK_SECRET "${PREFIX}-razorpay-webhook-secret")" ;;
    refund-service)
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_ID "${PREFIX}-razorpay-key-id")"
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_SECRET "${PREFIX}-razorpay-key-secret")"
      secrets="${secrets}$(optional_secret RAZORPAY_WEBHOOK_SECRET "${PREFIX}-razorpay-webhook-secret")" ;;
    notification-service)
      secrets="${secrets},HPG_ENCRYPTION_KEY=${PREFIX}-encryption-key:latest,HPG_NOTIFICATION_CLIENT_SECRET=${PREFIX}-notification-client-secret:latest"
      secrets="${secrets}$(optional_secret SMTP_USERNAME "${PREFIX}-smtp-username")"
      secrets="${secrets}$(optional_secret SMTP_PASSWORD "${PREFIX}-smtp-password")"
      secrets="${secrets}$(optional_secret MSG91_AUTH_KEY "${PREFIX}-msg91-auth-key")" ;;
    reconciliation-service)
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_ID "${PREFIX}-razorpay-key-id")"
      secrets="${secrets}$(optional_secret RAZORPAY_KEY_SECRET "${PREFIX}-razorpay-key-secret")" ;;
    api-gateway)
      { echo "REDIS_HOST: '${REDIS_HOST}'"
        echo "REDIS_PORT: '${REDIS_PORT}'"
        echo "HPG_CLIENT_IP_TRUSTED_PROXIES: '1'"   # Google's front end appends the caller's IP
        echo "HPG_PAYMENT_SERVICE_URI: '$(url_of payment-service)'"
        echo "HPG_REFUND_SERVICE_URI: '$(url_of refund-service)'"
        echo "HPG_TRANSACTION_SERVICE_URI: '$(url_of transaction-service)'"
        echo "HPG_NOTIFICATION_SERVICE_URI: '$(url_of notification-service)'"
        echo "HPG_RECON_SERVICE_URI: '$(url_of reconciliation-service)'"; } >>"$envfile"
      secrets="${secrets}$(optional_secret REDIS_PASSWORD "${PREFIX}-redis-password")" ;;
  esac
  secrets="${secrets}$(optional_secret SPRING_APPLICATION_JSON "${PREFIX}-kafka-client-json")"
  secrets="${secrets#,}"

  local ingress="internal"; [ "$public" = "yes" ] && ingress="all"
  info "deploying $name (port $port, ingress $ingress)"
  local secret_args=(); [ -n "$secrets" ] && secret_args=(--set-secrets "$secrets")
  gcloud run deploy "$name" --region "$REGION" --image "${REGISTRY}/${svc}:${IMAGE_TAG}" \
    --service-account "$RUN_SA" --port "$port" --cpu "$JAVA_CPU" --memory "$JAVA_MEMORY" \
    --min-instances 1 --max-instances "$MAX_INSTANCES" --no-cpu-throttling --cpu-boost \
    --execution-environment gen2 --timeout 60 --concurrency 80 \
    --network "$NETWORK" --subnet "$SUBNET" --vpc-egress all-traffic \
    --ingress "$ingress" --allow-unauthenticated \
    --env-vars-file "$envfile" "${secret_args[@]}" \
    --labels "app=hpg,component=${svc}" --quiet >/dev/null
}

deploy_dashboard() {
  info "deploying ${PREFIX}-dashboard (public)"
  gcloud run deploy "${PREFIX}-dashboard" --region "$REGION" --image "${REGISTRY}/dashboard:${IMAGE_TAG}" \
    --service-account "$RUN_SA" --port 8090 --cpu 1 --memory 256Mi --min-instances 0 --max-instances 3 \
    --ingress all --allow-unauthenticated \
    --set-env-vars "^@^GATEWAY_URL=$(url_of api-gateway)@GATEWAY_HOST_HEADER=\$proxy_host@NGINX_RESOLVER=169.254.169.254@OIDC_AUTHORITY=@OIDC_UPSTREAM=http://127.0.0.1:9" \
    --labels "app=hpg,component=dashboard" --quiet >/dev/null
}

deploy_services() {
  log "Deploying Cloud Run services (each takes 1-3 minutes)"
  local entry svc port db public
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r svc port db public <<<"$entry"
    deploy_service "$svc" "$port" "$db" "$public"
  done
  deploy_dashboard
}

# ------------------------------------------------------------------ 8. checks and summary
smoke_test() {
  log "Smoke test through the public gateway"
  local gw; gw="$(url_of api-gateway)"
  local code attempt
  for attempt in $(seq 1 12); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "${gw}/oauth2/jwks" || true)"
    [ "$code" = "200" ] && break
    info "gateway not ready yet (attempt ${attempt}/12, HTTP ${code}); waiting 10 s"
    sleep 10
  done
  if [ "$code" = "200" ]; then info "GET ${gw}/oauth2/jwks -> 200 (gateway reaches merchant-service)"
  else warn "GET ${gw}/oauth2/jwks -> ${code}. Check: gcloud run services logs read ${PREFIX}-api-gateway --region ${REGION}"; fi
  code="$(curl -s -o /dev/null -w '%{http_code}' -u "ops-console:$(gcloud secrets versions access latest --secret "${PREFIX}-ops-client-secret")" \
    -d grant_type=client_credentials "${gw}/oauth2/token" || true)"
  [ "$code" = "200" ] && info "operations token -> 200" || warn "operations token -> ${code}"
}

summary() {
  log "Done"
  cat <<EOF
    Dashboard        $(url_of dashboard)
    API gateway      $(url_of api-gateway)
    Checkout links   $(url_of api-gateway)/checkout/...

    Operations sign-in: client id  ops-console
                        secret     gcloud secrets versions access latest --secret ${PREFIX}-ops-client-secret
    Postman: set baseUrl to the API gateway URL and opsClientSecret to the secret above.

    Payment provider: ${HPG_PAYMENT_PROVIDER}   SMS: ${HPG_SMS_PROVIDER}   SMTP: ${SMTP_HOST:-not set (emails are retried, then marked DEAD)}
    Logs:   gcloud run services logs read ${PREFIX}-payment-service --region ${REGION}
    Update: run this script again. Remove everything: $0 destroy --yes
EOF
}

status() {
  preflight
  log "Cloud Run services"
  gcloud run services list --region "$REGION" --filter "metadata.labels.app=hpg" \
    --format 'table(metadata.name, status.conditions[0].status:label=READY, status.latestReadyRevisionName:label=REVISION)'
  log "Database and infra VM"
  gcloud sql instances list --filter "name=${SQL_INSTANCE}" --format 'table(name, state, ipAddresses[0].ipAddress)' || true
  gcloud compute instances list --filter "name=${INFRA_VM}" --format 'table(name, status, networkInterfaces[0].networkIP)' || true
  info "Dashboard $(url_of dashboard)"
  info "Gateway   $(url_of api-gateway)"
}

destroy() {
  [ "${1:-}" = "--yes" ] || die "This deletes the services, the database (all data) and the infra VM. Run: $0 destroy --yes"
  preflight
  log "Deleting Cloud Run services and job"
  local entry svc rest
  for entry in "${SERVICES[@]}"; do IFS='|' read -r svc rest <<<"$entry"
    gcloud run services delete "${PREFIX}-${svc}" --region "$REGION" --quiet 2>/dev/null || true; done
  gcloud run services delete "${PREFIX}-dashboard" --region "$REGION" --quiet 2>/dev/null || true
  gcloud run jobs delete "${PREFIX}-db-init" --region "$REGION" --quiet 2>/dev/null || true
  log "Deleting Cloud SQL instance, infra VM, network pieces"
  gcloud sql instances delete "$SQL_INSTANCE" --quiet 2>/dev/null || true
  gcloud compute instances delete "$INFRA_VM" --zone "$ZONE" --quiet 2>/dev/null || true
  gcloud compute addresses delete "$INFRA_IP_NAME" --region "$REGION" --quiet 2>/dev/null || true
  gcloud compute firewall-rules delete "$FW_RULE" --quiet 2>/dev/null || true
  gcloud compute routers delete "$ROUTER" --region "$REGION" --quiet 2>/dev/null || true
  log "Deleting secrets and images"
  gcloud secrets list --filter "name~^${PREFIX}-" --format 'value(name)' | while read -r s; do
    gcloud secrets delete "$s" --quiet || true; done
  gcloud artifacts repositories delete "$AR_REPO" --location "$REGION" --quiet 2>/dev/null || true
  info "Kept: the private service access peering on ${NETWORK} (other services may use it) and the APIs."
}

main() {
  case "${1:-deploy}" in
    deploy)
      preflight; enable_apis; ensure_registry; ensure_network; ensure_secrets
      build_images; ensure_database; ensure_infra; init_databases; deploy_services; smoke_test; summary ;;
    status)  status ;;
    destroy) shift; destroy "$@" ;;
    *) die "Usage: $0 [deploy|status|destroy --yes]" ;;
  esac
}
# Run main only when executed, not when sourced (tests load the functions).
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
