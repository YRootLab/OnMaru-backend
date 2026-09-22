# handoff.md

- **Date**: 2026-09-21 찜 영속화 + 이번 주 인기 한옥 소리 TOP N
- **Branch**: `feature/317-saved-popularity-persistence` (Issues #317, #318)
- **State**:
  - V017 마이그레이션: `journey_saved_places`, `journey_saved_odii_stories`, `audio_story_play_events` (기존 journey_saved_resources는 uuid resource_id 한계로 미사용 비고)
  - JDBC 스토어: `JdbcSavedPlaceStore`, `JdbcSavedOdiiStoryStore`, `JdbcOdiiStoryPopularityStore` — `@Profile("production") + @ConditionalOnBean(DataSource) + @ConditionalOnMissingBean` 패턴, 로컬/테스트는 인메모리 유지
  - 마커 인터페이스 `SavedPlaceRecordSource`/`SavedOdiiRecordSource`로 빈 타입 구분 (인메모리 @Primary 테스트 빈과 충돌 없음)
  - `GET /api/v1/home/popular-sounds` (기본 limit 7, window=week) + 호환 경로 `/api/home/popular-sounds`
  - `POST /api/v1/odii/stories/{storyId}/plays` 재생 기록 (CSRF 필요, 비인증 허용)
  - 점수 = 2×재생 + 저장(최근 7일). 신호 없으면 최근 게시순 폴백 (`basis: FALLBACK_RECENT`)
  - 기존 `trending-sounds` 계약 무변경
  - docs: `docs/toFE/popular-sounds.md`, journey.dbml/audio.dbml 갱신
  - 선병합 실패 수정: `MonthlyHanokEditionWebBoundaryTests` thumbnailUrl 기대값을 #301 이미지 시드 변경에 맞게 갱신
- **Verification**: `:modules:audio:test` PASS, spring-api 관련 경계 테스트 PASS (editorial 수정 포함)
- **Next**: PR 생성(verify CI) → develop 머지, Render Odii 키 교체 후 #307 검증

- **Active Issue**: 없음 (Issue #239 전체 23개 컨트롤러 및 DTO Swagger 명세화 완료 및 PR #240 머지)
- **Current Branch**: `develop`
- **Current State**:
  - Spring API 23개 전체 컨트롤러 및 요청/응답 DTO에 OpenAPI 3.1 명세(@Tag, @Operation, @Parameter, @ApiResponse, @Schema 등) 적용 완료
  - Swagger UI(`/swagger-ui/index.html`) 및 5개 기능 그룹별 OpenAPI 스펙 구성 완료
- **Verification**: `./gradlew test` (PASS), `node --test scripts/test/*.test.mjs` (54 PASS)

- **Active Issue**: #288 로컬 개발 서버 CORS 포트 범위 허용
- **Current Branch**: `fix/288-local-cors`
- **Current State**:
  - `/api/**` CORS allowlist에 `http://localhost:3000`부터 `http://localhost:3007`까지 등록
  - wildcard Origin 없이 명시적 Origin, 허용 메서드·헤더 및 credentials 정책 적용
  - 기존 CSRF same-origin 정책은 유지
- **Verification**: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.config.LocalDevelopmentCorsConfigurationTests'` (PASS)

- **Active Issue**: #287 Swagger에서 홈 호환 경로 중복 노출 제거
- **Current Branch**: `fix/287-hide-home-compatibility-paths`
- **Current State**:
  - canonical `/api/v1/home/**` 경로만 Swagger/OpenAPI에 노출
  - 기존 `/api/home/**` 호환 경로는 런타임 동작을 유지하고 OpenAPI에서만 숨김
- **Verification**: `./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.swagger.SwaggerEndpointTests' --tests 'com.yrootlab.onmaru.web.home.HomeWebBoundaryTests'` (PASS)

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
