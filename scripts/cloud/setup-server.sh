#!/usr/bin/env bash
# One-command setup of a public demo server for HPG on a fresh Ubuntu 22.04 / 24.04 machine
# (AWS EC2, Google Cloud, Azure, DigitalOcean, Hetzner, ...). Needs about 4 GB RAM and 20 GB disk.
#
#   curl -fsSL https://raw.githubusercontent.com/deepakgoli9640/docflow/master/scripts/cloud/setup-server.sh | sudo bash
#   # or, from a clone:  sudo ./scripts/cloud/setup-server.sh
#
# Settings (environment variables, all optional):
#   DOMAIN=hpg.example.com   your own domain (point its DNS A record at this server first).
#                            Default: <public-ip>.sslip.io, a free name that resolves to the server's IP.
#   BRANCH=master              git branch to deploy when the script clones the repository
#   REPO_URL=...             repository to clone (default: the GitHub repository)
#   INSTALL_DIR=/opt/hpg     where the repository is cloned
#   SEED_DEMO_DATA=1         create demo hospitals and payments on the first run (0 to skip)
#
# What is public: only ports 80/443, served by Caddy (automatic Let's Encrypt HTTPS), which forwards to
# the dashboard. The dashboard in turn forwards /api, /oauth2 and /checkout to the API gateway. Every
# other port (services, Postgres, Kafka, Mailpit) stays bound to 127.0.0.1.
# Payments use the SIMULATED provider; keep it that way for a public demo.
#
# Safe to run again: it pulls the latest code, rebuilds and restarts; secrets and data are kept.
set -euo pipefail

REPO_URL=${REPO_URL:-https://github.com/deepakgoli9640/docflow.git}
BRANCH=${BRANCH:-master}
INSTALL_DIR=${INSTALL_DIR:-/opt/hpg}
SEED_DEMO_DATA=${SEED_DEMO_DATA:-1}
CADDY_DIR=/opt/hpg-caddy

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "Run as root: sudo $0"
command -v apt-get >/dev/null || fail "This script supports Ubuntu/Debian (apt) only."
export DEBIAN_FRONTEND=noninteractive

# ------------------------------------------------------------------ 1. Packages and Docker
step "Installing packages"
apt-get update -qq
apt-get install -y -qq ca-certificates curl git jq openssl >/dev/null
if ! command -v docker >/dev/null; then
    step "Installing Docker"
    curl -fsSL https://get.docker.com | sh >/dev/null
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker compose version >/dev/null || fail "Docker Compose v2 is missing."

# ------------------------------------------------------------------ 2. Swap (the build + stack need ~4 GB)
mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if (( mem_mb < 6000 )) && ! swapon --show | grep -q .; then
    step "Adding 4 GB of swap (server has ${mem_mb} MB RAM)"
    if fallocate -l 4G /swapfile 2>/dev/null && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile; then
        grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    else
        echo "Could not add swap; continuing (use a server with at least 4 GB RAM)."
    fi
fi

# ------------------------------------------------------------------ 3. Code
script_repo=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." 2>/dev/null && pwd || true)
if [[ -n "$script_repo" && -f "$script_repo/deploy/docker-compose.yml" ]]; then
    APP_DIR=$script_repo
    step "Using the repository at $APP_DIR"
else
    APP_DIR=$INSTALL_DIR
    if [[ -d "$APP_DIR/.git" ]]; then
        step "Updating $APP_DIR (branch $BRANCH)"
        git -C "$APP_DIR" fetch -q origin "$BRANCH"
        git -C "$APP_DIR" checkout -q "$BRANCH"
        git -C "$APP_DIR" pull -q --ff-only origin "$BRANCH"
    else
        step "Cloning $REPO_URL (branch $BRANCH) into $APP_DIR"
        git clone -q -b "$BRANCH" "$REPO_URL" "$APP_DIR"
    fi
fi
cd "$APP_DIR"

# ------------------------------------------------------------------ 4. Public address
if [[ -z "${DOMAIN:-}" ]]; then
    ip=$(curl -fsS --max-time 10 https://api.ipify.org || curl -fsS --max-time 10 https://ifconfig.me || true)
    [[ $ip =~ ^[0-9]+(\.[0-9]+){3}$ ]] || fail "Could not detect the public IP. Set DOMAIN=... and run again."
    DOMAIN="${ip//./-}.sslip.io"
fi
step "Public address: https://$DOMAIN"

# ------------------------------------------------------------------ 5. Secrets
env_file=deploy/env/.env
if [[ ! -f $env_file ]]; then
    step "Generating $env_file with random secrets"
    ./scripts/init-env.sh
fi
set_env() { # set_env KEY VALUE
    if grep -q "^$1=" "$env_file"; then sed -i "s|^$1=.*|$1=$2|" "$env_file"; else echo "$1=$2" >> "$env_file"; fi
}
set_env HPG_CHECKOUT_BASE_URL "https://$DOMAIN/checkout"   # patient payment links point at the public site
grep -q '^HPG_PAYMENT_PROVIDER=simulated' "$env_file" || echo "Note: HPG_PAYMENT_PROVIDER is not 'simulated'."

# ------------------------------------------------------------------ 6. Build
step "Building the services (first run: 10-20 minutes)"
docker run --rm -v "$APP_DIR:/src" -v hpg-maven-cache:/root/.m2 -w /src \
    maven:3.9.9-eclipse-temurin-21 mvn -B -ntp -q -DskipTests package

# ------------------------------------------------------------------ 7. Start the stack
compose=(docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile refund
         --profile transactions --profile notify --profile recon --profile dashboard)
step "Starting the containers and waiting until they are healthy"
if ! "${compose[@]}" up -d --build --wait --wait-timeout 600; then
    "${compose[@]}" ps
    fail "Some containers are not healthy. See: docker compose -f $APP_DIR/deploy/docker-compose.yml logs <service>"
fi

# ------------------------------------------------------------------ 8. HTTPS in front (Caddy)
step "Starting Caddy (HTTPS for $DOMAIN)"
mkdir -p "$CADDY_DIR"
cat > "$CADDY_DIR/Caddyfile" <<EOF
$DOMAIN {
    encode gzip
    reverse_proxy 127.0.0.1:8090
}
EOF
docker pull -q caddy:2.8-alpine >/dev/null
docker rm -f hpg-caddy >/dev/null 2>&1 || true
docker run -d --name hpg-caddy --restart unless-stopped --network host \
    -v "$CADDY_DIR/Caddyfile:/etc/caddy/Caddyfile:ro" -v hpg-caddy-data:/data -v hpg-caddy-config:/config \
    caddy:2.8-alpine >/dev/null
if command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then
    ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
fi

# ------------------------------------------------------------------ 9. Demo data (first run only)
marker=$APP_DIR/deploy/env/.demo-seeded
if [[ $SEED_DEMO_DATA == 1 && ! -f $marker ]]; then
    step "Creating demo data"
    ./scripts/seed-demo-data.sh | tee "$APP_DIR/deploy/env/demo-credentials.txt"
    chmod 600 "$APP_DIR/deploy/env/demo-credentials.txt"
    touch "$marker"
fi

# ------------------------------------------------------------------ Done
ops_secret=$(grep '^HPG_OPS_CLIENT_SECRET=' "$env_file" | cut -d= -f2-)
cat <<EOF

$(printf '\033[1;32m')HPG is running at https://$DOMAIN$(printf '\033[0m')
(the HTTPS certificate is issued on the first visit; if the page does not load, wait a minute)

  Operations sign-in   Client ID: ops-console     Secret: $ops_secret
  Hospital sign-in     see $APP_DIR/deploy/env/demo-credentials.txt, or register a hospital via the API

Open ports 80 and 443 in your cloud provider's firewall / security group if the site does not load.
Patient emails stay private; view them with an SSH tunnel:
  ssh -L 8025:127.0.0.1:8025 <user>@<server>   then open http://localhost:8025

Update to the latest code: run this script again.   Stop: cd $APP_DIR && docker compose -f deploy/docker-compose.yml --profile '*' down && docker rm -f hpg-caddy
EOF
