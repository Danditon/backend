#!/usr/bin/env bash
set -euo pipefail
# Run only in a private mount namespace. No production path is changed outside it.
[[ $# == 3 && $1 =~ ^danditon-compose-qa-[a-z0-9]+$ ]] || exit 1
project=$1
fixture=$2
repo=$3
mount --make-rprivate /
mkdir -p "$fixture/etc/danditon" "$fixture/opt/danditon/releases" "$fixture/backups/danditon" "$fixture/bin"
cp "$fixture/env" "$fixture/etc/danditon/.env"
chmod 600 "$fixture/etc/danditon/.env"
age-keygen -y "$fixture/identity" > "$fixture/etc/danditon/backup.recipient"
sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
release=$fixture/opt/danditon/releases/$sha
mkdir "$release"
cp "$repo/compose.prod.yaml" "$release/compose.prod.yaml"
printf 'APP_IMAGE=danditon-task7-qa:local\n' > "$release/image.env"
printf '{"directory":"/opt/danditon/releases/%s"}\n' "$sha" > "$fixture/opt/danditon/current.json"
cat > "$fixture/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ $1 == compose && $2 == --project-name && $3 == danditon ]] || exit 1
shift 3
exec /usr/bin/docker compose --project-name "$QA_PROJECT" "$@"
EOF
chmod 755 "$fixture/bin/docker"
export QA_PROJECT=$project
export PATH="$fixture/bin:$PATH"
mount --bind "$fixture/etc" /etc
mount --bind "$fixture/opt" /opt
mount --bind "$fixture/backups" /var/backups
bash "$repo/ops/backup/backup.sh"
backup=(/var/backups/danditon/*.sql.age)
[[ ${#backup[@]} == 1 && -s ${backup[0]} ]]
cp "${backup[0]}" "$fixture/backup.sql.age"
(cd /var/backups/danditon && sha256sum -c ./*.sha256)
[[ -z $(find /var/backups/danditon -name '*.sql' -o -name '*.partial') ]]
mv /etc/danditon/.env /etc/danditon/secret-held
if bash "$repo/ops/backup/backup.sh" > "$fixture/missing-secret" 2>&1; then exit 1; fi
grep -Fxq MISSING_SECRET "$fixture/missing-secret"
echo BACKUP_PRODUCTION_SCRIPT_CIPHERTEXT_CHECKSUM_MISSING_SECRET_PASS
