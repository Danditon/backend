#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ $# == 0 || ( $# == 1 && $1 == --secrets-only ) ]] || exit 1
root=()
if [[ $EUID != 0 ]]; then root=(sudo); fi
fixture=$(mktemp -d /tmp/danditon-host-qa.XXXXXXXX)
created_sshd=false
backend_pid=''
nginx_pid=''
cleanup() {
    if [[ -n $nginx_pid ]]; then sudo kill "$nginx_pid" >/dev/null 2>&1 || true; wait "$nginx_pid" 2>/dev/null || true; fi
    if [[ -n $backend_pid ]]; then kill "$backend_pid" >/dev/null 2>&1 || true; wait "$backend_pid" 2>/dev/null || true; fi
    "${root[@]}" rm -rf -- "$fixture"
    if [[ $created_sshd == true ]]; then sudo rmdir /run/sshd; fi
    echo CLEANUP_HOST_FIXTURE
}
trap cleanup EXIT
secret_hash() { "${root[@]}" sha256sum "$1" | cut -d ' ' -f1; }
init_secret() { "${root[@]}" timeout 70 bash ops/host/init-secrets.sh "$1"; }
assert_secret() {
    local directory=$1 key jwt
    [[ $("${root[@]}" stat -c '%u:%g:%a' "$directory/.env") == 0:0:600 ]]
    for key in MYSQL_ROOT_PASSWORD PROD_DB_PASSWORD PROD_DB_USERNAME PROD_DB_URL JWT_SECRET_BASE64; do
        [[ $("${root[@]}" grep -c "^$key=" "$directory/.env") == 1 ]]
    done
    jwt=$("${root[@]}" sed -n 's/^JWT_SECRET_BASE64=//p' "$directory/.env")
    [[ $(printf '%s' "$jwt" | base64 --decode | wc -c) == 32 ]]
    [[ $(printf '%s' "$jwt" | base64 --decode | base64 -w 0) == "$jwt" ]]
    [[ -z $("${root[@]}" find "$directory" -maxdepth 1 -name '.env.*' -print) ]]
    printf 'PASS_SECRET_KEYS_COUNT_5_JWT_BYTES_32_PERMISSIONS_0:0:600 SHA256=%s\n' "$(secret_hash "$directory/.env")"
}
init_secret "$fixture"
assert_secret "$fixture"
before=$(secret_hash "$fixture/.env")
init_secret "$fixture"
[[ $before == "$(secret_hash "$fixture/.env")" ]]
echo PASS_SECRET_NEW_AND_EXISTING_KEY_BYTE_PRESERVATION
for ending in newline no-newline; do
    directory=$fixture/migrate-$ending
    "${root[@]}" install -d -m 700 "$directory"
    "${root[@]}" sed '/^JWT_SECRET_BASE64=/d' "$fixture/.env" | "${root[@]}" tee "$directory/.env" >/dev/null
    "${root[@]}" chmod 600 "$directory/.env"
    if [[ $ending == no-newline ]]; then "${root[@]}" truncate -s -1 "$directory/.env"; fi
    before=$(secret_hash "$directory/.env")
    size=$("${root[@]}" stat -c %s "$directory/.env")
    init_secret "$directory"
    [[ $before == "$("${root[@]}" head -c "$size" "$directory/.env" | sha256sum | cut -d ' ' -f1)" ]]
    assert_secret "$directory"
    after=$(secret_hash "$directory/.env")
    init_secret "$directory"
    [[ $after == "$(secret_hash "$directory/.env")" ]]
    echo "PASS_SECRET_DB_ONLY_MIGRATION_$ending"
done
for invalid in mode owner symlink missing-db duplicate-db empty-db duplicate-jwt malformed-jwt short-jwt empty-jwt unpadded-jwt noncanonical-jwt; do
    directory=$fixture/invalid-$invalid
    "${root[@]}" install -d -m 700 "$directory"
    "${root[@]}" cp -p "$fixture/.env" "$directory/.env"
    case $invalid in
        mode) "${root[@]}" chmod 644 "$directory/.env" ;;
        owner) "${root[@]}" chown 1:1 "$directory/.env" ;;
        symlink)
            "${root[@]}" mv "$directory/.env" "$directory/target"
            "${root[@]}" ln -s target "$directory/.env" ;;
        missing-db) "${root[@]}" sed -i '/^PROD_DB_URL=/d' "$directory/.env" ;;
        duplicate-db) "${root[@]}" sed -i '/^PROD_DB_URL=/p' "$directory/.env" ;;
        empty-db) "${root[@]}" sed -i 's/^PROD_DB_URL=.*/PROD_DB_URL=/' "$directory/.env" ;;
        duplicate-jwt) "${root[@]}" sed -i '/^JWT_SECRET_BASE64=/p' "$directory/.env" ;;
        malformed-jwt) "${root[@]}" sed -i 's/^JWT_SECRET_BASE64=.*/JWT_SECRET_BASE64=bad!/' "$directory/.env" ;;
        short-jwt) "${root[@]}" sed -i 's/^JWT_SECRET_BASE64=.*/JWT_SECRET_BASE64=YWJj/' "$directory/.env" ;;
        empty-jwt) "${root[@]}" sed -i 's/^JWT_SECRET_BASE64=.*/JWT_SECRET_BASE64=/' "$directory/.env" ;;
        unpadded-jwt) "${root[@]}" sed -i '/^JWT_SECRET_BASE64=/s/=$//' "$directory/.env" ;;
        noncanonical-jwt)
            "${root[@]}" sed -i "s|^JWT_SECRET_BASE64=.*|JWT_SECRET_BASE64=$(printf '%043d=' 1)|" "$directory/.env" ;;
    esac
    before=$(secret_hash "$directory/.env")
    if init_secret "$directory"; then exit 1; fi
    [[ $before == "$(secret_hash "$directory/.env")" ]]
    [[ -z $("${root[@]}" find "$directory" -maxdepth 1 -name '.env.*' -print) ]]
    if [[ $invalid == symlink ]]; then "${root[@]}" test -L "$directory/.env"; fi
    printf 'PASS_SECRET_REJECT_%s_UNCHANGED SHA256=%s\n' "$invalid" "$before"
done
directory=$fixture/concurrent
"${root[@]}" install -d -m 700 "$directory"
init_secret "$directory" &
first=$!
init_secret "$directory" &
second=$!
wait "$first"
wait "$second"
assert_secret "$directory"
echo PASS_SECRET_CONCURRENT_INITIALIZATION
directory=$fixture/long-key
"${root[@]}" install -d -m 700 "$directory"
"${root[@]}" cp -p "$fixture/.env" "$directory/.env"
long_key=$(head -c 64 /dev/urandom | base64 -w 0)
printf 'JWT_SECRET_BASE64=%s\n' "$long_key" | "${root[@]}" tee "$fixture/long-key-line" >/dev/null
"${root[@]}" sed -i '/^JWT_SECRET_BASE64=/d' "$directory/.env"
"${root[@]}" sh -c 'cat "$1" >> "$2"' sh "$fixture/long-key-line" "$directory/.env"
before=$(secret_hash "$directory/.env")
init_secret "$directory"
[[ $before == "$(secret_hash "$directory/.env")" ]]
echo PASS_SECRET_EXISTING_64_BYTE_KEY_PRESERVED
for failure in move interrupted interrupted-again; do
    directory=$fixture/failure-$failure
    "${root[@]}" install -d -m 700 "$directory" "$directory/bin"
    "${root[@]}" sed '/^JWT_SECRET_BASE64=/d' "$fixture/.env" | "${root[@]}" tee "$directory/.env" >/dev/null
    "${root[@]}" chmod 600 "$directory/.env"
    {
        printf '#!/usr/bin/env bash\n'
        if [[ $failure != move ]]; then printf 'kill -TERM "$PPID"\n'; fi
        printf 'exit 1\n'
    } | "${root[@]}" tee "$directory/bin/mv" >/dev/null
    "${root[@]}" chmod 700 "$directory/bin/mv"
    before=$(secret_hash "$directory/.env")
    if "${root[@]}" env PATH="$directory/bin:$PATH" timeout 70 bash ops/host/init-secrets.sh "$directory"; then exit 1; fi
    [[ $before == "$(secret_hash "$directory/.env")" ]]
    [[ -z $("${root[@]}" find "$directory" -maxdepth 1 -name '.env.*' -print) ]]
    init_secret "$directory"
    assert_secret "$directory"
    printf 'PASS_SECRET_FAILURE_%s_UNCHANGED_AND_RETRY SHA256=%s\n' "$failure" "$before"
done
if [[ ${1:-} == --secrets-only ]]; then exit 0; fi
if [[ ! -d /run/sshd ]]; then sudo install -d -m 755 /run/sshd; created_sshd=true; fi
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
