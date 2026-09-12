// # Database Schema
//
// 이 문서는 온마루(OnMaru) 프로젝트의 데이터베이스 스키마 구조를 정의합니다.
// 최신 회원/세션/탐색/저장/VisitReview/ReviewLike 설계:
// 원천별 revision/FK, OAuth 확장, sync schedule/checkpoint 후속 스키마:
// ../planning/revision-2026-09-11/regional-map-and-ingestion.md
// ../planning/revision-2026-09-11/data-and-identity.md
// 게시 revision 및 lease: ../planning/revision-2026-09-11/runtime-and-operations.md
// 새 DBML 기반 ERD entrypoint:
// ./schema.dbml
// Level 1 overview:
// ./overview.dbml
// 아래 DBML은 역사적 Odii 모델이며 새 migration의 전체 스키마가 아니다.
// Historical Odii model. The 2026-09-09 successor proposal is in
// ../planning/data-api-design.md; executable migrations are not yet created.
// 파일 전체(Cmd+A)를 복사하여 https://dbdiagram.io/ 에 붙여넣으면 
// 에러 없이 시각화된 ERD(관계도)를 볼 수 있습니다.

// ---------------------------------------------------------

// 1. 관광지 (Tour Spot) 테이블
Table tour_spots {
  tid varchar [pk] // 관광지 ID
  tlid varchar [pk] // 관광지 언어 ID
  lang_code varchar // 언어 코드
  theme_category varchar // 테마 카테고리
  title varchar // 관광지명
  addr1 varchar // 주소 1 (시/도)
  addr2 varchar // 주소 2 (시/군/구)
  map_x varchar // 경도 (X 좌표)
  map_y varchar // 위도 (Y 좌표)
  lang_check varchar // 언어 제공 여부 체크
  image_url varchar // 대표 이미지 URL
  created_time varchar // 데이터 생성일시
  modified_time varchar // 데이터 수정일시
  sync_status varchar // 동기화 상태 (A, U, D)

  Note: '한국관광공사 오디(Odii) API의 관광지(Theme) 정보를 저장합니다.'
}

// 2. 오디오 가이드 (Audio Guide / Story) 테이블
Table audio_guides {
  stid varchar [pk] // 이야기 ID
  stlid varchar [pk] // 이야기 언어 ID
  tid varchar // 관광지 ID (FK)
  tlid varchar // 관광지 언어 ID (FK)
  lang_code varchar // 언어 코드
  title varchar // 콘텐츠 제목
  audio_title varchar // 오디오 제목
  script text // 오디오 도슨트 대본/자막
  play_time varchar // 재생 시간 (초)
  audio_url varchar // 오디오 URL
  image_url varchar // 대표 이미지 URL
  map_x varchar // 경도 (X 좌표)
  map_y varchar // 위도 (Y 좌표)
  created_time varchar // 데이터 생성일시
  modified_time varchar // 데이터 수정일시
  sync_status varchar // 동기화 상태 (A, U, D)

  Note: '특정 관광지에 속한 개별 이야기(도슨트) 정보를 저장합니다.'
}

// 관계 (Relationships)
Ref: audio_guides.(tid, tlid) > tour_spots.(tid, tlid)
