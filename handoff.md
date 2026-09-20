# handoff.md

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
