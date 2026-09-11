# 회원과 탐색의 소유권을 DB 제약과 트랜잭션으로 연결한다

2026-09-11 개선안. 아래는 물리 설계 계약이며 executable migration이 아니다. UUID는 서버 생성, 시간은 UTC timestamptz, 만료 판정은 DB clock이다. 기존 `community.actors`는 회원 식별 정답으로 사용하지 않고 `identity.members`로 대체 제안한다.

## 최소 테이블과 제약

| 테이블 | 필수 칼럼 / 제약 | 조회 인덱스 |
|---|---|---|
| identity.members | id PK, status ACTIVE/DELETING, created_at | PK |
| identity.external_accounts | member_id FK, provider=KAKAO, subject; UNIQUE(provider,subject) | member_id |
| identity.sessions | token_hash PK, member_id FK, created_at, last_seen_at, absolute_expires_at, revoked_at | member_id, expiry |
| identity.guests | id PK, token_hash UNIQUE, expires_at, revoked_at | expiry |
| identity.oauth_states | state_hash PK, guest_id nullable FK, browser_nonce_hash, exploration_id nullable, return_path, expires_at, consumed_at | expiry |
| identity.exploration_grants | member_id FK, exploration_id FK, expires_at; PK(member_id,exploration_id) | expiry |
| discovery.explorations | id PK, owner_member_id XOR owner_guest_id FK, state_version>=0, board JSONB nullable, pinned_refs JSONB, excluded_refs JSONB, region_id nullable, expires_at, deleted_at | owner+updated_at |
| discovery.runs | id PK, exploration_id FK, actor_key, base_version, status, stage nullable, outcome nullable, clarification JSONB nullable, created_at, deadline_at, started_at nullable, generation, error_code nullable, engine | partial UNIQUE(exploration_id) WHERE status IN ('QUEUED','RUNNING'); partial UNIQUE(actor_key) same filter; deadline_at partial |
| discovery.proposals | id PK, run_id UNIQUE FK, exploration_id FK, base_version, ordered_refs, reasons/evidence JSONB, expires_at, status PENDING/APPLIED/DISMISSED/INVALIDATED | exploration_id,status |
| discovery.turns | id PK, exploration_id FK, client_turn_id, query, created_at; UNIQUE(exploration_id,client_turn_id) | exploration_id,created_at |
| journey.saved_journeys | id PK, member_id FK, source_exploration_id nullable FK ON DELETE SET NULL, source_version, saved_at, title, snapshot JSONB, snapshot_hash; UNIQUE(member_id,source_exploration_id,source_version) | member_id,saved_at DESC,id DESC |
| community.visit_reviews | id PK, member_id FK, place_id FK, text, status, created_at, deleted_at nullable; text trim nonempty <=300 Unicode code points, 최대5줄 | published(created_at DESC,id DESC), published(place_id,created_at DESC,id DESC) |
| community.review_likes | review_id FK ON DELETE CASCADE, member_id FK; PK(review_id,member_id), created_at | member_id |
| operations.idempotency | actor_key, operation, key PK tuple; request_hash, response_status, response_body, expires_at | expiry |
| operations.admission | scope_key PK, window_start, consumed, active_count | PK |

JSONB board/proposal은 API schemaVersion과 schema validation으로 검증한다. JSON 내부 ref는 SQL FK로 보호되지 않으므로 저장 transaction에서 catalog eligibility/evidence를 재검증한다. 최대3개 후보의 불변 snapshot에 사용하며 회원·시간·상태 검색 칼럼을 JSON에 숨기지 않는다.

actor_key는 서버가 MEMBER:<uuid>/GUEST:<uuid>로 생성한다. guest 재생성 우회에 대비해 IP 예산도 별도로 둔다. runtime role은 DDL 권한이 없고 migration role만 schema를 변경한다. readonly 운영 role은 identity/query 본문을 읽지 못하며 DB owner credential을 Spring에 주지 않는다.

## 세션·로그인·인가

OnMaru opaque session을 사용한다. 32 random bytes 이상의 session/guest/state 원문은 cookie 또는 OAuth 요청에만 보내고 DB에는 SHA-256 digest만 저장한다. 서버 응답 cookie는 `Secure; HttpOnly; SameSite=Lax; Path=/`, host-only `__Host-` prefix를 사용한다. TLS가 없는 local은 별도 이름으로 격리한다. 회원 session idle 24h, 절대 7일, guest 절대 24h, OAuth state 10분이다. last_seen 갱신은 최대 5분에 1회다.

`GET /auth/csrf`는 익명에서도 guest cookie와 session-bound CSRF token을 마련한다. FE는 unsafe method에 `X-CSRF-TOKEN`을 전송한다. cookie가 인증 수단인 모든 POST/DELETE에 검증하고, 로그인/로그아웃 후 새 token을 받는다. JSON content type만 허용하고 Origin allowlist도 검사한다. CORS는 동일 origin 기본이며 `*`+credential 조합은 금지다. 구현 시 Spring Security의 [SPA CSRF 처리](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)에 맞춰 토큰 회전·노출을 검증한다.

OAuth 시작 시 기존 guest credential로 exploration 소유권을 검증하고, state를 guest·browser nonce·exploration·허용 return path(`/discover`)에 묶는다. callback은 browser nonce cookie와 state digest/만료/미소비 조건을 원자적으로 검사하고 state를 소비한 후 code를 교환한다. 실패한 callback은 재사용하지 않고 login부터 재시작한다. code 교환 및 Kakao 사용자 조회는 DB transaction 밖에서 한다. provider subject로 회원을 upsert하고 새 OnMaru session을 발급한다. 기존 인증 session은 회전한다. OAuth token을 FE/localStorage에 노출하지 않는다. 로그인 목적의 provider token은 사용자 조회 뒤 지속 보관하지 않는다. 탈퇴는 OnMaru 계정 삭제이며 Kakao 연결 해제 기능과 구분한다. [Kakao REST 계약](https://developers.kakao.com/docs/ko/kakaologin/rest-api)의 state/code 검증에 OnMaru 소유권 검증을 추가하는 설계다.

guest 자체를 회원으로 바꾸지 않는다. callback에서 검증된 exploration 하나에만 회원 grant(10분)를 만든다. guest board와 run을 유지하여 취소/실패/다중 탭이 현재 결과를 잃지 않게 한다. 저장 시 활성 run이 있으면 409 ACTIVE_RUN; 확정 board/version과 소유 guest+grant 또는 직접 member ownership을 다시 확인한다. 로그인했다는 사실이나 explorationId만으로 접근을 허용하지 않는다. grant 만료면 원래 guest cookie를 보유한 동일 브라우저에서 재로그인하여 다시 획득한다.

| 동작 | 비회원 | 회원 | 소유권 실패 |
|---|---|---|---|
| 공개 지도/후기/장소 GET | 허용 | 허용 | 공개 상태만 노출 |
| exploration 생성/수정/조회 | 소유 guest | 소유 member 또는 유효 grant | 404; credential 없음 401 |
| 여정 저장/목록/재개/삭제 | 401 | 본인만 | 404 |
| 방문 후기 작성 | 401 | ACTIVE 회원 | 장소 비공개 404 |
| 방문 후기 삭제 | 401 | 작성자만 | 404 |
| 내부 AI / sync 운영 | 불가 | 일반 회원 불가 | service credential/운영 채널 분리 |

public DTO에 member ID/provider subject/guest key를 넣지 않는다. `mine`은 현재 principal로 계산하며 localStorage 작성 여부를 신뢰하지 않는다. 관리 moderation API는 제출 범위 밖이며 운영자 DB 직접 수정 권한을 일반 회원 역할로 섞지 않는다.

## 저장·재개·삭제 transaction

Save는 actor admission→회원 ACTIVE 확인/잠금→exploration 잠금→run 없음/소유/version 확인→snapshot 검증→saved row+idempotency response insert 순서다. 동일 actor/operation/key+동일 payload는 같은 응답을 반환한다. 다른 payload는 409 IDEMPOTENCY_CONFLICT. 같은 sourceVersion을 다른 key로 재저장하면 기존 savedJourney를 반환한다. source exploration 만료 후 snapshot은 독립적으로 유지되며 FK는 NULL이 된다.

Saved GET은 immutable 읽기다. `POST /saved-journeys/{id}/resume`은 본인 saved를 읽고 현재 catalog로 장소를 재검증한 새 member exploration을 만든다. 유효 board는 stateVersion=1, run/pending/history 없음. 삭제/비공개 ref는 board에서 제외하고 `unavailableRefs`로 반환한다. pin은 유효 ref만 복원한다. 전부 없으면 board=null, stateVersion=0이며 새 검색을 유도한다. saved 원본은 수정하지 않는다. 새 탐색의 저장은 새 snapshot이다.

회원 탈퇴는 먼저 회원 row를 잠그고 DELETING 처리하여 신규 작업을 막는다. 같은 transaction에서 sessions/grants 폐기, active runs CANCELLED, explorations deleted_at 설정, 공개 후기를 숨긴다. 모든 완료/save는 같은 회원 상태 재검증을 하므로 늦은 AI 응답이 재생성할 수 없다. 삭제 worker는 24h 안에 saved/exploration/turn/후기와 외부 account를 삭제하고 member를 삭제한다. DB 실패면 재시도한다. 외부 호출 중 transaction을 유지하지 않는다.

## 보존 기본안

| 데이터 | 수명 / 삭제 책임 |
|---|---|
| guest credential·exploration | 생성 후 24h; 만료 즉시 접근 차단, hourly cleanup 24h 이내 물리 삭제 |
| member 임시 exploration·원문 turn | 마지막 변경 후 7일; saved snapshot과 별개 |
| run/proposal | exploration과 함께 삭제, proposal 적용 유효기간 생성 후 10분 |
| saved journey | 회원이 삭제/탈퇴할 때까지; 계정당 100개 상한, 초과 409 SAVE_LIMIT |
| OAuth state / grant | 각각 10분; expiry 후 hourly cleanup |
| idempotency 응답 | 24h; 인증 secret 저장 금지, 삭제 대상 응답도 함께 제거 |
| JSON 운영 로그 | 7일; query/정확 위치/token/cookie/원천 URL query 제외 |
| 익명 IP limiter key | 일별 salt HMAC, 24h; 원문 IP 별도 저장 안 함 |
| opt-in 평가 feedback | 30일; query 원문 없이 run/rank version/평가값만, 탈퇴 시 제거 |
| 암호화 backup | 7일 순환; 삭제가 기존 backup에 즉시 반영되지는 않음 |

이는 제품 보존 제안이며 법정 보존기간을 판단한 문서가 아니다. 복원 후 삭제 요청을 다시 적용할 수 있도록 별도 암호화 deletion ledger를 backup 보존기간+1일 유지한다. raw 원천 payload는 권리 확인 자료만 최대 7일 격리 보존한다.

## 참조와 저장 표현의 경계

VisitReview의 place FK는 revision 행이 아니라 stable catalog.place_identity를 참조한다. catalog publication eligibility는 공개 API로 검사한다. 좋아요 회원 탈퇴 시 review_likes를 먼저 삭제하고 회원을 삭제한다. 숨김 후기와 삭제 tombstone은 동일 본인 DELETE의 멱등 응답을 위해 최대24h 유지하고 이후404가 될 수 있다.

Saved snapshot의 board.querySummary는 사용자 원문이 아니라 검수된 지역/주제 label에서 다시 만든다. 사용자가 정한 title은 개인 자료로 보호하며 공개 API에 노출하지 않는다. snapshot_hash는 정규화 JSON 기준이며 같은 sourceVersion 중복저장에 다른 제목을 보내도 기존 저장 제목을 유지한다. 제목 수정은 별도 후속 기능이다.
