# handoff.md

- **Date**: 2026-09-23 FE 전달 이슈 #342, #343, #344, #346 API 계약·운영 상태 점검
- **Branch**: `feature/342-fe-api-contract-fixes`
- **Related Issues**: #342, #343, #344, #346 (관련 기존 이슈 #241, #307, #345)
- **Findings**: 운영에서 `/api/v1/map/places`·`/api/v1/places/{placeId}`는 200이나 FE 보고서의 구 경로 `/api/map/*`, `/api/place/{id}`는 404. `/api/v1/hanoks/screen-hanok`는 구현돼 있으나 `200 {"total":0,"items":[]}`로 게시 데이터가 비어 있음. `/api/stories` 호환 API는 develop에는 병합됐으나 현재 운영 배포에는 404. `/api/map/heat`와 `/api/map/warmth`는 미구현이며, 히트맵의 실제 계약은 date-required `/api/v1/insights/heatmap`.
- **Implemented/Verified**: 한옥 도감 목록만 한옥 카페를 제외하고 홈 큐레이션은 기존 포함 정책을 보존했다. `/api/map/places`, `/api/place/{placeId}`, `/api/map/heat`를 기존 versioned API와 동일 계약의 호환 경로로 추가했다. VisitReview를 온기 후기 UI의 단일 API로 확장해 선택 `mood`(북적/한적), 선택 `score`(1..5), 선택 `tags`(최대 5개·각 20 code points)를 작성·조회 응답에 포함하고 V018 DB migration을 추가했다. 단일 VisitReview 경계 테스트와 community 모듈 전체 테스트, 변경 경계·R1 E2E·홈 회귀 테스트, `bash scripts/verify-contracts`를 통과했다.
- **Open Risk / Next Step**: 전체 `:apps:spring-api:test`는 기존 PostgreSQL 통합테스트 `JourneyMigrationTests.resetAndMigrate`의 DB reset SQL 대기 때문에 중단했다(VisitReview 변경과 무관한 외부 DB 대기). 스크린 한옥 프로덕션 수집 스모크(#241 후속)와 Odii sync 복구(#307)를 별도 실행 이슈로 추적한다. `visitorCount`는 활성 DataLab revision의 최신 지역 외지인 관측값으로 추가됐으며, Catalog source-code 공식 검증·운영 키 등록 뒤 production smoke test가 남아 있다.

- **Date**: 2026-09-23 SSoT umbrella 이슈 완결성 재점검
- **Request**: #342, #343, #344, #346의 부분 구현을 모두 완료해 달라는 사용자 요청.
- **Evidence**: #343이 지시한 screen-hanok route/mediaType/region/source-filter/FastAPI 호출/일일 스케줄은 Issue #241로 구현·검증됐고, 현재 0건은 운영 Gemini secret·게시 후보 데이터가 필요한 production smoke 미실행 상태다. #344의 map route는 구현했고 warmth는 별도 endpoint가 아닌 VisitReview로 문서화했다. #345의 세 Sorimaru route는 develop PR #358로 완료했으며 Redis cache는 구체 후속 #360에 남아 있다. #346의 Admin과 Journey Gemini는 request/response·권한·수용 조건이 없는 상태 확인 항목이다.
- **Plan**: `docs/superpowers/plans/2026-09-23-fe-sso-completion.md`에 #343 persistence·#360 Redis cache·운영 smoke를 분리해 기록. Admin과 Journey public API는 FE 계약 없이는 임의 구현하지 않는다.
- **Implemented after plan**: #343용 V019 `catalog_screen_hanok_placements` migration과 `JdbcScreenHanokPlacementStore`를 추가했다. production profile에서 DataSource가 있으면 JDBC snapshot을 사용하고, 로컬/DB 없는 profile에서는 기존 in-memory store를 유지한다. PostgreSQL Testcontainers에서 snapshot 전체 교체와 재생성 store read를 검증했다.

- **Date**: 2026-09-24 VisitReview 장소 식별자 ADR 및 Catalog mapping
- **Decision**: ADR-0014를 수락하고, 방문/온기 후기는 Catalog 내부 UUID FK와 작성 시점 공개 장소 snapshot을 함께 보존하기로 했다. 초안 ADR-0012·0013은 ADR-0014로 superseded 처리했다.
- **Implemented**: V020 `catalog_place_public_ids` 및 `CatalogPublicPlaceIdStore`/`JdbcCatalogPublicPlaceIdStore`를 추가했다. `p-*` 공개 ID와 Catalog UUID는 DB의 PK·UNIQUE 제약으로 일대일이며, 동일 pair 재등록만 idempotent하다. Testcontainers로 재생성 store 조회와 양방향 충돌 거절을 검증했다.
- **Implemented after ADR**: V021에 `public_place_id`, 장소명·권역·좌표 snapshot을 추가하고 `JdbcVisitReviewStore`를 구현했다. production + DataSource에서는 JDBC VisitReview store와 `JdbcVisitReviewPlaceLookup`을 wiring한다. lookup은 active revision의 `ACTIVE`·`visit_review_eligible` 장소만 반환하며, mapping/상태가 없으면 신규 후기를 거절한다. store는 Catalog UUID FK, snapshot, 온기 필드, 좋아요를 transaction으로 저장한다. Community 서비스는 `MutableVisitReviewStore` port에 의존하도록 전환했다.
- **Verification**: `JdbcVisitReviewStoreTests`, `JdbcVisitReviewPlaceLookupTests`, `VisitReviewCommandWebBoundaryTests` 총 6건이 통과했고 `bash scripts/verify-contracts`, `git diff --check`를 통과했다.
- **Implemented after JDBC hardening**: 신고·moderation audit은 `JdbcReviewReportStore`, idempotency receipt는 `JdbcIdempotencyStore`로 production에서 저장한다. VisitReview write와 idempotency receipt는 같은 JDBC transaction runner를 공유하며, 실패하면 함께 rollback된다.
- **User direction (2026-09-25)**: production에서 VisitReview 신고·moderation audit, command idempotency, DataLab visitor observation을 in-memory로 처리하지 않는다. 모두 JDBC로 전환하고 DB가 없거나 사용할 수 없으면 fallback write가 아닌 명시적 unavailable 오류로 처리한다. Redis는 별도 후속으로 유지한다.
- **Implemented DataLab pipeline**: `JdbcVisitorObservationStore`와 전용 dataset revision publisher가 관측값을 PostgreSQL에 저장하고, 활성 revision만 조회한다. DataLab은 광역·기초 endpoint를 전국 단위로 수집한 뒤 Catalog의 공식 검증된 `SIDO:<code>`/`SIGUNGU:<code>` mapping으로 매핑하며 외지인(`touDivCd=2`)만 `visitorCount`로 제공한다. production은 in-memory fallback을 사용하지 않으며 매일 03:30 KST scheduler가 수집한다.
- **Implemented DataLab official mapping (2026-09-26)**: 공식 v4.1 매뉴얼과 인증된 전국 응답을 대조해 `kr-11→SIDO:11`, `kr-11-jongno→SIGUNGU:11110`, `kr-45→SIDO:52`, `kr-45-jeonju→SIGUNGU:52110`을 V025로 등록했다. Catalog 행이 Flyway 이후 적재돼도 DB trigger가 mapping과 공식 URL·검증시각·검증자를 영속화한다. DataLab `touNum`의 소수 응답은 공개 `int64 visitorCount` 계약에 맞춰 가장 가까운 1명으로 반올림한다. `DataLabVisitorClientTests` 4건, source adapter 2건, JDBC publisher 3건과 `git diff --check`가 통과했다.
- **PR readiness (2026-09-26)**: Issue #393의 legacy review snapshot backfill, SQL NULL 좌표 제외 정책, `SELECT ... FOR UPDATE` 기반 좋아요 갱신, 신고·멱등성의 공유 JDBC transaction, production Insights JDBC query를 보완했다. `./gradlew test --no-daemon --max-workers=1`(53 tasks), 계약 검증, AI 212 tests·lint·mypy·offline eval을 통과했다. Redis cache는 사용자 요청대로 이번 PR 범위에서 제외하며 Issue #392의 production DataLab smoke는 운영 배포 후 수행한다.

- **Date**: 2026-09-23 소리마루 Single Source of Truth API 구현 및 PR 준비
- **Branch**: `feature/345-sorimaru-single-source-of-truth`로 rename 예정
- **Related Issue**: #345
- **Changed**: `/api/stories`, `/api/stories/nearby`, `/api/recommendation`, FE 전달 보고서
- **Verified**: `./gradlew test --no-daemon --max-workers=1`, `bash scripts/verify-contracts`
- **Open Risk**: Redis 캐시는 의도적으로 제외. 운영 active Odii dataset 배포 확인 필요

- **Date**: 2026-09-21 홈 API 운영 검증 및 후속 이슈 정리
- **Branch**: `feature/hanok-api-related-improvements` (master 576abfa 병합 완료, 병합 커밋 33ccf94)
- **Related Issues**: #264 (코멘트 남김), 신규 #306 (지역별 오디오 그룹 API), 신규 #307 (trending-sounds 503 원인 분석)
- **Verified**:
  - 운영 v0.3.12 배포 후 `GET /api/v1/home/curated-courses?limit=25` → 25개 중 24개 실제 TourAPI 썸네일 URL, URL 다운로드 테스트 200 OK (이미지 문제 해결)
  - cursor 페이징 정상 동작 (items/hasMore/nextCursor 계약)
  - 운영 Neon DB 조사: `audio_revision_stages`/`audio_story_versions`/`catalog_active_datasets` 0행 → trending-sounds 503 원인은 Odii 동기화 staging 실패. `operations_sync_leases` odii-audio generation 15로 스케줄러는 실행 중
- **Next Steps**:
  - Render 로그에서 `odii sync failed` 확인, `ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT` 유효성 점검 (#307)
  - `GET /api/v1/audio/regions` 지역 그룹 카운트 API 구현 (#306)
