#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture=$(mktemp -d /tmp/danditon-backup-qa.XXXXXXXX)
trap 'rm -rf -- "$fixture"; echo CLEANUP_BACKUP_FIXTURE' EXIT
if bash ops/backup/restore.sh danditon < /dev/null > "$fixture/error" 2>&1; then exit 1; fi
grep -q UNSAFE_RESTORE_TARGET "$fixture/error"
# No host paths are created by this precondition probe.
if [[ ! -e /etc/danditon/backup.recipient ]]; then
    if sudo bash ops/backup/backup.sh 2>&1 | tee "$fixture/error" >/dev/null; then exit 1; fi
    grep -q MISSING_RECIPIENT "$fixture/error"
else
    echo 'Refusing missing-recipient probe on configured host' >&2; exit 1
fi
age-keygen -o "$fixture/identity" 2>/dev/null
recipient=$(age-keygen -y "$fixture/identity")
printf 'fixture ciphertext probe' | age -r "$recipient" > "$fixture/test.age"
[[ $(age -d -i "$fixture/identity" "$fixture/test.age") == 'fixture ciphertext probe' ]]
printf 'broken' > "$fixture/broken.age"
if age -d -i "$fixture/identity" "$fixture/broken.age" >/dev/null 2>&1; then exit 1; fi
[[ -z $(find "$fixture" -name '*.sql') ]]
echo RESTORE_PRODUCTION_MISSING_RECIPIENT_CORRUPTION_REJECTED
