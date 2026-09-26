# 한옥 수결첩 체크인·수결 지급 설계

## 1. 목표와 성공 기준

Issue #262의 `/stamps` 화면을 브라우저 `localStorage` 기반 데모에서 서버가 판정하는 개인 수결첩으로 전환한다. 로그인 회원이 canonical 한옥 장소 근처에서 위치 기반 체크인을 요청하면 서버가 현재 공개 장소, GPS 정확도, 장소와의 거리를 검증하고 같은 transaction에서 체크인과 새 수결을 기록한다.

성공 기준은 다음과 같다.

- 비회원은 수결 정의를 볼 수 있지만 체크인과 개인 수결첩은 사용할 수 없다.
- 찜과 방문 체크인은 서로 다른 상태다. `saved-resources`는 수결을 지급하지 않는다.
- 위도·경도 원문은 요청 처리 중에만 사용하고 DB, application log, metric label에 남기지 않는다.
- 중복·재시도·동시 요청에도 같은 체크인이나 수결이 중복 생성되지 않는다.
- FE가 체크인 응답만으로 획득 animation과 최신 진행률을 갱신할 수 있다.
- PostgreSQL 제약, PostGIS 거리 계산, domain unit test, web boundary test, Testcontainers integration test로 계약을 검증한다.

## 2. 검토한 접근과 결정

### 채택: 관계형 수결 규칙과 동기식 지급

수결 정의·지역 조건·체크인·획득 관계를 PostgreSQL에 저장한다. 체크인 요청 안에서 거리 검증, 체크인 insert, 조건 평가, 수결 insert를 동기적으로 끝낸다. 즉시 응답과 강한 일관성이 필요한 현재 모듈러 모놀리스에 가장 적합하다.

### 기각: Java 코드에 규칙 고정

초기 파일 수는 줄지만 수결 이름, 정렬, 지역 조건을 바꿀 때마다 코드 배포가 필요하고 DB가 잘못된 관계를 거부할 수 없다. 현재 FE bundle의 장소명 substring 판정을 서버로 옮기는 방식도 canonical Catalog와 불일치하므로 사용하지 않는다.

### 보류: 이벤트 기반 비동기 지급

대규모 fan-out과 재처리에는 유리하지만 사용자가 체크인 직후 새 수결을 보지 못할 수 있고 outbox·consumer 운영이 추가된다. 수결 후속 알림이나 대규모 캠페인이 생길 때 재검토한다.

## 3. 경계와 구성요소

- `modules:stamp`: framework 독립 domain/application 모듈이다. 입력 정책, 위치 판정 결과, 수결 조건, 체크인 결과, store port를 소유한다.
- `adapters:persistence-jdbc`: active Catalog 장소 조회, PostGIS 거리 계산, 체크인·수결 transaction, 수결첩 조회를 구현한다.
- `apps:spring-api`: opaque member session 확인, DTO validation, `Idempotency-Key`, 오류 응답, OpenAPI annotation과 bean wiring을 담당한다.
- Catalog는 장소 식별과 현재 공개 상태의 Source of Truth다. 공개 `placeId`는 `catalog_place_public_ids`를 거쳐 내부 UUID로 해석한다.
- Identity는 회원 식별의 Source of Truth다. 모든 개인 row는 `identity_members.id`를 참조한다.

별도 microservice, queue, Redis cache는 도입하지 않는다. 현재 조회량에서는 PostgreSQL index와 한 번의 aggregate query면 충분하다.

## 4. 위치 판정 정책

요청 값은 `latitude`, `longitude`, `accuracyMeters` 세 가지다. 방문 시각은 client 값을 신뢰하지 않고 서버 `Clock`을 사용한다.

- 위도: `-90..90`
- 경도: `-180..180`
- GPS 정확도: `0 < accuracyMeters <= 100`
- 기본 허용 반경: 200m
- 성공 조건: `distanceMeters - accuracyMeters <= 200`
- accuracy 상한이 100m이므로 실제 장소 중심과의 최대 허용 거리는 300m다.
- 장소에 좌표가 없거나 현재 공개·체크인 가능 한옥이 아니면 resource 존재 여부를 과도하게 노출하지 않도록 404로 처리한다.

체크인 가능 category는 현재 Catalog allowlist 중 `HANOK`, `HANOK_STAY`, `HANOK_CAFE`, `HANOK_EXPERIENCE` 네 가지로 고정한다. `HISTORIC_SITE`처럼 한옥일 가능성만 있는 넓은 category는 수결 규칙에 자동 포함하지 않는다.

거리는 PostgreSQL geography의 `ST_Distance`로 계산한다. 애플리케이션의 별도 Haversine 구현을 운영 판정값으로 사용하지 않는다. 위도·경도는 prepared statement parameter로 전달한 뒤 버리고, 성공 row에는 반올림한 `distance_meters`와 `accuracy_meters`만 저장한다.

## 5. 관계형 데이터 모델

Flyway `V027__262_hanok_stamp_book.sql`을 추가하고 migration registry와 `docs/database/schema.md`를 함께 갱신한다.

### `stamp_definitions`

| 열 | 의미 |
|---|---|
| `code varchar PK` | FE와 API에서 쓰는 stable code (`stamp_bukchon` 등) |
| `name`, `description`, `condition_label` | 사용자 표시 문구 |
| `seal_text`, `icon_name`, `color` | 현재 수결첩 표현에 필요한 metadata |
| `rarity` | `COMMON`, `REGIONAL`, `RARE`, `LEGENDARY` |
| `condition_type` | `REGION_VISIT`, `NIGHT_VISIT`, `REGION_COUNT` |
| `required_count` | 집계 수결의 threshold, 아니면 null |
| `region_group` | 전국 진행률의 distinct 권역 key, 집계 수결이면 null |
| `sort_order`, `active` | 안정적인 노출 순서와 운영 활성 상태 |

기존 화면의 12개 수결을 seed한다. 전국 수결은 화면 문구대로 서로 다른 5개 권역 획득을 조건으로 삼는다. 현재 FE 구현의 내부 조건인 4개와 문구 5개의 불일치는 5개로 바로잡는다.

### `stamp_region_rules`

`(stamp_code, region_code)` 복합 PK를 사용한다. `stamp_code`는 definition FK이며 `region_code`는 현재 Catalog의 stable 법정동 코드 체계(`kr-11-jongno`, `kr-45-jeonju` 등)다. leaf code 자체 또는 `region_code-...` descendant가 일치하면 해당 지역 방문 조건을 만족한다. 이 문자열은 외부 provider code가 아니라 OnMaru canonical region code이며 format check를 둔다.

지역 규칙은 장소명·주소 substring을 사용하지 않는다. 북촌/은평처럼 같은 시도에 여러 수결이 있는 경우 시군구 단위 규칙으로 분리한다.

초기 지역 seed는 아래처럼 현재 저장소의 `kr-시도-시군구` canonical 체계를 사용한다. 한 수결에 여러 code가 필요하면 rule row를 추가하며 definition이나 Java 코드는 바꾸지 않는다.

| 수결 code | 초기 canonical 지역 |
|---|---|
| `stamp_bukchon` | `kr-11-jongno` |
| `stamp_eunpyeong` | `kr-11-eunpyeong` |
| `stamp_hwaseong` | `kr-41-suwon` |
| `stamp_gangneung` | `kr-42-gangneung` |
| `stamp_oeam` | `kr-44-asan` |
| `stamp_jeonju` | `kr-45-jeonju` |
| `stamp_unjoru` | `kr-46-gurye` |
| `stamp_andong` | `kr-47-andong` |
| `stamp_yangdong` | `kr-47-gyeongju` |
| `stamp_jeju_seongup` | `kr-50-seogwipo` |

운영 Catalog가 행정 코드 개편으로 다른 canonical code를 발행하기 시작하면 같은 migration을 수정하지 않고 후속 data migration으로 rule을 추가·비활성화한다.

### `stamp_check_ins`

| 열 | 의미 |
|---|---|
| `id uuid PK` | 서버 생성 체크인 ID |
| `member_id uuid FK` | 로그인 회원 |
| `place_id uuid FK` | canonical Catalog 장소 |
| `public_place_id varchar` | 응답 및 이력용 stable 공개 ID snapshot |
| `region_code varchar` | 판정 당시 canonical 지역 snapshot |
| `checked_in_at timestamptz` | 서버 판정 시각 |
| `check_in_bucket timestamptz` | UTC 15분 단위 중복 방지 bucket |
| `distance_meters integer` | 반올림된 계산 거리 |
| `accuracy_meters integer` | 반올림된 요청 정확도 |

`check_in_bucket`은 서버 UTC epoch를 15분 단위로 내림한 값이다. `UNIQUE(member_id, place_id, check_in_bucket)`로 다른 idempotency key를 사용한 동시·반복 제출도 한 row로 수렴시킨다. 개인 수결첩 최신 활동 조회용 `(member_id, checked_in_at DESC, id DESC)` index를 둔다. 위치 원문 열은 만들지 않는다.

### `stamp_awards`

`id`, `member_id FK`, `stamp_code FK`, `trigger_check_in_id FK`, `awarded_at`을 가지며 `UNIQUE(member_id, stamp_code)`를 둔다. 체크인 row와 새 award row는 같은 JDBC transaction에서 commit한다. 동시 요청 중 하나만 award를 만들며 다른 요청은 이미 획득한 상태를 반환한다.

회원 탈퇴 시 체크인과 award는 `ON DELETE CASCADE`로 제거한다. 정의 삭제는 금지하고 `active=false`로 비활성화해 과거 award의 참조를 보존한다.

## 6. 수결 판정 흐름

1. controller가 member session과 CSRF 경계를 확인한다.
2. DTO 범위와 UUID 형식의 `Idempotency-Key`를 확인한다.
3. public `placeId`를 active Catalog revision의 공개 장소로 해석한다.
4. 한옥 계열 허용 category와 좌표 존재 여부를 확인한다.
5. PostGIS로 거리를 계산하고 정확도·허용 반경을 판정한다.
6. 회원·장소·15분 bucket 기준 체크인을 insert한다. conflict면 같은 bucket의 기존 row를 읽는다.
7. 신규 체크인이면 지역 수결, KST 야간 수결, 서로 다른 권역 5개 수결을 차례로 평가한다.
8. `ON CONFLICT DO NOTHING`으로 새 award만 기록한다. 같은 idempotency receipt replay는 최초 응답을 그대로 돌려주고, 다른 key로 같은 bucket을 요청하면 `alreadyCheckedIn=true`, `newAwards=[]`를 반환한다.
9. 현재 전체 수결첩 summary와 이번 요청에서 새로 생긴 award를 응답한다.

어느 단계에서든 DB 오류가 발생하면 체크인과 award 모두 rollback한다. Stamp JDBC store와 `JdbcIdempotencyStore`는 Spring singleton `JdbcTransactionRunner`를 공유해 business row와 HTTP receipt까지 같은 connection/transaction에 기록한다. Idempotency receipt가 같은 key와 같은 payload를 보면 최초 HTTP 응답을 반환하고, 같은 key에 다른 payload를 보내면 409 `IDEMPOTENCY_CONFLICT`다.

## 7. HTTP API 계약

모든 경로는 기존 `/api/v1` convention과 `schemaVersion: "1.2"`를 유지한다.

### `GET /api/v1/stamps`

공개 endpoint다. 활성 수결 정의를 `sortOrder` 순으로 반환한다. 개인 획득 여부는 포함하지 않아 CDN/public cache와 privacy 경계를 단순하게 유지한다.

### `GET /api/v1/me/stamp-book`

로그인 전용 개인 endpoint다. `Cache-Control: no-store`를 적용한다.

```json
{
  "schemaVersion": "1.2",
  "summary": {
    "collectedCount": 3,
    "totalCount": 12,
    "visitedRegionCount": 2,
    "requiredRegionCount": 5,
    "completionRate": 25
  },
  "stamps": [
    {
      "code": "stamp_bukchon",
      "name": "북촌 한옥마을 인장",
      "rarity": "COMMON",
      "regionGroup": "SEOUL",
      "conditionLabel": "북촌·인사동 일대 한옥 방문",
      "description": "조선 왕실과 고관대작들의 숨결이 깃든 북촌 한옥 지구를 유람하다.",
      "sealText": "北村",
      "iconName": "Landmark",
      "color": "#b91c1c",
      "collected": true,
      "collectedAt": "2026-09-26T02:30:00Z",
      "triggerPlaceId": "p-seoul-bukchon-001"
    }
  ]
}
```

첫 구현은 12개 전체를 한 번에 반환하므로 pagination이 없다. 목록 크기가 운영 설정으로 100개를 넘기게 되면 cursor pagination을 추가한다.

### `POST /api/v1/places/{placeId}/check-ins`

로그인과 `Idempotency-Key`가 필수다.

```json
{
  "latitude": 37.5826,
  "longitude": 126.9831,
  "accuracyMeters": 18.4
}
```

새 체크인은 201, 같은 15분 bucket의 기존 체크인은 200이다.

```json
{
  "schemaVersion": "1.2",
  "checkIn": {
    "id": "8b9576b8-6ca8-4f8c-8d98-f337c749a862",
    "placeId": "p-seoul-bukchon-001",
    "checkedInAt": "2026-09-26T02:30:00Z",
    "distanceMeters": 74,
    "alreadyCheckedIn": false
  },
  "newAwards": [
    {
      "code": "stamp_bukchon",
      "name": "북촌 한옥마을 인장",
      "sealText": "北村",
      "rarity": "COMMON",
      "collectedAt": "2026-09-26T02:30:00Z"
    }
  ],
  "summary": {
    "collectedCount": 3,
    "totalCount": 12,
    "visitedRegionCount": 2,
    "requiredRegionCount": 5,
    "completionRate": 25
  }
}
```

응답에는 요청 좌표와 GPS 정확도를 되돌려주지 않는다.

`completionRate`는 `floor(collectedCount * 100 / totalCount)`인 0~100 정수다. `visitedRegionCount`는 획득한 지역 수결의 서로 다른 `regionGroup` 수이며 야간·전국 집계 수결은 이 값에 포함하지 않는다.

### 탐방 랭킹

공개 leaderboard는 이번 범위에서 제외한다. 현재 production identity schema에는 공개 nickname과 랭킹 공개 동의가 없으므로 member ID나 OAuth 이름을 노출하면 privacy 문제가 생긴다. `GET /me/stamp-book`의 개인 진행률을 먼저 제공하고, nickname 영속화·opt-in 정책이 승인되면 별도 Issue로 leaderboard를 추가한다. FE는 그때까지 랭킹 탭을 숨기거나 명확한 demo 표시를 해야 한다.

## 8. 오류 계약

공통 `ApiErrorResponse`와 request ID를 사용한다.

| HTTP | code | 의미와 FE 처리 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 좌표 범위, NaN/무한대, accuracy 형식 오류 |
| 400 | `IDEMPOTENCY_KEY_MISSING` / `IDEMPOTENCY_KEY_INVALID` | UUID key를 새로 생성해 다시 요청 |
| 401 | `AUTH_REQUIRED` | 로그인 후 동일 사용자 intent를 다시 실행 |
| 404 | `NOT_FOUND` | 공개·한옥·좌표 조건을 만족하는 장소가 아님 |
| 409 | `IDEMPOTENCY_CONFLICT` | key 재사용 버그, 새 key로 임의 재시도하지 말고 요청 상태 점검 |
| 422 | `LOCATION_ACCURACY_TOO_LOW` | GPS 정확도 100m 이하가 된 뒤 재시도 |
| 422 | `OUTSIDE_CHECK_IN_RADIUS` | 장소에서 200m 이내로 이동 후 재시도 |
| 429 | `CHECK_IN_RATE_LIMITED` | 회원당 일 30회 초과, `retryAfterSeconds` 표시 |
| 503 | `SERVICE_UNAVAILABLE` | Catalog/DB 일시 장애, 지수 backoff 재시도 |

`OUTSIDE_CHECK_IN_RADIUS`의 details에는 허용 거리만 포함하고 장소·사용자 실제 좌표는 포함하지 않는다. 내부 거리도 공격적 위치 추정을 막기 위해 오류 응답에는 노출하지 않는다.

## 9. 보안·개인정보·운영

- opaque session cookie와 기존 CSRF 정책을 그대로 사용한다.
- 개인 endpoint는 `@PrivateResponse`와 `no-store`를 적용한다.
- request body 전체를 structured log에 기록하지 않는다.
- 위치, member ID, place ID를 metric label로 사용하지 않는다. metric은 결과 code와 latency 같은 low-cardinality 값만 가진다.
- 일일 성공 체크인은 `Asia/Seoul` 날짜 기준 회원당 30회로 제한한다. idempotent replay와 같은 bucket 재조회는 새 성공 횟수로 세지 않는다.
- 위치 판정 실패는 DB에 저장하지 않는다.
- 회원 탈퇴 cleanup과 retention 검증에 두 개인 테이블을 포함한다.
- 목표 SLO는 check-in API DB 정상 상태에서 p99 200ms 미만이다. 실제 production 목표 충족 주장은 staging 측정 후에만 한다.

## 10. 검증 전략

TDD 순서로 각 동작의 실패 test를 먼저 확인한다.

1. domain unit: 좌표/accuracy 경계, 200m 경계, KST 야간, 지역 code descendant, 5개 distinct 권역, 이미 획득한 수결 제외
2. store contract: 체크인 중복, 수결 unique, transaction rollback, member cascade, 비활성 정의 제외
3. PostgreSQL/PostGIS integration: active revision과 public ID join, `ST_Distance`, 300m 절대 상한, query index 사용 가능성
4. web boundary: 401, 400, 404, 422, 429, 503, 201/200, `no-store`, 좌표 비반환
5. idempotency: 같은 key·payload replay와 다른 payload conflict
6. OpenAPI/contract validation과 전체 Gradle test

새 hot query는 다음 index 경로를 사용한다.

- public place lookup: 기존 `catalog_place_public_ids(public_id)` PK
- active place location: 기존 place revision PK와 `catalog_place_versions_location_gix`
- duplicate check-in: unique `(member_id, place_id, check_in_bucket)`
- stamp book: `(member_id, awarded_at DESC, id DESC)` 및 unique `(member_id, stamp_code)`

실제 seed와 Testcontainers data로 `EXPLAIN (ANALYZE, BUFFERS)`를 기록하되 작은 test table의 planner가 Seq Scan을 선택하는 사실만으로 잘못된 index를 추가하지 않는다.

## 11. 문서와 FE 인계

같은 PR에서 다음을 갱신한다.

- `docs/contracts/openapi/hanok-stamps.openapi.yaml`: 호출 가능한 canonical 계약
- `docs/contracts/rest-api.md`: endpoint 요약과 privacy 규칙
- `docs/database/schema.md`: 테이블·FK·unique·index와 위치 최소수집 설명
- `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`: 비개발자 설명, 브라우저 위치 권한 흐름, fetch 예제, 성공·오류별 화면 처리, localStorage 제거 절차
- `handoff.md`: Issue, branch, 변경 파일, 검증, 남은 작업

FE 문서에는 `navigator.geolocation.getCurrentPosition`의 `enableHighAccuracy: true`, 10초 timeout 예제와 매 클릭마다 새 UUID `Idempotency-Key`를 만들되 네트워크 재시도에는 같은 key를 재사용하는 규칙을 포함한다.

## 12. 명시적 비범위와 후속 조건

- 현장 QR, BLE beacon, background tracking
- 실패 위치와 raw 좌표 보존
- 관리자 수결 CRUD UI
- 공개 nickname leaderboard와 보상/쿠폰
- 기존 localStorage demo 데이터를 자동으로 server truth로 승격

leaderboard는 공개 nickname 저장, 회원 opt-in, 탈퇴·차단 반영 정책이 승인될 때 추가한다. 300m가 도심 오탐을 만들거나 GPS spoofing이 실제 abuse로 확인되면 device attestation, QR 또는 더 작은 장소별 반경을 재검토한다.
