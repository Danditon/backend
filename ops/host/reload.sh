#!/usr/bin/env bash
set -euo pipefail
# Validate every candidate before reloading any running service.
/usr/sbin/sshd -t
settings=$(/usr/sbin/sshd -T -C user=danditon-deploy,host=localhost,addr=127.0.0.1)
for setting in 'permitrootlogin no' 'passwordauthentication no' 'kbdinteractiveauthentication no' 'disableforwarding yes' 'permittty no'; do
    grep -Fxq "$setting" <<< "$settings" || { echo SSH_POLICY_CONFLICT >&2; exit 1; }
done
/usr/sbin/nginx -t
/usr/sbin/visudo -c
fail2ban-client -t
systemctl reload ssh
systemctl reload nginx
systemctl restart fail2ban
