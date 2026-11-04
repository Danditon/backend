# EC2 배포 운영 문서

보호된 `develop` → GitHub Actions 빌드/검증 → 공개 `ghcr.io/danditon/backend:<40자리 SHA>` → native SCP/SSH → EC2 Docker Compose 경로다. Nginx HTTP 80 → 호스트 `127.0.0.1:8080` → 앱 → 내부 MySQL 순서로 연결한다. 앱과 DB는 한 호스트를 공유하고 재시작 배포에는 중단이 있다. 이 문서는 설정 절차이며 실제 배포 성공은 Actions 실행, 이미지 digest, 호스트 메타데이터 및 HTTP readiness가 모두 일치할 때만 기록한다.

## 소유권과 사전 조건

운영 책임 팀은 `danditon`, 배포 담당은 README의 담당자다. 팀은 실제 정·부 운영자, 비상 연락처, AWS 계정/리전, instance/EBS/EIP allocation·association/SG/key-pair ID, 키 지문, 백업 보관 위치와 최근 복구일을 저장소 밖 비공개 운영 장부에 기록한다. 이전 담당자가 떠나기 전에 다음 담당자의 SSH와 백업 복호화를 검증한다.

- AWS: 기존 로그인 주체의 서울 `ap-northeast-2` 콘솔 접근과 [EC2 명세의 정확한 권한 목록](../infra/aws/ec2-console.md#필요한-권한)이 필요하다. 버튼이 보이는 것은 생성 권한의 증거가 아니다. 거부된 action을 기록하고 중단한다. IAM 역할·OIDC·SSM·S3·Parameter Store·CloudFormation은 사용하지 않는다.
- GitHub: `Danditon/backend` 저장소 admin, 조직/package 공개 전환 권한, 보호된 `develop` PR의 실제 팀원 1명 승인이 필요하다. `main`/`develop` 직접 push 및 관리자 우회를 하지 않는다. [협업 규칙](../CONTRIBUTING.md)을 따른다.
- 운영 단말: Bash, OpenSSH, curl, jq, age, Docker(격리 복구용). 아래 명령은 Bash용이다. PowerShell에 Bash 배열/인용문을 그대로 붙여 넣지 않는다. 키·백업은 저장소 밖 접근 제한 디렉터리에 둔다. 예시의 변수는 비공개 장부에서 설정하며 비밀값을 터미널에 출력하지 않는다.

## 저장소·환경·첫 패키지 공개

1. 모든 원격 브랜치/태그의 이력과 현재 트리, PR/issue, release, Actions 로그/산출물에 대한 노출 감사를 완료한다. 이미지 build context와 레이어도 별도로 검사한다. 미해결 키·비밀·비공개 자료가 있거나 감사 SHA가 바뀌면 공개하지 않는다. 공개 후 비공개로 돌려도 이미 복사된 자료는 회수되지 않는다.
2. [저장소 Settings](https://github.com/Danditon/backend/settings) → General → Danger Zone → Change visibility에서 확인된 저장소를 public으로 전환한다. 현재 Free 조직에서는 private 저장소의 규칙/환경 제한이 적용된다고 가정하지 않는다. 익명 조회로 public 여부를 확인한다.
3. Settings → Actions → General에서 기본 token 권한을 read-only로 둔다. PR `Backend CI`는 production secret 없이 Java 17로 `./gradlew --no-daemon clean build`를 수행한다. Rules/Branches에서 `develop`의 PR 필수, 승인 1명, 새 push 시 기존 승인 해제, 필수 `Backend CI`, 대화 해결, force push/삭제 금지, 우회 없음이 실제 적용되는지 확인한다.
4. Settings → Environments → `production` → Deployment branches and tags → **Selected branches and tags** → **Branch** `develop` 하나만 추가한다. Tag 규칙·와일드카드·배포 reviewer는 추가하지 않는다. PR의 팀원 승인은 별도로 유지한다. 아래 항목은 이 환경에만 저장한다.

| 종류 | 이름 | 입력 |
|---|---|---|
| Secret | `EC2_SSH_PRIVATE_KEY` | 별도 배포용 Ed25519 비밀키 전체, 보호된 입력으로 설치 |
| Secret | `EC2_SSH_KNOWN_HOSTS` | 아래 콘솔 지문과 일치한 EIP의 Ed25519 known_hosts 한 줄 |
| Variable | `EC2_HOST` | 해당 인스턴스에 연결한 고정 EIP |

`EC2_USER=danditon-deploy`는 검토된 workflow의 고정값이다. DB 비밀은 GitHub에 등록하지 않는다. production key는 deploy job만 받는다. 게시 job만 job-scoped `packages: write`와 `GITHUB_TOKEN` stdin 로그인 권한을 갖는다. EC2에는 PAT·registry 로그인·GitHub token·AWS access key를 저장하지 않는다. [환경 설정 근거](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

5. 첫 게시로 생성된 backend 패키지는 private일 수 있다. 조직 Packages → backend → Package settings에서 source가 이 저장소인지, SHA/revision과 이미지 감사 결과가 일치하는지 확인한다. 조직 Packages 정책상 public 생성이 막혔으면 권한 있는 소유자가 정책 또는 해당 패키지 전환 권한을 해결해야 한다. 저장소 public 전환은 패키지 공개를 의미하지 않는다.
6. 패키지를 public으로 바꾸기 전 deploy job의 익명 gate는 `GHCR_NOT_PUBLIC`로 **SSH 전에 실패해야 한다**. package admin이 Change visibility → Public을 완료한 뒤 빈 `DOCKER_CONFIG`로 정확한 SHA 이미지를 익명 pull한다. 공개 이미지의 익명 접근은 [GHCR 권한 문서](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)를 따른다.

```bash
[[ $SHA =~ ^[0-9a-f]{40}$ ]] || exit 1
ANON_CONFIG=$(mktemp -d)
if docker --config "$ANON_CONFIG" pull "ghcr.io/danditon/backend:$SHA"; then
  rm -rf -- "$ANON_CONFIG"
else
  rm -rf -- "$ANON_CONFIG"
  exit 1
fi
```

최신 remote `develop`이 여전히 해당 SHA일 때만 Actions에서 실패한 deploy job을 재실행한다. 기존 SHA 태그를 다시 빌드해 다른 내용으로 덮어쓰지 않는다. head가 바뀌었으면 최신 커밋의 실행을 사용한다. publish 성공만으로 배포 성공이라고 기록하지 않는다.

## 콘솔 생성과 SSH 서버 신뢰

[EC2 콘솔 명세](../infra/aws/ec2-console.md)와 [고정 자원 명세](../infra/aws/expected-resources.json)를 그대로 사용한다. 기존 퍼블릭 subnet/IGW, Canonical Ubuntu 24.04 x86_64, `t3.small` Standard credit, encrypted 20 GiB gp3, IMDSv2 required, instance profile 없음, EIP 1개다. 태그는 `Project=danditon`, `Environment=production`, `Owner=danditon`, `ManagedBy=manual-console`이다.

SG ingress는 IPv4 TCP22와 TCP80 각각 `0.0.0.0/0`이다. **22 전 세계 공개는 의도된 선택**이며 키 전용 인증·root 로그인 금지·fail2ban을 적용해도 공격 노출은 남는다. IPv6 ingress, 3306, 8080을 열지 않는다. EIP는 stop/start 시에도 유지하되 요금이 남는다.

운영자 `ubuntu` 키와 CI `danditon-deploy` 키를 별도로 만든다. 운영자 키는 암호와 로컬 agent로 보호할 수 있고, CI 키는 비대화형 로그인용으로 별도 보관한다. `OPERATOR_KEY`, `DEPLOY_KEY`는 존재하지 않는 저장소 밖 경로로 먼저 설정한다. `.pub`만 EC2 key-pair import/호스트 authorized_keys에 넣는다.

```bash
umask 077
ssh-keygen -t ed25519 -f "$OPERATOR_KEY" -C danditon-operator-2026
ssh-keygen -t ed25519 -N '' -f "$DEPLOY_KEY" -C danditon-deploy-prod
```

EC2 → instance → Actions → Monitor and troubleshoot → **Get system log**에서 서버의 **Ed25519 SHA256 지문**을 확보한다. 콘솔의 imported key-pair 지문은 사용자 키 지문이므로 대체할 수 없다. `EXPECTED_HOST_FINGERPRINT`에 이 권위 있는 값을 설정하고 다음 블록을 한 번에 실행한다. 빈 값/잘못된 형식이면 keyscan·접속 전에 중단한다. `CANDIDATE_HOSTS`, `KNOWN_HOSTS`는 새 로컬 파일 경로다.

```bash
(
  set -euo pipefail
  [[ ${EXPECTED_HOST_FINGERPRINT:-} =~ ^SHA256:[A-Za-z0-9+/]{43}$ ]] || {
    echo MISSING_AUTHORITATIVE_HOST_FINGERPRINT >&2; exit 1;
  }
  : "${EC2_HOST:?}" "${CANDIDATE_HOSTS:?}" "${KNOWN_HOSTS:?}"
  umask 077
  ssh-keyscan -T 15 -t ed25519 "$EC2_HOST" > "$CANDIDATE_HOSTS"
  [[ $(wc -l < "$CANDIDATE_HOSTS") == 1 ]]
  ACTUAL_HOST_FINGERPRINT=$(ssh-keygen -lf "$CANDIDATE_HOSTS" -E sha256 | awk '{print $2}')
  [[ $ACTUAL_HOST_FINGERPRINT == "$EXPECTED_HOST_FINGERPRINT" ]] || {
    echo HOST_FINGERPRINT_MISMATCH >&2; exit 1;
  }
  install -m 600 "$CANDIDATE_HOSTS" "$KNOWN_HOSTS"
  rm -f -- "$CANDIDATE_HOSTS"
)
```

실패하면 이후 접속 단계를 진행하지 않는다. keyscan 결과 자체는 신뢰 근거가 아니다. 지문을 얻을 수 없으면 콘솔 출력/공식 복구 경로로 해결할 때까지 멈춘다. 호스트 교체·키 변경도 이 절차로 재검증하며 경고를 없애려고 known_hosts를 지우거나 `StrictHostKeyChecking=no`를 쓰지 않는다.

```bash
SSH_OPTS=(-i "$DEPLOY_KEY" -o BatchMode=yes -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$KNOWN_HOSTS"
  -o GlobalKnownHostsFile=/dev/null -o HostKeyAlgorithms=ssh-ed25519
  -o UpdateHostKeys=no -o ConnectTimeout=15
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
OPERATOR_OPTS=(-i "$OPERATOR_KEY" -o BatchMode=yes -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$KNOWN_HOSTS"
  -o GlobalKnownHostsFile=/dev/null -o HostKeyAlgorithms=ssh-ed25519
  -o UpdateHostKeys=no -o ConnectTimeout=15
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" 'id -un'
```

## 호스트 초기 설정과 권한

검토된 체크아웃의 `ops` 디렉터리와 배포 **공개키만** 운영자 SCP로 전송한다. 기존 operator SSH 세션을 유지하고 새 세션 접속 성공까지 닫지 않는다. 다음 경로가 기존 작업과 충돌하면 재사용하지 않는다.

```bash
ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" 'mkdir -m 700 /home/ubuntu/danditon-bootstrap'
scp "${OPERATOR_OPTS[@]}" -r ops "ubuntu@$EC2_HOST:/home/ubuntu/danditon-bootstrap/"
scp "${OPERATOR_OPTS[@]}" "$DEPLOY_KEY.pub" "ubuntu@$EC2_HOST:/home/ubuntu/danditon-bootstrap/deploy.pub"
ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
  'sudo bash /home/ubuntu/danditon-bootstrap/ops/host/bootstrap.sh /home/ubuntu/danditon-bootstrap/deploy.pub'
```

[bootstrap](../ops/host/bootstrap.sh)은 공식 Docker Ubuntu 패키지, Compose plugin, Nginx/fail2ban/age, 2 GiB swap, SSH/sudoers/백업 timer를 설치한다. [비밀 초기화](../ops/host/init-secrets.sh)는 호스트에서 난수를 생성하고 root:root 600 `/etc/danditon/.env`에 `MYSQL_ROOT_PASSWORD`, `PROD_DB_PASSWORD`, `PROD_DB_USERNAME`, `PROD_DB_URL`을 기록한다. 기존 정상 파일은 보존하며 새 DB 비밀번호로 덮어쓰지 않는다. `.env.example`은 이름 목록일 뿐 배포용 비밀 파일이 아니다. root-invoked Compose가 host env 다음 release `image.env`를 읽는다.

`danditon-deploy`는 일반 docker 그룹에 넣지 않고 `sudo -n /usr/bin/docker`만 sudoers에 둔다. **임의 Docker 인자를 허용하는 이 권한은 root와 동등하다.** 호스트 파일 마운트 등으로 비밀을 읽거나 바꿀 수 있으므로 `.env` 직접 읽기 금지는 배포 계정 침해에 대한 격리 경계가 아니다. CI workflow/배포 키 탈취는 호스트 전체 침해로 처리한다. [Docker 보안 설명](https://docs.docker.com/engine/security/).

호스트의 operator 셸에서 검증한다. 배포 사용자는 forwarding/PTY가 금지되고 운영자는 로컬 터널을 사용한다. SSH/Nginx 설정 오류이면 reload하지 않는다.

```bash
sudo sshd -t
sudo sshd -T -C user=danditon-deploy,host=localhost,addr=198.51.100.1
sudo visudo -cf /etc/sudoers.d/danditon-deploy
sudo -l -U danditon-deploy
sudo fail2ban-client status sshd
swapon --show --bytes
sudo stat -c '%U %G %a' /etc/danditon/.env
sudo docker compose version
sudo nginx -t
```

새 operator/deploy 접속을 검증한 뒤 초기 전송 경로만 삭제한다. 최초 배포 전 DB를 임의로 먼저 시작하지 않는다. 첫 공개 이미지·완전한 release 디렉터리가 준비되면 배포 스크립트가 `up -d --wait db`로 초기화한다. 이미 volume/container가 있는데 기록이 없으면 자동 재초기화 대신 운영자 확인이 필요하다.

## 릴리스와 일상 확인

[release 계약](../ops/deploy/README.md)의 `/opt/danditon/releases/<sha>`에는 `deploy.sh`, `compose.prod.yaml`, `image-digest.txt`, `SHA256SUMS`가 있고 실행 시 `image.env`가 추가된다. native SCP로 SHA별 incoming 디렉터리에 완전히 옮긴 후 원자적으로 확정한다. 기존 SHA의 바이트/digest가 다르면 거부한다. 실행 형태는 `bash /opt/danditon/releases/$SHA/deploy.sh $SHA` 한 개의 검증된 전체 SHA 인자다.

Actions concurrency `danditon-production`은 `cancel-in-progress: false`, 호스트는 `flock`으로 직렬화하고 최신 develop을 재확인한다. 일반 배포는 DB healthy를 확인하고 `up -d --no-deps app`만 실행한다. `current.json`/`previous.json`은 SHA, digest-qualified image, manifest, release directory, env 경로를 보존한다. Compose project는 항상 `danditon`이다. MySQL은 `danditon-mysql-data`, 업로드 사진은 `danditon-uploads-data` named volume에 저장되어 앱 컨테이너 재생성에도 유지된다. `/app/uploads`는 이미지의 비루트 UID/GID `10001:10001`이 쓸 수 있어야 한다.

외부에서는 아래 첫 명령, 나머지는 **호스트의 operator 셸**에서 실행한다. `.directory`가 메타데이터의 실제 키이며 `CURRENT_COMPOSE`를 추측하지 않는다.

```bash
curl -i --max-time 10 "http://$EC2_HOST/health"
```

```bash
CURRENT_COMPOSE=$(sudo jq -er '.directory + "/compose.prod.yaml"' /opt/danditon/current.json)
sudo test -f "$CURRENT_COMPOSE"
sudo docker compose -p danditon --env-file /etc/danditon/.env --env-file "$(dirname "$CURRENT_COMPOSE")/image.env" -f "$CURRENT_COMPOSE" ps
sudo docker compose -p danditon --env-file /etc/danditon/.env --env-file "$(dirname "$CURRENT_COMPOSE")/image.env" -f "$CURRENT_COMPOSE" logs --no-color --since 15m --tail 100 app db
sudo jq '{sha,image,directory,manifest,env,image_env}' /opt/danditon/current.json
APP_ID=$(sudo docker compose -p danditon --env-file /etc/danditon/.env --env-file "$(dirname "$CURRENT_COMPOSE")/image.env" -f "$CURRENT_COMPOSE" ps -q app)
sudo docker inspect --format '{{.Image}} {{.State.Status}}' "$APP_ID"
sudo docker image inspect --format '{{.Id}} {{json .RepoDigests}}' "$(sudo jq -r .image /opt/danditon/current.json)"
curl -i --max-time 10 http://127.0.0.1:8080/actuator/health/readiness
sudo journalctl -u nginx -u ssh -u fail2ban --since '15 minutes ago' --no-pager -n 100
sudo docker volume inspect --format '{{.Name}} {{.Driver}}' danditon-mysql-data danditon-uploads-data
```

정상은 HTTP 200과 `{"status":"UP"}`, DB healthy, 앱 running, container Image ID와 기록된 이미지 Id 일치다. `/health`는 Nginx가 정확히 readiness로 매핑하고 다른 Actuator 경로는 외부에 제공하지 않는다. DB 단절 시 readiness 503이므로 앱 프로세스가 살아 있다는 사실만으로 정상 처리하지 않는다. `docker compose config` 렌더링, 전체 `docker inspect`, `env`/`printenv`, `.env` cat, `set -x`를 운영 로그 수집에 사용하지 않는다. 앱 로그에도 개인정보가 있을 수 있으므로 제한된 범위만 보고 외부 공유 전 가린다.

## HTTP 사용 제한과 SSH 터널

public HTTP는 상태 확인과 비민감 데모만 허용한다. 실제 로그인 비밀번호·token·개인정보를 보내지 않는다. 운영 단말에서 다음 터널을 전경 실행하고 별도 터미널에서 로컬 주소를 사용한다. 사용 후 Ctrl-C로 종료하고 8080 listener가 사라졌는지 확인한다. 배포 키는 터널에 사용하지 않는다.

```bash
ssh -N -L 127.0.0.1:8080:127.0.0.1:8080 "${OPERATOR_OPTS[@]}" \
  -o ExitOnForwardFailure=yes "ubuntu@$EC2_HOST"
```

```bash
curl -i --max-time 10 http://127.0.0.1:8080/actuator/health/readiness
```

로컬 8080이 사용 중이면 다른 업무 프로세스를 종료하지 말고 비어 있는 로컬 포트로 바꿔 장부에 기록한다. 이 터널은 Nginx를 거치지 않으므로 readiness 경로가 `/health`가 아닌 `/actuator/health/readiness`다. [OpenSSH forwarding](https://man.openbsd.org/ssh).

## 실패·롤백·중단 복구

`STALE_RELEASE`, `RELEASE_COLLISION`, `DIGEST_MISMATCH`, `DATABASE_NOT_HEALTHY`, `OPERATOR_RECONCILIATION_REQUIRED`는 실패다. 파일/digest를 맞추려고 이전 release를 덮어쓰지 않는다. DB가 아프면 원인을 고치고 정상 상태를 먼저 확인한다. 실패 실행의 Actions 상태와 종료 코드를 보존한다.

앱 활성화 후 실패하면 스크립트는 `rollback.json`에 저장한 직전 앱 이미지/Compose/env로 되돌려 readiness와 메타데이터를 확인한다. `ROLLED_BACK`이어도 실패 배포는 nonzero/red다. 최초 배포 실패는 앱을 멈추고 DB/transaction을 남긴다. HUP/INT/TERM은 복구를 시도하지만 SIGKILL·전원 손실·복구 자체 실패까지 자동 해결하지 않는다. local/prod는 명시적으로 `ddl-auto: update`를 사용하므로 애플리케이션 롤백이 이미 적용된 스키마 변경을 되돌리지는 않는다. 장애 대응 중 이 정책을 임의 변경하지 않는다.

`transaction.json`이 남으면 다음 배포를 중지하고 운영자가 아래 순서로 복구한다.

1. Actions 활성 실행과 호스트 프로세스를 확인한다. 진행 중인 배포를 단지 정리하려고 취소하지 않는다. operator root 셸에서 `exec 9>/opt/danditon/deploy.lock; flock -w 60 9`로 같은 lock을 얻는다. 실패하면 작업하지 않는다.
2. root 셸에서 `jq '{sha,image,directory,manifest,env,image_env}' /opt/danditon/{transaction,current,previous,rollback}.json`으로 존재하는 기록만 확인한다. 누락 기록은 초기 실패 가능성을 뜻한다. `ps`, 제한된 로그, DB health, 실제 app Image ID와 readiness를 함께 대조한다.
3. 복구 대상으로 선택한 정상 기록의 `.directory`가 `/opt/danditon/releases/<40자리 SHA>`인지 확인하고 해당 디렉터리에서 `sha256sum --check --strict SHA256SUMS`를 실행한다. `image.env`의 단일 `APP_IMAGE`가 기록된 tag+digest와 같아야 한다. 최초 실패 또는 설명되지 않는 DB volume은 자동 스크립트 재실행으로 해결하지 않는다. 데이터 소유자와 초기화 상태/백업을 확인한다.
4. 직전 정상 release를 확인한 경우에만 그 Compose/env로 `docker compose -p danditon --env-file /etc/danditon/.env --env-file "$RECOVERY_DIR/image.env" -f "$RECOVERY_DIR/compose.prod.yaml" up -d --no-deps app`를 실행한다. `RECOVERY_DIR`는 위에서 검증한 절대경로다. DB healthy, readiness 200/UP, 실제 image ID 일치를 다시 증명한다.
5. 검증한 복구 기록을 `current.json.tmp`에 복사하고 `mv -f`로 `current.json`을 원자 교체한다. 이전 파일과 transaction은 비공개 사고 기록에 보존한다. 파일/실행 상태가 일치함을 확인한 후에만 `transaction.json`을 제거하고 lock 셸을 종료한다. marker를 지워 재시도하는 것으로 복구를 대신하지 않는다. 정상 복구 대상을 증명하지 못하면 멈추고 격리 복구/새 호스트 재구축을 선택한다.

정상 운영 중 수동 되돌리기는 새 revert PR → 팀원 승인 → 최신 develop 배포를 우선한다. 오래된 SHA를 deploy.sh에 넣으면 freshness gate가 거부한다. 긴급 수동 복구도 위 lock/기록/검증 절차를 따라야 한다.

초기 암호화 백업과 격리 복구가 성공한 뒤에는 운영자만 실제 앱 롤백 시험을 한 번 수행한다. 어댑터는 현재 release에서 checksum을 검증한 운영 `deploy.sh`를 source하여 동일한 `compose`/`ready`/`recover` 경로를 사용한다. 현재 digest의 앱 컨테이너만 즉시 종료되는 entrypoint로 교체하고 DB 컨테이너·volume은 건드리지 않는다. 정상 복구되어도 의도한 실패 시험이므로 종료 코드는 42다.

```bash
CURRENT_SHA=$(sudo jq -er '.sha' /opt/danditon/current.json)
set +e
sudo bash ops/tests/live-rollback.sh --current "$CURRENT_SHA" --case unhealthy-app 2>&1 | tee /tmp/danditon-live-rollback.log
ROLLBACK_QA_STATUS=${PIPESTATUS[0]}
set -e
[[ $ROLLBACK_QA_STATUS == 42 ]]
grep -Fxq EXPECTED_UNHEALTHY_CANDIDATE /tmp/danditon-live-rollback.log
grep -Fxq ROLLED_BACK /tmp/danditon-live-rollback.log
grep -Fq LIVE_ROLLBACK_VERIFIED /tmp/danditon-live-rollback.log
curl -fsS http://127.0.0.1:8080/actuator/health/readiness | jq -e '.status == "UP"'
curl -fsS http://43.201.189.37/health | jq -e '.status == "UP"'
rm -- /tmp/danditon-live-rollback.log
```

배포·백업·다른 장애 시험과 겹치지 않는다. `LIVE_ROLLBACK_FAILED OPERATOR_RECONCILIATION_REQUIRED`가 나오거나 연결이 끊기면 재시도하거나 marker/QA override를 지우지 말고 위 중단 복구 절차로 실제 앱/DB/기록을 대조한다. 실패 시 어댑터는 조사에 필요한 transaction과 QA 파일을 보존한다. 이 어댑터는 `ops/tests`에만 있고 정상 `deploy.sh`에는 시험 인자가 없다.

## 키 교체·폐기와 SSH 분실

운영 키와 배포 키는 공유하지 않는다. 예정된 교체 때 새 공개키를 기존 키와 잠시 함께 `authorized_keys`에 설치하고 소유자/700 디렉터리/600 파일 권한을 유지한다. `danditon-deploy`의 새 줄에는 기존 `restrict` 옵션을 유지한다. 새 키로 별도 세션 인증을 확인한 다음 GitHub `EC2_SSH_PRIVATE_KEY`를 교체하고 최신 develop 배포를 확인한다. 그 뒤 옛 공개키 줄과 옛 private key 사본을 폐기한다. operator는 새 operator 세션이 sudo와 터널까지 동작하는 것을 확인한 후 옛 키를 제거한다. bootstrap 재실행은 배포 authorized_keys를 제공된 한 키로 교체하므로 이중 키 교체 중에는 사용하지 않는다.

키 침해는 즉시 workflow 비활성화/환경 secret 폐기, 해당 authorized_keys 제거와 기존 세션 조사·종료, 호스트 격리로 대응한다. root-equivalent deploy 키가 유출되었다면 호스트·DB 비밀도 노출된 것으로 보고 검증된 백업에서 새 호스트를 만들고 DB 자격증명을 함께 교체한다. host `.env`만 바꾸면 기존 MySQL 계정 암호는 바뀌지 않으므로 정합성을 맞추는 별도 정비가 필요하다. EC2 콘솔의 key pair 삭제는 이미 설치된 authorized_keys를 회수하지 않는다.

SSH를 잃었으면 EIP association, instance status checks, SG 22 규칙, 키 경로/권한, 지문 변경 여부부터 콘솔과 로컬에서 확인한다. password/root SSH를 켜거나 host checking을 끄지 않는다. 살아 있는 승인 operator 세션이 있으면 fail2ban/sshd 로그와 설정을 고치고 `sshd -t` 후 reload한다. 모든 세션을 잃었으면 중단 비용을 알리고, AWS의 [분실 키 복구](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/TroubleshootingInstancesConnecting.html#replacing-lost-key-pair) 절차에 따라 소유자 승인을 받은 별도 구조 인스턴스·루트 EBS attach 방식으로 공개키/설정을 수리하거나 검증된 백업으로 재구축한다. 이는 추가 자원·권한·중단이 필요한 비상 절차이며 현재 구성에 SSM/구조 호스트가 있다고 가정하지 않는다. 원본 디스크를 삭제하지 말고 복구 후 새 호스트 지문을 다시 검증하고 임시 구조 자원을 정리한다.

## 데이터·암호화 백업·격리 복구

MySQL 8.4 이미지는 [Compose](../compose.prod.yaml)의 digest로 고정한다. `danditon-mysql-data` named volume은 앱 교체/재부팅에도 남지만 같은 EC2 루트 EBS 위에 있어 호스트 손실에 대한 백업이 아니다. root volume의 Delete on termination은 Yes다. 일상 배포에서 DB를 recreate하지 않는다. `docker compose down -v`, volume prune, broad system prune을 사용하지 않는다.

백업은 일일 timer(03:00 UTC, 최대 15분 지연)가 SQL dump를 평문 파일 없이 age 암호화로 직접 흘려보낸다. 암호문과 `.sha256` 파일을 만들며 오래된 백업은 자동 삭제하지 않는다. age private identity는 **운영 단말에만** 두고 public recipient만 호스트에 설치한다. 초기 로컬 설정에서 `AGE_IDENTITY`는 새 저장소 밖 파일, `AGE_RECIPIENT`는 공개 수신자 파일 경로다.

```bash
umask 077
age-keygen -o "$AGE_IDENTITY"
age-keygen -y "$AGE_IDENTITY" > "$AGE_RECIPIENT"
scp "${OPERATOR_OPTS[@]}" "$AGE_RECIPIENT" "ubuntu@$EC2_HOST:/home/ubuntu/danditon-backup.recipient"
ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
  'sudo install -o root -g root -m 600 /home/ubuntu/danditon-backup.recipient /etc/danditon/backup.recipient && rm /home/ubuntu/danditon-backup.recipient'
```

호스트에서 timer 및 백업 산출물을 확인한다. 파일 목록/크기/시각만 조회하며 내용을 출력하지 않는다.

```bash
sudo systemctl start danditon-backup.service
sudo systemctl status danditon-backup.timer --no-pager
sudo systemctl list-timers danditon-backup.timer --no-pager
sudo journalctl -u danditon-backup.service --since '2 days ago' --no-pager -n 50
sudo find /var/backups/danditon -maxdepth 1 -type f -name '*.age' -printf '%f %s bytes %TY-%Tm-%Td %TH:%TM\n'
```

**외부 회수는 자동이 아니다.** 정 운영자가 매일 백업 완료 후 24시간 이내 암호문을 단말로 당겨받고, 부 운영자가 미수행 시 인계한다. 매일 최근 성공 시각/크기/원격·로컬 SHA256 일치를 기록한다. 24시간 넘게 외부 사본이 없으면 대응하고, 복구 가능 시점은 마지막 검증된 외부 백업까지다. 월 1회 및 DB/백업 변경 후 격리 복구를 하고 최근 검증일을 장부에 남긴다. 최소 최근 7개 일일 외부 사본을 보관하며 실제 보존/삭제 정책과 추가 암호화 저장소 비용은 소유자가 관리한다. 서버 파일 생성만으로 외부 백업 성공이라 부르지 않는다.

`BACKUP_NAME`은 위 목록에서 선택한 파일 basename, `OFFHOST_DIR`은 운영자 전용 로컬 디렉터리다. 다음은 로컬 Bash에서 실행한다. 공백/경로 탈출을 거부하고 실패 시 부분 파일만 지운다.

```bash
[[ $BACKUP_NAME =~ ^[A-Za-z0-9._-]+\.age$ && $BACKUP_NAME != .* ]] || exit 1
install -d -m 700 "$OFFHOST_DIR"
if ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
  "sudo cat /var/backups/danditon/$BACKUP_NAME" > "$OFFHOST_DIR/$BACKUP_NAME.part"; then
  chmod 600 "$OFFHOST_DIR/$BACKUP_NAME.part"
  REMOTE_HASH=$(ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
    "sudo sha256sum /var/backups/danditon/$BACKUP_NAME" | awk '{print $1}')
  LOCAL_HASH=$(sha256sum "$OFFHOST_DIR/$BACKUP_NAME.part" | awk '{print $1}')
  [[ $REMOTE_HASH =~ ^[0-9a-f]{64}$ && $REMOTE_HASH == "$LOCAL_HASH" ]] || {
    rm -f -- "$OFFHOST_DIR/$BACKUP_NAME.part"; exit 1;
  }
  mv "$OFFHOST_DIR/$BACKUP_NAME.part" "$OFFHOST_DIR/$BACKUP_NAME"
  printf '%s  %s\n' "$LOCAL_HASH" "$BACKUP_NAME" > "$OFFHOST_DIR/$BACKUP_NAME.sha256"
else
  rm -f -- "$OFFHOST_DIR/$BACKUP_NAME.part"
  exit 1
fi
```

원격 cat 대상은 age **암호문만**이다. 복호화 키를 EC2로 복사하지 않는다. 로컬에서 다음을 실행하면 복호화된 SQL이 검증된 operator SSH를 통해 [복구 helper](../ops/backup/restore.sh)의 stdin으로만 들어간다. `RESTORE_TARGET`은 매번 새 이름이며 운영 DB/volume을 받는 옵션은 없다. 원격 helper는 network-none, 768 MiB 제한, 512 MiB tmpfs의 별도 MySQL 컨테이너를 만들고 종료 시 지운다. 실제 운영 호스트에서 실행하면 추가 메모리/CPU를 쓰므로 배포·DB 장애 시험과 겹치지 않는 정비 시간에 수행한다. 덤프가 512 MiB tmpfs를 넘으면 실패로 기록하고 별도 용량의 격리 복구 환경을 검토한다.

```bash
(
  set -euo pipefail
  cd "$OFFHOST_DIR"
  sha256sum --check --strict "$BACKUP_NAME.sha256"
  RESTORE_TARGET="danditon-restore-qa-$(date -u +%Y%m%d%H%M%S)"
  age --decrypt -i "$AGE_IDENTITY" "$BACKUP_NAME" |
    ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
      "sudo /usr/local/sbin/danditon-restore $RESTORE_TARGET"
  ssh "${OPERATOR_OPTS[@]}" "ubuntu@$EC2_HOST" \
    "sudo docker ps -aq --filter name=^/$RESTORE_TARGET\$"
)
```

PASS는 pipeline exit 0, 기대한 schema table count, `RESTORE_VERIFIED_ISOLATED`, 마지막 컨테이너 조회 출력 없음이 함께 성립할 때다. helper의 표 개수만으로 업무 데이터 의미가 검증되는 것은 아니다. 초기 복구 검증에는 알려진 fixture row/count를 넣은 별도 시험 데이터로 dump/복구 일치를 증명하고, 운영 인계에는 DB의 기대 schema/count를 별도 장부와 비교한다. 실패·연결 중단 시 같은 target의 실제 잔존 여부를 operator가 확인하고 `danditon.restore-qa=true` 라벨/이름을 대조한 **그 컨테이너만** 제거한다. plaintext SQL 파일을 저장하거나 운영 volume에 복원하지 않는다.

## 디스크·비용·종료

매일 호스트에서 `df -h / /var/lib/docker /var/backups/danditon`, `df -i /`, `sudo docker system df`, `sudo journalctl --disk-usage`, `free -m`, `swapon --show`를 확인한다. 20 GiB에는 image/DB/log/backup/swap이 함께 들어간다. 여유 공간이 부족하면 새 배포를 멈추고 어떤 경로가 늘었는지 확인한다. 성공한 배포는 lock을 보유한 상태에서 `current.json`·`previous.json`·`transaction.json`이 참조하거나 기존 컨테이너가 사용하는 이미지 ID를 보존하고, 그 외 `ghcr.io/danditon/backend:<40자리 SHA>` 참조만 자동 삭제한다. 이 정리는 배포 시에만 실행되며 실패해도 정상 배포를 롤백하지 않고 `IMAGE_CLEANUP_WARNING`을 남긴다. 검증된 외부 사본이 있는 **개별 오래된 암호문**만 별도로 검토해 지운다. `docker image prune`이나 broad system prune으로 공간을 확보하지 않는다. 컨테이너 로그는 host Docker의 `json-file` 드라이버에서 파일당 10 MiB, 최대 3개로 제한한다. 기존 `/etc/docker/daemon.json`이 이 설정과 다르면 bootstrap은 덮어쓰지 않고 중단하므로 운영자가 병합·검증한 뒤 재실행한다. DB 증가가 원인이면 별도 용량 계획이 필요하다.

2026-11-04 서울 조사 기준 730시간 월 모델은 t3.small `730 × $0.026 = $18.98`, gp3 20 GiB `20 × $0.0912 = $1.824`, EIP 1개 `730 × $0.005 = $3.65`, 합계 **약 $24.45/월**이다. 이는 고정 자원 기준이며 상한이 아니다. [EC2 요금](https://aws.amazon.com/ec2/pricing/on-demand/), [EBS 지역 요금](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json), [IPv4 요금](https://aws.amazon.com/vpc/pricing/)을 생성/인계 때 재확인한다. 사용 중/미연결 EIP 모두 과금되고 stop으로 EBS/IP 비용이 없어지지 않는다.

전송 무료 잔여량을 소진했다면 서울 첫 유료 구간 `$0.126/GB` 기준 백업 pull 10 GB는 약 $1.26가 더해진다. [전송 요금](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/datatransfer/USD/current/datatransfer.json)의 현재 지역·구간과 계정 공유 무료량을 확인한다. 앱 전송, 스냅샷, GitHub Actions/Packages, 로컬 백업 매체, KMS 요청, 세금/환율도 별도다. Standard credit의 성능 제한과 Unlimited의 추가 credit 과금을 혼동하지 않는다. 예산 알림이 있어도 hard spending cap이 아니다.

**소유자가 서비스 종료를 지시한 때만** 다음 순서로 진행한다. 문서 검증을 위해 살아 있는 endpoint/data를 삭제하지 않는다.

1. 최근 암호화 백업을 외부 회수하고 격리 복구를 확인한다. 보존 책임자·기간을 기록한다.
2. 새 배포를 막고 진행 중인 작업 종료를 확인한다. workflow 비활성화, GitHub 환경의 deploy secret 폐기, host authorized_keys에서 deploy 키를 폐기한다.
3. 장부와 태그로 task instance ID를 확인하여 종료한다. 루트 EBS Delete on termination 여부를 다시 확인한다. 기존 다른 인스턴스나 네트워크를 건드리지 않는다.
4. EIP의 association을 확인해 필요한 연결 해제를 거친 뒤 **Release Elastic IP address**로 allocation을 반납한다. 미연결 상태는 반납이 아니다.
5. EC2 Volumes와 Elastic IPs에서 해당 태그/기록 ID를 다시 검색한다. 남은 task 소유 EBS는 백업/보존 결정을 확인한 후 삭제하거나, 보존 사유·소유자·월 비용을 장부에 명시한다. task SG와 imported key pair를 의존성 해제 후 삭제한다. 기본 VPC/subnet/route/IGW는 유지한다.
6. instance terminated, EIP allocation 없음, 불필요한 EBS 없음, SG/key 정리 및 비용/백업/패키지 보존 여부를 대조한다. package/release와 외부 백업 삭제는 별도 소유자 결정이다. **미연결 EIP 또는 retained volume이 하나라도 남으면 비용 종료 완료로 표시하지 않는다.** 의도적 보존도 잔여 비용과 다음 확인일을 기록한다.

읽기 전용 종료 리허설은 실제 콘솔 inventory를 비공개 장부와 대조한다. 예를 들어 instance=terminated, EIP=unassociated, EBS=available이면 결과는 `RESIDUAL_COST`, 완료=false다. 지문이 없는 SSH 초기 설정 역시 `MISSING_AUTHORITATIVE_HOST_FINGERPRINT`로 접속 전 중단한다. 이 두 실패 조건을 제거 없이 보고할 수 있어야 운영 인계가 완료된다.
