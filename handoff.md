# handoff.md

- Branch: `fix/453-single-active-revision`
- Issue: #453
- Scope: Neon 512MiB 제한에서 활성 PUBLISHED revision 1벌만 보존하고 동일 snapshot 재발행을 방지한다.
- Key changes:
  - retention은 데이터셋별 활성 revision만 보호하고 비활성 PUBLISHED와 FAILED를 즉시 정리한다.
  - STAGING/READY는 진행 중 수집 보호를 위해 1시간 유예 후 정리한다.
  - TourAPI와 ODII 새 snapshot이 활성본과 동일하면 신규 revision을 발행하지 않고 임시 stage를 삭제한다.
  - 성공 기록과 watermark는 기존 활성 revision을 가리키도록 갱신한다.
- Verification:
  - 활성본 1벌 보호, 비활성/실패 revision 제거, 동일 TourAPI·ODII snapshot 재사용 회귀 테스트를 추가했다.
  - 대상 PostgreSQL·모듈 회귀 테스트가 `BUILD SUCCESSFUL`로 끝났다.
  - 2026-09-28 운영 긴급 정리에서 기존 비활성/실패 revision과 정리 중 재생성된 superseded revision을 삭제했다.
  - 최종 활성 PUBLISHED는 DataLab·TourAPI·ODII 각각 1개, 비활성 revision은 0개이며 DB 크기는 324MB다.
- Next step:
  - fresh PostgreSQL 통합 테스트와 변경 범위 검증 후 `develop` 대상 PR을 생성한다.
  - CI의 필수 `verify`를 통과시켜 병합하고 release branch를 통해 운영에 배포한다.
  - 배포 후 retention을 다시 실행하고 logical size와 동일 snapshot 재동기화 전후 행 수를 비교한다.
- Open risk:
  - 운영은 아직 이전 코드라 배포 전 새 동기화가 실행되면 중복 revision이 다시 생성될 수 있다.
  - CI, Staging Deploy, Release Please가 수동 중단 상태라 병합·배포 전에 필요한 workflow 재활성화가 필요하다.
  - `.env.local`의 Neon credential은 도구 로그 노출 이력 때문에 작업 종료 후 반드시 회전해야 한다.
  - frontend 전체 build는 기존 `/stamps` prerender에서 `catalog.stamps`가 undefined인 별도 오류로 실패한다.

## 2026-09-27 Issue #454 추가 검증

- Branch: `fix/454-production-api-latency`
- User request: 상세 요청마다 ODII 전체 snapshot을 읽는 원인을 바로 수정하고 약 8천 자 트러블슈팅 기록을 남긴다.
- Added verification: cold cache에 24개 요청을 동시에 시작해도 전체 snapshot load가 1회인지 검증한다.
- Worklog: `troubleshooting-worklog/26.09.27 odii-active-snapshot-query-cache.md`
- Verification: 대상 동시성 테스트 `BUILD SUCCESSFUL`, `git diff --check` 성공.
- Next step: audio 모듈 전체 테스트와 Spring API 조립 검증 후 커밋·push하고 `develop` 대상 PR을 준비한다.
- Open risk: production 미배포 상태이므로 Render latency·memory·502/503 개선은 배포 후 측정해야 한다. 프론트의 카드별 상세 fan-out도 별도 수정해야 한다.
