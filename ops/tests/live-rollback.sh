#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $EUID == 0 ]] || fail ROOT_REQUIRED
[[ $# == 4 && $1 == --current && $2 =~ ^[0-9a-f]{40}$ && $3 == --case && $4 == unhealthy-app ]] || {
    fail 'Usage: sudo bash ops/tests/live-rollback.sh --current SHA --case unhealthy-app'
}
sha=$2
root=/opt/danditon
exec 9>"$root/deploy.lock"
flock -w 60 9 || fail LOCK_TIMEOUT
[[ ! -e $root/transaction.json ]] || fail OPERATOR_RECONCILIATION_REQUIRED
release="$root/releases/$sha"
[[ -f $root/current.json && ! -L $root/current.json ]] || fail MISSING_CURRENT_RECORD
[[ $(jq -er .sha "$root/current.json") == "$sha" ]] || fail CURRENT_SHA_MISMATCH
[[ $(jq -er .directory "$root/current.json") == "$release" ]] || fail CURRENT_DIRECTORY_MISMATCH
for file in deploy.sh compose.prod.yaml image-digest.txt SHA256SUMS image.env; do
    [[ -f $release/$file && ! -L $release/$file ]] || fail INVALID_RELEASE_FILE
done
[[ $(wc -l < "$release/SHA256SUMS") == 3 ]] || fail INVALID_MANIFEST
for file in deploy.sh compose.prod.yaml image-digest.txt; do
    grep -Eq "^[0-9a-f]{64}  ${file//./\\.}$" "$release/SHA256SUMS" || fail INVALID_MANIFEST
done
(cd "$release" && sha256sum --check --strict --status SHA256SUMS) || fail RELEASE_COLLISION
[[ $(sha256sum "$release/SHA256SUMS" | cut -d ' ' -f1) == "$(jq -er .manifest "$root/current.json")" ]] || fail RELEASE_COLLISION
# Source the verified deployed version, so this exercise uses its actual recovery code.
# shellcheck source=ops/deploy/deploy.sh
source "$release/deploy.sh"
validate_record "$root/current.json" || fail INVALID_CURRENT_RECORD
prior=$release
ready "$prior" || fail CURRENT_NOT_HEALTHY
db_before=$(compose "$prior" ps -q db)
app_before=$(compose "$prior" ps -q app)
volume_before=$(docker_cmd volume inspect --format '{{.Name}}:{{.Mountpoint}}' danditon-mysql-data)
[[ -n $db_before && -n $app_before && -n $volume_before ]] || fail CURRENT_RUNTIME_INCOMPLETE
work=$(mktemp -d "$root/.rollback-qa.XXXXXXXX")
cp "$root/current.json" "$work/current-before.json"
cat > "$work/unhealthy.yaml" <<'EOF'
services:
  app:
    entrypoint: ["/bin/sh", "-c", "exit 42"]
    restart: "no"
EOF
cp "$root/current.json" "$root/rollback.json"
transaction_record=$(jq --arg override "$work/unhealthy.yaml" '. + {operator_qa:"unhealthy-app",qa_override:$override}' "$root/current.json")
printf '%s\n' "$transaction_record" > "$root/transaction.json"

# A separate shell runs the production EXIT trap; the parent then checks its outcome.
set +e
(
    set -Eeuo pipefail
    activated=false
    trap recover EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    activated=true
    docker_cmd compose --project-name danditon --env-file /etc/danditon/.env \
        --env-file "$release/image.env" --file "$release/compose.prod.yaml" \
        --file "$work/unhealthy.yaml" up -d --no-deps app
    candidate=$(compose "$release" ps -aq app)
    [[ -n $candidate && $candidate != "$app_before" ]] || die CANDIDATE_NOT_ACTIVATED
    [[ $(docker_cmd inspect --format '{{json .Config.Entrypoint}}' "$candidate") == '["/bin/sh","-c","exit 42"]' ]] || die CANDIDATE_ENTRYPOINT_MISMATCH
    for _attempt in {1..10}; do
        [[ $(docker_cmd inspect --format '{{.State.Running}}' "$candidate") == false ]] && break
        sleep 1
    done
    [[ $(docker_cmd inspect --format '{{.State.ExitCode}}' "$candidate") == 42 ]] || die CANDIDATE_DID_NOT_FAIL
    if ready "$release"; then die CANDIDATE_UNEXPECTEDLY_READY; fi
    printf 'EXPECTED_UNHEALTHY_CANDIDATE\n' >&2
    exit 42
)
code=$?
set -e
if [[ $code == 42 && ! -e $root/transaction.json ]] &&
    cmp -s "$work/current-before.json" "$root/current.json" &&
    validate_record "$root/current.json" && ready "$prior" &&
    [[ $(compose "$prior" ps -q db) == "$db_before" ]] &&
    [[ $(docker_cmd volume inspect --format '{{.Name}}:{{.Mountpoint}}' danditon-mysql-data) == "$volume_before" ]]; then
    rm -- "$work/current-before.json" "$work/unhealthy.yaml"
    rmdir "$work"
    printf 'LIVE_ROLLBACK_VERIFIED current=%s database_unchanged=true\n' "$sha" >&2
    exit 42
fi
printf '%s\n' "$transaction_record" > "$root/transaction.json"
printf 'LIVE_ROLLBACK_FAILED OPERATOR_RECONCILIATION_REQUIRED evidence=%s\n' "$work" >&2
exit 1
