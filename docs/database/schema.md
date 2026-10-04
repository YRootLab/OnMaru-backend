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
// V038은 operations_sync_runs에 nullable trigger_source, lifecycle_status,
// failure_phase, lease_generation을 추가해 Odii scheduler 실행 이력을 저장한다.
// V039는 관리자 cursor 목록의 필터 전체 건수와 keyset page 조회를 위해
// identity_members(status, created_at, id), 좌표 snapshot이 완전한
// community_visit_reviews(status, created_at, id), OPEN community_review_reports
// (reason, created_at, id)에 전용 index를 추가한다. totalCount count query에는
// cursor 조건을 적용하지 않아 첫 페이지와 다음 페이지가 같은 필터 전체 건수를 반환한다.
// 기존 status enum은 유지하며 STARTED→RUNNING, COMPLETED→SUCCEEDED,
// FAILED→FAILED, SKIPPED→ABANDONED로 대응한다. 다른 sync job은 새 컬럼을 null로 유지한다.
// 시작과 terminal은 같은 id로 독립 commit하며 terminal 재기록은 기존 terminal을 덮어쓰지 않는다.
// error_code에는 내부 오류 코드만 저장한다. counts의 fetched는 정상 수신 원천 story 수
// (중복·curation 제외 포함), mapped는 중복 제거·curation 후 성공적으로 변환한 story 수다.
// staged/published는 이전 revision 복사분을 포함한 spot+story 행 수이며 실패/skip의 published는 0이다.
// tombstones는 삭제 후보 spot+story 집계이며 실패의 counts는 관측된 부분값이다.
// 과거 run에는 fetched/mapped가 없을 수 있다. 운영 판정: ../operations/runbooks/odii-sync.md
// provider 원문·예외 메시지·credential은 저장하지 않고 DB 장애 시 로그가 진단 경로가 된다.
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
// 한옥 수결첩의 실행 스키마는 V028을 기준으로 한다. stamp_definitions와
// stamp_region_rules가 수결 표시 정보와 canonical 지역 조건을 소유하고,
// stamp_check_ins는 회원·Catalog 장소·15분 bucket 관계와 서버 판정 거리/정확도만 저장하며,
// CHECK(distance_meters - accuracy_meters <= 200)로 성공 판정 반경도 DB에서 보호한다.
// 요청 latitude/longitude 원문은 저장하지 않는다. stamp_awards는 회원별 수결을 한 번만
// 허용하며 trigger_check_in_id와 member_id의 복합 FK로 다른 회원의 체크인을 참조하지 못한다.
// 체크인·수결·idempotency receipt는 동일 JdbcTransactionRunner transaction으로 commit한다.
// V029의 stamp_ranking_profiles는 회원별 익명 랭킹 참여 설정을 저장한다. 참여 기본값은 false이며
// 미참여 시 ranking_public_id와 공개 닉네임 필드는 null이다. 참여 시에만 랜덤 공개 UUID,
// 생성형 닉네임과 정규화 닉네임, 동의 시각이 필수이고 withdrawn_at은 null이어야 한다.
// 참여 중인 공개 UUID와 정규화 닉네임에만 partial unique index가 적용된다. 회원 삭제는
// ON DELETE CASCADE로 설정 row를 함께 제거한다. 순위 점수는 stamp_awards에서 조회 시
// 계산하며 profile이나 별도 테이블에 저장하지 않는다.
// V030부터 production OAuth와 회원 lifecycle은 identity_members를 포함한 JDBC 원장을
// 단일 Source of Truth로 사용한다. identity_oauth_states.pkce_verifier_hash는 PKCE 검증값의
// SHA-256 hash만 저장하고 상태 consume 시 nonce/provider와 함께 원자적으로 검증한다.
// 탈퇴 cleanup은 DELETING 회원 row를 잠근 뒤 수결 획득·체크인·랭킹 profile과 회원 UUID를
// subject_id로 가진 HTTP idempotency receipt를 삭제한다. 각 receipt는 복합 키의 SHA-256으로
// 식별해 개인정보를 복제하지 않고 deletion ledger에 기록하며, 모든 대상이 사라진 뒤에만
// 회원 deletion ledger를 COMPLETED로 전환한다. DELETING tombstone은
// cleanup과 경합한 체크인 또는 랭킹 참여가 개인정보 row를 다시 만들지 못하게 한다.
// 탈퇴 후 동일 외부 계정으로 재로그인하면 DELETING 회원의 external account 연결을
// 원자적으로 해제하고 새 ACTIVE 회원·프로필에 연결한다. 이전 회원의 deletion ledger,
// 폐기된 세션과 데이터는 새 회원 ID로 이전하지 않으며 기존 cleanup 대상에 남는다.
// 기존 연결의 회원 ID에 유효한 관리자 제재가 있으면 연결을 해제하기 전에 로그인을 거부한다.
// V031은 TourAPI 국문 v4.4에서 기존 areaCode/sigunguCode를 대체한 법정동 코드
// lDongRegnCd/lDongSignguCd를 catalog_kto_korean_content_versions에 보존한다.
// 최초 areaBasedList2 전 페이지는 적격성 판정과 무관하게 원천 version에 저장하고,
// 공개 catalog_place_versions만 공식 lclsSystmCode2 분류명·좌표 정책을 통과한 행으로 구성한다.
// 제목에 "한옥"이 포함되는지는 공개 적격성 판정 기준으로 사용하지 않는다.
// V032는 한국관광공사 DataLab의 2026-08-23 전국 응답을 검증 snapshot으로 사용해
// 시도 16개와 시군구 269개의 provider code를 catalog_regions,
// catalog_region_source_codes, catalog_datalab_region_mappings에 등록한다.
// 매핑은 공식 data.go.kr URL과 검증 시각을 보존하며, API의 약 35일 제공 지연을 고려한
// 일별 방문자 동기화와 DB 기반 행정구역 원형 히트맵의 지역 레지스트리로 사용한다.
// V033은 ODII 공개 조회를 활성 revision 전체 Java snapshot 복원에서 PostgreSQL read model로
// 전환한다. audio_odii_spots.public_id와 audio_odii_stories.public_id는 기존 Java
// UUID.nameUUIDFromBytes 공개 ID와 동일한 generated stored UUID이며 (public_id, lang_code)
// unique index로 상세 탐색한다. 같은 provider ID의 언어별 identity는 허용하므로
// public_id 단독은 unique 제약으로 사용하지 않는다.
// 활성 목록은 audio_story_versions_active_page_idx로 keyset pagination하고, 인기 조회는
// audio_story_play_events_occurred_story_idx로 기간을 먼저 제한해 DB 안에서 집계한다.
// 자막은 기존 (revision_id, story_id, position) PK가 상세 한 건 조회와 순서를 모두 지원하므로
// 중복 index를 추가하지 않는다.
// Historical Odii model. The 2026-09-09 successor proposal is in
// ../planning/data-api-design.md; executable migrations are not yet created.
// V034는 Admin actor 계정/refresh session, 회원 제재, Catalog curation override와
// append-only Admin audit log의 저장 경계를 추가한다. Admin actor의 ADMIN/EDITOR role은
// 일반 회원의 USER 표시값과 분리한다. refresh token 원문은 저장하지 않고 SHA-256 hash만
// identity_admin_sessions에 저장하며, 후기/신고 변경은 기존 community moderation command와
// 같은 transaction에서 audit row를 남긴다. curation override는 TourAPI 원천 행을 수정하지 않고
// active Catalog projection에 별도로 적용한다.
// V037은 V036보다 먼저 게시된 활성 TourAPI revision의 지도 장소·카테고리·지역 집계
// projection 및 publication을 원천 장소 변경 없이 채운다. 지역 코드가 없는 장소도
// 위치와 공개 ID가 있으면 지도 목록에 포함하고 지역 집계에서는 제외한다.
// V040부터 identity_member_profiles가 OnMaru 회원의 현재 공개 프로필을 소유한다.
// display_name은 trim된 2~20자 익명 이름이며 중복을 허용한다. character_id는
// CHARACTER_01..10, background_id는 BACKGROUND_01..10의 고정 FE 자산 슬롯만 저장한다.
// 실제 캐릭터 이미지와 배경 HEX는 FE가 관리하며 DB에는 URL이나 HEX를 저장하지 않는다.
// migration은 기존 회원 UUID hash로 프로필을 결정적으로 backfill한다. 신규 회원은 OAuth
// 최초 연결 transaction에서 프로필을 함께 만들고, 후기 조회는 작성 당시 snapshot 대신
// 이 현재 profile을 batch join한다. 회원 row 삭제 시 profile은 ON DELETE CASCADE로 삭제된다.
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
