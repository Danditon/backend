#!/usr/bin/env bash
set -euo pipefail
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
source_script=$(realpath ops/deploy/deploy.sh)
mkdir "$work/bin"
export FIXTURE="$work"
export PATH="$work/bin:$PATH"
cat > "$work/bin/git" <<'EOF'
#!/usr/bin/env bash
printf '%s\trefs/heads/develop\n' "$(cat "$FIXTURE/head")"
EOF
cat > "$work/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
running=$(cat "$FIXTURE/running" 2>/dev/null || true)
bad=$(cat "$FIXTURE/bad" 2>/dev/null || true)
[[ $running != "$bad" && ! -f $FIXTURE/allbad ]] || exit 22
printf '{"status":"UP"}\n'
EOF
cat > "$work/bin/sudo" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
image_id() {
    local first=${1:0:1} i
    printf 'sha256:'
    for ((i=0; i<64; i++)); do printf '%s' "$first"; done
    printf '\n'
}
shift 2
printf '%s\n' "$*" >> "$FIXTURE/docker.log"
case "$1" in
    pull)
        tag=${2##*:}
        if ! grep -Eq "^ghcr.io/danditon/backend ${tag} " "$FIXTURE/images"; then
            printf 'ghcr.io/danditon/backend %s %s\n' "$tag" "$(image_id "$tag")" >> "$FIXTURE/images"
        fi ;;
    image)
        case "$2" in
            inspect)
                if [[ $4 == '{{.Id}}' ]]; then
                    reference=$5
                    tag=${reference#ghcr.io/danditon/backend:}
                    tag=${tag%@*}
                    image_id "$tag"
                else
                    printf 'ghcr.io/danditon/backend@%s\n' "$(cat "$FIXTURE/digest")"
                fi ;;
            ls) cat "$FIXTURE/images" ;;
            rm)
                [[ ! -f $FIXTURE/fail-image-cleanup ]] || exit 44
                reference=$3
                printf '%s\n' "$reference" >> "$FIXTURE/removed"
                awk -v reference="$reference" '$1 ":" $2 != reference' "$FIXTURE/images" > "$FIXTURE/images.tmp"
                /bin/mv "$FIXTURE/images.tmp" "$FIXTURE/images" ;;
            *) exit 9 ;;
        esac ;;
    inspect)
        if [[ $3 == '{{json .Config.Entrypoint}}' ]]; then printf '["/bin/sh","-c","exit 42"]\n'
        elif [[ $3 == '{{.State.Running}}' ]]; then printf 'false\n'
        elif [[ $3 == '{{.State.ExitCode}}' ]]; then printf '42\n'
        elif [[ $3 == '{{.Image}}' ]]; then
            if [[ $4 == app ]]; then image_id "$(cat "$FIXTURE/running")"
            else image_id f; fi
        else printf 'healthy\n'; fi ;;
    volume) [[ -f $FIXTURE/db ]] && printf 'danditon-mysql-data:/fixture/db\n' ;;
    ps)
        [[ ! -f $FIXTURE/db ]] || printf 'db\n'
        [[ ! -f $FIXTURE/running ]] || printf 'app\n' ;;
    compose)
        directory=$(dirname "$9")
        shift 9
        case "$*" in
            'ps -q db') [[ ! -f $FIXTURE/db ]] || printf 'db\n' ;;
            'ps -q app') [[ ! -f $FIXTURE/running ]] || printf 'app\n' ;;
            'ps -aq app') printf 'qa-app\n' ;;
            --file*'up -d --no-deps app')
                [[ -f $(dirname "$(dirname "$directory")")/transaction.json ]] || exit 98
                printf 'qa\n' > "$FIXTURE/running"
                printf 'qa\n' > "$FIXTURE/bad"
                [[ ! -f $FIXTURE/qa-recovery-failure ]] || touch "$FIXTURE/allbad" ;;
            'up -d --wait --wait-timeout 120 db') touch "$FIXTURE/db" ;;
            'up -d --no-deps app')
                basename "$directory" > "$FIXTURE/running"
                if [[ -f $FIXTURE/interrupt ]]; then
                    rm "$FIXTURE/interrupt"
                    kill -TERM "$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$PPID/status")"
                    /bin/sleep 0.1
                fi ;;
            'stop --timeout 20 app') rm -f "$FIXTURE/running" ;;
            *) printf 'unknown compose: %s\n' "$*" >&2; exit 9 ;;
        esac ;;
    *) exit 9 ;;
esac
EOF
cat > "$work/bin/mv" <<'EOF'
#!/usr/bin/env bash
if [[ -f $FIXTURE/fail-restore && $* == *current.json.tmp* ]]; then exit 1; fi
/bin/mv "$@" || exit
if [[ -f $FIXTURE/post-promotion && $* == *candidate.json* ]]; then
    /bin/rm "$FIXTURE/post-promotion"
    kill -TERM "$PPID"
fi
EOF
cat > "$work/bin/rm" <<'EOF'
#!/usr/bin/env bash
if [[ -f $FIXTURE/fail-remove && $* == *current.json* ]]; then exit 1; fi
if [[ -f $FIXTURE/fail-cleanup && $* == *transaction.json* ]]; then exit 1; fi
/bin/rm "$@"
if [[ -f $FIXTURE/post-cleanup && $* == *transaction.json* ]]; then
    /bin/rm "$FIXTURE/post-cleanup"
    kill -TERM "$PPID"
fi
EOF
chmod +x "$work/bin/"*
a=$(printf 'a%.0s' {1..40})
b=$(printf 'b%.0s' {1..40})
c=$(printf 'c%.0s' {1..40})
digest="sha256:$(printf 'd%.0s' {1..64})"
printf '%s\n' "$digest" > "$work/digest"
setup() {
    local case_name=$1 sha
    root="$work/case-$case_name"
    mkdir -p "$root/releases"
    /bin/rm -f "$work/db" "$work/running" "$work/bad" "$work/allbad" "$work/interrupt" \
        "$work/post-promotion" "$work/post-cleanup" "$work/fail-restore" "$work/fail-remove" "$work/fail-cleanup" "$work/qa-recovery-failure"
    /bin/rm -f "$work/fail-image-cleanup"
    : > "$work/docker.log"
    : > "$work/removed"
    printf 'ghcr.io/danditon/backend %s sha256:%s\n' "$c" "$(printf 'c%.0s' {1..64})" > "$work/images"
    printf 'ghcr.io/danditon/backend latest sha256:%s\n' "$(printf 'e%.0s' {1..64})" >> "$work/images"
    for sha in "$a" "$b"; do
        mkdir "$root/releases/$sha"
        sed "s|root=/opt/danditon|root=$root|; s/flock -w 60/flock -w 1/" "$source_script" > "$root/releases/$sha/deploy.sh"
        printf 'services: {}\n' > "$root/releases/$sha/compose.prod.yaml"
        printf '%s\n' "$digest" > "$root/releases/$sha/image-digest.txt"
        (cd "$root/releases/$sha" && sha256sum deploy.sh compose.prod.yaml image-digest.txt > SHA256SUMS)
    done
    printf '%s\n' "$a" > "$work/head"
}
deploy() { bash "$root/releases/$1/deploy.sh" "$1"; }
fails() {
    local label=$1 expected
    shift
    if "$@" > "$work/result" 2>&1; then
        printf 'FAIL: %s unexpectedly succeeded\n' "$label"; exit 1
    fi
    case "$label" in
        invalid-sha|extra-argument) expected=INVALID_SHA ;;
        changed-file) expected=RELEASE_COLLISION ;;
        missing-file) expected=MISSING_FILE ;;
        wrong-digest) expected=DIGEST_MISMATCH ;;
        stale) expected=STALE_RELEASE ;;
        lock-timeout) expected=LOCK_TIMEOUT ;;
        unhealthy-rollback|interrupted-deploy) expected=ROLLED_BACK ;;
        first-deploy-failure) expected=FIRST_RELEASE_FAILED ;;
        reconciliation-required) expected=OPERATOR_RECONCILIATION_REQUIRED ;;
        rollback-failure) expected=ROLLBACK_FAILED ;;
        *) exit 2 ;;
    esac
    grep -q "$expected" "$work/result"
    printf 'PASS: %s: %s\n' "$label" "$(tr '\n' ' ' < "$work/result")"
}
setup happy
deploy "$a"
printf '%s\n' "$b" > "$work/head"
deploy "$b"
[[ $(jq -r .sha "$root/current.json") == "$b" ]]
[[ $(jq -r .sha "$root/previous.json") == "$a" ]]
[[ $(grep -c 'up -d --wait' "$work/docker.log") == 1 ]]
grep -Fxq "ghcr.io/danditon/backend:$c" "$work/removed"
if grep -Fxq "ghcr.io/danditon/backend:$a" "$work/removed"; then exit 1; fi
if grep -Fxq "ghcr.io/danditon/backend:$b" "$work/removed"; then exit 1; fi
grep -Eq '^ghcr.io/danditon/backend latest ' "$work/images"
deploy "$b" | grep -q ALREADY_CURRENT
printf 'PASS: A->B, metadata, database untouched, retained current/previous, removed old SHA image, ignored non-SHA tag, idempotent retry\n'
setup cleanup_warning
touch "$work/fail-image-cleanup"
deploy "$a" > "$work/result" 2>&1
grep -Fxq 'IMAGE_CLEANUP_WARNING deployment_succeeded=true' "$work/result"
grep -Fxq "DEPLOYED $a" "$work/result"
[[ $(jq -r .sha "$root/current.json") == "$a" ]]
grep -Eq "^ghcr.io/danditon/backend ${c} " "$work/images"
printf 'PASS: cleanup failure warns without rolling back healthy deployment\n'
fails invalid-sha bash "$root/releases/$a/deploy.sh" 'a;touch /tmp/injected'
fails extra-argument bash "$root/releases/$a/deploy.sh" "$a" surprise
printf 'changed\n' >> "$root/releases/$b/compose.prod.yaml"
fails changed-file deploy "$b"
setup missing
rm "$root/releases/$a/image-digest.txt"
fails missing-file deploy "$a"
setup digest
printf 'sha256:%064d\n' 0 > "$work/digest"
fails wrong-digest deploy "$a"
[[ ! -e $root/current.json && ! -e $work/db ]]
printf '%s\n' "$digest" > "$work/digest"
setup stale
printf '%s\n' "$b" > "$work/head"
fails stale deploy "$a"
setup locked
exec 8>"$root/deploy.lock"
flock 8
fails lock-timeout deploy "$a"
flock -u 8
setup rollback
deploy "$a"
printf '%s\n' "$b" > "$work/head"
printf '%s\n' "$b" > "$work/bad"
: > "$work/removed"
fails unhealthy-rollback deploy "$b"
[[ $(cat "$work/running") == "$a" && ! -e $root/transaction.json ]]
[[ $(jq -r .sha "$root/current.json") == "$a" ]]
[[ ! -s $work/removed ]]
grep -Eq "^ghcr.io/danditon/backend ${b} " "$work/images"
setup first
printf '%s\n' "$a" > "$work/bad"
fails first-deploy-failure deploy "$a"
[[ ! -e $root/current.json && ! -e $work/running && -e $root/transaction.json ]]
fails reconciliation-required deploy "$a"
setup interrupted
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/interrupt"
fails interrupted-deploy deploy "$b"
[[ $(cat "$work/running") == "$a" && ! -e $root/transaction.json ]]
setup rollback_failure
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/allbad"
: > "$work/removed"
fails rollback-failure deploy "$b"
[[ -e $root/transaction.json ]]
[[ ! -s $work/removed ]]
grep -Eq "^ghcr.io/danditon/backend ${b} " "$work/images"
setup post_promotion
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/post-promotion"
fails interrupted-deploy deploy "$b"
[[ $(cat "$work/running") == "$a" && $(jq -r .sha "$root/current.json") == "$a" && ! -e $root/transaction.json ]]
printf 'PASS: post-promotion TERM restores runtime and current metadata\n'
setup post_cleanup
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/post-cleanup"
fails interrupted-deploy deploy "$b"
[[ $(cat "$work/running") == "$a" && $(jq -r .sha "$root/current.json") == "$a" && ! -e $root/transaction.json ]]
printf 'PASS: post-cleanup TERM recreates marker before consistent rollback\n'
setup metadata_restore_failure
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/post-promotion" "$work/fail-restore"
fails rollback-failure deploy "$b"
[[ $(cat "$work/running") == "$a" && $(jq -r .sha "$root/current.json") == "$b" && -e $root/transaction.json ]]
fails reconciliation-required deploy "$b"
setup transaction_cleanup_failure
deploy "$a"
printf '%s\n' "$b" > "$work/head"
touch "$work/fail-cleanup"
fails rollback-failure deploy "$b"
[[ $(cat "$work/running") == "$a" && $(jq -r .sha "$root/current.json") == "$a" && -e $root/transaction.json ]]
setup first_post_promotion
touch "$work/post-promotion"
fails first-deploy-failure deploy "$a"
[[ ! -e $work/running && ! -e $root/current.json && -e $root/transaction.json ]]
setup first_metadata_remove_failure
touch "$work/post-promotion" "$work/fail-remove"
fails first-deploy-failure deploy "$a"
[[ ! -e $work/running && -e $root/current.json && -e $root/transaction.json ]]
fails reconciliation-required deploy "$a"
printf 'PASS: all release fixtures; cleanup on EXIT\n'

operator_qa() {
    # Only the fixture bypasses root and rewrites /opt; production has no override.
    sed "s|root=/opt/danditon|root=$root|; /\[\[ \$EUID == 0 \]\]/d; s/flock -w 60/flock -w 1/" \
        ops/tests/live-rollback.sh > "$work/operator-qa.sh"
    bash "$work/operator-qa.sh" --current "$a" --case unhealthy-app
}
setup operator_recovery
deploy "$a"
set +e
operator_qa > "$work/qa-result" 2>&1
code=$?
set -e
[[ $code == 42 ]]
grep -Fxq EXPECTED_UNHEALTHY_CANDIDATE "$work/qa-result"
grep -Fxq ROLLED_BACK "$work/qa-result"
grep -q LIVE_ROLLBACK_VERIFIED "$work/qa-result"
[[ $(cat "$work/running") == "$a" && ! -e $root/transaction.json ]]
[[ -z $(find "$root" -maxdepth 1 -name '.rollback-qa.*' -print) ]]
printf 'PASS: operator adapter activates candidate, shared recovery restores app, exits42, removes QA artifacts\n'
setup operator_recovery_failure
deploy "$a"
touch "$work/qa-recovery-failure"
if operator_qa > "$work/qa-result" 2>&1; then exit 1; fi
grep -q LIVE_ROLLBACK_FAILED "$work/qa-result"
[[ -s $root/transaction.json ]]
[[ -f $(jq -r .qa_override "$root/transaction.json") ]]
printf 'PASS: operator recovery failure preserves transaction and override for reconciliation\n'
if operator_qa > "$work/qa-result" 2>&1; then exit 1; fi
grep -Fxq OPERATOR_RECONCILIATION_REQUIRED "$work/qa-result"
setup operator_stale
deploy "$a"
printf '%s\n' "$b" > "$work/head"
deploy "$b"
if operator_qa > "$work/qa-result" 2>&1; then exit 1; fi
grep -Fxq CURRENT_SHA_MISMATCH "$work/qa-result"
[[ $(cat "$work/running") == "$b" && ! -e $root/transaction.json ]]
exec 8>"$root/deploy.lock"
flock 8
if operator_qa > "$work/qa-result" 2>&1; then exit 1; fi
grep -Fxq LOCK_TIMEOUT "$work/qa-result"
flock -u 8
printf 'PASS: operator adapter refuses unknown transaction, stale current SHA and busy deployment lock\n'
