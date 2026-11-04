# Release contract

The reviewed workflow atomically finalizes `/opt/danditon/releases/<sha>` from its
incoming directory. Existing release directories must never be overwritten: compare
all transferred bytes on retries and reject a different digest or file. Each release
contains `deploy.sh`, `compose.prod.yaml`, `image-digest.txt` and `SHA256SUMS`.
The digest file contains one `sha256:<64 lowercase hex>` value. Generate the manifest
with `sha256sum deploy.sh compose.prod.yaml image-digest.txt > SHA256SUMS`.

Invoke `bash /opt/danditon/releases/<sha>/deploy.sh <sha>` with exactly one full,
lowercase 40-character commit SHA. Host prerequisites are Bash, coreutils, flock,
git, curl, jq, Docker Engine and Compose. The deploy user needs write access to
`/opt/danditon` and passwordless `sudo -n /usr/bin/docker`; this is root-equivalent
authority. Only root can read `/etc/danditon/.env` (root:root, mode 600).

Every Compose invocation specifies project `danditon`, the release Compose file,
the host environment file, and then the release's generated `image.env`. Pulls
use the full SHA tag; activation uses that tag qualified by the verified digest.
First deployment initializes DB only when neither project containers nor the named
database volume exist. Routine deployments only update `app` with `--no-deps`.

`current.json` records SHA, digest-qualified image, manifest hash, release directory,
and both environment paths. `previous.json` is the preceding successful record.
Promotion occurs after DB health, HTTP readiness and running image ID agree.
The exclusive lock waits up to 60 seconds; network Git lookup is limited to 30
seconds, each Docker operation to 180 seconds, DB initialization to 120 seconds,
and readiness to 30 attempts with 2-second pauses and 3-second HTTP requests.

Failure after app activation restores the prior app/config and current metadata
from `rollback.json`, and checks readiness and metadata consistency before clearing
the transaction marker. Recovery also covers signals after metadata promotion.
The failed deployment still returns nonzero. HUP, INT and TERM take the same recovery path. First
deployment failure stops the app and retains the database and transaction marker.
Failed recovery, SIGKILL or power loss requires operator reconciliation: inspect
`transaction.json`, `current.json`, `previous.json`, actual running image and health
before repairing state and explicitly removing the marker. Never remove a marker
just to retry. Rollback does not reverse database migrations.

Run `bash ops/tests/run.sh release` for isolated command-boundary fixtures and
`bash ops/tests/image.sh danditon-qa:local <source-revision>` after the amd64 image
build. Real EC2 rollback and interruption proof belongs to the later operator QA.
