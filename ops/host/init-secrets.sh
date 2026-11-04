#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $EUID == 0 ]] || exit 1
directory=${1:-/etc/danditon}
[[ -d $directory && ! -L $directory ]] || exit 1
exec 9>"$directory/.secrets.lock"
flock -x 9
file=$directory/.env
if [[ -e $file || -L $file ]]; then
    [[ -f $file && ! -L $file && $(stat -c '%u:%g:%a' "$file") == 0:0:600 ]] || exit 1
    for key in MYSQL_ROOT_PASSWORD PROD_DB_PASSWORD PROD_DB_USERNAME PROD_DB_URL; do
        grep -q "^$key=." "$file" || exit 1
    done
    exit 0
fi
temporary=$(mktemp "$directory/.env.XXXXXX")
trap 'rm -f -- "$temporary"' EXIT
random() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
{
    printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(random)"
    printf 'PROD_DB_PASSWORD=%s\n' "$(random)"
    printf 'PROD_DB_USERNAME=danditon\n'
    printf 'PROD_DB_URL=jdbc:mysql://db:3306/danditon\n'
} > "$temporary"
chmod 600 "$temporary"
chown root:root "$temporary"
mv -T "$temporary" "$file"
