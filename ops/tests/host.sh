#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=$(mktemp -d /tmp/danditon-host-qa.XXXXXXXX)
created_sshd=false
cleanup() {
    sudo rm -rf -- "$fixture"
    if [[ $created_sshd == true ]]; then sudo rmdir /run/sshd; fi
    echo CLEANUP_HOST_FIXTURE
}
trap cleanup EXIT
if [[ ! -d /run/sshd ]]; then sudo install -d -m 755 /run/sshd; created_sshd=true; fi
sudo bash ops/host/init-secrets.sh "$fixture"
before=$(sudo sha256sum "$fixture/.env")
sudo bash ops/host/init-secrets.sh "$fixture"
[[ $before == "$(sudo sha256sum "$fixture/.env")" ]]
[[ $(sudo stat -c '%u:%g:%a' "$fixture/.env") == 0:0:600 ]]
sudo chmod 644 "$fixture/.env"
if sudo bash ops/host/init-secrets.sh "$fixture"; then exit 1; fi
echo SECRET_ONCE_AND_INSECURE_FILE_REJECTED
sudo /usr/sbin/sshd -t -f ops/ssh/00-danditon.conf
sudo /usr/sbin/sshd -T -f ops/ssh/00-danditon.conf -C user=danditon-deploy,host=localhost,addr=127.0.0.1 | tee "$fixture/sshd" >/dev/null
for setting in 'permitrootlogin no' 'passwordauthentication no' 'kbdinteractiveauthentication no' 'disableforwarding yes' 'permittty no'; do
    grep -Fxq "$setting" "$fixture/sshd"
done
printf 'InvalidDirective yes\n' > "$fixture/bad-ssh"
if sudo /usr/sbin/sshd -t -f "$fixture/bad-ssh" >/dev/null 2>&1; then exit 1; fi
printf 'events {}\nhttp { include %s/ops/nginx/danditon.conf; }\n' "$PWD" > "$fixture/nginx"
sudo nginx -t -c "$fixture/nginx"
printf 'invalid_nginx;\n' >> "$fixture/nginx"
if sudo nginx -t -c "$fixture/nginx" >/dev/null 2>&1; then exit 1; fi
sudo visudo -cf ops/sudoers/danditon-deploy
cp -r /etc/fail2ban "$fixture/fail2ban"
cp ops/fail2ban/danditon.local "$fixture/fail2ban/jail.d/danditon.local"
sudo fail2ban-client -c "$fixture/fail2ban" -t
echo SSH_NGINX_SUDOERS_VALID_AND_MALFORMED_REJECTED
