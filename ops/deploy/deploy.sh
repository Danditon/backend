#!/usr/bin/env bash
# Shared release operations are also sourced by the operator-only rollback exercise.
die() { printf '%s\n' "$*" >&2; exit 1; }
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
update_runtime_jwt() {
    local directory=$1 secret_line=$2 runtime_image
    runtime_image=$(cut -d= -f2- "$directory/image.env")
    [[ $runtime_image =~ ^ghcr\.io/danditon/backend:[0-9a-f]{40}@sha256:[0-9a-f]{64}$ ]] || return 1
    printf '%s\n' "$secret_line" | docker_cmd run --rm --interactive --network none \
        --read-only --user 0:0 --cap-drop ALL --security-opt no-new-privileges \
        --mount type=bind,src=/etc/danditon,dst=/runtime \
        --entrypoint /bin/bash "$runtime_image" -c '
set -Eeuo pipefail
umask 077
IFS= read -r secret_line
[[ $secret_line == JWT_SECRET_BASE64=* ]]
secret=${secret_line#JWT_SECRET_BASE64=}
[[ $secret =~ ^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$ ]]
decoded_size=$(printf "%s" "$secret" | base64 --decode 2>/dev/null | wc -c)
[[ $decoded_size -ge 32 ]]
[[ $(printf "%s" "$secret" | base64 --decode | base64 -w 0) == "$secret" ]]
file=/runtime/.env
[[ -f $file && ! -L $file && $(stat -c "%u:%g:%a" "$file") == 0:0:600 ]]
exec 9>/runtime/.secrets.lock
flock -x -w 60 9
for key in MYSQL_ROOT_PASSWORD PROD_DB_PASSWORD PROD_DB_USERNAME PROD_DB_URL; do
    [[ $(grep -c "^$key=" "$file") == 1 ]]
    grep -q "^$key=." "$file"
done
[[ $(grep -c "^JWT_SECRET_BASE64=" "$file" || true) -le 1 ]]
temporary=$(mktemp /runtime/.env.XXXXXX)
trap "rm -f -- \"$temporary\"" EXIT
grep -v "^JWT_SECRET_BASE64=" "$file" > "$temporary"
printf "JWT_SECRET_BASE64=%s\n" "$secret" >> "$temporary"
chmod 600 "$temporary"
chown 0:0 "$temporary"
mv -T "$temporary" "$file"
trap - EXIT
printf "RUNTIME_JWT_UPDATED\n"
'
}
cleanup_old_app_images() {
    local record reference image_id containers listing repository tag removed=0
    local -A keep=()
    for record in "$root/current.json" "$root/previous.json" "$root/transaction.json"; do
        [[ -e $record ]] || continue
        [[ -f $record && ! -L $record ]] || return 1
        reference=$(jq -er '.image | select(test("^ghcr\\.io/danditon/backend:[0-9a-f]{40}@sha256:[0-9a-f]{64}$"))' "$record") || return 1
        image_id=$(docker_cmd image inspect --format '{{.Id}}' "$reference") || return 1
        [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
        keep["$image_id"]=1
    done
    containers=$(docker_cmd ps -aq) || return 1
    if [[ -n $containers ]]; then
        while IFS= read -r container; do
            [[ -n $container ]] || continue
            image_id=$(docker_cmd inspect --format '{{.Image}}' "$container") || return 1
            [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
            keep["$image_id"]=1
        done <<< "$containers"
    fi
    listing=$(docker_cmd image ls --no-trunc --format '{{.Repository}} {{.Tag}} {{.ID}}' ghcr.io/danditon/backend) || return 1
    while read -r repository tag image_id; do
        [[ -n ${repository:-} ]] || continue
        [[ $repository == ghcr.io/danditon/backend && $tag =~ ^[0-9a-f]{40}$ && $image_id =~ ^sha256:[0-9a-f]{64}$ ]] || continue
        [[ -z ${keep[$image_id]+x} ]] || continue
        reference="$repository:$tag"
        docker_cmd image rm "$reference" || return 1
        printf 'IMAGE_CLEANUP_REMOVED %s %s\n' "$reference" "$image_id"
        ((removed += 1))
    done <<< "$listing"
    printf 'IMAGE_CLEANUP_COMPLETE removed=%d\n' "$removed"
}
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

deploy_main() {
    set -Eeuo pipefail
    umask 077
    [[ $# == 1 && $1 =~ ^[0-9a-f]{40}$ ]] || die INVALID_SHA
    IFS= read -r jwt_secret_line || die MISSING_JWT_SECRET
    [[ $jwt_secret_line == JWT_SECRET_BASE64=* ]] || die INVALID_JWT_SECRET
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
    prior=''
    if [[ -f $root/current.json ]]; then
        validate_record "$root/current.json" || die RELEASE_COLLISION
        prior=$(jq -r '.directory' "$root/current.json")
        if [[ $(jq -r '.sha' "$root/current.json") == "$sha" ]]; then
            [[ $(jq -r '.manifest' "$root/current.json") == "$manifest" ]] || die RELEASE_COLLISION
            update_runtime_jwt "$release" "$jwt_secret_line" || die RUNTIME_JWT_UPDATE_FAILED
            unset jwt_secret_line
            compose "$release" up -d --no-deps app
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
    trap recover EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    activated=true
    update_runtime_jwt "$release" "$jwt_secret_line" || die RUNTIME_JWT_UPDATE_FAILED
    unset jwt_secret_line
    if [[ -z $prior ]]; then
        compose "$release" up -d --wait --wait-timeout 120 db
        healthy "$release" || die DATABASE_NOT_HEALTHY
    fi
    compose "$release" up -d --no-deps app
    ready "$release" || die READINESS_FAILED
    if [[ -n $prior ]]; then
        cp "$root/current.json" "$root/previous.json.tmp"
        mv -f "$root/previous.json.tmp" "$root/previous.json"
    fi
    mv -f "$root/candidate.json" "$root/current.json"
    rm -f -- "$root/transaction.json"
    trap - EXIT HUP INT TERM
    if ! cleanup_old_app_images; then
        printf 'IMAGE_CLEANUP_WARNING deployment_succeeded=true\n' >&2
    fi
    printf 'DEPLOYED %s\n' "$sha"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    deploy_main "$@"
fi
