# Ubuntu 24.04 host assets

For disposable Linux QA, first build the JAR with Gradle bootJar and run
`docker build -t danditon-task7-qa:local .`, then run the compose, host and backup
suites via `bash ops/tests/run.sh <suite>` as root in an owned Ubuntu 24.04
environment. Compose QA uses ephemeral loopback ports, unique project/volume names
and the actual JVM app; backup path fixtures use a private mount namespace.

Run `sudo bash ops/host/bootstrap.sh /absolute/path/deploy.pub` from an operator
session whose distinct public key already works. Keep that session open until a
second operator SSH connection succeeds. Only an Ed25519 deploy public key is
accepted. Review `sshd -T` after bootstrap: Ubuntu reads the first setting found,
so conflicting earlier configuration must be reconciled before exposure.

The deploy user has no supplementary groups, PTY, forwarding or user RC. Its
precise sudo command is `/usr/bin/docker` with arbitrary arguments. **This is
root-equivalent authority**, including reading host secrets via mounted paths;
mode 600 is protection against ordinary reads, not against Docker administration.

The root-owned `/etc/danditon/.env` is generated once and retained on reruns.
The host generates only MYSQL_ROOT_PASSWORD, PROD_DB_PASSWORD, PROD_DB_USERNAME and
PROD_DB_URL. JWT_SECRET_BASE64 is supplied by the GitHub production Environment
Secret during deployment and updated in the same file. Only the DB receives MYSQL_ROOT_PASSWORD. Never print production
Compose config or inspect container environment. The release script validates the
full SHA/digest APP_IMAGE in each release's image.env. Bootstrap does not start DB.

MySQL 8.4 index digest was verified on 2026-11-04 via authenticated anonymous
Docker Registry v2 GET `/v2/library/mysql/manifests/8.4`; amd64 manifest reports
8.4.13. Updates require a reviewed explicit digest change and DB maintenance.
Official install reference: https://docs.docker.com/engine/install/ubuntu/ .

Backup: create `/etc/danditon/backup.recipient` containing only the off-host age
public recipient. `sudo systemctl start danditon-backup.service` streams directly
to `/var/backups/danditon/*.sql.age` and a corresponding `.sha256`. No plaintext
dump is written. Timer runs daily at 03:00 UTC plus up to 15 minutes jitter.
An operator must copy ciphertext/checksum off-host routinely; this is not an
automated off-host delivery guarantee. Keep the private age identity off-host.
There is deliberately no automatic deletion of retained backups.

After checking the ciphertext checksum, decrypt off-host and pipe SQL over verified
operator SSH to `sudo /usr/local/sbin/danditon-restore danditon-restore-qa-<run>`.
The run suffix must be 8–32 lowercase letters/digits. The helper creates a new
network-isolated MySQL container with a fresh secret and tmpfs data, imports SQL,
reports schema table count, and removes the container on exit. It never accepts an
existing container or production target. Its 512 MiB tmpfs bounds the drill size.
