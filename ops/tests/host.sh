#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=$(mktemp -d /tmp/danditon-host-qa.XXXXXXXX)
created_sshd=false
backend_pid=''
nginx_pid=''
cleanup() {
    if [[ -n $nginx_pid ]]; then sudo kill "$nginx_pid" >/dev/null 2>&1 || true; wait "$nginx_pid" 2>/dev/null || true; fi
    if [[ -n $backend_pid ]]; then kill "$backend_pid" >/dev/null 2>&1 || true; wait "$backend_pid" 2>/dev/null || true; fi
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
python3 ops/tests/http-body-server.py "$fixture/backend.port" &
backend_pid=$!
for _attempt in {1..50}; do [[ -s $fixture/backend.port ]] && break; sleep 0.1; done
[[ -s $fixture/backend.port ]]
backend_port=$(cat "$fixture/backend.port")
nginx_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
sed -e "s/listen 80 default_server;/listen 127.0.0.1:$nginx_port;/" \
    -e "s|127.0.0.1:8080|127.0.0.1:$backend_port|g" \
    ops/nginx/danditon.conf > "$fixture/server.conf"
printf 'pid %s/nginx.pid;\nerror_log %s/nginx-error.log;\nevents {}\nhttp { include %s/server.conf; }\n' \
    "$fixture" "$fixture" "$fixture" > "$fixture/nginx-runtime"
sudo nginx -t -c "$fixture/nginx-runtime"
sudo nginx -c "$fixture/nginx-runtime" -g 'daemon off;' &
nginx_pid=$!
code=''
for _attempt in {1..50}; do
    code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --data-binary ready "http://127.0.0.1:$nginx_port/upload" || true)
    [[ $code == 204 ]] && break
    sleep 0.1
done
[[ $code == 204 ]]
truncate -s 10M "$fixture/photo.bin"
code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --form "photo=@$fixture/photo.bin;type=image/jpeg" \
    --form 'request={"caption":"boundary"};type=application/json' \
    "http://127.0.0.1:$nginx_port/upload")
[[ $code == 204 ]]
truncate -s 12M "$fixture/photo-too-large.bin"
code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --form "photo=@$fixture/photo-too-large.bin;type=image/jpeg" \
    --form 'request={"caption":"boundary"};type=application/json' \
    "http://127.0.0.1:$nginx_port/upload")
[[ $code == 413 ]]
echo NGINX_MULTIPART_10M_PLUS_OVERHEAD_PASS_AND_OVER_12M_REJECTED
sudo visudo -cf ops/sudoers/danditon-deploy
[[ $(jq -r '."log-driver"' ops/docker/daemon.json) == json-file ]]
[[ $(jq -r '."log-opts"."max-size"' ops/docker/daemon.json) == 10m ]]
[[ $(jq -r '."log-opts"."max-file"' ops/docker/daemon.json) == 3 ]]
cp -r /etc/fail2ban "$fixture/fail2ban"
cp ops/fail2ban/danditon.local "$fixture/fail2ban/jail.d/danditon.local"
sudo fail2ban-client -c "$fixture/fail2ban" -t
echo SSH_NGINX_SUDOERS_DOCKER_LOGS_VALID_AND_MALFORMED_REJECTED
