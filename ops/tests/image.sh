#!/usr/bin/env bash
set -euo pipefail
image=${1:-danditon-qa:local}
revision=${2:?expected source revision required}
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
docker image inspect "$image" --format '{{json .}}' > "$work/inspect.json"
jq -e --arg revision "$revision" '
    .Architecture == "amd64" and .Os == "linux" and
    .Config.User == "10001:10001" and
    .Config.Labels["org.opencontainers.image.revision"] == $revision and
    .Config.Labels["org.opencontainers.image.source"] == "https://github.com/Danditon/Backend"
' "$work/inspect.json" >/dev/null
docker run --rm --entrypoint java "$image" -version 2>&1 | grep -E 'version "17\.'
docker image save -o "$work/image.tar" "$image"
tar -xOf "$work/image.tar" manifest.json | jq -r '.[0].Layers[]' > "$work/layers"
while IFS= read -r layer; do
    tar -xOf "$work/image.tar" "$layer" > "$work/layer.tar"
    tar -tf "$work/layer.tar" >> "$work/paths"
done < "$work/layers"
if grep -E '(^|/)(\.env([^/]*|)|\.git|\.codex|\.omo|id_rsa|id_ed25519)(/|$)' "$work/paths"; then
    printf 'SECRET_OR_WORKSPACE_PATH_IN_IMAGE\n' >&2
    exit 1
fi
grep -Fxq app/app.jar "$work/paths"
printf 'PASS: amd64 Java17 non-root revision/source labels; all %s layers lack secret/workspace paths\n' "$(wc -l < "$work/layers")"
docker image inspect "$image" --format '{{.Id}} {{.Architecture}} {{.Config.User}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
