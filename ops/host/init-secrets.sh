#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $EUID == 0 ]] || exit 1
directory=${1:-/etc/danditon}
[[ -d $directory && ! -L $directory ]] || exit 1
[[ ! -L $directory/.secrets.lock ]] || exit 1
exec 9>"$directory/.secrets.lock"
flock -x -w 60 9
file=$directory/.env
if [[ -e $file || -L $file ]]; then
    [[ -f $file && ! -L $file && $(stat -c '%u:%g:%a' "$file") == 0:0:600 ]] || exit 1
    for key in MYSQL_ROOT_PASSWORD PROD_DB_PASSWORD PROD_DB_USERNAME PROD_DB_URL; do
        [[ $(grep -c "^$key=" "$file") == 1 ]] || exit 1
        grep -q "^$key=." "$file" || exit 1
    done
    jwt_count=$(grep -c '^JWT_SECRET_BASE64=' "$file" || true)
    [[ $jwt_count == 0 || $jwt_count == 1 ]] || exit 1
    if [[ $jwt_count == 1 ]]; then
        jwt=$(sed -n 's/^JWT_SECRET_BASE64=//p' "$file")
        [[ $jwt =~ ^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$ ]] || exit 1
        decoded_size=$(printf '%s' "$jwt" | base64 --decode 2>/dev/null | wc -c) || exit 1
        [[ $decoded_size -ge 32 ]] || exit 1
        [[ $(printf '%s' "$jwt" | base64 --decode | base64 -w 0) == "$jwt" ]] || exit 1
        exit 0
    fi
fi
temporary=''
trap 'rm -f -- "$temporary"' EXIT
trap 'exit 1' HUP INT TERM
temporary=$(mktemp "$directory/.env.XXXXXX")
random() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
jwt=$(head -c 32 /dev/urandom | base64 -w 0)
[[ $(printf '%s' "$jwt" | base64 --decode | wc -c) == 32 ]] || exit 1
if [[ -f $file ]]; then
    cat -- "$file" > "$temporary"
    if [[ $(tail -c 1 "$file" | wc -l) == 0 ]]; then printf '\n' >> "$temporary"; fi
else
    {
        printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(random)"
        printf 'PROD_DB_PASSWORD=%s\n' "$(random)"
        printf 'PROD_DB_USERNAME=danditon\n'
        printf 'PROD_DB_URL=jdbc:mysql://db:3306/danditon\n'
    } > "$temporary"
fi
printf 'JWT_SECRET_BASE64=%s\n' "$jwt" >> "$temporary"
chmod 600 "$temporary"
chown root:root "$temporary"
mv -T "$temporary" "$file"
