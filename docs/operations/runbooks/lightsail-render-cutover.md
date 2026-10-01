# Lightsail 배포 및 Render 앱·Neon DB 전환 runbook

상태: **부분 실행 중**. Issue #519의 전환 절차와 수동 Compose 배포 구성이며 완료된 배포 기록은 아니다. 2026-10-01 서울 리전에 Ubuntu 24.04 LTS, $7 번들 Lightsail 인스턴스 `onmaru-prod-seoul`을 만들고 `onmaru-prod-seoul-ip` 고정 IP를 연결했다. 인스턴스 방화벽에는 HTTP 80, SSH 22, HTTPS 443(모든 IPv4)을 허용했다. 사용자가 브라우저 SSH 접속을 확인했고 Ubuntu 패키지 업데이트/재부팅 뒤 Docker Engine과 Compose plugin 설치 및 Docker 서비스 `active` 상태를 확인했다. `/opt/onmaru`도 만들고 `ubuntu` 소유권을 설정했다. SSH 22는 관리자 IP 제한이 아직 안 되어 있으므로 production 서비스 전 제한해야 한다. DNS, TLS 인증서, Compose 컨테이너, Spring, PostgreSQL/PostGIS 데이터는 아직 구성하지 않았다. 자동 스냅샷도 현재 비활성 상태다.

## 목표 구성

```text
브라우저 (Vercel: onmaru.site / www.onmaru.site)
  └─ HTTPS https://api.onmaru.site
       └─ Lightsail 고정 IP :443 → Nginx (TLS, reverse proxy)
                                  └─ Spring :8080
                                       └─ PostgreSQL 17 + PostGIS (private container network)
```

- 현재 Spring API는 Render Web Service에서 실행하고 PostgreSQL 원본은 Neon에 있다. AWS 검증 전까지 Render 앱과 Neon 원본 DB를 유지한다. 자동 라우팅이나 자동 장애 전환은 구성하지 않는다.
- 초기 AWS는 단일 Lightsail 인스턴스다. 호스트 장애 시 API와 DB가 함께 중단되는 단일 장애 지점이다.
- FastAPI용 별도 인스턴스와 로드밸런서는 이번 Spring 이전에 포함하지 않는다.
- Nginx는 Lightsail 내부에서 TLS를 종료하고 Spring으로 전달한다. Render proxy나 health-based failover가 아니다.
- FE 도메인 `onmaru.site`, `www.onmaru.site`의 현재 Vercel DNS 설정은 유지한다. `api` 서브도메인만 Lightsail로 연결한다.

## 0. AWS 계정과 월 비용 확인

1. AWS Billing의 **Credits**에서 보유한 $200 크레딧의 적용 범위와 만료일을 확인한다. Lightsail 비용을 크레딧이 상쇄한다고 가정하지 않는다.
2. Billing에서 예산 알림을 설정한다. 인스턴스, 고정 IP, snapshots, 추가 디스크, 데이터 전송의 실제 청구 항목을 확인한다.
3. Lightsail 인스턴스 번들을 고른다. 현재 가격표 기준 Linux $7은 1GB RAM/40GB SSD, $12는 2GB RAM/60GB SSD다. Spring, PostgreSQL/PostGIS, Nginx를 한 호스트에서 함께 구동하면 $7은 메모리 여유가 작다. $7을 선택하면 먼저 부하가 작은 테스트 환경으로 운용하고 OOM 여부를 확인한다. 메모리 부족이 반복되면 $12로 올린다.
4. 데이터센터 위치는 FE/API 사용자와 가까운 리전을 고른다. 선택 가능한 리전에 Seoul이 있으면 우선 검토하고, 인스턴스와 이후 AWS 리소스는 같은 리전에 둔다.

## 1. Lightsail 인스턴스 만들기

1. AWS 콘솔에서 **Lightsail → Instances → Create instance**를 연다.
2. Linux/Unix, OS Only, Ubuntu LTS 이미지를 고른다. WordPress 등의 앱 blueprint는 고르지 않는다.
3. 위에서 정한 $7 또는 $12 bundle을 선택하고 `onmaru-api`처럼 용도를 알아볼 이름으로 생성한다.
4. 인스턴스가 생성되면 **Networking → Create static IP**에서 고정 IPv4를 만들고 이 인스턴스에 연결한다. 동적 IP는 중지/재시작 때 바뀔 수 있다.
5. 인스턴스 방화벽은 다음처럼 설정한다.
   - TCP 22: 관리자 현재 공인 IP만 허용
   - TCP 80, 443: 인터넷 공개
   - TCP 8080, 5432: 외부 공개 규칙을 만들지 않는다.
   - IPv6를 쓴다면 IPv4 규칙과 별개로 IPv6 방화벽도 확인한다.
6. Automatic snapshot은 선택 항목이며 추가 비용을 예산에 반영한 뒤 활성화한다. 활성화 여부와 관계없이 PostgreSQL 논리 backup을 별도로 만들고, 실제 복구 drill로 데이터가 복원되는지 확인한다.

## 2. 서버 프로세스와 저장소 구성

1. Ubuntu 보안 업데이트를 적용하고 Docker Engine 및 Compose plugin을 설치한다.
2. 서버에서 Spring 이미지를 빌드하지 않는다. 저장소의 [Spring Dockerfile](../../../Dockerfile)로 만든 SHA tag GHCR 이미지를 pull한다. 현재 [Staging Deploy workflow](../../../.github/workflows/deploy.yml)는 이미지를 빌드하고 staging smoke를 실행하지만 Lightsail에는 자동 배포하지 않는다.
3. 수동 배포 구성은 [`infra/lightsail`](../../../infra/lightsail)을 사용한다. Compose는 `nginx`, `spring-api`, `postgres`를 실행하고 Nginx만 호스트 80/443을 공개한다. Spring 8080은 Docker 내부 네트워크, PostgreSQL 5432는 Spring과만 공유하는 internal network에 둔다.
4. PostgreSQL은 `postgis/postgis:17-3.5-alpine`이며 데이터는 named volume에 저장한다. 초기화 스크립트가 `V001` baseline이 기대하는 NOLOGIN role groups와 분리된 로그인 계정을 준비한다. Spring은 DB dump 복원이 끝난 후 시작한다.
5. PostgreSQL bootstrap superuser는 앱 연결에 사용하지 않는다. migration, runtime, readonly, backup login을 분리하고 role 권한 경계는 [staging credential 규칙](../../../infra/staging/README.md)과 [Lightsail Compose 설명](../../../infra/lightsail/README.md)을 따른다.
6. 환경변수와 비밀값은 GitHub, 문서, 이미지에 기록하지 않는다. [`infra/lightsail/.env.example`](../../../infra/lightsail/.env.example)을 복사한 서버의 `.env`에 실제 값을 넣고 Compose 실행 계정 소유로 `chmod 600` 권한을 제한한다.
   - `ONMARU_DB_URL=jdbc:postgresql://postgres:5432/onmaru`
   - `ONMARU_DB_RUNTIME_USER`, `ONMARU_DB_RUNTIME_PASSWORD`
   - `ONMARU_DB_MIGRATION_USER`, `ONMARU_DB_MIGRATION_PASSWORD`
   - `ONMARU_SECRETS_SOURCE=environment`와 필요한 `*_CURRENT` 외부 API 키
   - `ONMARU_OAUTH_KAKAO_CLIENTID`
   - `ONMARU_OAUTH_KAKAO_REDIRECTURI=https://api.onmaru.site/auth/kakao/callback`
   - `ONMARU_OAUTH_KAKAO_FRONTENDBASEURL=https://www.onmaru.site`
7. [Spring secret runbook](secrets.md)와 [Flyway startup ADR](../../decisions/0011-spring-boot-flyway-startup-migrations.md)을 따른다. migration 실패 시 앱이 정상이라고 간주하지 않는다.

## 3. DNS와 HTTPS

1. 도메인 등록처 또는 현재 DNS 관리 화면에서 `api` A record를 Lightsail 고정 IPv4로 추가한다. 기존 `@` 및 `www` 레코드는 수정하지 않는다. DNS 관리 자체를 Lightsail로 옮길 필요는 없다.
2. DNS가 새 IP를 가리키는지 확인한 뒤 Compose의 `nginx/bootstrap.conf`로 ACME HTTP challenge를 처리하고 Certbot으로 `api.onmaru.site` 인증서를 발급한다. 발급 성공 후에만 `NGINX_SITE_CONFIG`를 `nginx/site.conf`로 전환한다. 상세 명령은 [Lightsail Compose 설명](../../../infra/lightsail/README.md)을 따른다.
3. Nginx의 기본 경로는 `https://api.onmaru.site:443` → `http://spring-api:8080`으로 전달한다. 브라우저와 API 구간은 HTTPS이며 DB 연결은 인스턴스 내부 네트워크에 둔다. Spring forwarded-header 처리를 켜고 외부 `/actuator/**` 경로를 차단한다.
4. SSE 경로는 `proxy_buffering off`, `proxy_read_timeout` 120초 이상으로 설정한다. SSE heartbeat가 있으므로 proxy timeout은 heartbeat 간격보다 충분히 길어야 한다.
5. Nginx가 `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`를 전달하고 Spring forwarded-header 처리를 구성한다. 프록시를 우회해 Spring에 직접 접근할 수 있는 외부 포트는 열지 않는다.
6. 인증서 갱신 timer를 확인하고 자동 갱신 후 Nginx reload까지 되는지 확인한다.

## 4. CORS, CSRF, 세션 쿠키와 Kakao 로그인

1. CORS는 Lightsail 방화벽이나 DNS가 아니라 Spring의 `ONMARU_CORS_ALLOWED_ORIGINS` 환경변수로 설정한다. [`LocalDevelopmentCorsConfiguration`](../../../apps/spring-api/src/main/java/com/yrootlab/onmaru/config/LocalDevelopmentCorsConfiguration.java)은 `/api/**`와 `/auth/csrf`에 동일 allowlist/credentials 설정을 적용한다.
2. AWS `.env`에는 `https://onmaru.site,https://www.onmaru.site`만 허용한다. 개발 기본 allowlist에 포함된 localhost와 Render/Vercel Preview origin을 production으로 가져오지 않는다.
3. FE 브라우저 요청은 `credentials: "include"`를 사용한다. credentialed CORS에서는 wildcard origin을 쓰지 않고 실제 FE origin을 명시한다. CSRF 필터도 동일 allowlist를 사용하므로 FE→API 쓰기 요청은 허용된 Origin, 쿠키 token, `X-CSRF-TOKEN` 조건을 통과해야 한다.
4. `__Host-onmaru-session` 쿠키는 Spring API 호스트에서 발급한다. `Secure`, `HttpOnly`, `Path=/`, `SameSite=None`을 유지하고 `Domain` 속성을 임의로 추가하지 않는다. 쿠키는 웹 호스트가 아니라 API 요청에 자동 전송된다.
5. Kakao Developers에 새 Redirect URI `https://api.onmaru.site/auth/kakao/callback`을 추가한다. AWS 로그인 성공 및 실패 복귀 주소는 `https://www.onmaru.site`다. Render Redirect URI는 AWS 로그인이 검증될 때까지 제거하지 않는다.

## 5. Neon DB 백업과 AWS 복원

1. Render 앱이 실제 사용하는 Neon project/branch와 직접 연결 endpoint를 확인한다. 해당 branch의 PostgreSQL major, PostGIS extension/version, DB 용량, 계정/세션/운영 데이터의 이전 범위를 확인한다. 로컬 `.env.local`의 Neon branch를 운영 원본이라고 가정하지 않는다.
2. 확인된 Neon 원본 branch의 직접 연결 endpoint(풀러 제외)에서 호환되는 버전의 `pg_dump -Fc`로 custom-format dump를 만들고 별도 안전한 위치에 저장한다. 비밀번호/접속 문자열을 명령 기록이나 로그에 남기지 않는다.
3. AWS 대상 DB에서 `postgis` 등 필수 extension과 DB role을 준비한 뒤 backup을 restore한다. source dump를 보존한다.
4. restore 뒤 Flyway history, 필수 schema/table, 핵심 행 수, PostGIS version, 좌표/공간 조회를 점검한다. 단순히 Spring health만 200인 것으로 데이터 이전 완료를 판정하지 않는다.
5. 이 단계에서는 Neon 원본 DB와 Render API를 그대로 둔다. 테스트 트래픽은 AWS 복사본에만 보내고, 양쪽 DB를 동시에 쓰기 원본으로 사용하지 않는다.

## 6. AWS 검증과 웹사이트 전환

1. API 도메인에서 HTTPS와 Spring health endpoint가 정상 응답하고, Nginx access/error log와 Spring log가 확인되는지 점검한다.
2. Vercel의 Preview 환경에 `NEXT_PUBLIC_API_BASE_URL=https://api.onmaru.site`를 설정하고 preview를 재배포한다. Preview origin이 CORS allowlist에 없으면 정확한 preview origin을 추가하거나 고정된 staging FE origin을 사용한다.
3. Preview FE에서 공개 API, CSRF 발급, 게스트 요청, Kakao 로그인/복귀, 세션 유지, 개인화 API, SSE 연결을 점검한다. 로컬 localhost에서 원격 API로 테스트할 때는 browser third-party cookie 정책의 영향을 받을 수 있어 production-like preview를 우선한다.
4. 검증이 끝나면 최종 DB 이전 창을 잡는다. Neon 원본에 쓰는 Render 앱의 쓰기를 잠시 멈추거나 maintenance를 켜고 최종 dump/restore를 수행해 누락 writes를 막는다.
5. Vercel **Production** 환경의 `NEXT_PUBLIC_API_BASE_URL`만 AWS 주소로 바꾸고 새 배포를 진행한다. `onmaru.site`와 `www`는 계속 Vercel을 가리킨다.
6. 운영 브라우저에서 로그인, 저장, 여정 생성, 후속 조회, 사용자 쓰기 기능을 확인한다. 처음엔 Render API와 AWS API를 동시에 활성 production writer로 두지 않는다.

## 7. Render 앱·Neon 원본 정리 및 rollback

### 즉시 중지하지 않을 것

- AWS 인스턴스 생성 직후에는 Render Web Service, Neon 원본 branch, 현재 FE production 설정을 그대로 유지한다.
- AWS API/FE 로그인·쓰기·DB 복원·백업 검증 전에는 Neon 원본 branch를 삭제하지 않는다.
- AWS Kakao login 확인 전에는 Render callback URI를 지우지 않는다.
- Vercel project, `onmaru.site`/`www` DNS, GitHub repository/GHCR image는 종료 대상이 아니다.

### 검증 후 종료 순서

1. 운영 트래픽이 AWS로 전환되고 모니터링 기간 동안 health, 오류, 로그인, 핵심 쓰기 작업이 정상인지 확인한다.
2. 새 AWS DB 백업을 만들고, 실제로 restore할 수 있는지 확인한다.
3. Render 앱과 Neon 원본 branch를 rollback 대상으로 유지할 기간을 정한다. Render Web Service는 필요 시 suspend하고 Neon 원본은 백업/비용을 고려해 보존한다. 각 서비스의 suspend/backup 가능 범위를 먼저 확인한다.
4. rollback 유지 기간 종료와 AWS DB 복구 검증 뒤에만 Render Web Service를 해지하고 Neon 원본 branch의 삭제 여부를 별도로 결정한다. Neon 원본 삭제 직전 최종 snapshot/dump를 저장하고 보존 기한을 정한다.
5. Render 앱 해지 후 더 이상 쓰지 않는 Render 환경변수와 Kakao의 Render callback URI를 정리한다. Neon 원본 정리는 별도 복구 검증과 보존 결정 뒤에 한다. Vercel Production API base URL은 AWS를 유지한다.

### rollback 기준과 방법

- API health 실패, 로그인/세션 실패, DB 오류, 핵심 FE 요청 실패가 확인되면 cutover를 중단하고 원인을 먼저 확인한다.
- DNS 주소를 되돌리는 것만으로 DB rollback이 안전해지지 않는다. AWS 전환 후 쓰기가 발생했다면 Neon 원본 DB로 돌아갈 때 그 writes가 유실될 수 있다.
- 사용자 write가 시작된 뒤 rollback할 경우 maintenance/read-only로 쓰기를 멈추고, AWS DB의 변경분을 보존·비교·복원한 후 단일 writer를 정한다. 조정 없이 Neon 원본 DB를 다시 활성 writer로 만들지 않는다.
- DB 무결성과 인증 확인 뒤 Vercel API base URL을 Render endpoint로 되돌리고 배포한다. 새 AWS 데이터 변경을 어떻게 보존했는지 기록한다.

## 중단/정리 체크리스트

| 항목 | AWS 준비 중 | AWS cutover 후 검증 중 | rollback 기간 종료 후 |
|---|---|---|---|
| Render Spring Web Service | 유지 | rollback 필요성에 따라 유지 또는 suspend | 확인 후 해지 |
| Neon 원본 branch | 유지, 원본 데이터 | 백업 보유 상태로 유지 | 최종 backup 및 복구 확인 뒤 정리 결정 |
| Vercel Production API base | Render 유지 | AWS로 변경 | AWS 유지 |
| Vercel site/domain | 유지 | 유지 | 유지 |
| DNS `@`, `www` | Vercel 연결 유지 | Vercel 연결 유지 | Vercel 연결 유지 |
| DNS `api` | 추가 전 또는 준비 | Lightsail static IP | Lightsail static IP 유지 |
| Kakao Redirect URI | Render 유지 + AWS 추가 | 양쪽 유지 | AWS 확인 후 Render 항목 제거 |
| Render secrets | 유지 | rollback 동안 유지 | Render 종료 후 불필요 값 제거/회전 검토 |
| GHCR 및 GitHub Actions | 유지 | 유지 | 유지; Lightsail deploy 자동화는 별도 작업 |

## 실행 기록 양식

실제 전환 시 이 문서만 완료 처리하지 말고 별도 handoff/작업 기록에 아래를 적는다.

- AWS 계정/리전/resource name과 적용된 bundle, 실제 청구 기준(비밀값 제외)
- static IP, DNS 변경 시각, FE 배포 SHA, Spring image SHA
- Neon project/branch 식별자와 backup ID, restore 시각, table count/integrity 결과
- 인증서 만료/renewal 검증, Nginx/Spring health와 CORS/CSRF/Kakao 확인
- backup restore 결과, rollback window와 종료 결정
- 해지된 Render resource 이름과 해지 일자
