#!/usr/bin/env bash
set -euo pipefail
# Given a registry CLI fixture that distinguishes absence from transport/auth failure.
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin" "$work/runner"
export FIXTURE="$work" RUNNER_TEMP="$work/runner" GITHUB_OUTPUT="$work/output"
export GITHUB_TOKEN=fixture-token GITHUB_ACTOR=fixture-user
export SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
cat > "$work/bin/docker" <<'EOF'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$FIXTURE/calls"
case "$1" in
    login) [[ $(cat) == fixture-token ]]; printf '{"auths":{}}' > "$DOCKER_CONFIG/config.json" ;;
    manifest)
        case "$SCENARIO" in
            existing) echo '{}' ;;
            missing) echo 'manifest unknown' >&2; exit 1 ;;
            denied) echo 'denied' >&2; exit 1 ;;
        esac ;;
    build|push|pull) exit 0 ;;
    image) echo ghcr.io/danditon/backend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
    *) exit 9 ;;
esac
EOF
chmod +x "$work/bin/docker"
for scenario in existing missing denied; do
    export SCENARIO=$scenario
    : > "$work/calls"
    : > "$GITHUB_OUTPUT"
    # When running the actual publication script.
    set +e
    PATH="$work/bin:$PATH" bash ops/ci/publish.sh > "$work/log" 2>&1
    code=$?
    set -e
    # Then reruns reuse, absence publishes, and ambiguous failures cannot overwrite.
    case "$scenario" in
        existing) [[ $code == 0 ]]; ! grep -Eq '^(build|push) ' "$work/calls" ;;
        missing) [[ $code == 0 ]]; grep -q '^build ' "$work/calls"; grep -q '^push ' "$work/calls" ;;
        denied) [[ $code != 0 ]]; ! grep -Eq '^(build|push) ' "$work/calls" ;;
    esac
    [[ ! -d $RUNNER_TEMP/danditon-publish ]]
    [[ $code != 0 ]] || grep -Eq '^digest=sha256:[0-9a-f]{64}$' "$GITHUB_OUTPUT"
    printf 'PASS publication=%s exit=%s credentials_removed=true\n' "$scenario" "$code"
done
