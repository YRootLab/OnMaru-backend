# handoff.md

- **Active Issue**: #239 (Spring API 전체 컨트롤러 및 DTO 대상 Swagger/OpenAPI 3.1 명세 상세화)
- **Current Branch**: `feature/239-swagger-api-docs`
- **Current State**:
  - Spring API 23개 전체 컨트롤러 및 요청/응답 DTO에 OpenAPI 3.1 명세(@Tag, @Operation, @Parameter, @ApiResponse, @Schema 등) 적용 완료
  - 5개 기능 그룹별(한옥/장소, 오디 오디오, 지도/후기, AI 여정 탐색, 개인화/타임라인) OpenAPI 3.1 스펙 및 Swagger UI 구성 완료
- **Verification**: `./gradlew test` (PASS), `node --test scripts/test/*.test.mjs` (54 PASS)

