# Lightsail 운영 및 Spring Blue-Green CD

Issue #519에서 시작한 Lightsail 단일 인스턴스 구성과 Spring Blue-Green CD의 운영 기준이다. `nginx`만 호스트의 80/443 포트를 공개하고, Spring의 8080과 PostgreSQL의 5432는 외부 포트로 공개하지 않는다. Spring과 DB 사이에는 별도의 내부 네트워크를 둔다. FastAPI는 이 Compose에 추가하지 않고 별도 Lightsail과 독립된 CD로 운영한다.

## 서버 준비와 환경 파일

1. Ubuntu 24.04에 Docker Engine과 Compose plugin을 설치한다.
2. 저장소를 `/opt/onmaru/repo`에 clone하고 `infra/lightsail` 디렉터리로 이동한다. 자동 배포 스크립트는 이 고정 경로와 `ubuntu` 소유 checkout을 검증한다.
3. `.env.example`을 `.env`로 복사한다. 실제 GHCR image digest, DNS 검증용 이메일, DB 계정 비밀번호와 모든 Spring secret/API key를 채우고 권한을 `chmod 600 .env`로 제한한다. DB 비밀번호와 signing/operator secret은 `openssl rand -hex 32` 등으로 각각 새로 생성한다. 실제 `.env`는 Git에 추가하지 않는다.
4. `.env.example`의 placeholder를 남겨둔 상태로 Compose를 실행하지 않는다. Spring은 현재 `OnMaruSecretProperties`에 선언된 8개의 `*_CURRENT` secret을 모두 요구한다.
5. GHCR package가 private이면 `read:packages` 권한만 가진 GitHub token으로 로그인한 다음 이미지를 pull한다. 최초 수동 배포 이후 자동 CD는 mutable `latest`나 SHA tag 대신 workflow가 만든 immutable digest를 사용한다.

## 데이터 이전 전 시작 순서

초기 database volume에 Spring을 먼저 실행하면 empty schema에 Flyway baseline이 적용된다. Neon 데이터를 복원할 계획이라면 **Spring을 올리기 전에** 빈 PostGIS만 시작하고 dump를 복원한다. 이미 Spring을 시작한 volume에는 `docker compose down -v`를 사용하지 않는다.

```bash
docker compose --env-file .env config --quiet
docker compose --env-file .env --profile legacy pull spring-api
docker compose --env-file .env up -d postgres
docker compose --env-file .env ps
```

PostgreSQL 첫 초기화 시 `postgres/init/01-create-login-roles.sh`가 PostGIS extension, baseline migration에서 기대하는 `NOLOGIN` role groups, 각기 분리된 runtime/migration/readonly/backup login을 만든다. Flyway migration 로그인은 `onmaru_migration` 권한 그룹의 멤버이고 runtime login에는 DDL 권한을 주지 않는다. 초기화 SQL은 빈 named volume을 만들 때만 실행되므로 비밀번호 변경 후 기존 volume에 재실행되지 않는다.

Render 앱이 실제 사용하는 Neon project/branch와 직접 연결 endpoint를 확인한 뒤, 해당 Neon 원본에서 호환되는 버전의 `pg_dump -Fc`로 custom-format dump를 만든다. 별도 보관 위치에 보존하고 SSH/SFTP로 서버의 `/opt/onmaru/source.dump`에 안전하게 전송한다. 아직 Spring을 시작하기 전에 아래 helper로 빈 database에 복원한다. 이 helper는 기존 application schema가 보이면 거부하고, migration login을 임시로 database CREATE 권한을 줘서 복원 object owner로 사용한다. 복원이 끝나면 runtime/readonly table grants와 migration default privileges를 적용하고 임시 database CREATE 권한을 회수한다. 원본 dump는 AWS 복구 확인 전 삭제하지 않는다.

```bash
./restore-source-db.sh /opt/onmaru/source.dump
```

5432를 Lightsail 방화벽에 추가하지 않는다. 복원이 끝나면 Flyway history, schema/table과 핵심 row count, PostGIS extension/공간 조회를 확인한 뒤 앱을 시작한다.

## HTTP에서 인증서 발급, 이후 HTTPS 전환

먼저 DNS `api` A record가 Static IP를 가리키고, Lightsail firewall에서 80/443이 열려 있어야 한다. `.env`의 `NGINX_SITE_CONFIG=./nginx/bootstrap.conf`를 유지한 상태로 HTTP challenge 전용 Nginx와 앱을 올린다. bootstrap 설정은 ACME challenge만 제공하고 나머지 URL은 503을 반환한다.

```bash
docker compose --env-file .env --profile legacy up -d spring-api nginx
docker compose --env-file .env --profile tls run --rm certbot
```

인증서 발급이 성공한 뒤에만 `.env`의 `NGINX_SITE_CONFIG`를 `./nginx/site.conf`로 바꾸고 Nginx를 재생성한다.

```bash
docker compose --env-file .env --profile legacy up -d --force-recreate nginx
docker compose --env-file .env ps
```

`site.conf`는 HTTPS를 Nginx에서 종료해 runtime upstream이 선택한 Spring 슬롯으로 전달하고, `/actuator/**`는 외부 응답에서 숨긴다. 모든 proxy 응답에서 buffering을 꺼 SSE 응답을 버퍼링하지 않으며 read timeout은 130초다. 인증서 갱신 timer를 설치한다.

```bash
sudo install -m 0644 systemd/onmaru-certbot-renew.service /etc/systemd/system/
sudo install -m 0644 systemd/onmaru-certbot-renew.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now onmaru-certbot-renew.timer
sudo systemctl list-timers onmaru-certbot-renew.timer
```

## DB backup과 관측

`backup-postgres.sh`는 PostgreSQL의 별도 backup login으로 custom-format dump를 만들고 파일 권한을 제한한다. 같은 서버 디스크만으로는 재해 복구 backup이 되지 않으므로 생성 파일을 암호화해 다른 저장소로 복사하고 실제 restore를 확인한다. Automatic Snapshot은 DB 논리 backup을 대신하지 않는다.

```bash
./backup-postgres.sh
docker compose --env-file .env logs --tail=100 spring-blue spring-green nginx postgres
```

## 1GB에서 짧게 겹치는 Blue-Green 모델

운영 Lightsail은 1GB RAM에서 PostgreSQL/PostGIS, Nginx와 Spring을 함께 실행한다. 두 Spring 슬롯을 상시 실행하는 구조가 아니다. 평소에는 Blue 또는 Green 하나만 실행하고, 배포 중에만 비활성 슬롯을 384MB와 0.55 CPU로 기동한다. 전환이 끝나면 새 활성 슬롯을 448MB와 1.2 CPU로 올리고 이전 슬롯을 중지한다.

2026-10-04 운영 서버에서 현재 이미지로 비라우팅 후보를 시험했을 때 Green은 약 30초 만에 healthy가 됐고 약 212MB를 사용했다. 가용 메모리는 최소 약 156MB였으며 swap 사용량은 약 114MB 증가했다. 60초 유지 구간에 OOM은 없었고 기존 Blue health는 200, 내부 health latency는 약 21~37ms였다. 이는 무부하 기동 가능성을 확인한 제한된 rehearsal이며 실제 사용자 부하나 모든 신규 이미지의 안전성을 보장하지 않는다.

> swap은 RAM이 아니다. 사용 빈도가 낮은 memory page를 SSD로 내보내 OOM까지의 여유를 만드는 장치이며, JVM heap과 GC가 활발한 상태에서 지속적으로 사용하면 page fault와 I/O wait 때문에 지연이 크게 튈 수 있다. 현재 4GB swap은 배포 중 짧은 중첩을 위한 안전망으로만 사용하고, 상시 두 슬롯 운영 용량으로 계산하지 않는다.

배포 스크립트는 기동 직전 대비 swap 증가를 192MB로 제한하고 `MemAvailable`이 128MB 아래로 내려가면 후보를 중지한다. 후보가 OOMKilled되거나 health가 실패해도 전환하지 않는다. 같은 호스트의 온디맨드 스테이징이 실행 중이면 production 배포를 거부하므로, 운영 배포 전에는 반드시 스테이징을 중지해야 한다.

## Blue-Green 최초 전환

자동 배포 workflow는 **Repository Variable** `PRODUCTION_DEPLOY_ENABLED`가 문자열 `true`일 때만 실행된다. 이 값은 `production` environment variable이 아니다. job-level `if`는 runner와 environment가 준비되기 전에 평가되므로 environment scope에 두면 값이 비어 production job이 계속 skip될 수 있다. 최초 CD 변경이 `master`에 들어가는 순간 준비되지 않은 서버로 SSH하지 않도록 처음에는 repository variable을 만들지 않거나 `false`로 둔다.

먼저 CI 전용 Ed25519 키를 생성한다. 개인 관리자 키를 GitHub Actions에 재사용하지 않는다. private key는 GitHub `production` environment의 `PRODUCTION_DEPLOY_SSH_KEY` secret에 등록하고 public key만 서버 설치에 사용한다. `ssh-keyscan` 결과를 별도 관리자 연결에서 fingerprint와 대조한 뒤 `PRODUCTION_SSH_KNOWN_HOSTS` environment variable에 등록한다.

서버 checkout이 이 변경을 포함한 `master`에 도달한 뒤 아래 순서로 최초 전환을 준비한다.

```bash
cd /opt/onmaru/repo/infra/lightsail
sudo ./production/bootstrap-blue-green.sh
sudo ./production/install-deployer.sh /secure/path/onmaru-production-ci.pub
```

`bootstrap-blue-green.sh`는 기존 `spring-api`를 계속 active로 둔 채 runtime upstream directory를 Nginx에 mount하고 Nginx를 재생성한다. 내부 Spring health와 공개 CSRF smoke가 모두 성공해야 끝난다. 이후 GitHub repository와 `production` environment에 다음 값을 각 scope에 맞게 등록한다.

| Scope | 종류 | 이름 | 용도 |
| --- | --- | --- | --- |
| Repository | Variable | `PRODUCTION_DEPLOY_ENABLED=true` | 최초 bootstrap과 첫 수동 점검 완료 후 예약·명시적 운영 배포 활성화 |
| `production` environment | Secret | `PRODUCTION_DEPLOY_SSH_KEY` | 제한된 production deployer의 Ed25519 private key |
| `production` environment | Secret | `PRODUCTION_DEPLOY_WEBHOOK_URL` | 성공·실패·no-op·rollback 결과를 받을 Discord/Slack 호환 webhook |
| `production` environment | Variable | `PRODUCTION_SSH_KNOWN_HOSTS` | 별도 관리자 연결에서 fingerprint를 대조한 Lightsail SSH host key |

승인 reviewer는 요구하지 않는다. `master` push는 Spring image build·Trivy scan·migration gate까지만 수행하고 운영 트래픽은 바꾸지 않는다. FastAPI image 단계는 `develop`의 스테이징 실행에만 남겨 두며, 별도 Lightsail CD가 생기기 전에는 운영 경로에 포함하지 않는다. 실제 운영 배포는 아래 두 경우에만 같은 검증 단계를 다시 통과한 뒤 Spring digest와 master SHA를 제한된 SSH principal에 전달한다. SSH 사용자는 임의 shell을 열 수 없고 `deploy <master-sha> <digest> <actor>` 명령만 실행할 수 있다.

- 예약 배포: GitHub Actions cron `17 18 * * *`, 즉 매일 `03:17 KST`에 최신 `master`를 확인한다. GitHub Actions schedule은 정확한 시각을 보장하지 않으므로 실제 시작은 지연될 수 있다.
- 즉시 배포: GitHub Actions의 `workflow_dispatch`를 UI 또는 `gh workflow run deploy.yml --ref master -f deploy_production=true`로 명시적으로 요청한다. 프로젝트의 `skills/onmaru-production-deploy/SKILL.md`도 이 dispatch만 호출한다. 단, 사용자가 `develop` 승격까지 명시한 경우에는 저장소 Git Flow로 `release/*`와 `master` 승격을 완료한 뒤 같은 dispatch를 호출한다.

예약·수동 운영 실행은 image build보다 먼저 제한 SSH의 `status <master-sha>`를 호출한다. 같은 master SHA가 이미 배포됐고 checkout과 공개 health까지 일치하면 `deployed`를 반환하므로 image build·scan과 Blue-Green 중첩을 모두 건너뛴다. 상태가 다를 때만 immutable image를 build·scan하고 migration gate를 통과한다. `master` push 자체는 향후 배포할 artifact를 검증하기 위해 계속 build·scan하지만 트래픽은 바꾸지 않는다.

따라서 이 CD 코드를 처음 `master`에 넣는 release는 bootstrap release로 취급한다. 그 push 자체는 원래도 운영 배포 조건이 아니다. 서버 checkout을 해당 `master`로 fast-forward하고 위 bootstrap·key 설치·GitHub 설정을 마친 다음 `PRODUCTION_DEPLOY_ENABLED=true`로 바꾸고 **예약 실행을 기다리지 말고 첫 수동 dispatch를 관찰하며** 수행한다. workflow URL, build·scan, migration gate, 후보 memory/swap, Nginx switch, 공개 CSRF 200, webhook 수신, `/var/lib/onmaru/production-*` 상태를 확인한 뒤에야 예약 배포가 준비됐다고 본다.

## 실패 중 자동 복구와 성공 후 운영 rollback

배포 transaction 안에서 후보 health, 메모리, Nginx 검증, reload 또는 공개 smoke가 실패하면 스크립트가 upstream과 `.env`를 원래 값으로 복구하고 후보를 중지한다. 아직 성공을 확정하지 않은 작업의 원상복구이므로 별도의 운영자 입력이 필요하지 않다.

반대로 성공 처리 뒤 기능 오류가 발견되면 GitHub Actions에서 `rollback_production=true`만 선택해 수동 rollback한다. `deploy_production`과 동시에 선택할 수 없다. rollback은 마지막 배포가 남긴 이전 slot을 낮은 cgroup 한도로 다시 시작하고 health·메모리·swap을 검증한 후 Nginx를 전환한다. 첫 Blue-Green 전환 직전의 legacy `spring-api`도 이전 slot으로 기록되므로 첫 배포 직후 되돌리기도 같은 절차를 사용한다. 다만 DB migration은 역적용하지 않으므로 이전 image가 현재 schema와 호환되는 expand/contract 배포만 이 경로를 사용할 수 있다.

성공한 rollback은 거부한 SHA를 `/var/lib/onmaru/production-deploy-hold`에 기록한다. 그렇지 않으면 다음 `03:17 KST` schedule이 같은 최신 `master`를 다시 배포해 장애를 재현할 수 있다. hold와 같은 SHA의 preflight는 실패 알림만 남기며 build도 시작하지 않는다. 수정 commit이 `master`에 들어오면 새 SHA는 정상 배포되고 hold가 제거된다. 같은 SHA를 꼭 재사용해야 한다면 원인을 확인하고 관리자 SSH에서 hold 파일을 명시적으로 제거해야 하며, 자동화 계정에는 이 권한을 주지 않는다.

배포와 rollback이 성공하면 dangling 상태이면서 168시간보다 오래된 Docker image만 정리한다. 직전 image는 중지된 이전 컨테이너가 참조하므로 prune 대상이 아니며 즉시 rollback 가능성을 보존한다. 디스크 정리 실패는 이미 성공한 트래픽 전환을 다시 뒤집지 않고 warning과 webhook 결과로 관찰한다.

## master 병합 뒤 전환 순서

배포기는 다음 순서를 fail-closed로 수행한다.

1. production checkout이 clean한 `master`인지, 요청 SHA가 최신 `origin/master`인지 확인한다.
2. 스테이징 컨테이너가 모두 중지됐는지 확인하고 배포 lock을 획득한다.
3. GHCR의 immutable digest를 먼저 pull하고 `.env`의 이미지 참조를 원자적으로 변경한다.
4. 비활성 Spring 슬롯을 384MB로 기동하고 최대 120초 동안 health, OOM, 가용 RAM과 swap 증가량을 확인한다.
5. Nginx upstream 파일을 원자적으로 바꾸고 `nginx -t`를 통과한 뒤 reload한다.
6. 공개 `/auth/csrf` smoke를 확인하고 SSE 최대 연결 시간보다 긴 70초 동안 기존 연결을 drain한다.
7. 활성 슬롯 상태를 원자적으로 기록하고 이전 슬롯을 중지한 뒤 새 슬롯 한도를 정상 운영 값으로 올린다.

후보 기동, Nginx 검증, reload 또는 공개 smoke 중 하나라도 실패하면 upstream과 `.env`를 이전 값으로 되돌리고 후보 슬롯을 중지한다. 이전 슬롯을 제거하지 않고 `stop` 상태로 남기므로 다음 배포에서 반대 슬롯으로 재사용할 수 있다.

## PostgreSQL migration은 두 애플리케이션 버전과 호환돼야 한다

Blue와 Green은 전환 구간에 같은 production PostgreSQL을 사용한다. 따라서 새 Green이 Flyway migration을 적용하는 순간에도 기존 Blue가 해당 schema로 요청을 처리할 수 있어야 한다. migration은 컬럼·테이블을 먼저 추가하는 expand, 새 코드 배포, 이전 코드가 사라진 다음 제거하는 contract 순서를 따른다. 같은 배포에서 기존 컬럼 삭제·rename·타입 축소처럼 이전 Blue를 깨는 변경을 수행하면 Blue-Green이라고 해도 무중단을 보장할 수 없다.

배포 후보에서는 TourAPI와 Odii의 application-ready 초기 동기화를 끈다. 정기 scheduler는 유지되지만 짧은 중첩 구간의 작업은 DB lease, due check와 상태 전이의 멱등성을 전제로 한다. 향후 두 replica를 상시 운영하게 되면 모든 scheduler에 명시적인 leader election 또는 분리 worker가 필요하다.

## 운영 확인과 중단 기준

```bash
sudo cat /var/lib/onmaru/production-active-slot
docker compose --env-file .env ps
docker stats --no-stream
free -m
sudo journalctl -k --since '30 minutes ago' | grep -Ei 'out of memory|oom-kill|killed process'
```

무부하 rehearsal 통과만으로 1GB가 충분하다고 결론내리지 않는다. 실제 배포에서 가용 메모리 하한, swap in/out, API p95, DB connection wait와 OOM 이력을 관측한다. 반복적인 memory pressure나 latency 목표 위반이 확인되면 swap을 더 늘리지 않고 2GB 플랜과 상시 Blue-Green 또는 인스턴스 분리를 검토한다.

같은 서버에서 필요할 때만 별도 Spring·PostGIS를 실행하는 스테이징 준비와 FE 개발자의 제한된 시작·중지 명령은 [staging/README.md](staging/README.md)를 참고한다.
