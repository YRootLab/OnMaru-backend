// # Database Schema
//
// 이 문서는 온마루(OnMaru) 프로젝트의 데이터베이스 스키마 구조를 정의합니다.
// 최신 회원/세션/탐색/저장/VisitReview/ReviewLike 설계:
// 원천별 revision/FK, OAuth 확장, sync schedule/checkpoint 후속 스키마:
// ../spring/catalog-ingestion.md
// ../spring/identity-and-journey.md
// 게시 revision 및 lease: ../operations/runtime-and-reliability.md
// 새 DBML 기반 ERD entrypoint:
// ./schema.dbml
// Level 1 overview:
// ./overview.dbml
// 아래 DBML은 역사적 Odii 모델이며 새 migration의 전체 스키마가 아니다.
// Journey durable run의 최신 논리 계약은 ./modules/discovery.dbml의
// discovery_runs와 discovery_run_commands에 있다.
// command receipt와 run mutation은 같은 PostgreSQL transaction에서 commit되어야 하며,
// run snapshot이 SSE보다 우선하는 정답이다.
// Retention cleanup ledger와 late saved write 차단은 V015 Flyway migration을
// 기준으로 한다. operations_retention_deletion_ledger는 resource_type,
// resource_id, reason unique index로 replay-safe 삭제 기록을 남기고,
// identity_deletion_ledger가 REQUESTED/COMPLETED인 member의 saved resource와
// saved journey write는 trigger에서 거절한다.
// VisitReview의 최신 실행 스키마는 V007, V018, V021 Flyway migration을 기준으로 한다.
// community_visit_reviews의 mood(varchar, nullable), score(smallint, nullable),
// tags(jsonb array, 기본 [])는 지도 온기 후기 표시에 사용한다. mood는 북적/한적,
// score는 1..5로 DB와 애플리케이션 경계에서 검증한다.
// visitorCount는 V023 이후 활성 `kto-datalab-visitor` revision의 최신 COMPLETE
// 지역 관측값을 조회해 제공한다. 원천 결측은 0이 아니라 null이며 후기 자체에는
// 중복 저장하지 않는다.
// V024 이후 DataLab 지역 호출에 쓰는 catalog_region_source_codes row는
// catalog_region_source_code_verifications의 공식 HTTPS 근거, 검증 시각, 검증자를
// 반드시 가져야 한다. 미검증 code는 JDBC lookup에서 제외한다.
// V025는 공식 DataLab 지역별 방문자 수_GW의 전국 응답과 v4.1 활용 매뉴얼을 근거로
// 내부 법정동 코드와 다른 DataLab 코드(`SIDO:11`, `SIDO:52`, `SIGUNGU:11110`,
// `SIGUNGU:52110`)를 활성 Catalog 지역에 등록한다. Catalog 행이 Flyway 이후 적재되어도
// trigger가 mapping과 공식 검증 이력을 같은 DB에 기록한다.
// V027은 catalog_datalab_region_mappings를 source-code row의 DataLab 전용 기간 registry로
// 추가한다. 내부 지역과 DataLab code의 유효기간 중복, SIDO/SIGUNGU 계층·parent 불일치,
// 공식 verification row와 다른 ACTIVE provenance를 DB에서 거부한다. 종료된 mapping 뒤에는
// 새 valid_from 이력을 추가할 수 있다. PENDING/REJECTED mapping은 감사 이력에는 남지만 운영
// 수집 대상으로 선택되지 않는다.
// 스크린 한옥의 최신 게시 snapshot은 V019 Flyway migration을 기준으로 한다.
// catalog_screen_hanok_placements는 FastAPI 리서치 결과 중 출처 URL이 있는 항목만
// 저장하며, 전체 snapshot 교체 transaction으로 마지막 검증된 게시본을 보존한다.
// Catalog의 FE 공개 장소 ID는 V020 Flyway migration을 기준으로 한다.
// catalog_place_public_ids는 public_id(p-lowercase-kebab-case)와 catalog_place_identity UUID를
// 각각 PK/UNIQUE로 묶는다. 같은 pair의 재시도만 허용하며, Community는 이 mapping을 생성하거나
// 변경하지 않고 Catalog가 만든 mapping을 조회해서 VisitReview의 작성 시점 장소 snapshot에 사용한다.
// V021은 community_visit_reviews에 public_place_id, place_name, region_code, latitude, longitude를
// nullable migration으로 추가한다. 기존 row와의 호환을 위해 nullable로 도입하되 JDBC 신규 write는
// active·visit_review_eligible Catalog version을 조회한 뒤 모든 snapshot 필드를 채운다.
// V026은 V021 이전 VisitReview에 결정적 p-legacy-* 공개 ID를 등록하고 가능한 최신
// published Catalog version의 장소명·지역·좌표 snapshot을 backfill해 JDBC 전환 시
// 기존 후기가 조회에서 사라지지 않게 한다.
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

// 2-1. Production revision/link 보강 (#197)
// 실제 PostgreSQL baseline은 docs/database/modules/audio.dbml 및
// V008-V010 Flyway migration을 기준으로 한다.
// - audio_revision_stages: staged revision의 ready/failure/row/tombstone 상태를 저장한다.
// - audio_spot_versions/audio_story_versions: source_modified_at, observed,
//   missing_observations를 보존해 LKG 복사와 tombstone publish를 지원한다.
// - audio_story_versions.transcript_provenance와 audio_subtitle_lines가
//   공개 projection의 transcriptStatus/transcript line을 구성한다.
// - audio_place_odii_links.review_status는 PENDING/APPROVED/REJECTED이며,
//   partial unique index audio_place_odii_links_one_approved_per_spot_uq로
//   spot별 APPROVED 연결을 최대 1개로 제한한다.

// 관계 (Relationships)
Ref: audio_guides.(tid, tlid) > tour_spots.(tid, tlid)
