# handoff.md
# 현재 세션 handoff

- 브랜치: `feature/493-map-info-be`
- Issue: `#493` 지도 정보모드 BE API·SQL·PostGIS 조회 구조
- 구현: API hard timeout, snapshot 변경 시 cache 무효화, 이름/지역 null-safe keyset cursor, active 공간 인덱스 및 PostGIS cluster 쿼리 최적화, DB query duration/coverage/timeout/pool/slow-query 관측성, stale-if-error fallback, snapshot expired 409, PLACE partial coverage, places 계약·장애 응답 검증
- 검증: map-info 단위/웹 경계/cache stale 테스트, PostGIS publication·legacy·cursor 통합 테스트, 30,000건 성능 게이트(재실행 통과; 전체 suite 중 1회는 ARM64 Docker 에뮬레이션으로 p95 일시 초과), OpenAPI/fixture 검증, migration policy, `git diff --check` 통과
- 미추적 파일: `tempGithubIssue/`는 기존 작업물로 보존하고 커밋하지 않음
- 다음 단계: 전체 Gradle 검증, 리뷰, 커밋·원격 push 후 PR 생성 및 #493~#499 연결

## 2026-09-29 Issue #375 Admin API foundation

- Branch: `feature/375-admin-api`
- User request: Admin API 설계안을 실제 구현으로 진행한다.
- Changed paths: Admin auth domain/JWT/password hash/login-me controller, refresh session/JDBC store, admin reviews/reports controller, V034 Admin foundation migration, Admin OpenAPI draft, design spec, secret configuration, migration registry/schema docs.
- Implemented: `POST /api/v1/auth/admin/login`, `POST /api/v1/auth/admin/refresh`, `POST /api/v1/auth/admin/logout`, `GET /api/v1/auth/admin/me`, `GET /api/v1/admin/reviews`, `GET /api/v1/admin/reports`, `GET /api/v1/admin/moderation/queue`, `POST /api/v1/admin/reviews/{reviewId}/moderation-actions`, `GET /api/v1/admin/dashboard/summary`, `GET /api/v1/admin/users`, `GET/POST /api/v1/admin/users/{memberId}/sanctions`, `DELETE /api/v1/admin/users/{memberId}/sanctions/{sanctionId}`, `GET /api/v1/admin/curations`, `PUT /api/v1/admin/curations/{placeId}`, `GET /api/v1/admin/pipelines/{dataset}/status`, `POST /api/v1/admin/pipelines/{dataset}/runs`.
- Dashboard는 실제 community review/report 데이터를 날짜 범위로 집계하고, Users는 production JDBC에서 회원 상태·가입일·후기 수를 조회한다. Pipeline 실행 명령과 Curation mutation은 아직 의도적으로 노출하지 않았다.
- Sanctions는 ADMIN만 생성·해제 가능하고, 활성 제재는 회원당 1건으로 제한한다. production은 `identity_member_sanctions`에 JDBC로 저장하고, EDITOR는 조회만 허용한다.
- Curations는 버전 증가형 override로 저장하고, Pipelines는 production에서 `kto-korean-tour` TourAPI full snapshot sync를 실행한다. sync adapter가 없는 환경에서는 상태를 `MISSING`, 실행을 `NOT_IMPLEMENTED`로 명시한다.
- production bootstrap은 `onmaru.admin.bootstrap.email/password/nickname` 설정이 모두 있을 때만 최초 ADMIN 계정을 `ON CONFLICT DO NOTHING`으로 생성한다. password가 없으면 임의 계정을 만들지 않고 skip한다.
- `AdminApiWebBoundaryTests`를 추가해 신규 관리자 endpoint의 missing Bearer 및 CSRF 경계를 검증했다. 이 테스트로 `AdminLoginService`의 Spring 다중 생성자 autowire 누락도 수정했다.
- Security: refresh token은 HttpOnly/Secure/SameSite=Strict cookie로 전달하고 DB에는 SHA-256 hash만 저장한다. 회전된 이전 token 재사용은 거부한다.
- Verification: Admin auth/session/dashboard/curation/pipeline/sanction tests와 `AdminApiWebBoundaryTests` 성공, `:apps:spring-api:compileJava` 성공, migration policy 성공, OpenAPI YAML 검증 성공, PostgreSQL baseline/upgrade 대상 테스트 성공, `git diff --check` 성공. 전체 Spring suite는 Testcontainers 장기 실행으로 최종 완료 전 중단함.
- Next step: controller boundary/OpenAPI contract test와 production bootstrap/배포 secret 주입을 마무리한다.
- Open risk: V034 schema는 account seed를 만들지 않으므로 운영에서는 bootstrap admin provisioning이 필요하다. `admin.jwt-signing-key` secret도 Render에 설정해야 한다.

## 2026-09-28 Issue #265 프론트엔드 OAuth 복귀

- Branch: `fix/265-kakao-frontend-redirect`
- Scope: 카카오 OAuth callback 성공·실패 후 백엔드 상대경로가 아니라 설정된 OnMaru FE origin으로 복귀한다.
- Changed paths: `KakaoOAuthController`, `KakaoOAuthProperties`, OAuth 웹 경계 테스트, Spring/FE 로그인 문서.
- Verification: 상대경로 회귀 테스트가 먼저 실패하는 것을 확인한 뒤 절대 FE URL 기대값으로 통과했다.
- Next step: 전체 Spring API 검증 후 `develop` 대상 PR을 생성한다.
- Open risk: Render에서 커스텀 FE 주소를 쓸 경우 `ONMARU_OAUTH_KAKAO_FRONTENDBASEURL`을 명시해야 한다. 기본값은 `https://www.onmaru.site`다.

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

저장소와 artifact에는 공공데이터 key, operations token, DB URL, provider payload를 남기지 않는다.

## 병합된 후속 작업: #307 / #382

## 2026-09-29 Issue #375 관리자 API 후속 보강

## 2026-09-29 Issue #375 회원 제재와 카카오 OAuth 접근 정책 연결

- Scope: Redis/JTI 영속화는 제외하고, identity 모듈에 공용 `MemberAccessPolicy` 포트를 추가해 카카오 OAuth 신규 로그인과 기존 회원 세션 조회에 동일한 제재 정책을 적용했다.
- Changed: 활성 제재 회원의 OAuth 세션 발급 거부, 기존 세션 조회 차단, 관리자 제재 생성 시 기존 회원 세션 일괄 폐기, InMemory/JDBC identity store의 회원 세션 일괄 폐기 지원.
- Policy: `ACTIVE`이며 시작 시각이 도래했고 종료 시각 전인 제재만 접근을 차단한다. `REVOKED`/`EXPIRED` 또는 아직 시작하지 않은 제재는 허용한다.
- Compatibility: Kakao OAuth 설정만 단독으로 로드되는 테스트/구성에서는 allow-all 기본 정책을 사용하고, 실제 애플리케이션에서는 `AdminSanctionMemberAccessPolicy`가 주입된다.
- Verification: `:modules:identity:test`, 관리자·Kakao OAuth 설정·web admin 관련 Spring 테스트, 제재 세션 폐기 테스트 성공.
- Open risk: 제재 저장소 조회가 로그인/기존 세션 확인 경로에 추가되므로 운영 PostgreSQL 인덱스·쿼리 latency를 확인해야 한다. JTI 영속화는 별도 Issue #492 범위다.

- Branch: `feature/375-admin-api`
- Scope: 병렬 작업으로 관리자 파이프라인 비동기 실행, refresh token family 재사용 탐지, JWT JTI 폐기 기반, 감사 로그 저장소를 추가하고 애플리케이션 wiring을 연결했다.
- Added: `GET /api/v1/admin/audit-logs`, 제재 만료 상태 전환(`EXPIRED`), 후기 moderation 멱등성 처리, 제재·후기·큐레이션·pipeline 감사 로그 기록.
- Security: refresh token 원문/JWT 원문은 저장하지 않으며 JTI와 해시만 사용한다. 현재 JTI 폐기 저장소는 애플리케이션 메모리 기반이므로 운영 Redis/DB 영속화가 후속 필요하다.
- Verification: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.admin.*' --tests 'com.yrootlab.onmaru.web.admin.*' --no-daemon --console=plain` 성공.
- Open risk: 회원 제재를 일반 회원 OAuth 로그인·기존 세션·전체 쓰기 경계에 연결하는 것은 identity 모듈과의 의존성 경계 검토 후 별도 작업이 필요하다. 관리자 mutate 전체 endpoint의 멱등성 확대도 남아 있다.

- **브랜치**: `fix/307-odii-production-jdbc-store`
- **관련 이슈**: #307, #382
- **상태**: production의 in-memory `AudioRevisionStore` 선택 경쟁 재현 및 수정, 운영 재배포 전

## 확인한 운영 증거

- Render health는 `UP`이지만 Odii 목록·홈·검색·근처·추천 API는 모두 `503 SERVICE_UNAVAILABLE`이다.
- Neon에서 `odii-audio` lease generation은 증가하지만 dataset revision, active pointer, stage, story, sync run은 모두 0행이었다.
- 배포 API에 초기화 패치 이후 추가된 `/api/stories`가 존재하므로 단순 구버전 배포만으로는 설명되지 않는다.
- `JdbcAudioRevisionStore`가 선택되면 bean 생성 시 bootstrap revision이 반드시 생성된다. 운영 DB 0행은 JDBC store가 선택되지 않았다는 증거다.

## 구현 내용

- production에서는 in-memory `AudioRevisionStore` fallback을 비활성화하고 JDBC store를 조건 없이 등록하도록 변경했다.
- `OdiiStoryQueryStore`가 명시적인 `AudioRevisionStore`를 요구하도록 변경해 production DB 설정 오류를 fail-fast 처리한다.
- 설정 등록 순서를 반대로 하거나 DataSource 설정을 나중에 처리하는 통합 테스트를 추가했고, 수정 전 실패·수정 후 성공을 확인했다.
- `troubleshooting-worklog/26.09.19 production-audio-revision-store-and-neon-persistence.md`의 평문 DB credential을 제거했다.
- 현재 working tree에는 동일 credential 패턴이 없지만 기존 Git 이력에는 남아 있으므로 폐기·교체와 이력 정리가 필요하다.

## 검증

- `JdbcAudioRevisionStoreIntegrationTests.productionProfileSelectsJdbcRevisionStoreRegardlessOfConfigurationRegistrationOrder` — 성공
- `JdbcAudioRevisionStoreIntegrationTests.productionProfileSelectsJdbcRevisionStoreWhenDataSourceConfigurationIsProcessedLater` — 성공
- `./gradlew :modules:audio:test :adapters:tourism-api:test :apps:spring-api:test --tests '*Odii*' --tests '*JdbcAudioRevisionStoreIntegrationTests'` — 성공
- `./gradlew test --no-daemon --max-workers=1` — 성공, 53 tasks
- `bash scripts/verify-contracts` — 성공
- `git diff --check` — 성공

## 다음 단계

1. PR을 `develop` 대상으로 생성하고 `verify` 통과를 확인한다.
2. `master` 릴리스 및 Render 재배포 후 bootstrap revision → stage → story → active pointer 순서로 Neon을 확인한다.
3. stage가 `SOURCE_FAILED`이면 Render provider 오류와 `storyBasedSyncList` 실제 응답을 추가 진단한다.
4. 모든 Odii API 200 확인 후 #307을 종료한다. #382의 run 이력·phase 관측성은 별도 구현을 계속한다.
- User request: 전체 revision을 Java로 복원하던 구조가 만들어진 배경, 운영 문제, SQL read model 전환 과정을 비개발자도 이해할 수 있는 약 7천 자 트러블슈팅으로 설명한다.
- Worklog: `troubleshooting-worklog/26.09.28 odii-full-revision-read-origin-and-fix.md` (7,521자).
- Key conclusion: revision의 원자적 게시 목적은 유지하되, 조회 시에는 활성 revision ID를 SQL 조건으로 사용하고 응답에 필요한 최소 행만 Java로 전달한다.
- Verification: 문서 분량·구성 확인과 `git diff --check`를 수행한다. 코드 변경과 배포는 없다.

## 2026-09-29 Issue #375 SQL·레이어 최적화 및 FE 히트맵 계약

- V035 `catalog_admin_curation_overrides(canonical_place_id, category, version DESC, updated_at DESC)` 인덱스를 추가하고, 큐레이션 조회가 place/category별 최신 override만 DB에서 선택하도록 변경했다.
- `JdbcVisitReviewStore.update`를 단일 JDBC transaction과 `SELECT ... FOR UPDATE` 기반 단건 조회·수정으로 변경해 전체 리뷰 snapshot 및 다중 transaction 호출을 제거했다.
- 관리자 dashboard pipeline 상태를 하드코딩하지 않고 `AdminPipelinePort`의 실제 상태를 표시하도록 연결했다.
- curation/sanction/pipeline mutation에 `Idempotency-Key` 처리를 확대하고 OpenAPI 계약을 동기화했다.
- FE Issue #245 원문에 온기모드 히트맵 전환 요구사항을 추가했다. 백엔드는 기존 FE viewport 파라미터와 맞는 `GET /api/map/heat`를 제공하지만, FE `src/app/api/map/heat/route.ts`의 직접 DataLab 호출·혼잡도 계산 제거는 FE 작업으로 남아 있다.
- Remaining: 관리자 후기·대시보드·운영 큐의 전체 snapshot 조회는 데이터 규모가 커질 경우 SQL 페이지네이션/집계 read port로 추가 전환해야 한다. 이번 변경은 mutation 경로와 최신 curation 조회의 병목을 우선 해소했다.
- Verification: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.admin.*' --tests 'com.yrootlab.onmaru.admin.*' --no-daemon --console=plain`, `./gradlew :adapters:persistence-jdbc:test --no-daemon --console=plain`, `node --test scripts/test/migration-policy.test.mjs`, `git diff --check` 성공.

## 2026-09-29 Issue #375 온기모드 히트맵 BE 계산 보강

- FE `VisitorService`와 BE 구현을 대조한 결과, 기존 BE는 날짜별 지역 방문자 수를 날짜 최대값으로만 정규화했고 FE는 방문자 수·지역민 수·전체 최대 방문자 수를 조합했다. 또한 DataLab `touDivCd`를 저장 단계에서 `TOTAL`로 소실하고 있었다.
- `VisitorObservation`에 `visitorType`을 보존하고 DataLab `touDivCd`를 JDBC snapshot에 저장하도록 변경했다. 기존 생성자는 `TOTAL` 기본값을 유지한다.
- BE 히트맵 점수를 FE 산식에 맞춰 계산하도록 변경했다: `concentration 45% + volume 55%`, `surgeMultiplier`, `RELAXED/MODERATE/BUSY/SURGE` 등급. 지역민 관측값이 없는 기존 데이터는 명시적으로 120,000 baseline을 사용한다.
- `/api/map/heat`가 FE의 level별 최소 반경 규칙을 적용하고, `coverageStatus`, `origin=DERIVED_INDEX`, `spatialLevel=SIGUNGU`, 관측 기간, `methodologyVersion=warmth-v2` 메타데이터를 반환한다.
- 온기모드 데이터는 장소별 실측이 아니라 SIGUNGU 공공 방문 관측값을 중심 좌표에 표현한 파생 지수다. FE는 이를 장소의 현재 혼잡도라고 표시하지 않아야 한다.
- FE Issue #245에 메타데이터 처리, 결측/오래된 데이터 처리, DataLab 직접 호출 제거 요구사항을 추가했다.
- Verification: insights/web boundary 및 JDBC visitor heat spot 테스트 성공, compile 성공, `git diff --check` 성공.

## 2026-09-29 Issue #375 PR 전 리뷰 보강

- FE 기본 요청 `metric=VISIT_COUNT`가 BE의 `CONGESTION_SCORE` spot을 정상적으로 조회하도록 metric 계약을 정규화했다.
- `observations` JDBC 조회에서 `DOMESTIC`/`TOTAL` 계열만 반환해 LOCAL·DOMESTIC·TOTAL 중복 시계열을 제거했다.
- 부분 날짜 series를 0으로 합성하지 않고 모든 spot에 관측값이 존재하는 공통 날짜만 반환한다.
- Idempotency-Key 누락/잘못된 UUID는 인증 실패가 아닌 `400 VALIDATION_ERROR`로 반환하도록 관리자 mutation controller를 보강했다.
- primary `/api/v1/insights/heatmap`에도 `origin`, `spatialLevel`, 관측 기간, `methodologyVersion`을 추가하고 OpenAPI fixture를 동기화했다.
- Verification: insights/admin Spring 테스트, contract validation, migration policy, `git diff --check` 성공.

## 2026-09-29 Issue #375 PR 충돌 정리

- PR #510이 `develop` 갱신으로 `DIRTY` 상태가 되어 `origin/develop`을 현재 작업 브랜치에 병합했다.
- `handoff.md`는 현재 세션의 PR·검증·운영 위험을 보존해야 하므로 작업 브랜치 버전을 유지했고, `develop`의 삭제 변경과 충돌을 해결했다.
- Next step: merge commit을 커밋·push한 뒤 PR #510의 CI 재실행과 mergeable 상태를 확인한다.
