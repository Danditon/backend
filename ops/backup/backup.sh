#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 0 ]] || exit 1
recipient_file=/etc/danditon/backup.recipient
[[ -f $recipient_file && ! -L $recipient_file ]] || { echo MISSING_RECIPIENT >&2; exit 1; }
recipient=$(cat "$recipient_file")
[[ $recipient =~ ^age1[0-9a-z]{58}$ ]] || { echo INVALID_RECIPIENT >&2; exit 1; }
printf '' | age -r "$recipient" >/dev/null
[[ -f /etc/danditon/.env && $(stat -c '%u:%g:%a' /etc/danditon/.env) == 0:0:600 ]] || { echo MISSING_SECRET >&2; exit 1; }
exec 9>/var/backups/danditon/backup.lock
flock -n 9 || exit 1
directory=$(jq -er '.directory' /opt/danditon/current.json)
[[ $directory =~ ^/opt/danditon/releases/[0-9a-f]{40}$ ]] || exit 1
compose=(docker compose --project-name danditon --env-file /etc/danditon/.env --env-file "$directory/image.env" --file "$directory/compose.prod.yaml")
"${compose[@]}" config --quiet
output=/var/backups/danditon/$(date -u +%Y%m%dT%H%M%S)-$$.sql.age
trap 'rm -f -- "$output.partial"' EXIT
# Expanded only inside the DB container.
# shellcheck disable=SC2016
"${compose[@]}" exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF danditon' |
    age -r "$recipient" > "$output.partial"
mv "$output.partial" "$output"
(cd /var/backups/danditon && sha256sum "$(basename "$output")" > "$(basename "$output").sha256")
printf 'BACKUP_CREATED %s\n' "$output"
