#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 1 ]] || { echo 'Usage: sudo bash bootstrap.sh DEPLOY_PUBLIC_KEY_FILE' >&2; exit 1; }
# shellcheck source=/dev/null
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 && $(uname -m) == x86_64 ]] || exit 1
source_dir=$(cd "$(dirname "$0")/.." && pwd)
key=$(realpath "$1")
ssh-keygen -lf "$key" >/dev/null
[[ $(wc -l < "$key") == 1 ]] || exit 1
grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' "$key" || exit 1
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl git jq coreutils util-linux age fail2ban nginx openssh-server sudo
for package in docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
        echo "Conflicting package: $package; reconcile existing Docker installation first" >&2
        exit 1
    fi
done
install -d -m 755 /etc/apt/keyrings
curl --fail --silent --show-error https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod 644 /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
install -d -m 755 /etc/docker
if [[ -e /etc/docker/daemon.json ]]; then
    cmp -s "$source_dir/docker/daemon.json" /etc/docker/daemon.json || {
        echo 'Existing Docker daemon configuration differs; reconcile it before bootstrap' >&2
        exit 1
    }
else
    install -m 644 "$source_dir/docker/daemon.json" /etc/docker/daemon.json
fi
systemctl enable docker
systemctl restart docker
if [[ ! -e /swapfile ]]; then
    install -m 600 /dev/null /swapfile
    fallocate -l 2G /swapfile
    mkswap /swapfile
fi
[[ -f /swapfile && ! -L /swapfile && $(stat -c %s /swapfile) == 2147483648 ]] || exit 1
chmod 600 /swapfile
swapon --show=NAME --noheadings | grep -Fxq /swapfile || swapon /swapfile
grep -Eq '^/swapfile[[:space:]]' /etc/fstab || printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
id danditon-deploy >/dev/null 2>&1 || useradd --create-home --shell /bin/bash danditon-deploy
[[ $(id -nG danditon-deploy) == danditon-deploy ]] || { echo 'Unexpected deploy supplemental groups' >&2; exit 1; }
passwd -l danditon-deploy >/dev/null
install -d -o danditon-deploy -g danditon-deploy -m 700 /home/danditon-deploy/.ssh
{ printf 'restrict '; cat "$key"; } > /home/danditon-deploy/.ssh/authorized_keys
chown danditon-deploy:danditon-deploy /home/danditon-deploy/.ssh/authorized_keys
chmod 600 /home/danditon-deploy/.ssh/authorized_keys
install -d -o danditon-deploy -g danditon-deploy -m 750 /opt/danditon /opt/danditon/releases /opt/danditon/incoming
install -d -o root -g root -m 700 /etc/danditon /var/backups/danditon
bash "$source_dir/host/init-secrets.sh"
install -m 644 "$source_dir/ssh/00-danditon.conf" /etc/ssh/sshd_config.d/00-danditon.conf
install -m 440 "$source_dir/sudoers/danditon-deploy" /etc/sudoers.d/danditon-deploy
install -m 644 "$source_dir/fail2ban/danditon.local" /etc/fail2ban/jail.d/danditon.local
install -m 644 "$source_dir/nginx/danditon.conf" /etc/nginx/sites-available/danditon
rm -f /etc/nginx/sites-enabled/default
ln -sfn /etc/nginx/sites-available/danditon /etc/nginx/sites-enabled/danditon
install -m 755 "$source_dir/backup/backup.sh" /usr/local/sbin/danditon-backup
install -m 755 "$source_dir/backup/restore.sh" /usr/local/sbin/danditon-restore
install -m 644 "$source_dir/systemd/"danditon-backup.* /etc/systemd/system/
bash "$source_dir/host/reload.sh"
systemctl daemon-reload
systemctl enable --now nginx fail2ban danditon-backup.timer
echo 'Bootstrap complete. Install the off-host age PUBLIC recipient in /etc/danditon/backup.recipient before backup.'
