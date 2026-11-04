#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=$(mktemp -d /tmp/danditon-compose-qa.XXXXXXXX)
project=danditon-compose-qa-$(basename "$fixture" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
cleanup() {
    local status=$? cleanup_status=0 volumes
    trap - EXIT
    if [[ -f $fixture/env && -f $fixture/override.yaml ]]; then
        docker compose -p "$project" --env-file "$fixture/env" -f compose.prod.yaml -f "$fixture/override.yaml" down >/dev/null || cleanup_status=1
    fi
    if volumes=$(docker volume ls -q --filter "name=^${project}-data$"); then
        if [[ $volumes == "$project-data" ]]; then
            docker volume rm "$project-data" >/dev/null || cleanup_status=1
        fi
    else
        cleanup_status=1
    fi
    rm -rf -- "$fixture" || cleanup_status=1
    if [[ -e $fixture ]]; then cleanup_status=1; fi
    if (( cleanup_status == 0 )); then echo CLEANUP_COMPOSE_FIXTURE; else echo CLEANUP_FAILED >&2; fi
    if (( status != 0 )); then exit "$status"; fi
    exit "$cleanup_status"
}
trap cleanup EXIT
printf 'FIXTURE_DIRECTORY=%s\nFIXTURE_PROJECT=%s\n' "$fixture" "$project"
cat > "$fixture/env" <<'EOF'
APP_IMAGE=danditon-task7-qa:local
PROD_DB_URL=jdbc:mysql://db:3306/danditon
PROD_DB_USERNAME=fixture
PROD_DB_PASSWORD=fixture-app-only
MYSQL_ROOT_PASSWORD=fixture-root-only
EOF
if [[ -n ${QA_APP_IMAGE:-} ]]; then
    printf 'APP_IMAGE=%s\n' "$QA_APP_IMAGE" >> "$fixture/env"
fi
cat > "$fixture/override.yaml" <<EOF
services:
  app:
    ports: !override ["127.0.0.1::8080"]
volumes:
  mysql-data:
    name: $project-data
EOF
compose=(docker compose -p "$project" --env-file "$fixture/env" -f compose.prod.yaml -f "$fixture/override.yaml")
docker compose --env-file "$fixture/env" -f compose.prod.yaml config --quiet
if env -u MYSQL_ROOT_PASSWORD docker compose --env-file /dev/null -f compose.prod.yaml config --quiet >/dev/null 2>&1; then exit 1; fi
echo MISSING_SECRET_REJECTED
docker image inspect "${QA_APP_IMAGE:-danditon-task7-qa:local}" >/dev/null
"${compose[@]}" up -d --wait --wait-timeout 180
db=$("${compose[@]}" ps -q db)
app=$("${compose[@]}" ps -q app)
ready() {
    local attempt address
    address=$("${compose[@]}" port app 8080)
    for ((attempt=0; attempt<60; attempt++)); do
        if curl -fsS --max-time 3 "http://$address/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null; then return 0; fi
        sleep 2
    done
    return 1
}
ready
[[ $(docker inspect -f '{{.HostConfig.Memory}}' "$app") == 805306368 ]]
[[ $(docker inspect -f '{{.HostConfig.Memory}}' "$db") == 805306368 ]]
[[ $(docker inspect -f '{{len .HostConfig.PortBindings}}' "$db") == 0 ]]
[[ $(docker network inspect -f '{{.Internal}}' "${project}_backend") == true ]]
docker exec "$app" sh -c 'test -z "${MYSQL_ROOT_PASSWORD:-}"'
sql() { docker exec "$db" sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" danditon -Nse "$1"' sh "$1"; }
sql 'CREATE TABLE persistence_probe (id INT PRIMARY KEY); INSERT INTO persistence_probe VALUES(7)'
"${compose[@]}" up -d --no-deps --force-recreate app
ready
[[ $(sql 'SELECT id FROM persistence_probe') == 7 ]]
ready
"${compose[@]}" stop db
address=$("${compose[@]}" port app 8080)
[[ $(curl -sS --max-time 40 -o "$fixture/readiness" -w '%{http_code}' "http://$address/actuator/health/readiness") == 503 ]]
jq -e '.status == "DOWN" and (keys == ["status"])' "$fixture/readiness" >/dev/null
"${compose[@]}" up -d --no-deps --wait --wait-timeout 180 db
ready
echo JVM_DATABASE_INCLUSIVE_READINESS_UP_DOWN_UP_PASS
"${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 db
db=$("${compose[@]}" ps -q db)
[[ $(sql 'SELECT id FROM persistence_probe') == 7 ]]
echo COMPOSE_PERSISTENCE_PRIVATE_DB_MEMORY_AND_SECRET_BOUNDARY_PASS
age-keygen -o "$fixture/identity" 2>/dev/null
sudo unshare --mount --propagation private bash ops/tests/backup-live.sh "$project" "$fixture" "$PWD"
# shellcheck disable=SC2016
age -d -i "$fixture/identity" "$fixture/backup.sql.age" | grep 'INSERT INTO `persistence_probe` VALUES (7)' >/dev/null
age -d -i "$fixture/identity" "$fixture/backup.sql.age" | sudo bash ops/backup/restore.sh "danditon-restore-qa-$(date +%s)"
[[ $(sql 'SELECT id FROM persistence_probe') == 7 ]]
echo ENCRYPTED_ROUNDTRIP_AND_PRODUCTION_UNCHANGED_PASS
