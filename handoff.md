# handoff.md

- **Active Issue**: #269 A0206 문화시설 cat3 실코드 검증 — 완료 (2026-09-21 수동 close)
- **Current Branch**: `release/0.3.13` (정리 완료, 로컬 브랜치 삭제)
- **Current State**:
  - PR #308 (feature/269-culture-facility-cat3) develop 병합, PR #309 (release/0.3.13 → master) 병합
  - 태그 v0.3.13 및 GitHub Release 발행 (master 2a76f4f). Release Please는 이슈 #277 조직 Actions 설정 미적용으로 PR 생성 실패 → 수동 태그 처리
  - Staging Deploy 실패는 기존 문제(Trivy SARIF category 중복 → 이전 배포 실패 → 롤백 digest 부재 연쇄). 이번 릴리즈와 무관, 별도 후속 필요
- **Verification**: `./gradlew check` (PASS), `verify` CI (PASS), `gh release view v0.3.13` (PUBLISHED)
- **Follow-ups**: ① Release Please 조직 설정(이슈 #277) ② Staging Deploy Trivy category 중복 수정 ③ A0206 전체 cat3 코드표 전수 capture(categoryCode2?cat2=A0206, serviceKey 필요) ④ release/0.3.13 원격 브랜치는 ruleset상 삭제 불가 — 기존 release/*와 동일하게 보관



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
