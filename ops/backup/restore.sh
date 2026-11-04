#!/usr/bin/env bash
set -euo pipefail
umask 077
# Receives SQL on stdin only; age identity and decryption remain off-host.
[[ $# == 1 && $1 =~ ^danditon-restore-qa-[a-z0-9]{8,32}$ ]] || { echo UNSAFE_RESTORE_TARGET >&2; exit 1; }
target=$1
[[ $EUID == 0 ]] || exit 1
[[ -z $(docker ps -aq --filter "name=^/${target}$") ]] || { echo TARGET_EXISTS >&2; exit 1; }
image=mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902
secret=$(mktemp)
container=''
cleanup() {
    if [[ -n $container ]]; then docker rm -f "$container" >/dev/null; fi
    rm -f "$secret"
}
trap cleanup EXIT
printf 'MYSQL_ROOT_PASSWORD=%s\nMYSQL_DATABASE=danditon\n' "$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')" > "$secret"
container=$(docker run -d --name "$target" --label danditon.restore-qa=true --network none --memory 768m \
    --tmpfs /var/lib/mysql:rw,size=512m --env-file "$secret" "$image" \
    --innodb-buffer-pool-size=256M --max-connections=50)
rm -f "$secret"
ready=false
for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$target" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -Nse "SELECT 1"' >/dev/null 2>&1; then ready=true; break; fi
    sleep 2
done
[[ $ready == true ]] || { echo RESTORE_DB_UNHEALTHY >&2; exit 1; }
docker exec -i "$target" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot danditon'
docker exec "$target" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"danditon\""'
echo RESTORE_VERIFIED_ISOLATED
