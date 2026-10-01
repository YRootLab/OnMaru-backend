# 온디맨드 스테이징

운영과 같은 1GB Lightsail 인스턴스에서 **필요할 때만** 별도 Spring API·PostGIS 컨테이너를 실행한다. 운영 Compose 프로젝트, DB volume, 계정과 환경 파일은 공유하지 않는다. 평소에는 스테이징 컨테이너를 중지하고, 시작 후 2시간이 지나면 자동 중지한다. 1GB에서 둘을 동시에 실행하면 swap 사용과 운영 응답 지연이 생길 수 있으므로 긴 부하 테스트에는 사용하지 않는다.

## 최초 준비 (운영자)

1. 운영 저장소 `/opt/onmaru/repo`와 분리해 `develop` 전용 checkout을 `/opt/onmaru/staging-repo`에 둔 다음 `/opt/onmaru/staging-repo/infra/lightsail`로 이동한다. 운영 `.env`는 유지한다. `staging/.env.example`을 `staging/.env`로 복사해 **스테이징 전용** DB 비밀번호·서명키를 각각 생성하고, 외부 API 키를 채운 뒤 `chmod 600 staging/.env`를 실행한다. 운영 DB dump나 운영 세션·회원 데이터는 복사하지 않는다. 이미지에는 이 checkout과 동일한 `develop` 검증 SHA tag를 쓴다.
2. `staging-api.onmaru.site`의 A 레코드를 기존 Lightsail 고정 IP로 추가한다. 운영 `api.onmaru.site` 레코드는 변경하지 않는다. 운영 checkout에 이 PR의 Nginx 파일과 Compose 변경이 release 경로로 반영된 뒤, DNS가 확인되면 HTTP 전용 `staging-bootstrap.conf`로 ACME challenge를 제공한다. 기존 certbot webroot·이메일로 staging API 인증서를 발급하고, 발급 성공 후에만 운영 `.env`에 `NGINX_STAGING_SITE_CONFIG=./nginx/staging-site.conf`를 설정해 Nginx를 재생성한다. 운영 `NGINX_SITE_CONFIG` 값은 그대로 둔다. staging 앱이 중지되어 있으면 staging API는 503을 반환한다.
3. `sudo install -m 0644 staging/systemd/* /etc/systemd/system/` 및 `sudo systemctl daemon-reload`로 자동 중지 timer를 설치한다. timer는 `start.sh`가 기동할 때 시작된다. 서버 재부팅 후 스테이징은 자동 실행되지 않는다.
4. FE 개발자에게 **전용 SSH 공개키만** 받아 서버의 별도 임시 파일로 전달하고 `sudo staging/install-operator.sh /path/to/developer.pub`를 실행한다. 개인키나 운영 `.env`는 전달하지 않는다. 이 계정은 아래 세 명령만 실행할 수 있다.
5. FE의 고정 스테이징 도메인 `https://staging.onmaru.site`를 Vercel Preview에 연결하고, 해당 배포의 `NEXT_PUBLIC_API_URL`을 `https://staging-api.onmaru.site`로 설정해 재배포한다. Kakao 개발자 설정에는 `https://staging-api.onmaru.site/auth/kakao/callback`을 추가한다. 운영 Vercel Production 설정은 유지한다.

## FE 개발자의 사용법

FE 팀에 전달할 짧은 절차는 [FE 스테이징 안내](../../../docs/operations/staging-fe-guide.md)에 정리했다.

```bash
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 start
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 status
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 stop
```

`start`는 운영 PostgreSQL health를 먼저 확인하고, 스테이징 DB→Flyway→Spring 순서로 기동한다. Flyway가 빈 DB에 schema를 만든 후 `seed.sql`의 합성 장소 2개를 넣는다. 실패하면 스테이징 컨테이너를 중지한다. 사용 중에도 2시간 뒤 자동 중지되므로 더 필요하면 `start`를 다시 실행해 timer를 갱신한다. `status`는 컨테이너 상태와 자동 중지 예정 시각을 출력한다. 스테이징 인증키와 DB는 운영과 별도다.

이미 healthy인 상태에서 `start`를 다시 실행하면 컨테이너를 재시작하지 않고 자동 종료 시간만 갱신한다. 새 이미지 SHA를 적용할 때는 운영자가 `stop` 후 staging `.env`를 갱신하고 이미지를 pull한 뒤 `start`를 실행한다.

최초 `V001`은 DB 역할을 생성·수정하므로 스테이징 전용 bootstrap 로그인으로 일회성 Flyway 컨테이너를 실행한다. 이 로그인과 비밀번호는 운영 DB에 접근할 수 없고 Spring runtime에는 주입되지 않는다.

## 확인과 배포 상태

운영자는 첫 기동 시 Spring health, Flyway, 합성 장소 조회, CSRF, OAuth redirect, FE 로그인·쓰기·SSE 및 운영 health를 확인한다. `docker stats`와 `free -h`로 동시 실행 시 swap·메모리를 감시한다. 운영 상태가 흔들리면 즉시 `stop`을 실행한다.

현재 `.github/workflows/deploy.yml`의 이름은 `Build Images and Optional Staging Smoke`이며 Lightsail 자동 배포는 하지 않는다. `develop` SHA 이미지 빌드·검증 후 스테이징 `.env`의 SHA 갱신, pull, 기동 검증은 별도 운영 작업이다. `STAGING_SPRING_URL`은 실제 staging API가 준비된 뒤 `https://staging-api.onmaru.site`로 바꾼다. 운영 URL로 우회하지 않는다. staging smoke는 FE 개발자가 서버를 켠 상태에서 workflow를 수동 실행할 때만 선택한다.
