#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
case "${1:-}" in
    release) bash ops/tests/release.sh ;;
    workflow) python3 ops/tests/test_pr_workflow.py; python3 ops/tests/test_deploy_workflow.py ;;
    compose|host|backup) bash "ops/tests/${1}.sh" ;;
    *) printf 'Usage: %s {release|workflow|compose|host|backup}\n' "$0" >&2; exit 2 ;;
esac
