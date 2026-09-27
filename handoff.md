# handoff.md

- Branch: `fix/453-quarantine-lifecycle`
- Issue: #453
- Scope: Neon 512MiB 제한에서 실패 원문 저장을 중단하고 활성 PUBLISHED revision 1벌만 유지하며 TourAPI 동기화를 72시간 간격으로 제한한다.
- Key changes:
  - `UNSUPPORTED_CATEGORY`는 장애가 아닌 `SKIPPED`로 분류하고 quarantine JSON을 저장하지 않는다.
  - 좌표 오류 등 실제 provider 오류도 원문은 저장하지 않고 sync run 건수만 유지한다.
  - TourAPI 실패 STAGING은 즉시 제거하고, 변경 snapshot 게시 후 이전 PUBLISHED도 같은 트랜잭션에서 제거한다.
  - 재배포 시 전체 동기화를 제거하고 마지막 성공 후 72시간이 지나야 다음 동기화를 허용한다.
  - 운영 정리는 자동 migration이 아닌 승인용 dry-run/cleanup SQL로 분리했다.
  - 운영 장소 상세는 sample 메모리가 아니라 active PUBLISHED PostgreSQL snapshot을 조회한다.
  - 일반 상세은 `contentId/contentTypeId`로 TourAPI를 on-demand 호출하고 장애 시 DB 기본정보를 반환한다.
  - 저장된 한옥 상세가 있으면 DB를 우선하고 TourAPI 호출을 생략한다.
- Verification:
  - 운영 확인: quarantine 137,298행 중 `UNSUPPORTED_CATEGORY` 137,239행, 고유 fingerprint 25,900개, 실행 7회 중복.
  - 반복 snapshot, 실패 snapshot, 이전 published 제거, 72시간 cadence, cleanup SQL PostgreSQL 통합 테스트가 성공했다.
  - 운영 cleanup 실행 후 DB 338MB, 비활성 revision 0, quarantine 0, 활성 장소 23,743·Odii story 6,205·자막 6,069를 확인했다.
  - `./gradlew check :apps:spring-api:bootJar --no-daemon` 성공(13m 14s).
- Next step:
  - fresh 전체 검증 후 `develop` 대상 PR을 생성·병합한다.
  - dry-run SQL 결과를 검토하고 명시적 승인 후에만 운영 cleanup SQL을 실행한다.
  - 단일 긴급 PR로 #453과 #464 범위를 함께 `develop`에 반영한다.
- Open risk:
  - 운영은 아직 이전 코드라 새 코드가 master에 배포되기 전 Cron 실행 시 재누적 가능성이 남는다.
  - `.env.local`의 Neon credential은 도구 로그 노출 이력 때문에 작업 종료 후 반드시 회전해야 한다.

## 2026-09-27 Issue #454 추가 검증

- Branch: `fix/454-production-api-latency`
- User request: 상세 요청마다 ODII 전체 snapshot을 읽는 원인을 바로 수정하고 약 8천 자 트러블슈팅 기록을 남긴다.
- Added verification: cold cache에 24개 요청을 동시에 시작해도 전체 snapshot load가 1회인지 검증한다.
- Worklog: `troubleshooting-worklog/26.09.27 odii-active-snapshot-query-cache.md`
- Verification: 대상 동시성 테스트 `BUILD SUCCESSFUL`, `git diff --check` 성공.
- Next step: audio 모듈 전체 테스트와 Spring API 조립 검증 후 커밋·push하고 `develop` 대상 PR을 준비한다.
- Open risk: production 미배포 상태이므로 Render latency·memory·502/503 개선은 배포 후 측정해야 한다. 프론트의 카드별 상세 fan-out도 별도 수정해야 한다.

## 2026-09-27 Issue #454 PostgreSQL read model 전환

- User request: 활성 revision 전체를 Java에 적재해 검색하지 말고 PostgreSQL 조건·인덱스·집계·PostGIS를 사용하도록 최적화하며 1만 자 이상 근거를 남긴다.
- Branch: `fix/454-production-api-latency`
- Changed paths: V033 공개 UUID/generated column과 최소 index, `JdbcOdiiStoryReadStore`, relational read port/DTO, production wiring, module·PostGIS 통합 테스트, DBML/schema 문서.
- Optimized routes: 기본 ODII 목록/상세, 검색, 추천, 주변, 홈 인기. 목록은 limit+1, 상세는 공개 UUID 한 건과 해당 자막만, 주변은 ST_DWithin, 인기는 기간 group/score/limit을 DB에서 수행한다.
- Evidence: 운영 full snapshot cold DB 실행 약 2.31초(spot 146.001ms, subtitle 936.377ms, story 1,228.296ms), result cardinality 약 14,369행. 상세 공개 키 중복은 story/spot 모두 0그룹.
- Worklog: `troubleshooting-worklog/26.09.27 odii-postgresql-relational-read-optimization.md` (16,234자).
- Verification: 대상 module/JDBC integration test 성공, 전체 `./gradlew check :apps:spring-api:bootJar --no-daemon` 성공(10m 46s), `git diff --check` 성공.
- Remaining debt: category/region 필터 목록과 region group은 region polygon/category key가 DB read model에 없어 snapshot fallback을 유지한다. 다음 schema에서 versioned `region_id`와 `category_code`를 승격해야 한다.
- Deployment: 현재 develop/master 및 Render에 미반영. CI/CD 일시 중단 요청을 유지하며 명시적 승인 전 PR merge·release·배포하지 않는다.

## 2026-09-28 Issue #454 비개발자용 설계 회고

- User request: 전체 revision을 Java로 복원하던 구조가 만들어진 배경, 운영 문제, SQL read model 전환 과정을 비개발자도 이해할 수 있는 약 7천 자 트러블슈팅으로 설명한다.
- Worklog: `troubleshooting-worklog/26.09.28 odii-full-revision-read-origin-and-fix.md` (7,521자).
- Key conclusion: revision의 원자적 게시 목적은 유지하되, 조회 시에는 활성 revision ID를 SQL 조건으로 사용하고 응답에 필요한 최소 행만 Java로 전달한다.
- Verification: 문서 분량·구성 확인과 `git diff --check`를 수행한다. 코드 변경과 배포는 없다.
