#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ ${SHA:-} =~ ^[0-9a-f]{40}$ ]] || { echo INVALID_SHA >&2; exit 1; }
work=${RUNNER_TEMP:?}/danditon-publish
mkdir -m 700 "$work"
trap 'rm -rf -- "$work"' EXIT
export DOCKER_CONFIG="$work/docker"
mkdir -m 700 "$DOCKER_CONFIG"
printf '%s' "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
image="ghcr.io/danditon/backend:$SHA"
if timeout 60 docker manifest inspect "$image" > "$work/manifest" 2> "$work/error"; then
    echo REUSING_EXISTING_SHA
else
    grep -Eqi 'manifest unknown|no such manifest' "$work/error" || { cat "$work/error" >&2; exit 1; }
    timeout 600 docker build --platform linux/amd64 --build-arg "SOURCE_REVISION=$SHA" -t "$image" .
    timeout 300 docker push "$image"
fi
timeout 180 docker pull "$image"
digest=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image" |
    sed -n 's|^ghcr.io/danditon/backend@||p')
[[ $digest =~ ^sha256:[0-9a-f]{64}$ ]] || { echo INVALID_DIGEST >&2; exit 1; }
printf 'digest=%s\n' "$digest" >> "${GITHUB_OUTPUT:?}"
