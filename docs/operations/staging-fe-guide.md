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

## FE 페이지 검증 순서

1. 위 SSH `start` 명령으로 온디맨드 스테이징을 기동한다.
2. FE 코드 변경 없이 기존 FE staging mode에서 `https://staging-api.onmaru.site`를 사용한다.
3. 지도를 이동해 권역을 바꾸고 줌을 변경하면서 place/cluster/district/region 응답을 확인한다. 안정 public ID `p-staging-hanok-a`는 기존 장소 상세·후기·오디오 연결 흐름 확인에 사용할 수 있다.
4. 온기 후기 및 Odii story 목록을 `limit=30`으로 요청하고, 응답의 `nextCursor`를 다음 요청에 그대로 전달해 마지막 `nextCursor=null`, `hasMore=false`까지 순회한다. fixture에는 지도 장소 100건, 공개 온기 후기 65건, Odii story 65건이 있다. 추가 공개 사용자 후기가 없는 기준 상태의 페이지별 기대 건수는 30/30/5다.
5. 검증 후 위 SSH `stop` 명령으로 스테이징을 중지한다.

fixture의 모든 행은 합성 테스트 데이터이며, seed는 운영 회원·세션 데이터를 복사하지 않습니다. 같은 staging DB에 반복 기동해도 fixture 행 수가 늘어나지 않습니다. 장소 fixture는 `p-staging-generated-001`~`p-staging-generated-096`을 포함한다.

사용자가 작성한 공개 후기는 seed 재실행 후에도 보존되므로 온기 API의 `totalCount`와 페이지 수가 증가할 수 있다. 이 경우 실제 cursor를 끝까지 순회하고, 공개 fixture 후기 65개 ID의 포함 여부와 중복 없음, 추가 사용자 후기의 ID·건수를 구분해 기록한다. fixture 후기 ID는 기존 `54500000-0000-4000-8000-000000000111`·`112`와 생성 `54500675-0000-4000-8600-000000000001`~`063`이다. 이전 생성 범위 밖의 합성 후기는 참조를 보존한 채 숨김 처리되며 공개 65건에 포함하지 않는다. 사용자 후기를 지워 기준 페이지 수에 맞추지 않는다.

FE의 고정 스테이징 배포에서는 Vercel **Preview** 환경변수 `NEXT_PUBLIC_API_URL=https://staging-api.onmaru.site`를 사용한다. Vercel 환경변수 변경은 재배포 후 브라우저 번들에 반영된다. 운영 Vercel Production의 API URL은 그대로 둔다. Kakao 로그인은 스테이징 callback 등록이 끝난 뒤 테스트한다.

현재 FE 저장소의 `vercel.json`은 `git.deploymentEnabled=false`라서 Git push로 Preview가 자동 생성되지 않는다. FE 프로젝트 권한을 가진 개발자가 Vercel CLI에서 Preview를 수동 배포한 뒤 그 배포 URL에 고정 도메인을 연결한다. 도메인은 먼저 Vercel 프로젝트에 등록하고 안내된 DNS 레코드를 설정해야 한다.

```bash
# OnMaruFE 저장소에서, Vercel 프로젝트에 연결된 계정으로 실행
vercel deploy
vercel alias set <위 명령이 출력한 배포 URL> staging.onmaru.site
```

다음 Preview를 배포할 때도 별칭을 새 배포 URL로 갱신한다. [Vercel Preview 배포](https://vercel.com/docs/cli/deploy)와 [alias 명령](https://vercel.com/docs/cli/alias)의 공식 절차를 따른다.

시작에 실패하면 `status` 출력과 실패 메시지를 운영자에게 전달한다. 비밀번호·토큰·개인키는 보내지 않는다. 운영 서버가 느려지면 테스트를 멈추고 `stop`을 실행한 후 운영자에게 알린다. 이 계정은 스테이징 시작·종료·상태 확인만 허용하며 운영 DB나 일반 SSH 셸에는 접근할 수 없다.
