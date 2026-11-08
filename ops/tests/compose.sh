#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=$(mktemp -d /tmp/danditon-compose-qa.XXXXXXXX)
project=danditon-compose-qa-$(basename "$fixture" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
db_volume=$project-db-data
uploads_volume=$project-uploads-data
cleanup() {
    local status=$? cleanup_status=0 volume
    trap - EXIT
    if [[ -f $fixture/env && -f $fixture/override.yaml ]]; then
        docker compose -p "$project" --env-file "$fixture/env" -f compose.prod.yaml -f "$fixture/override.yaml" down >/dev/null || cleanup_status=1
    fi
    for volume in "$db_volume" "$uploads_volume"; do
        if docker volume inspect "$volume" >/dev/null 2>&1; then
            docker volume rm "$volume" >/dev/null || cleanup_status=1
        fi
    done
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
JWT_SECRET_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
GEMINI_API_KEY=fixture-gemini-key
DANDITON_STORAGE_BASE_URL=https://api.example.test
EMBED_HOST=http://embed.example.test:8000
EMBEDDING_SIMILARITY_THRESHOLD=0.75
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
    name: $db_volume
  uploads-data:
    name: $uploads_volume
EOF
compose=(docker compose -p "$project" --env-file "$fixture/env" -f compose.prod.yaml -f "$fixture/override.yaml")
docker compose --env-file "$fixture/env" -f compose.prod.yaml config --quiet
docker compose --env-file "$fixture/env" -f compose.prod.yaml config --format json |
    jq -e '.services.app.environment.JWT_SECRET_BASE64 != null and
        .services.app.environment.GEMINI_API_KEY != null and
        .services.app.environment.DANDITON_STORAGE_BASE_URL != null and
        .services.app.environment.EMBED_HOST != null and
        .services.app.environment.EMBEDDING_SIMILARITY_THRESHOLD != null and
        (.services.db.environment | has("JWT_SECRET_BASE64") | not) and
        (.services.db.environment | has("GEMINI_API_KEY") | not) and
        (.services.db.environment | has("DANDITON_STORAGE_BASE_URL") | not) and
        (.services.db.environment | has("EMBED_HOST") | not) and
        (.services.db.environment | has("EMBEDDING_SIMILARITY_THRESHOLD") | not)' >/dev/null
echo APP_SECRETS_CONFIG_PASS
sed '/^MYSQL_ROOT_PASSWORD=/d' "$fixture/env" > "$fixture/missing-root-env"
if env -u MYSQL_ROOT_PASSWORD docker compose --env-file "$fixture/missing-root-env" -f compose.prod.yaml config --quiet >"$fixture/missing-root-error" 2>&1; then exit 1; fi
grep -q MYSQL_ROOT_PASSWORD "$fixture/missing-root-error"
echo MISSING_SECRET_REJECTED
sed '/^JWT_SECRET_BASE64=/d' "$fixture/env" > "$fixture/missing-jwt-env"
if env -u JWT_SECRET_BASE64 docker compose --env-file "$fixture/missing-jwt-env" -f compose.prod.yaml config --quiet >"$fixture/missing-jwt-error" 2>&1; then exit 1; fi
grep -q JWT_SECRET_BASE64 "$fixture/missing-jwt-error"
echo MISSING_JWT_SECRET_REJECTED
sed '/^GEMINI_API_KEY=/d' "$fixture/env" > "$fixture/missing-gemini-env"
if env -u GEMINI_API_KEY docker compose --env-file "$fixture/missing-gemini-env" -f compose.prod.yaml config --quiet >"$fixture/missing-gemini-error" 2>&1; then exit 1; fi
grep -q GEMINI_API_KEY "$fixture/missing-gemini-error"
echo MISSING_GEMINI_API_KEY_REJECTED
for variable in DANDITON_STORAGE_BASE_URL EMBED_HOST EMBEDDING_SIMILARITY_THRESHOLD; do
    sed "/^$variable=/d" "$fixture/env" > "$fixture/missing-runtime-env"
    if env -u "$variable" docker compose --env-file "$fixture/missing-runtime-env" -f compose.prod.yaml config --quiet >"$fixture/missing-runtime-error" 2>&1; then exit 1; fi
    grep -q "$variable" "$fixture/missing-runtime-error"
done
echo MISSING_RUNTIME_CONFIG_REJECTED
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
docker exec "$app" sh -c 'test -n "${JWT_SECRET_BASE64:-}"'
docker exec "$app" sh -c 'test -n "${GEMINI_API_KEY:-}"'
docker exec "$app" sh -c 'test -n "${DANDITON_STORAGE_BASE_URL:-}"'
docker exec "$app" sh -c 'test -n "${EMBED_HOST:-}"'
docker exec "$app" sh -c 'test -n "${EMBEDDING_SIMILARITY_THRESHOLD:-}"'
docker exec "$db" sh -c 'test -z "${JWT_SECRET_BASE64:-}"'
docker exec "$db" sh -c 'test -z "${GEMINI_API_KEY:-}"'
docker exec "$db" sh -c 'test -z "${DANDITON_STORAGE_BASE_URL:-}"'
docker exec "$db" sh -c 'test -z "${EMBED_HOST:-}"'
docker exec "$db" sh -c 'test -z "${EMBEDDING_SIMILARITY_THRESHOLD:-}"'
echo APP_SECRETS_RUNTIME_PASS
[[ $(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/app/uploads"}}{{.Name}}{{end}}{{end}}' "$app") == "$uploads_volume" ]]
docker exec "$app" sh -c '
    test "$(id -u):$(id -g)" = "10001:10001"
    test "$(stat -c %u:%g /app/uploads)" = "10001:10001"
    printf persistent-photo > /app/uploads/.compose-persistence-probe
'
sql() { docker exec "$db" sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" danditon -Nse "$1"' sh "$1"; }
sql 'CREATE TABLE persistence_probe (id INT PRIMARY KEY); INSERT INTO persistence_probe VALUES(7)'
"${compose[@]}" up -d --no-deps --force-recreate app
app=$("${compose[@]}" ps -q app)
ready
docker exec "$app" grep -Fxq persistent-photo /app/uploads/.compose-persistence-probe
echo UPLOADS_UID_10001_WRITE_AND_RECREATE_PERSISTENCE_PASS
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
