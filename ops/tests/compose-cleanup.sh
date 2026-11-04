#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ $# == 2 && $1 == --success-log ]]; then
    log=$2
    grep -Fxq ENCRYPTED_ROUNDTRIP_AND_PRODUCTION_UNCHANGED_PASS "$log"
    result=SUCCESS_RESOURCE_CLEANUP_PASS
elif [[ $# == 0 ]]; then
    log=$(mktemp /tmp/danditon-compose-cleanup.XXXXXXXX)
    trap 'rm -f -- "$log"' EXIT
    image=danditon-task7-missing-$(basename "$log" | tr '[:upper:]' '[:lower:]'):absent
    if docker image inspect "$image" >/dev/null 2>&1; then exit 1; fi
    status=0
    QA_APP_IMAGE=$image timeout 60 bash ops/tests/compose.sh > "$log" 2>&1 || status=$?
    [[ $status == 1 ]]
    grep -q 'No such image' "$log"
    result=PRESTART_FAILURE_STATUS_AND_CLEANUP_PASS
else
    exit 2
fi
grep -Fxq CLEANUP_COMPOSE_FIXTURE "$log"
fixture=$(sed -n 's/^FIXTURE_DIRECTORY=//p' "$log")
project=$(sed -n 's/^FIXTURE_PROJECT=//p' "$log")
[[ $fixture == /tmp/danditon-compose-qa.* && ! -e $fixture ]]
[[ $project == danditon-compose-qa-* ]]
[[ -z $(docker ps -aq --filter "label=com.docker.compose.project=$project") ]]
[[ -z $(docker network ls -q --filter "label=com.docker.compose.project=$project") ]]
[[ -z $(docker volume ls -q --filter "name=^${project}-data$") ]]
cat "$log"
echo "$result"
