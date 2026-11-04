#!/usr/bin/env bash
set -Eeuo pipefail
# Given an isolated SSH server, exercise real OpenSSH/SFTP and remote Bash.
root=$(pwd)
work=$(mktemp -d)
name="danditon-ssh-fixture-$$"
cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker image rm "$name" >/dev/null 2>&1 || true
    rm -rf -- "$work"
}
trap cleanup EXIT
mkdir "$work/bin" "$work/runner" "$work/source" "$work/source/ops" "$work/source/ops/deploy"
ssh-keygen -q -t ed25519 -N '' -f "$work/key"
ssh-keygen -q -t ed25519 -N '' -f "$work/host"
cat > "$work/Dockerfile" <<'EOF'
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server jq && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash danditon-deploy && passwd -d danditon-deploy && mkdir -p /run/sshd /opt/danditon/releases /home/danditon-deploy/.ssh && chown -R danditon-deploy:danditon-deploy /opt/danditon /home/danditon-deploy/.ssh
COPY key.pub /home/danditon-deploy/.ssh/authorized_keys
COPY host /etc/ssh/ssh_host_ed25519_key
COPY sudo /usr/local/bin/sudo
RUN chmod 600 /etc/ssh/ssh_host_ed25519_key && chmod 755 /usr/local/bin/sudo
CMD ["/usr/sbin/sshd", "-D", "-e", "-o", "PasswordAuthentication=no", "-o", "StrictModes=no"]
EOF
cat > "$work/sudo" <<'EOF'
#!/bin/bash
case "$*" in
    *'ps -q app') echo fixture-app ;;
    *'image inspect'*) echo sha256:fixture ;;
    *'inspect'*) [[ ! -f /opt/danditon/wrong-running ]] && echo sha256:fixture || echo sha256:wrong ;;
    *) exit 9 ;;
esac
EOF
timeout 180 docker build -q -t "$name" "$work" >/dev/null
docker run -d --name "$name" -p 127.0.0.1::22 "$name" >/dev/null
port=$(docker port "$name" 22/tcp | cut -d: -f2)
export SSH_PORT=$port
export FIXTURE="$work"
export SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export IMAGE_DIGEST=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
export EC2_HOST=127.0.0.1 RUNNER_TEMP="$work/runner" GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT=1
export SSH_PRIVATE_KEY SSH_KNOWN_HOSTS
SSH_PRIVATE_KEY=$(cat "$work/key")
SSH_KNOWN_HOSTS="[127.0.0.1]:$port $(cut -d' ' -f1-2 "$work/host.pub")"
cat > "$work/bin/ssh" <<'EOF'
#!/bin/bash
exec /usr/bin/ssh -p "$SSH_PORT" "$@"
EOF
cat > "$work/bin/scp" <<'EOF'
#!/bin/bash
if [[ ${SCENARIO:-} == partial ]]; then
    args=("$@")
    unset 'args[-3]'
    /usr/bin/scp -P "$SSH_PORT" "${args[@]}"
    exit 23
fi
exec /usr/bin/scp -P "$SSH_PORT" "$@"
EOF
cat > "$work/bin/git" <<'EOF'
#!/bin/bash
[[ ${SCENARIO:-} == stale ]] && printf '%040d\trefs/heads/develop\n' 0 || printf '%s\trefs/heads/develop\n' "$SHA"
EOF
cat > "$work/bin/docker" <<'EOF'
#!/bin/bash
[[ -d $DOCKER_CONFIG && ! -f $DOCKER_CONFIG/config.json ]] || exit 91
case "$1" in
    pull) [[ ${SCENARIO:-} != private ]] ;;
    image) [[ ${SCENARIO:-} != digest ]] && echo "ghcr.io/danditon/backend@$IMAGE_DIGEST" || echo wrong ;;
    *) exit 92 ;;
esac
EOF
cat > "$work/bin/curl" <<'EOF'
#!/bin/bash
case "${!#}" in
    "http://$EC2_HOST/health") printf '{"status":"UP"}\n' ;;
    *) printf 'HTTP 404: %s\n' "${!#}" >&2; exit 22 ;;
esac
EOF
chmod +x "$work/bin/"*
cat > "$work/source/ops/deploy/deploy.sh" <<'EOF'
#!/bin/bash
set -euo pipefail
[[ ! -f /opt/danditon/remote-failure ]] || exit 37
digest=$(cat "/opt/danditon/releases/$1/image-digest.txt")
printf '{"image":"ghcr.io/danditon/backend:%s@%s"}\n' "$1" "$digest" > /opt/danditon/current.json
printf 'APP_IMAGE=ghcr.io/danditon/backend:%s@%s\n' "$1" "$digest" > "/opt/danditon/releases/$1/image.env"
EOF
echo 'services: {}' > "$work/source/compose.prod.yaml"
for _attempt in {1..30}; do
    if timeout 3 /usr/bin/ssh -p "$port" -i "$work/key" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null danditon-deploy@127.0.0.1 true 2>/dev/null; then break; fi
    sleep 1
done
for scenario in invalid wrong-key private stale partial digest remote running old-health-path happy; do
    export SCENARIO=$scenario
    docker exec "$name" bash -c 'rm -rf /opt/danditon/releases/* /opt/danditon/releases/.incoming-* /opt/danditon/current.json /opt/danditon/remote-failure /opt/danditon/wrong-running'
    test_sha=$SHA test_hosts=$SSH_KNOWN_HOSTS
    [[ $scenario != invalid ]] || test_sha='bad;injection'
    [[ $scenario != wrong-key ]] || test_hosts="[127.0.0.1]:$port $(cut -d' ' -f1-2 "$work/key.pub")"
    [[ $scenario != remote ]] || docker exec "$name" touch /opt/danditon/remote-failure
    [[ $scenario != running ]] || docker exec "$name" touch /opt/danditon/wrong-running
    runner_script="$root/ops/ci/deploy.sh"
    if [[ $scenario == old-health-path ]]; then
        sed 's|/health"|/actuator/health/readiness"|' "$runner_script" > "$work/old-health-path.sh"
        runner_script="$work/old-health-path.sh"
    fi
    # When executing the production runner script through native SSH and SCP.
    set +e
    (cd "$work/source" && PATH="$work/bin:$PATH" SHA="$test_sha" SSH_KNOWN_HOSTS="$test_hosts" bash "$runner_script") > "$work/$scenario.log" 2>&1
    code=$?
    set -e
    # Then errors are red, before inappropriate mutation, and secrets are removed.
    [[ ! -e $RUNNER_TEMP/danditon-deploy ]]
    case "$scenario" in
        happy) [[ $code == 0 ]]; grep -q DEPLOY_VERIFIED "$work/$scenario.log" ;;
        remote) [[ $code == 37 ]] ;;
        partial) [[ $code == 23 ]]; docker exec "$name" test ! -e "/opt/danditon/releases/$SHA" ;;
        running) [[ $code != 0 ]] ;;
        old-health-path) [[ $code != 0 ]]; grep -q 'HTTP 404:' "$work/$scenario.log" ;;
        *) [[ $code != 0 ]]; docker exec "$name" test ! -e "/opt/danditon/releases/$SHA" ;;
    esac
    [[ $scenario != private ]] || grep -q GHCR_NOT_PUBLIC "$work/$scenario.log"
    if [[ -n ${EVIDENCE_DIR:-} ]]; then
        printf 'scenario=%s exit=%s runner_credentials_removed=true\n' "$scenario" "$code" >> "$work/$scenario.log"
        cp "$work/$scenario.log" "$EVIDENCE_DIR/task-10-ssh-ghcr-$scenario.log"
    fi
    printf 'PASS scenario=%s exit=%s runner_credentials_removed=true\n' "$scenario" "$code"
done
docker rm -f "$name" >/dev/null
docker image rm "$name" >/dev/null
echo 'PASS fixture container/image removed; EXIT removes temporary key and fixture directory'
