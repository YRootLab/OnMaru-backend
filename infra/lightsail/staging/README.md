# 온디맨드 스테이징

운영과 같은 1GB Lightsail 인스턴스에서 **필요할 때만** 별도 Spring API·PostGIS 컨테이너를 실행한다. 운영 Compose 프로젝트, DB volume, 계정과 환경 파일은 공유하지 않는다. 평소에는 스테이징 컨테이너를 중지하고, 시작 후 2시간이 지나면 자동 중지한다. 1GB에서 둘을 동시에 실행하면 swap 사용과 운영 응답 지연이 생길 수 있으므로 긴 부하 테스트에는 사용하지 않는다.

## 최초 준비 (운영자)

1. 운영 저장소 `/opt/onmaru/repo`와 분리해 `develop` 전용 checkout을 `/opt/onmaru/staging-repo`에 둔 다음 `/opt/onmaru/staging-repo/infra/lightsail`로 이동한다. 운영 `.env`는 유지한다. `staging/.env.example`을 `staging/.env`로 복사해 **스테이징 전용** DB 비밀번호·서명키를 각각 생성하고, 외부 API 키를 채운 뒤 `chmod 600 staging/.env`를 실행한다. 운영 DB dump나 운영 세션·회원 데이터는 복사하지 않는다. 이미지에는 이 checkout과 동일한 `develop` 검증 SHA tag를 쓴다.
2. `staging-api.onmaru.site`의 A 레코드를 기존 Lightsail 고정 IP로 추가한다. 운영 `api.onmaru.site` 레코드는 변경하지 않는다. 운영 checkout에 이 PR의 Nginx 파일과 Compose 변경이 release 경로로 반영된 뒤, DNS가 확인되면 HTTP 전용 `staging-bootstrap.conf`로 ACME challenge를 제공한다. 기존 certbot webroot·이메일로 staging API 인증서를 발급하고, 발급 성공 후에만 운영 `.env`에 `NGINX_STAGING_SITE_CONFIG=./nginx/staging-site.conf`를 설정해 Nginx를 재생성한다. 운영 `NGINX_SITE_CONFIG` 값은 그대로 둔다. staging 앱이 중지되어 있으면 staging API는 503을 반환한다.
3. `sudo install -m 0644 staging/systemd/* /etc/systemd/system/` 및 `sudo systemctl daemon-reload`로 자동 중지 timer를 설치한다. timer는 `start.sh`가 기동할 때 시작된다. 서버 재부팅 후 스테이징은 자동 실행되지 않는다.
4. FE 개발자에게 **전용 SSH 공개키만** 받아 서버의 별도 임시 파일로 전달하고 `sudo staging/install-operator.sh /path/to/developer.pub`를 실행한다. 개인키나 운영 `.env`는 전달하지 않는다. 이 계정은 아래 세 명령만 실행할 수 있다.
5. FE의 고정 스테이징 도메인 `https://staging.onmaru.site`를 Vercel Preview에 연결하고, 해당 배포의 `NEXT_PUBLIC_API_URL`을 `https://staging-api.onmaru.site`로 설정해 재배포한다. 로컬 FE는 `staging/.env`의 `ONMARU_CORS_ALLOWED_ORIGINS`에 명시된 `http://localhost:3000`~`http://localhost:3008`에서 스테이징 API를 호출할 수 있다. Kakao 개발자 설정에는 `https://staging-api.onmaru.site/auth/kakao/callback`을 추가한다. 운영 Vercel Production 설정은 유지한다.

## 검증된 develop 이미지 반영 (운영자)

스테이징을 중지한 상태에서만 새 이미지를 준비한다. GitHub Actions의 `Build Images and Deploy`를 `develop` ref로 수동 실행하며 `deploy_staging=true`를 선택한다. 이 작업은 같은 commit의 `CI / verify`, 이미지 취약점 검사, migration gate가 모두 통과한 뒤에만 진행된다. 이미지와 migration 파일을 해당 `develop` commit으로 맞추지만 스테이징 컨테이너는 시작하지 않는다. FE의 다음 `start`에서 Flyway와 Spring이 새 버전으로 기동한다.

```bash
gh workflow run deploy.yml --ref develop -f deploy_staging=true
```

최초 설정에는 GitHub `staging` 환경에 `STAGING_DEPLOY_SSH_KEY` secret과 `STAGING_SSH_KNOWN_HOSTS` variable이 필요하다. 운영자 개인 SSH 키를 GitHub에 올리지 않는다. CI 전용 Ed25519 키 쌍을 생성해 개인키만 GitHub 환경 secret에 등록하고, 서버에는 공개키만 전달해 `sudo staging/install-deployer.sh /path/to/ci.pub`를 실행한다. 설치 스크립트는 전용 계정에 배포 명령 하나만 허용하며, 일반 셸·운영 Compose 접근 권한을 주지 않는다. 서버 host key는 기존에 신뢰한 SSH 연결의 fingerprint와 대조한 뒤 GitHub variable에 고정한다. CI의 단기 GHCR token은 SSH 표준 입력으로만 전달되고 서버의 임시 Docker 인증 디렉터리는 이미지 pull 후 삭제된다.

실행 중인 스테이징에 새 이미지를 적용하려 하면 배포가 실패하므로 FE가 테스트를 마치고 `stop`한 뒤 다시 실행한다. 배포 스크립트는 이전 이미지 참조를 `/var/lib/onmaru/staging-previous-image`에 보관한다. 배포 실패나 새 버전 기동 실패 시 운영자가 원인과 migration 호환성을 확인한 뒤 이전 이미지로 되돌린다. DB migration은 자동 역적용되지 않는다.

## FE 개발자의 사용법

FE 팀에 전달할 짧은 절차는 [FE 스테이징 안내](../../../docs/operations/staging-fe-guide.md)에 정리했다.

```bash
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 start
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 status
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 stop
```

`start`는 운영 PostgreSQL health를 먼저 확인하고, 스테이징 DB→Flyway→Spring 순서로 기동한다. Flyway가 schema를 만든 후 `seed.sql`에 고정된 합성 fixture를 넣는다. 지도 장소 100건, 공개 온기 후기 65건, Odii story 65건과 지도 projection·지역 집계·이미지·태그·장소 연결을 제공한다. fixture의 모든 행은 합성 테스트 데이터이며, seed는 운영 회원·세션 데이터를 복사하지 않습니다. 같은 staging DB에 반복 기동해도 fixture 행 수가 늘어나지 않습니다. 지도에서 얻은 안정 public ID `p-staging-hanok-a`로 기존 장소 상세·후기·오디오 연결 흐름을 확인하고, `p-staging-generated-001` 등 생성 장소로 지도 페이지 결과를 확인할 수 있다.

FE 검증은 다음 순서로 진행한다. 기존 FE staging mode에서 `staging-api.onmaru.site`를 사용하고, 지도 권역 이동과 줌 변경으로 place/cluster/district/region 응답을 확인한다. 온기 후기 및 Odii story 목록은 `limit=30`으로 응답의 `nextCursor`를 다음 요청에 그대로 전달하며 마지막 `nextCursor=null`, `hasMore=false`까지 순회한다. 추가 공개 사용자 후기가 없는 기준 상태의 페이지별 기대 건수는 30/30/5다. 검증이 끝나면 SSH `stop`으로 스테이징을 중지한다. 실패하면 스테이징 컨테이너를 중지한다. 사용 중에도 2시간 뒤 자동 중지되므로 더 필요하면 `start`를 다시 실행해 timer를 갱신한다. `status`는 컨테이너 상태와 자동 중지 예정 시각을 출력한다. 스테이징 인증키와 DB는 운영과 별도다.

사용자가 작성한 공개 후기는 seed 재실행 후에도 보존되므로 온기 API의 `totalCount`와 페이지 수가 증가할 수 있다. 이 경우 실제 cursor를 끝까지 순회해 공개 fixture 후기 65개 ID의 포함 여부와 중복 없음, 추가 사용자 후기의 ID·건수를 구분해 기록한다. fixture 후기 ID는 기존 `54500000-0000-4000-8000-000000000111`·`112`와 생성 `54500675-0000-4000-8600-000000000001`~`063`이다. 이전 생성 범위 밖의 합성 후기는 참조를 보존한 채 숨김 처리되며 공개 65건에 포함하지 않는다. 사용자 후기를 지워 기준 페이지 수에 맞추지 않는다.

fixture의 이미지와 짧은 오디오 샘플은 각각 `picsum.photos`, `samplelib.com`의 공개 테스트 자원을 사용한다. 이 host들은 fixture 렌더링 확인 용도이며 운영 데이터나 회원·세션 정보는 포함하지 않는다.

이미 healthy인 상태에서 `start`를 다시 실행하면 컨테이너를 재시작하지 않고 자동 종료 시간만 갱신한다. 새 이미지 SHA를 적용할 때는 운영자가 `stop` 후 staging `.env`를 갱신하고 이미지를 pull한 뒤 `start`를 실행한다.

최초 `V001`은 DB 역할을 생성·수정하므로 스테이징 전용 bootstrap 로그인으로 일회성 Flyway 컨테이너를 실행한다. 이 로그인과 비밀번호는 운영 DB에 접근할 수 없고 Spring runtime에는 주입되지 않는다.

## 확인과 배포 상태

운영자는 첫 기동 시 Spring health, Flyway, 지도→장소 상세→후기→연결 오디오 fixture 조회, CSRF, OAuth redirect, FE 로그인·쓰기·SSE 및 운영 health를 확인한다. `docker stats`와 `free -h`로 동시 실행 시 swap·메모리를 감시한다. 운영 상태가 흔들리면 즉시 `stop`을 실행한다.

현재 `.github/workflows/deploy.yml`은 `master` push에서 이미지를 빌드·검사하지만 즉시 운영에 배포하지 않는다. Lightsail 운영 Blue-Green 배포는 Repository Variable `PRODUCTION_DEPLOY_ENABLED=true`인 상태에서 매일 `03:17 KST` 예약 실행 또는 `deploy_production=true`를 선택한 명시적 수동 실행으로만 진행한다. 운영 preflight는 같은 SHA가 이미 healthy하게 배포됐다면 build 전에 no-op으로 끝낸다. `develop` 스테이징 이미지 반영도 위 수동 명령으로만 수행한다. 운영 Blue-Green 배포와 수동 rollback은 같은 1GB 호스트의 메모리 여유를 지키기 위해 스테이징이 실행 중이면 fail-closed로 중단한다. `STAGING_SPRING_URL`은 `https://staging-api.onmaru.site`이며 운영 URL로 우회하지 않는다. 공개 staging smoke는 DNS/TLS가 준비되고 FE 개발자가 서버를 켠 상태에서 workflow를 수동 실행할 때만 `run_staging_smoke=true`로 선택한다. 이 smoke는 배포와 별도 실행한다.
