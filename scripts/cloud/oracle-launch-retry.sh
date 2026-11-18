#!/usr/bin/env bash
# Creates the free Oracle ARM server for HPG and keeps retrying while Oracle reports "Out of capacity".
# Run it in Oracle Cloud Shell (the >_ icon at the top right of the Oracle console; already signed in):
#
#   curl -fsSL https://raw.githubusercontent.com/deepakgoli9640/docflow/master/scripts/cloud/oracle-launch-retry.sh | bash
#
# Before running: create a VCN with a public subnet and open TCP 80/443 in its security list
# (Networking -> Virtual cloud networks -> Start VCN Wizard -> "Create VCN with Internet Connectivity").
#
# Settings (environment variables, all optional):
#   OCPUS=4 MEMORY_GB=24   size (Always Free allows 4 OCPUs / 24 GB in total). Smaller, e.g. OCPUS=2 MEMORY_GB=12,
#                          is found more often and still runs the whole stack.
#   COMPARTMENT_ID=...     default: the root compartment (your tenancy)
#   SUBNET_ID=...          default: the first public subnet found
#   RETRY_SECONDS=60       wait between attempts
#
# The server installs HPG by itself on first boot (scripts/cloud/oracle-cloud-init.sh). The SSH key is created in
# Cloud Shell (~/.ssh/hpg), so you can connect from Cloud Shell with:  ssh -i ~/.ssh/hpg ubuntu@<public-ip>
# Keep Cloud Shell open while it retries (it closes after about 20 idle minutes: press a key now and then).
set -uo pipefail

OCPUS=${OCPUS:-4}
MEMORY_GB=${MEMORY_GB:-24}
RETRY_SECONDS=${RETRY_SECONDS:-60}
NAME=hpg
SHAPE=VM.Standard.A1.Flex
CLOUD_INIT_URL=https://raw.githubusercontent.com/deepakgoli9640/docflow/master/scripts/cloud/oracle-cloud-init.sh

say()  { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
command -v oci >/dev/null || fail "The oci command is missing. Run this in Oracle Cloud Shell."
command -v jq >/dev/null || fail "jq is missing."

COMPARTMENT_ID=${COMPARTMENT_ID:-${OCI_TENANCY:-}}
[[ -n $COMPARTMENT_ID ]] || fail "Set COMPARTMENT_ID=<your tenancy OCID> (Profile menu -> Tenancy -> OCID) and run again."

# ---------------------------------------------------------------- Already there?
existing=$(oci compute instance list --compartment-id "$COMPARTMENT_ID" --display-name "$NAME" --all 2>/dev/null \
    | jq -r '[.data[]? | select(."lifecycle-state" != "TERMINATED" and ."lifecycle-state" != "TERMINATING")][0].id // empty')
if [[ -n $existing ]]; then
    fail "An instance named '$NAME' already exists ($existing). Terminate it first, or use it."
fi

# ---------------------------------------------------------------- Inputs
say "Finding a public subnet"
SUBNET_ID=${SUBNET_ID:-$(oci network subnet list --compartment-id "$COMPARTMENT_ID" --all \
    | jq -r '[.data[]? | select(."prohibit-public-ip-on-vnic" == false and ."lifecycle-state" == "AVAILABLE")][0].id // empty')}
[[ -n $SUBNET_ID ]] || fail "No public subnet found. Create one with the VCN Wizard (\"Create VCN with Internet Connectivity\")."
echo "$SUBNET_ID"

say "Finding the latest Ubuntu 24.04 image for $SHAPE"
IMAGE_ID=$(oci compute image list --compartment-id "$COMPARTMENT_ID" --operating-system "Canonical Ubuntu" \
    --operating-system-version "24.04" --shape "$SHAPE" --sort-by TIMECREATED --sort-order DESC --all \
    | jq -r '[.data[]? | select(."display-name" | test("Minimal") | not)][0].id // empty')
[[ -n $IMAGE_ID ]] || fail "No Ubuntu 24.04 ARM image found in this region."
echo "$IMAGE_ID"

mapfile -t ADS < <(oci iam availability-domain list --compartment-id "$COMPARTMENT_ID" | jq -r '.data[].name')
(( ${#ADS[@]} > 0 )) || fail "Could not list availability domains."
echo "Availability domains: ${ADS[*]}"

mkdir -p ~/.ssh
[[ -f ~/.ssh/hpg ]] || ssh-keygen -q -t ed25519 -N '' -f ~/.ssh/hpg -C hpg || fail "Could not create the SSH key ~/.ssh/hpg."
work=$(mktemp -d)
curl -fsSL "$CLOUD_INIT_URL" -o "$work/cloud-init.sh" || fail "Could not download $CLOUD_INIT_URL"

# ---------------------------------------------------------------- Launch, retrying on capacity errors
say "Creating $SHAPE with $OCPUS OCPUs / $MEMORY_GB GB, retrying every ${RETRY_SECONDS}s while Oracle is out of capacity"
attempt=0
while :; do
    for ad in "${ADS[@]}"; do
        attempt=$((attempt + 1))
        out=$(oci compute instance launch --compartment-id "$COMPARTMENT_ID" --availability-domain "$ad" \
            --display-name "$NAME" --shape "$SHAPE" \
            --shape-config "{\"ocpus\": $OCPUS, \"memoryInGBs\": $MEMORY_GB}" \
            --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" --assign-public-ip true \
            --boot-volume-size-in-gbs 100 \
            --ssh-authorized-keys-file ~/.ssh/hpg.pub --user-data-file "$work/cloud-init.sh" 2>&1)
        if instance_id=$(jq -er '.data.id' <<<"$out" 2>/dev/null); then
            break 2
        fi
        if grep -qiE 'out of (host )?capacity|TooManyRequests|InternalError' <<<"$out"; then
            printf '%s  attempt %d (%s): out of capacity\n' "$(date +%H:%M:%S)" "$attempt" "${ad##*:}"
            continue
        fi
        echo "$out" >&2
        fail "Oracle refused the request for another reason (above). Fix it and run again."
    done
    sleep "$RETRY_SECONDS"
done

say "Created $instance_id; waiting until it is running"
oci compute instance get --instance-id "$instance_id" --wait-for-state RUNNING --max-wait-seconds 900 >/dev/null
ip=$(oci compute instance list-vnics --instance-id "$instance_id" | jq -r '.data[0]."public-ip" // empty')
rm -rf "$work"

cat <<EOF

$(printf '\033[1;32m')The server is running: $ip$(printf '\033[0m')
It is now installing HPG by itself (about 25-30 minutes). Then open:

    https://${ip//./-}.sslip.io

Follow the progress (from this Cloud Shell):
    ssh -i ~/.ssh/hpg ubuntu@$ip "sudo tail -f /var/log/hpg-setup.log"
Sign-in details once it has finished:
    ssh -i ~/.ssh/hpg ubuntu@$ip "sudo grep HPG_OPS_CLIENT_SECRET /opt/hpg/deploy/env/.env; sudo cat /opt/hpg/deploy/env/demo-credentials.txt"
EOF
