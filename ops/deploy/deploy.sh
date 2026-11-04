#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }
[[ $# == 1 && $1 =~ ^[0-9a-f]{40}$ ]] || die INVALID_SHA
sha=$1
root=/opt/danditon
release="$root/releases/$sha"
[[ $(realpath -- "${BASH_SOURCE[0]}") == "$release/deploy.sh" ]] || die RELEASE_PATH_MISMATCH
cd -- "$release"
for file in deploy.sh compose.prod.yaml image-digest.txt SHA256SUMS; do
    [[ -f $file && ! -L $file ]] || die "MISSING_FILE $file"
done
[[ $(wc -l < SHA256SUMS) == 3 ]] || die INVALID_MANIFEST
for file in deploy.sh compose.prod.yaml image-digest.txt; do
    grep -Eq "^[0-9a-f]{64}  ${file//./\\.}$" SHA256SUMS || die INVALID_MANIFEST
done
sha256sum --check --strict --status SHA256SUMS || die RELEASE_COLLISION
digest=$(cat image-digest.txt)
[[ $digest =~ ^sha256:[0-9a-f]{64}$ ]] || die INVALID_DIGEST
manifest=$(sha256sum SHA256SUMS | cut -d ' ' -f1)
image="ghcr.io/danditon/backend:$sha@$digest"

exec 9>"$root/deploy.lock"
flock -w 60 9 || die LOCK_TIMEOUT
sha256sum --check --strict --status SHA256SUMS || die RELEASE_COLLISION
[[ $(sha256sum SHA256SUMS | cut -d ' ' -f1) == "$manifest" ]] || die RELEASE_COLLISION
[[ ! -e $root/transaction.json ]] || die OPERATOR_RECONCILIATION_REQUIRED
remote=$(timeout 30 git ls-remote https://github.com/Danditon/Backend.git refs/heads/develop)
[[ ${remote%%$'\t'*} == "$sha" ]] || die STALE_RELEASE
docker_cmd() { timeout 180 sudo -n /usr/bin/docker "$@"; }
compose() {
    local directory=$1
    shift
    docker_cmd compose --project-name danditon --env-file /etc/danditon/.env \
        --env-file "$directory/image.env" --file "$directory/compose.prod.yaml" "$@"
}
healthy() {
    local directory=$1 id status
    id=$(compose "$directory" ps -q db) || return 1
    [[ -n $id ]] || return 1
    status=$(docker_cmd inspect --format '{{.State.Health.Status}}' "$id") || return 1
    [[ $status == healthy ]]
}
ready() {
    local directory=$1 attempt id actual expected
    for ((attempt=0; attempt<30; attempt++)); do
        if healthy "$directory" && curl --silent --fail --max-time 3 \
            http://127.0.0.1:8080/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null; then
            id=$(compose "$directory" ps -q app) || return 1
            [[ -n $id ]] || return 1
            actual=$(docker_cmd inspect --format '{{.Image}}' "$id") || return 1
            expected=$(docker_cmd image inspect --format '{{.Id}}' "$(cut -d= -f2- "$directory/image.env")") || return 1
            [[ -n $actual && $actual == "$expected" ]] && return 0
        fi
        sleep 2
    done
    return 1
}
validate_record() {
    local record=$1 old_sha directory
    old_sha=$(jq -er '.sha | select(test("^[0-9a-f]{40}$"))' "$record") || return 1
    directory="$root/releases/$old_sha"
    [[ $(jq -r '.directory' "$record") == "$directory" ]] || return 1
    [[ $(sha256sum "$directory/SHA256SUMS" | cut -d ' ' -f1) == "$(jq -r '.manifest' "$record")" ]] || return 1
    (cd "$directory" && sha256sum --check --strict --status SHA256SUMS) || return 1
    [[ $(cat "$directory/image.env") == "APP_IMAGE=$(jq -r '.image' "$record")" ]]
}
prior=''
if [[ -f $root/current.json ]]; then
    validate_record "$root/current.json" || die RELEASE_COLLISION
    prior=$(jq -r '.directory' "$root/current.json")
    if [[ $(jq -r '.sha' "$root/current.json") == "$sha" ]]; then
        [[ $(jq -r '.manifest' "$root/current.json") == "$manifest" ]] || die RELEASE_COLLISION
        ready "$release" || die CURRENT_NOT_HEALTHY
        printf 'ALREADY_CURRENT\n'
        exit 0
    fi
    healthy "$prior" || die DATABASE_NOT_HEALTHY
else
    [[ -z $(docker_cmd ps -aq --filter label=com.docker.compose.project=danditon) ]] || die OPERATOR_RECONCILIATION_REQUIRED
    if docker_cmd volume inspect danditon-mysql-data >/dev/null 2>&1; then
        die OPERATOR_RECONCILIATION_REQUIRED
    fi
fi
docker_cmd pull "ghcr.io/danditon/backend:$sha"
docker_cmd image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' \
    "ghcr.io/danditon/backend:$sha" | grep -Fxq "ghcr.io/danditon/backend@$digest" || die DIGEST_MISMATCH
if [[ -e image.env ]]; then
    [[ $(cat image.env) == "APP_IMAGE=$image" ]] || die RELEASE_COLLISION
else
    printf 'APP_IMAGE=%s\n' "$image" > image.env
fi
jq -n --arg sha "$sha" --arg image "$image" --arg directory "$release" \
    --arg manifest "$manifest" --arg env /etc/danditon/.env \
    --arg image_env "$release/image.env" \
    '{sha:$sha,image:$image,directory:$directory,manifest:$manifest,env:$env,image_env:$image_env}' > "$root/candidate.json"
transaction_record=$(cat "$root/candidate.json")
if [[ -n $prior ]]; then
    cp "$root/current.json" "$root/rollback.json"
fi
cp "$root/candidate.json" "$root/transaction.json"
activated=false
recover() {
    local code=$?
    trap - EXIT
    trap '' HUP INT TERM
    if [[ $activated == true ]]; then
        if ! printf '%s\n' "$transaction_record" > "$root/transaction.json"; then
            printf 'ROLLBACK_FAILED OPERATOR_RECONCILIATION_REQUIRED\n' >&2
            exit 1
        fi
        if [[ -n $prior ]]; then
            if compose "$prior" up -d --no-deps app && ready "$prior" &&
                cp "$root/rollback.json" "$root/current.json.tmp" &&
                mv -f "$root/current.json.tmp" "$root/current.json" &&
                cmp -s "$root/rollback.json" "$root/current.json" &&
                validate_record "$root/current.json" &&
                [[ $(jq -r '.directory' "$root/current.json") == "$prior" ]] &&
                rm -f -- "$root/transaction.json"; then
                printf 'ROLLED_BACK\n' >&2
            else
                printf 'ROLLBACK_FAILED OPERATOR_RECONCILIATION_REQUIRED\n' >&2
            fi
        else
            compose "$release" stop --timeout 20 app || true
            rm -f -- "$root/current.json" || printf 'METADATA_RECOVERY_FAILED\n' >&2
            printf 'FIRST_RELEASE_FAILED OPERATOR_RECONCILIATION_REQUIRED\n' >&2
        fi
    fi
    (( code != 0 )) || code=1
    exit "$code"
}
trap recover EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ -z $prior ]]; then
    compose "$release" up -d --wait --wait-timeout 120 db
    healthy "$release" || die DATABASE_NOT_HEALTHY
fi
activated=true
compose "$release" up -d --no-deps app
ready "$release" || die READINESS_FAILED
if [[ -n $prior ]]; then
    cp "$root/current.json" "$root/previous.json.tmp"
    mv -f "$root/previous.json.tmp" "$root/previous.json"
fi
mv -f "$root/candidate.json" "$root/current.json"
rm -f -- "$root/transaction.json"
trap - EXIT HUP INT TERM
printf 'DEPLOYED %s\n' "$sha"
