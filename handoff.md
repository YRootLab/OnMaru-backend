# handoff.md
# 현재 세션 handoff

- 브랜치: `feature/493-map-info-be`
- Issue: `#493` 지도 정보모드 BE API·SQL·PostGIS 조회 구조
- 구현: API hard timeout, snapshot 변경 시 cache 무효화, 이름/지역 null-safe keyset cursor, active 공간 인덱스 및 PostGIS cluster 쿼리 최적화, DB query duration/coverage/timeout/pool/slow-query 관측성, stale-if-error fallback, snapshot expired 409, PLACE partial coverage, places 계약·장애 응답 검증
- 검증: map-info 단위/웹 경계/cache stale 테스트, PostGIS publication·legacy·cursor 통합 테스트, 30,000건 성능 게이트(재실행 통과; 전체 suite 중 1회는 ARM64 Docker 에뮬레이션으로 p95 일시 초과), OpenAPI/fixture 검증, migration policy, `git diff --check` 통과
- 미추적 파일: `tempGithubIssue/`는 기존 작업물로 보존하고 커밋하지 않음
- 다음 단계: 전체 Gradle 검증, 리뷰, 커밋·원격 push 후 PR 생성 및 #493~#499 연결
