# handoff.md

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
