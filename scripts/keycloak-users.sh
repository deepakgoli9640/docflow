#!/usr/bin/env bash
# Creates a dashboard user in the local Keycloak (Compose profile "auth") and prints a generated password.
#   scripts/keycloak-users.sh ops      <username>
#   scripts/keycloak-users.sh hospital <username> <merchantId>
# Hospital users get the MERCHANT role and the "mid" attribute (their hospital); operations users get OPS.
# In production, users come from your directory (LDAP/AD/SAML federation in Keycloak) or an admin creates them.
set -euo pipefail
cd "$(dirname "$0")/.."
KC=${KC:-http://localhost:8090/auth}
kind=${1:?usage: $0 ops|hospital <username> [merchantId]}
user=${2:?username required}
mid=${3:-}
[[ "$kind" == hospital && -z "$mid" ]] && { echo "hospital users need a merchantId" >&2; exit 1; }
admin_pw=$(grep '^KC_BOOTSTRAP_ADMIN_PASSWORD=' deploy/env/.env | cut -d= -f2-)

admin_token=$(curl -sf -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode "password=$admin_pw" \
  "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
api() { curl -sf -H "Authorization: Bearer $admin_token" -H 'Content-Type: application/json' "$@"; }

password="Hpg-$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)"
attrs='{}'
[[ -n "$mid" ]] && attrs=$(jq -n --arg mid "$mid" '{mid: [$mid]}')
body=$(jq -n --arg u "$user" --argjson attrs "$attrs" --arg pw "$password" \
  '{username: $u, email: ($u + "@hpg.example"), emailVerified: true, firstName: $u, lastName: "Demo", enabled: true,
    attributes: $attrs, credentials: [{type: "password", value: $pw, temporary: false}]}')
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $admin_token" -H 'Content-Type: application/json' \
  -X POST -d "$body" "$KC/admin/realms/hpg/users")
[[ "$status" == 201 ]] || { echo "could not create $user (HTTP $status; does it exist already?)" >&2; exit 1; }

id=$(api "$KC/admin/realms/hpg/users?username=$user&exact=true" | jq -r '.[0].id')
role=$([[ "$kind" == ops ]] && echo OPS || echo MERCHANT)
role_json=$(api "$KC/admin/realms/hpg/roles/$role")
api -X POST -d "[$role_json]" "$KC/admin/realms/hpg/users/$id/role-mappings/realm" > /dev/null
echo "$kind user: $user  password: $password  role: $role${mid:+  hospital: $mid}"
