#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
die() { printf '%s\n' "$*" >&2; exit 1; }
[[ ${SHA:-} =~ ^[0-9a-f]{40}$ ]] || die INVALID_SHA
[[ ${IMAGE_DIGEST:-} =~ ^sha256:[0-9a-f]{64}$ ]] || die INVALID_DIGEST
[[ ${EC2_HOST:-} =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || die INVALID_HOST
JWT_SECRET_BASE64=${JWT_SECRET_BASE64:-}
[[ $JWT_SECRET_BASE64 =~ ^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$ ]] || die INVALID_JWT_SECRET
decoded_size=$(printf '%s' "$JWT_SECRET_BASE64" | base64 --decode 2>/dev/null | wc -c) || die INVALID_JWT_SECRET
[[ $decoded_size -ge 32 ]] || die INVALID_JWT_SECRET
[[ $(printf '%s' "$JWT_SECRET_BASE64" | base64 --decode | base64 -w 0) == "$JWT_SECRET_BASE64" ]] || die INVALID_JWT_SECRET
jwt_secret_line=JWT_SECRET_BASE64=$JWT_SECRET_BASE64
unset JWT_SECRET_BASE64
GEMINI_API_KEY=${GEMINI_API_KEY:-}
[[ $GEMINI_API_KEY =~ ^[A-Za-z0-9_-]+$ ]] || die INVALID_GEMINI_API_KEY
gemini_secret_line=GEMINI_API_KEY=$GEMINI_API_KEY
unset GEMINI_API_KEY
work=${RUNNER_TEMP:?}/danditon-deploy
mkdir -m 700 "$work"
cleanup() {
    local code=$?
    trap - EXIT
    if [[ ${incoming_created:-false} == true ]]; then
        timeout 20 ssh "${SSH_OPTS[@]}" "$target" "rm -rf -- '$INCOMING'" || true
    fi
    rm -rf -- "$work"
    exit "$code"
}
trap cleanup EXIT
export DOCKER_CONFIG="$work/docker"
mkdir -m 700 "$DOCKER_CONFIG"
head=$(timeout 30 git ls-remote https://github.com/Danditon/Backend.git refs/heads/develop)
[[ ${head%%$'\t'*} == "$SHA" ]] || die STALE_RELEASE
image="ghcr.io/danditon/backend:$SHA"
timeout 180 docker pull "$image" || die GHCR_NOT_PUBLIC
docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image" |
    grep -Fxq "ghcr.io/danditon/backend@$IMAGE_DIGEST" || die DIGEST_MISMATCH
printf '%s\n' "${SSH_PRIVATE_KEY:?}" > "$work/key"
printf '%s\n' "${SSH_KNOWN_HOSTS:?}" > "$work/known_hosts"
printf '%s\n' "$EC2_HOST" > "$work/host"
chmod 600 "$work/key" "$work/known_hosts" "$work/host"
unset SSH_PRIVATE_KEY SSH_KNOWN_HOSTS
SSH_OPTS=(-i "$work/key" -o IdentitiesOnly=yes -o BatchMode=yes
    -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$work/known_hosts"
    -o GlobalKnownHostsFile=/dev/null -o UpdateHostKeys=no
    -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
target="danditon-deploy@$EC2_HOST"
INCOMING="/opt/danditon/releases/.incoming-$SHA-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
[[ $GITHUB_RUN_ID =~ ^[0-9]+$ && $GITHUB_RUN_ATTEMPT =~ ^[0-9]+$ ]] || die INVALID_RUN
printf '%s\n' "$IMAGE_DIGEST" > "$work/image-digest.txt"
cp compose.prod.yaml ops/deploy/deploy.sh "$work/"
(cd "$work" && sha256sum compose.prod.yaml deploy.sh image-digest.txt > SHA256SUMS)
timeout 30 ssh "${SSH_OPTS[@]}" "$target" "umask 077; mkdir '$INCOMING'"
incoming_created=true
timeout 90 scp "${SSH_OPTS[@]}" "$work/compose.prod.yaml" "$work/deploy.sh" \
    "$work/image-digest.txt" "$work/SHA256SUMS" "$target:$INCOMING/"
timeout 30 ssh "${SSH_OPTS[@]}" "$target" "bash -s -- '$SHA' '$INCOMING'" <<'REMOTE'
set -Eeuo pipefail
sha=$1 incoming=$2
release=/opt/danditon/releases/$sha
exec 9>/opt/danditon/deploy.lock
flock -w 20 9
cd "$incoming"
[[ $(wc -l < SHA256SUMS) == 3 ]]
for file in compose.prod.yaml deploy.sh image-digest.txt; do
    [[ -f $file && ! -L $file ]]
    grep -Eq "^[0-9a-f]{64}  ${file//./\\.}$" SHA256SUMS
done
sha256sum --check --strict SHA256SUMS
if [[ -e $release ]]; then
    cmp SHA256SUMS "$release/SHA256SUMS"
    (cd "$release" && sha256sum --check --strict SHA256SUMS)
    rm -- compose.prod.yaml deploy.sh image-digest.txt SHA256SUMS
    rmdir "$incoming"
else
    mv -T "$incoming" "$release"
fi
REMOTE
incoming_created=false
printf '%s\n' "$jwt_secret_line" "$gemini_secret_line" | timeout 780 ssh "${SSH_OPTS[@]}" "$target" \
    "bash /opt/danditon/releases/$SHA/deploy.sh $SHA"
unset jwt_secret_line gemini_secret_line
timeout 30 ssh "${SSH_OPTS[@]}" "$target" "bash -s -- '$SHA' '$IMAGE_DIGEST'" <<'REMOTE'
set -Eeuo pipefail
release=/opt/danditon/releases/$1
image=ghcr.io/danditon/backend:$1@$2
[[ $(jq -r '.image' /opt/danditon/current.json) == "$image" ]]
id=$(sudo -n /usr/bin/docker compose --project-name danditon --env-file /etc/danditon/.env --env-file "$release/image.env" --file "$release/compose.prod.yaml" ps -q app)
[[ -n $id ]]
actual=$(sudo -n /usr/bin/docker inspect --format '{{.Image}}' "$id")
expected=$(sudo -n /usr/bin/docker image inspect --format '{{.Id}}' "$image")
[[ -n $actual && $actual == "$expected" ]]
REMOTE
curl --fail --silent --show-error --connect-timeout 5 --max-time 20 \
    "http://$EC2_HOST/health" | jq -e '.status == "UP"' >/dev/null
printf 'DEPLOY_VERIFIED %s %s\n' "$SHA" "$IMAGE_DIGEST"
