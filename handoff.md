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
