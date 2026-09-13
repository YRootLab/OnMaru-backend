# Spring: catalog ingestion and regional map policy

2026-09-11 사용자 추가 요구에 따른 검토안. 구현·FE 합의·ADR 승인을 의미하지 않는다. 이번 문서의 지도 변경은 기존 1.1 viewport 계약을 대체하는 **1.2 제안**이다. 기존 OpenAPI 1.1을 1.2 구현 계약으로 사용하지 않는다. 여정 API의 1.1 계약은 그대로다.

## 기존 문서에서 확인한 사실과 공백

| 항목 | 기존 근거 | 추가 설계가 필요한 부분 |
|---|---|---|
| 내부 회원 ID | data-and-identity의 members UUID와 external_accounts | KAKAO 고정 표기, 공급자별 port 이름, 계정 연결 정책 |
| 관광공사 장소·오디·통계 | ../data-api-design.md 관계 모델, ../../api/tour 원천 문서 | revision 표에 누락, 모든 원천에 게시 revision을 적용하는 FK/키 |
| 정기 수집 | runtime-and-operations의 일1회·lease·LKG | 03:00 시간대, 누락 실행·재개·원천별 일정 |
| 지도 이동 | api-contract의 350ms 뒤 자동 VIEWPORT | 사용자 요구와 충돌: 지역 집계와 명시적 목록 조회 필요 |
| 페이징 | api-contract의 최신순 cursor, 10분 만료 | 페이지 번호와 배열 인덱스 구분, 집계와 목록의 차이 |

## 공급자와 회원을 분리한다

회원의 정답은 `identity.members.id` 서버 UUID다. Kakao ID, Google subject, Naver subject, 이메일을 회원 PK나 후기 소유자 키로 쓰지 않는다. 외부 식별자는 문자열 그대로 저장하고 숫자로 변환하지 않는다.

`identity.external_accounts`: id UUID PK, member_id FK, provider varchar, issuer varchar, subject varchar, created_at timestamptz; UNIQUE(provider,issuer,subject), INDEX(member_id). provider/issuer는 서버 설정 allowlist로 결정한다. MVP 활성 공급자는 KAKAO 하나지만 GOOGLE/NAVER 추가가 회원·후기·저장 테이블 변경을 요구하지 않는다. provider를 KAKAO만 가능한 DB CHECK로 고정하지 않는다. issuer는 callback 입력을 그대로 신뢰하지 않는다.

identity application이 소유한 `ExternalIdentityPort.authenticate(callback, loginContext)`는 검증된 `ExternalIdentity(provider,issuer,subject)`를 반환한다. Kakao/Google/Naver adapter가 각 프로토콜 검증과 오류 변환을 담당한다. core와 다른 업무 모듈은 공급자 SDK·토큰·프로필 DTO를 참조하지 않는다. app composition에서 공급자를 allowlist로 선택한다. state에는 provider와 목적 LOGIN/LINK를 추가로 결합해 다른 공급자 callback으로 바꿀 수 없게 한다. 구체적인 Google/Naver 검증 필드와 endpoint는 도입 시 공식 명세를 검증하고 별도 계약으로 승인한다.

새 외부 identity는 새 회원을 만든다. 이메일이 같다는 이유로 자동 병합하지 않는다. 계정 연결은 기존 회원의 유효 세션과 재인증, 새 공급자의 인증을 모두 요구하는 별도 후속 기능이다. 다른 회원에 이미 연결된 identity는 409 ACCOUNT_ALREADY_LINKED; 마지막 로그인 수단 해제는 거절한다. MVP에는 연결/해제 API를 노출하지 않는다. 동시 최초 로그인은 unique 충돌 시 전체 회원 생성 transaction을 rollback하고 기존 연결 회원을 다시 읽어 고아 회원을 방지한다.

이메일/비밀번호 가입은 아직 선택하지 않았다. 도입 시 `identity.password_credentials(member_id PK/FK, normalized_email UNIQUE, password_hash, verified_at, password_changed_at)`와 이메일 검증·복구용 일회용 token 저장소를 추가하는 확장점으로 둔다. 비밀번호·복구 정책을 지금 구현 확정한 것으로 해석하지 않는다. 모든 로그인 수단은 동일 OnMaru session 발급 use case에 합류한다.

## 원천별 테이블과 게시 단위

환경당 PostgreSQL 1개를 유지한다. 아래는 executable DDL 이전의 후속 스키마 계약이다. audio/insights는 원천 저장 소유권 구분이며 별도 서비스·DB를 뜻하지 않는다. UUID PK, 시각 timestamptz, 날짜 date, 원본 코드는 text가 기본이다. 원천 응답 필드 매핑의 정답은 기존 국문·오디·방문자·집중률 API 문서와 **Spring adapter가 기록한 실제 호출 fixture**다. FE에서 정상 호출한 사실은 연결 가능성의 근거지만, 서버 수집의 pagination·429·오류 envelope·권한·일일 quota 계약을 대신하지 않는다. 이 검증은 별도 사전 프로젝트가 아니라 첫 Spring ingestion adapter 구현과 함께 수행한다.

| 테이블 | 키·필수 데이터·제약 |
|---|---|
| catalog.regions | id PK, parent_id self FK nullable, name, level SIDO/SIGUNGU, active; 내부 코드와 원천 코드를 분리 |
| catalog.region_source_codes | provider,dataset,source_code,valid_from 복합PK; region_id FK, valid_to nullable; 동일 원천 코드의 유효기간 중복 금지 |
| catalog.region_boundaries | boundary_revision,region_id 복합PK/FK, geometry MultiPolygon(4326), 출처·권리·관측시각; GiST(geometry); 경계 자료 확보 전 좌표→지역 기능 활성화 금지 |
| catalog.place_identity | id PK; 삭제 원천에도 안정 ID 유지 |
| catalog.place_sources | id PK,place_id FK,provider,dataset,external_id,language; UNIQUE(provider,dataset,external_id,language); 미지정 language는 빈 문자열로 정규화 |
| catalog.dataset_revisions | id PK,dataset,status STAGING/READY/PUBLISHED/FAILED,base_revision_id nullable FK,source_observed_at nullable,fetched_at,published_at nullable; UNIQUE(dataset,id) |
| catalog.active_datasets | dataset PK,revision_id; (dataset,revision_id) 복합FK → dataset_revisions |
| catalog.place_versions | revision_id,place_id 복합PK/FK; source_ref FK,region_id FK nullable,name,category,address,location nullable,overview nullable,eligibility,status,normalized_hash; 지역 미해결은 REGION 결과에서 제외 |
| catalog.place_image_versions | revision_id,place_id,position 복합PK; (revision_id,place_id) FK, URL·출처·권리, position>=0 |
| catalog.hanok_detail_versions | revision_id,place_id 복합PK/FK; type,hours,parking,homepage nullable; 국문 상세/한옥 원천 provenance 보존 |
| audio.odii_spots | id PK,provider,tid,tlid,lang_code; UNIQUE(provider,tid,tlid) |
| audio.odii_stories | id PK,spot_id FK,provider,stid,stlid,lang_code; UNIQUE(provider,stid,stlid) |
| audio.spot_versions | revision_id,spot_id 복합PK/FK; title,address,location nullable,status,hash |
| audio.story_versions | revision_id,story_id 복합PK/FK; spot_id, title,script,audio_url,duration_seconds nullable>=0,status,hash; (revision_id,spot_id) FK → spot_versions |
| audio.subtitle_lines | revision_id,story_id,position 복합PK; story_versions 복합FK; text,start_seconds>=0,timing_mode; position 0부터 |
| audio.place_odii_links | place_id,spot_id 복합PK/FK; match_method,confidence,verified_at nullable; 이름·거리만 같은 후보는 검증된 연결로 공개하지 않음 |
| insights.visitor_observations | revision_id,region_id,basis_date,visitor_type 복합PK/FK; provider,spatial_level,count>=0,source_observed_at nullable,fetched_at |
| insights.tourism_targets | id PK,provider,source_target_key UNIQUE tuple,region_id FK,source_name; 원본 ID 없는 경우 정규키 생성 버전 보존 |
| insights.target_place_links | target_id PK/FK,place_id FK,match_method,verified_at; 미해결은 link 없음 |
| insights.concentration_observations | revision_id,target_id,basis_date,metric_type 복합PK/FK; value numeric,unit,source_observed_at nullable,fetched_at; 지표별 범위 검증 |

국문 장소, 오디 spot+story, 방문자 통계, 집중률은 각각 별도 dataset이다. 오디는 부모 spot과 story를 같은 revision으로 게시한다. 모든 version/observation의 revision FK는 해당 dataset과 일치해야 하며 고정 dataset 칼럼+복합 FK 또는 publish validator로 검증한다. 최신 장소 정답은 원천별 필드 우선순위와 provenance를 적용한 Catalog 공개 projection이다. community는 원천 테이블을 직접 선택하거나 갱신하지 않는다.

증분 수집도 공개 revision은 완전한 조회 집합이어야 한다. 새 private revision에 기존 활성 집합을 복사한 뒤 변경·tombstone을 적용한다. 변경분만 넣고 active pointer를 교체하지 않는다. full 미관측 삭제 판정은 기존 2회 연속 성공 규칙을 유지한다. 통계는 최근 30일을 재수집해 정정값을 반영하고 이전 보존 집합에 병합한다. 이 기간은 초기 비용 제한값이며 공급자 실제 정정 주기를 검증해 조정한다. raw payload는 canonical 대체물이 아니며 권리·크기 제한 아래 최대7일 보관한다.

방문자 수는 원천의 일자별 지역 추정 통계이고 OnMaru 접속자 수나 지금 장소에 있는 실사용자 수가 아니다. `basisDate`, `spatialLevel`, `metricType`, `unit`을 함께 전달한다. 후기 수·좋아요 수와 합산하지 않는다. 누락값은 null/미제공이며 0명으로 바꾸지 않는다.

## 전국 주제 allowlist와 03:00 KST 수집과 실패 복구

원천은 한국관광공사 데이터 전체를 그대로 공개하는 저장소가 아니다. 전국 데이터를 수집하되 public catalog·AI 후보·Odii 연결 대상으로 게시하는 범위는 `HANOK`, `HANOK_STAY`, `HANOK_CAFE`, `HANOK_EXPERIENCE`, `TRADITIONAL_MARKET`, 그리고 이 주제와 검수된 관계가 있는 `ODII`다. source adapter는 원천 분류 코드와 검수 mapping을 stable canonical category로 기록하고, 매칭하지 못한 행을 추정해 공개하지 않는다. allowlist와 mapping 버전은 dataset revision에 묶어 재현하며, 실제 원천 응답 capture/fixture로 검증하기 전 LIVE_CANONICAL을 활성화하지 않는다.

| 테이블 | 키·필드·불변식 |
|---|---|
| operations.sync_schedules | dataset PK,timezone='Asia/Seoul',local_time='03:00',enabled,next_due_at; UTC timestamp로 실행 시각 저장 |
| operations.sync_runs | id PK,dataset,scheduled_for,attempt,status QUEUED/RUNNING/SUCCEEDED/FAILED/ABANDONED,revision_id nullable FK,started_at,finished_at,next_attempt_at,error_code,counts; UNIQUE(dataset,scheduled_for,attempt) |
| operations.sync_checkpoints | run_id,partition_key 복합PK/FK; next_page,source_cursor nullable,expected_total nullable,seen_count,last_source_modified,last_external_id; 성공 page stage와 같은 transaction에서 저장 |
| operations.sync_leases | dataset PK,owner_token,generation,lease_until; 기존30초lease/10초heartbeat/fencing 유지 |
| operations.sync_watermarks | dataset PK,source_modified_at nullable,external_id nullable,last_full_success_at,last_success_at,revision_id FK; 성공 publish에서만 전진 |
| operations.sync_quarantine | run_id,record_key 복합PK/FK,error_code,payload_hash,redacted_payload nullable,expires_at; key·token 포함 원문 저장 금지 |

Spring cron 제안은 `0 0 3 * * *`, zone `Asia/Seoul`이다. cron은 durable due run을 등록하는 inbound adapter이고 수집 업무를 직접 수행하지 않는다. 일요일은 full reconcile, 나머지는 공급자가 지원할 때 incremental, 미지원이면 full이다. 호출 범위는 서비스 지원 지역/언어/종류로 제한한다. 오디 AI 출시 여부와 원천 오디 수집 여부는 별개다.

앱이 잠들면 프로세스 내부 cron도 실행되지 않는다. 기동 시 및 깨어 있는 동안 매5분 due scan을 실행하고, dataset별 누락 일정은 최신1개로 합친다. 긴 중단 후 과거 날짜별 전체 배치를 폭발적으로 재생하지 않는다. 최신 due run 등록은 schedule row lock으로 중복 방지한다. 정확히03:00 시작이 필요한 경우 외부 scheduler/상시 worker가 필요하며 무료 호스팅이 선택되지 않아 정확한 실행 시각은 보장하지 않는다.

수집 worker1, 전역 원천 HTTP 동시1, dataset 순차 수행, AI executor와 분리한다. 원격 대기 중 DB connection/transaction을 점유하지 않는다. dataset 실행budget30분, 원천 credential별 일 호출quota는 Spring adapter integration test에서 실제 응답 header/body와 함께 기록한 뒤 20% 여유를 남겨 설정한다. 이 검증은 구현 시작을 막지 않지만, 통과 전에는 `FIXTURE` 또는 `VERIFIED_SNAPSHOT`만 허용하며 `LIVE_CANONICAL` 공개를 켤 수 없다.

page 단위 connect1초/attempt5초/retry최대2/page총12초는 기존 정책을 유지한다. timeout·5xx는 일시실패, 429는 Retry-After를 존중해 다음 attempt 예약, 인증/권한 오류와 schema drift는 자동 반복하지 않고 경보한다. HTTP200 오류 envelope·잘못된 content-type·깨진 JSON도 성공으로 세지 않는다. dataset attempt 실패 시 15분·60분 뒤 추가2회까지만 실행하고, 공급자 Retry-After가 더 길면 그 이후로 미룬다. 다음 일일 일정 이후까지 미뤄지면 최신 일정에 합친다.

lease 만료 run은 ABANDONED. 안정적인 원천 snapshot/cursor가 검증된 경우만 checkpoint 재개, 그 외 새 revision으로 처음부터 재시도한다. 다른 worker의 revision을 이어 쓰지 않는다. 마지막 page까지 검증한 후 lease fence→active revision CAS→watermark→SUCCEEDED를 한 transaction으로 게시한다. 실패는 이전 LKG 유지, 최초 성공 데이터가 없으면 미지원/일시불가를 구분해 응답한다. full 0건은 원천 삭제로 즉시 게시하지 않고 검수한다. stage·checkpoint는 terminal 후7일, 실행 요약은30일 보존한다.

## 지도는 행정구역 집계에서 목록으로 내려간다

대안은 자동 viewport(탐색은 자연스럽지만 잦은 본문 다운로드), 구역 전환마다 자동 목록(viewport보다 적지만 드래그 중 요청), **행정구역 집계+명시적 목록 조회**다. 세 번째를 추천한다. 행정구역은 균일하게 시→구가 아니다. 전국→시·도→시·군·구를 기본으로 하고 구 없는 시·군도 최종 목록 지역이다. 중구/서구는 상위 지역과 코드로 구분한다.

1. 최초 전국 지도에는 시·도별 공개 후기 누적 개수와 대표 중심점만 받는다. 게시글 본문·개별 핀은 받지 않는다.
2. 시·도 집계 클릭/지역 선택 시 그 아래 시·군·구 집계를 조회한다. 전국 화면으로 돌아가면 메모리 캐시를 재사용한다.
3. 줌은 표현 확대이며 곧바로 후기 GET을 유발하지 않는다. 지도 이동 후 선택 지역 후보를 표시하고 `이 지역 선택`으로 확정한다. zoom 숫자는 지도 SDK마다 달라 서버 계약으로 사용하지 않는다.
4. 시·군·구 선택 후 `이 지역 후기 보기`를 누르면 해당 구역 첫20개와 그 페이지 핀을 읽는다. 인접 구역 후기를 합치지 않는다. 인접 지역도 사용자가 선택하면 위치 권한 없이 조회할 수 있다.
5. 지도 이동만으로 선택 지역/list/cursor를 바꾸지 않는다. 선택 경계와 현재 목록 지역명을 유지한다. 새 지역 조회 성공 시 원자적으로 교체하며 실패 시 이전 지역 표시를 유지한다.
6. 내 인근은 `내 지역`으로 표시한다. 위치 동의 후 좌표를 시·군·구로 해석하고 사용자 확인으로 REGION 조회한다. 반경10km 검색은 없다. 거절·지역 미해결은 수동 선택, 경계점 복수 후보는 사용자가 선택한다.

집계는 현재 공개 가능한 후기 수(기간 전체)이며 방문자 수·작성자 수·화면 핀 수가 아니다. 초기에는 같은 DB에서 지역별 GROUP BY로 계산하고 별도 counter/Redis를 도입하지 않는다. 부모 수는 공개 leaf 지역 자식의 합, 지역 미해결 공개 후기는 ALL에만 포함하고 `unassignedCount`로 별도 표시한다. 기존 좌표 없는 장소 후기 작성 금지와 함께 신규 후기에는 유효 region도 요구한다. 지역 변경·장소 숨김은 현재 Catalog 공개 projection 기준으로 읽는다. 기존 글을 과거 지역에 영구 귀속시키지 않는다.

## FE 1.2 변경 계약

prefix `/api/v1`, HTTPS JSON. 아래 endpoint는 1.2 제안이며 구현 전 OpenAPI/FE fixture 동시 전환이 필요하다. 공통 오류·CSRF·인가는 기존 계약을 계승한다.

| GET | 요청 | 응답과 경계 |
|---|---|---|
| /visit-review-regions | parentRegionCode 생략=시·도, 지정=해당 시·도의 시·군·구 | schemaVersion,regionRevision,countsAsOf,parentRegionCode(null 허용),items,unassignedCount; leaf parent 지정400 |
| /regions/resolve | lat,lng | schemaVersion,regionRevision,candidates[{regionCode,parentRegionCode,name}]; 빈 배열=해결 불가, 좌표 범위 오류400; 개인 위치 응답 no-store |
| /visit-reviews | scope=ALL 또는 REGION, REGION만 regionCode 필수; limit 기본20/1..50, cursor 선택 | 기존 ReviewPage 구조, schemaVersion='1.2'; NEARBY/VIEWPORT와 radius/bbox/좌표 파라미터400 |
| /places/{id}/visit-reviews | limit,cursor | 동일1.2 ReviewPage; 장소 필터 고정 |

집계 item은 `{regionCode,parentRegionCode,name,level,center:{lat,lng},bounds:{west,south,east,north},reviewCount,coverageStatus,hasChildren}`다. 부모 코드/중심점/경계는 검증된 지역 데이터에서 제공한다. 해당 부모의 자식은 최대300개, regionCode 오름차순 한 응답으로 전달하며 페이지 없음; 상한 초과는 조용히 자르지 않고503 후 계약 재검토. 지원하는 0건 지역도 표시하고 UNSUPPORTED와 구분한다. 경계 GeoJSON 전체를 매응답에 싣지 않는다. 상세 경계는 revision 고정 정적 자산으로 FE에 제공하고 출처·권리를 별도 검증한다.

집계 no-store를 초기 기본으로 하고 FE 탭 메모리 캐시 TTL60초, 최대20개 parent만 유지한다. 회원 탈퇴/숨김 직후 과거 캐시 count가 남을 수 있지만 본문 조회는 현재 공개 상태를 검사한다. 정확한 실시간 수치로 안내하지 않는다. 로그인 전후 공유 가능한 집계에 mine/회원정보를 포함하지 않는다. 게시/삭제 성공 시 관련 부모 캐시를 무효화한다. 응답 countsAsOf는 조회 transaction 기준시각이며 목록 asOf와 같다고 보장하지 않는다.

목록 정렬은 `createdAt DESC,id DESC`, 서버가 limit+1개를 읽어 초과1개 존재로 hasMore를 계산한다. items는 앞 limit개만, nextCursor는 **실제로 반환한 마지막 item**으로 생성한다. 초과 조회한 행을 cursor로 쓰면 한 건을 건너뛰므로 금지한다.

- page/pageIndex/offset/lastIndex/totalPages는 제공하지 않는다. 첫 요청은 cursor 생략이다.
- JSON 배열 인덱스는0부터, 페이지 길이 n의 마지막 인덱스는 n-1; 빈 페이지에는 마지막 인덱스가 없다. FE 표시 순번1부터는 UI 전용이다.
- 마지막 페이지는 `hasMore:false,nextCursor:null`. 빈 결과도 동일하며 `items:[]`. 전체 건수가 limit의 배수여도 추가 빈 페이지 요청 없이 끝난다.
- cursor는 version,filterHash,limit,lastCreatedAt,lastId,asOf,regionRevision,expiresAt를 서명/opaque 처리한다. TTL10분, 필터/limit 변경400 CURSOR_INVALID, 만료410 CURSOR_EXPIRED, 지역코드 체계 revision 변경409 REGION_REVISION_CHANGED 후 첫 페이지 재조회다.
- 첫 요청 DB asOf 이후 신규 글은 현재 chain에 끼워 넣지 않는다. 삭제·공개 자격 변경은 매페이지 즉시 적용하며 완전한 snapshot을 보장하지 않는다. 다음 요청 시 남은 글이 사라져 빈 종료 페이지가 되는 경우는 정상이다.
- FE는 같은 queryKey 안에서 id 중복 제거, 목록 최대200개; 이후 더 좁은 지역/새로고침 안내. 새 지역은 requestSequence 증가와 이전 요청 취소, 늦은 응답 폐기. 더 보기 중 동시 중복 요청 금지.

원천 TourAPI의 pageNo=1 시작 규칙과 OnMaru public cursor는 별개다. 원천 adapter가 내부 변환하며 FE에 원천 pageNo/totalCount를 전달하지 않는다. 저장 여정 목록에도 cursor 종료 규칙은 동일하게 적용하되 기존 회원 소유·정렬·필터 scope를 유지한다.

지역 필터는 Catalog의 published place/region 공개 projection을 통해 Community가 읽는다. B-tree place(region_id,id)와 published review(place_id,created_at DESC,id DESC), ALL 최신순 partial index를 비교 검증한다. 전국 count는 밀집/희소10만건 fixture에서 timeout2초와 p95 목표500ms를 확인한다. 초과하면 별도 비동기 집계 projection을 검토하며 지금 성능 보장을 하지 않는다. 지도는 SSE·주기 polling 없이 사용자 동작 REST이며, **AI 여정만** REST command + SSE notification + GET snapshot을 사용한다.

## 검토 결과와 구현 전 증거

한 검토자가 architect/developer 관점으로 검토했다. 독립 에이전트 토론이나 운영 검증이 아니다.

| 반론 | 채택한 대응 / 남는 비용 |
|---|---|
| 지역마다 확인 버튼이면 탐색 단계가 늘어난다 | 집계 클릭으로 선택, 본문 조회는 명시적 행동. 실제 FE 사용성 검증 필요 |
| 모든 도시에 구가 있는가 | SIDO/SIGUNGU와 실제 parent 데이터 사용, 서울 중구/부산 중구/구 없는 시·군 fixture |
| 매일03시에 서버가 자면 수집이 누락된다 | durable schedule+startup due scan, 시각 보장은 외부 실행기 선택 필요 |
| 증분만 게시하면 기존 장소가 사라진다 | 이전 전체 집합 복사+delta 적용 후 원자 pointer 교체 |
| Google/Naver 추가 시 이메일로 같은 사람을 찾으면 되는가 | 자동 병합 금지, 검증된 identity 연결은 별도 기능 |
| 누적 count와 페이지 수가 다르다 | 집계/목록 관측시각 및 공개 자격 변화를 명시, count로 페이징 종료 판단 금지 |

필수 fixture: 동일 subject/다른 issuer, 동시 로그인 고아 회원0, 서버03시 수면 후 재기동1회 등록, 마지막 page 실패 LKG 불변, 원천200오류/빈full/429 장기대기, lease 상실 후 늦은 write0, delta 게시 기존행 보존, 부모 없는 구/동명구/경계점/미지원지역, 지도 drag 본문 요청0, limit의 정확한 배수/동일createdAt/삭제 중cursor/지역 변경 늦은응답. Spring ingestion 첫 구현은 provider별 정상 목록/마지막 페이지/빈 결과/HTTP200 오류 envelope/4xx·5xx/429 Retry-After/timeout을 실제 호출 capture로 고정하고, 민감 query·key는 redaction한 fixture만 저장한다. 실제 DB·FE 테스트는 아직 없다.

추가 개선 우선순위는 전체 source→canonical→FE 필드 매핑 fixture, 실행 DDL, 1.2 OpenAPI, FE 경계자산·선택 UX 승인, hosting scheduler/호출quota, 복원·보안·부하 검증이다. 기존 예상79/100은 유지한다. 이번 명세 보강만으로 점수를 올리지 않으며 신규 계약 미통합도 감점 요소다.
