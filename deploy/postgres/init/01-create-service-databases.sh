#!/bin/sh
# Runs once, when the Postgres data volume is first created.
#
# One database and one login role per service. Each role owns only its own database, and PUBLIC's
# CONNECT privilege is revoked, so a service physically cannot open another service's database.
# This enforces the "each service owns its data" rule with database permissions, not just convention.
#
# Locally all databases share one Postgres container to save RAM; in production each would be a
# separate instance.
set -eu

create_service_db() {
    db="$1"; role="$2"; password="$3"
    if [ -z "$password" ]; then
        echo "Skipping $db: no password set for $role (see deploy/env/.env)" >&2
        return 0
    fi
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
         -v db="$db" -v role="$role" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
CREATE DATABASE :"db" OWNER :"role";
REVOKE CONNECT, TEMPORARY ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" \
         -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO \"$role\";"
    echo "Created database $db owned by $role"
}

create_service_db merchant_db       merchant_app       "${MERCHANT_DB_PASSWORD:-}"
create_service_db payment_db        payment_app        "${PAYMENT_DB_PASSWORD:-}"
create_service_db refund_db         refund_app         "${REFUND_DB_PASSWORD:-}"
create_service_db transaction_db    transaction_app    "${TRANSACTION_DB_PASSWORD:-}"
create_service_db notification_db   notification_app   "${NOTIFICATION_DB_PASSWORD:-}"
create_service_db recon_db          recon_app          "${RECON_DB_PASSWORD:-}"
