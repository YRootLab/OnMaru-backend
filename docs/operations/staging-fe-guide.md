# FE 개발자용 스테이징 시작·종료 안내

스테이징 API와 DB는 운영 서버와 계정·데이터를 분리해 두었으며, 테스트가 필요할 때만 켠다. FE 스테이징 주소는 `https://staging.onmaru.site`, API 주소는 `https://staging-api.onmaru.site`이다. 준비가 끝나기 전에는 두 주소가 아직 동작하지 않을 수 있다. 운영 FE/DB 테스트에는 이 명령을 사용하지 않는다.

## 최초 1회: 개인 SSH 키 등록

```bash
ssh-keygen -t ed25519 -f ~/.ssh/onmaru-staging -C 'onmaru-staging-fe'
chmod 600 ~/.ssh/onmaru-staging
cat ~/.ssh/onmaru-staging.pub
```

마지막 명령의 **`.pub` 한 줄만** 운영자에게 전달한다. 개인키 파일 `~/.ssh/onmaru-staging`은 공유하지 않는다. 운영자가 스테이징 전용 제한 계정에 공개키를 등록한 후 아래 명령을 사용할 수 있다. 키를 분실하거나 담당자가 바뀌면 운영자에게 기존 키 폐기·교체를 요청한다.

## 필요할 때 켜고 끄기

```bash
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 start
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 status
ssh -i ~/.ssh/onmaru-staging onmaru-staging-operator@13.125.191.16 stop
```

`start`가 정상 완료되면 `Staging is ready`가 표시된다. 첫 시작은 DB 마이그레이션과 Spring 기동으로 몇 분 걸릴 수 있다. `status`는 API·DB 컨테이너와 자동 종료 예정 시각을 보여 준다. 테스트를 마치면 `stop`을 실행한다. 잊어도 시작 2시간 뒤 자동 중지된다. 더 오래 필요하면 `start`를 다시 실행해 2시간을 갱신한다. 중지 중인 API는 HTTP 503을 반환한다.

FE의 고정 스테이징 배포에서는 `NEXT_PUBLIC_API_URL=https://staging-api.onmaru.site`를 사용한다. Vercel 환경변수 변경은 재배포 후 브라우저 번들에 반영된다. 운영 Vercel Production의 API URL은 그대로 둔다. Kakao 로그인은 스테이징 callback 등록이 끝난 뒤 테스트한다.

시작에 실패하면 `status` 출력과 실패 메시지를 운영자에게 전달한다. 비밀번호·토큰·개인키는 보내지 않는다. 운영 서버가 느려지면 테스트를 멈추고 `stop`을 실행한 후 운영자에게 알린다. 이 계정은 스테이징 시작·종료·상태 확인만 허용하며 운영 DB나 일반 SSH 셸에는 접근할 수 없다.
