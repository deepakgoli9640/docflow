#!/bin/bash
# Paste this whole file into Oracle Cloud's "Initialization script" box when creating the instance
# (Create instance -> Show advanced options -> Management -> Paste cloud-init script).
# The new server then deploys HPG by itself on first boot (about 20-30 minutes); no SSH needed for setup.
# Progress and the result:  sudo tail -f /var/log/hpg-setup.log      Site: https://<public-ip-with-dashes>.sslip.io
# Works with any Ubuntu cloud image that runs cloud-init (also AWS / GCP / Azure "user data").
exec >> /var/log/hpg-setup.log 2>&1
echo "=== HPG setup started $(date -u)"
export HOME=/root
# First boot also runs apt (unattended upgrades): wait for its lock instead of failing.
echo 'DPkg::Lock::Timeout "900";' > /etc/apt/apt.conf.d/90hpg-lock-timeout
command -v git >/dev/null || apt-get install -y -qq git
if [ ! -d /opt/hpg/.git ]; then
    git clone -q https://github.com/deepakgoli9640/docflow.git /opt/hpg
fi
if /opt/hpg/scripts/cloud/setup-server.sh; then
    echo "=== HPG setup finished $(date -u)"
else
    echo "=== HPG setup FAILED $(date -u); fix the cause, then run: sudo /opt/hpg/scripts/cloud/setup-server.sh"
fi
