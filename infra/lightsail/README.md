# Lightsail 수동 배포 구성

Issue #519의 Lightsail 단일 인스턴스 배포를 위한 Compose 구성이다. `nginx`만 호스트의 80/443 포트를 공개하고, Spring의 8080과 PostgreSQL의 5432는 외부 포트로 공개하지 않는다. Spring과 DB 사이에는 별도의 내부 네트워크를 둔다.

## 서버 준비와 환경 파일

1. Ubuntu 24.04에 Docker Engine과 Compose plugin을 설치한다.
2. 저장소를 `/opt/onmaru` 등에 clone하고 `infra/lightsail` 디렉터리로 이동한다.
3. `.env.example`을 `.env`로 복사한다. 실제 GHCR 이미지 SHA, DNS 검증용 이메일, DB 계정 비밀번호와 모든 Spring secret/API key를 채우고 권한을 `chmod 600 .env`로 제한한다. DB 비밀번호와 signing/operator secret은 `openssl rand -hex 32` 등으로 각각 새로 생성한다. 실제 `.env`는 Git에 추가하지 않는다.
4. `.env.example`의 placeholder를 남겨둔 상태로 Compose를 실행하지 않는다. Spring은 현재 `OnMaruSecretProperties`에 선언된 8개의 `*_CURRENT` secret을 모두 요구한다.
5. GHCR package가 private이면 `read:packages` 권한만 가진 GitHub token으로 로그인한 다음 이미지를 pull한다. 이미지 tag에는 mutable `latest` 대신 master workflow에서 만들어진 commit SHA를 사용한다.

## 데이터 이전 전 시작 순서

초기 database volume에 Spring을 먼저 실행하면 empty schema에 Flyway baseline이 적용된다. 기존 운영 AWS PostgreSQL 데이터를 이 절차로 다시 복원하지 않는다. 빈 신규 환경의 bootstrap에만 적용하며, 현재 운영 DB에는 `docker compose down -v` 등 volume 초기화 명령을 실행하지 않는다.

```bash
docker compose --env-file .env config --quiet
docker compose --env-file .env pull spring-api
docker compose --env-file .env up -d postgres
docker compose --env-file .env ps
```

PostgreSQL 첫 초기화 시 `postgres/init/01-create-login-roles.sh`가 PostGIS extension, baseline migration에서 기대하는 `NOLOGIN` role groups, 각기 분리된 runtime/migration/readonly/backup login을 만든다. Flyway migration 로그인은 `onmaru_migration` 권한 그룹의 멤버이고 runtime login에는 DDL 권한을 주지 않는다. 초기화 SQL은 빈 named volume을 만들 때만 실행되므로 비밀번호 변경 후 기존 volume에 재실행되지 않는다.

과거 Neon→AWS 초기 이관 절차(Neon 원본 dump 생성 및 복원)는 deprecated다. 현재 운영 DB가 이미 AWS PostgreSQL이므로 이 단계를 재실행하지 않는다. 신규 빈 환경 복원은 [PostgreSQL restore runbook](../../docs/operations/runbooks/restore.md)을 따르고, 운영 복구 전에는 별도 승인을 받는다. 기존 dump나 production volume은 임의로 덮어쓰거나 삭제하지 않는다.

```bash
./restore-source-db.sh /opt/onmaru/source.dump
```

5432를 Lightsail 방화벽에 추가하지 않는다. 복원이 끝나면 Flyway history, schema/table과 핵심 row count, PostGIS extension/공간 조회를 확인한 뒤 앱을 시작한다.

## HTTP에서 인증서 발급, 이후 HTTPS 전환

먼저 DNS `api` A record가 Static IP를 가리키고, Lightsail firewall에서 80/443이 열려 있어야 한다. `.env`의 `NGINX_SITE_CONFIG=./nginx/bootstrap.conf`를 유지한 상태로 HTTP challenge 전용 Nginx와 앱을 올린다. bootstrap 설정은 ACME challenge만 제공하고 나머지 URL은 503을 반환한다.

```bash
docker compose --env-file .env up -d spring-api nginx
docker compose --env-file .env --profile tls run --rm certbot
```

인증서 발급이 성공한 뒤에만 `.env`의 `NGINX_SITE_CONFIG`를 `./nginx/site.conf`로 바꾸고 Nginx를 재생성한다.

```bash
docker compose --env-file .env up -d --force-recreate nginx
docker compose --env-file .env ps
```

`site.conf`는 HTTPS를 Nginx에서 종료해 `spring-api:8080`으로 전달하고, `/actuator/**`는 외부 응답에서 숨긴다. 모든 proxy 응답에서 buffering을 꺼 SSE 응답을 버퍼링하지 않으며 read timeout은 130초다. 인증서 갱신 timer를 설치한다.

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
docker compose --env-file .env logs --tail=100 spring-api nginx postgres
```

현재 구성은 수동 Compose 운영이다. `.github/workflows/deploy.yml`은 GHCR 이미지 생성과 staging smoke를 수행하지만 Lightsail에 자동 배포하지 않는다.

같은 서버에서 필요할 때만 별도 Spring·PostGIS를 실행하는 스테이징 준비와 FE 개발자의 제한된 시작·중지 명령은 [staging/README.md](staging/README.md)를 참고한다.
