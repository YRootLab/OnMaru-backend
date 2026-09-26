# 한옥 수결첩 공개 랭킹 설계

## 1. 배경과 결정 상태

이 문서는 Issue #262의 후속 범위로 한옥 수결첩 공개 랭킹과 개인 참여 설정을 정의한다. 기존 체크인·수결 지급 설계의 “공개 nickname과 opt-in 정책이 승인되기 전까지 랭킹 제외” 조건을 충족한 후속 설계이며, 해당 문서의 위치·체크인·수결 지급 정책은 변경하지 않는다.

현재 저장소에서 확인한 사실은 다음과 같다.

- `modules:stamp`가 수결 정의, 지급 정책과 개인 수결첩을 소유한다.
- `identity_members.status`는 `ACTIVE`, `DELETING`만 지원한다.
- 사용자 지정 nickname, 금칙어, 공개 프로필 정책은 아직 없다.
- 체크인과 `stamp_awards` 기록은 하나의 PostgreSQL transaction에서 끝난다.
- 기존 수결첩의 완료율은 활성 수결 중 획득 수결 비율을 내림한 정수이며, 방문 권역 수는 획득한 활성 `REGION_VISIT` 수결의 서로 다른 `region_group` 수다.

따라서 첫 공개 랭킹은 **명시적으로 동의한 활성 회원에게 서버 생성 익명 별명만 부여**한다. OAuth 이름, 이메일, 회원 ID, 내부 UUID는 어떤 공개 응답에도 사용하지 않는다. 사용자가 입력하는 공개 nickname과 금칙어·신고·운영 제재 정책은 별도 후속 Issue로 분리한다.

## 2. 목표와 비목표

### 목표

- 비로그인 FE가 실제 공개 참여자의 수결 순위를 조회할 수 있다.
- 로그인 회원이 자신의 참여 상태와 공개 참여자 기준 순위를 조회할 수 있다.
- 참여 시작과 철회를 하나의 멱등적인 설정 API로 처리한다.
- 미동의, 탈퇴 진행 또는 향후 추가될 비활성 회원은 공개 결과에서 제외한다.
- 철회가 성공한 transaction 직후의 새 공개 조회에서 해당 회원이 보이지 않는다.
- 점수는 기존 수결 원장으로 계산해 수결첩과 랭킹의 값이 달라지지 않게 한다.

### 비목표

- 사용자가 직접 입력하는 공개 nickname
- 금칙어 사전, nickname 신고와 운영자 제재
- 공식 칭호(`title`) 규칙
- cursor pagination, Redis cache, materialized view 또는 별도 랭킹 service
- 동의하지 않은 회원을 익명화해서 자동으로 랭킹에 포함하는 기능

## 3. 검토한 접근과 결정

### 채택: opt-in 익명 프로필과 실시간 관계형 집계

참여 시 랭킹 전용 UUID와 서버 생성 별명을 만들고, 공개 조회는 참여 설정·활성 회원·기존 수결 원장을 join해 계산한다. 현재 수결 정의가 12개이고 회원당 award 수도 제한적이므로 중복 점수 column이나 비동기 projection 없이 강한 일관성을 유지할 수 있다.

### 기각: 모든 회원의 자동 익명 랭킹

랜덤 별명이어도 방문 이력에서 파생된 지속적 공개 프로필이 생긴다. 명시적 동의 원칙에 어긋나므로 사용하지 않는다.

### 보류: 사용자 지정 nickname

사용자 지정 문자열을 허용하면 Unicode 유사문자, 금칙어 우회, 신고, 운영자 차단과 이력 정책까지 함께 필요하다. 익명 별명 계약을 먼저 운영한 뒤 별도 Issue와 계약 버전으로 추가한다.

### 보류: cache와 사전 집계

철회 직후 제외가 개인정보 요구사항이므로 최초 버전은 공개 응답도 cache하지 않는다. 운영 데이터에서 집계 query가 목표 성능을 넘을 때만 짧은 TTL cache 또는 projection을 별도 설계한다.

## 4. 모듈 경계와 요청 흐름

- `modules:stamp`는 참여 명령, 익명 프로필, 랭킹 entry, 순위 snapshot과 store port를 소유한다.
- `adapters:persistence-jdbc`는 참여 설정 transaction, 익명 값의 DB uniqueness, 수결 집계와 ordinal rank query를 구현한다.
- `apps:spring-api`는 session, CSRF, DTO, `limit`, cache header, 오류 계약과 OpenAPI annotation을 담당한다.
- Identity는 회원 상태의 Source of Truth다. 공개 query는 `identity_members.status = 'ACTIVE'`를 반드시 요구한다. 이 조건은 현재 `DELETING`뿐 아니라 향후 추가되는 다른 상태도 기본적으로 제외한다.
- Stamp award 원장이 점수의 Source of Truth다. 랭킹 설정에는 mutable 점수를 저장하지 않는다.

참여 변경과 공개 조회 사이에 application cache를 두지 않는다. 체크인 transaction이 commit한 award는 다음 ranking query의 PostgreSQL snapshot에서 즉시 집계된다.

## 5. 관계형 데이터 모델

Flyway `V029__262_hanok_stamp_ranking.sql`과 migration registry를 추가한다.

### `stamp_ranking_profiles`

| 열 | 형식 | 의미 |
|---|---|---|
| `member_id` | `uuid PK FK` | 회원당 설정 한 개, 회원 삭제 시 cascade |
| `ranking_public_id` | `uuid nullable` | 참여 기간에만 존재하는 랜덤 UUID v4 |
| `public_nickname` | `varchar(20) nullable` | 서버 생성 표시 별명 |
| `nickname_normalized` | `varchar(20) nullable` | NFKC 후 소문자 변환한 중복 판정값 |
| `nickname_type` | `varchar nullable` | 첫 버전은 `GENERATED`만 허용 |
| `participating` | `boolean` | 기본값 `false` |
| `consented_at` | `timestamptz nullable` | 현재 또는 마지막 참여 동의 시각 |
| `withdrawn_at` | `timestamptz nullable` | 마지막 철회 시각 |
| `created_at` | `timestamptz` | 설정 최초 생성 시각 |
| `updated_at` | `timestamptz` | 실제 상태 변경 시각과 재참여 제한 기준 |

다음 제약을 DB에서 강제한다.

- `member_id`는 `identity_members(id) ON DELETE CASCADE`를 참조한다.
- 참여 중이면 공개 ID, 별명, 정규화 값, `GENERATED`, `consented_at`이 모두 존재하고 `withdrawn_at`은 null이다.
- 미참여이면 공개 ID, 별명, 정규화 값과 nickname type은 모두 null이다.
- 참여 중인 `ranking_public_id`와 `nickname_normalized`에 partial unique index를 둔다.
- nickname은 앞뒤 공백이 없고 2~20 Unicode code point 범위임을 application과 migration integration test에서 검증한다.

철회 시 참여 row와 동의 이력 시각은 남기되 공개 ID와 별명은 null로 지운다. 재동의하면 새 공개 ID와 새 익명 별명을 발급한다. 과거 공개 식별자를 재사용하지 않아 철회 전후의 프로필 연결 가능성을 줄인다.

## 6. 익명 별명 정책

별명은 서버가 관리하는 한옥·여행 관련 단어 목록과 cryptographically strong random suffix로 만든다. 예시는 `고즈넉한여행자-A7K2`다.

- 회원 정보, OAuth profile, 이메일, 전화번호와 내부 ID를 seed로 사용하지 않는다.
- 표시 문자열은 NFKC 정규화하고, 중복 key는 NFKC 후 locale-independent lowercase로 만든다.
- 생성 문자열은 2~20 code point이며 첫 버전의 고정 문자 집합만 사용한다.
- DB unique violation이 발생하면 새 후보로 최대 5회 재시도한다.
- 5회 모두 충돌하면 개인정보나 후보를 log하지 않고 `503 SERVICE_UNAVAILABLE`을 반환한다.
- 요청 body로 nickname을 받지 않으므로 `PUBLIC_NICKNAME_REQUIRED`, `PUBLIC_NICKNAME_ALREADY_TAKEN`, 금칙어 오류는 첫 버전의 외부 계약에 넣지 않는다. 사용자 지정 nickname 후속 계약에서 추가한다.

## 7. API 계약 1.3

수결 OpenAPI 문서 version을 `1.3.0`, 관련 응답의 `schemaVersion`을 `1.3`으로 올린다. 기존 세 endpoint의 필드와 동작은 유지하고 schema version만 같은 계약 문서 안에서 일관되게 갱신한다.

### `GET /api/v1/stamps/leaderboard?limit=20`

- 인증 없이 호출할 수 있다.
- `limit` 기본값은 20이며 허용 범위는 1~100이다.
- 응답은 `Cache-Control: no-store`다. CDN과 application cache를 사용하지 않아 철회 반영 최대 지연은 DB transaction commit 후 다음 요청까지다.
- `generatedAt`은 서버 `Clock`의 UTC timestamp다.
- `title`은 공식 규칙이 없으므로 반환하지 않는다.
- `memberId`, OAuth 이름, 위치, 장소, 체크인 시각과 마지막 수결 시각을 반환하지 않는다.

```json
{
  "schemaVersion": "1.3",
  "generatedAt": "2026-09-27T03:00:00Z",
  "entries": [
    {
      "rank": 1,
      "publicId": "550e8400-e29b-41d4-a716-446655440000",
      "nickname": "고즈넉한여행자-A7K2",
      "nicknameType": "GENERATED",
      "stampCount": 12,
      "visitedRegionCount": 7,
      "completionRate": 100
    }
  ]
}
```

### `GET /api/v1/me/stamp-ranking`

- 로그인 필수이며 `Cache-Control: no-store`다.
- 설정 row가 없거나 미참여여도 200을 반환한다.
- `participantCount`는 활성 공개 참여자 수다.
- `rank`는 참여 중이고 회원 상태가 활성일 때만 값이 있다.

```json
{
  "schemaVersion": "1.3",
  "participating": false,
  "publicNickname": null,
  "nicknameType": null,
  "rank": null,
  "participantCount": 143,
  "stampCount": 8,
  "visitedRegionCount": 5,
  "completionRate": 66
}
```

### `PUT /api/v1/me/stamp-ranking`

- 로그인과 기존 double-submit CSRF cookie/header가 필수다.
- 요청은 `{"participating": true}` 또는 `{"participating": false}`만 허용한다.
- 성공 응답은 `GET /api/v1/me/stamp-ranking`과 같은 snapshot이며 `Cache-Control: no-store`다.
- 이미 같은 상태인 요청은 값을 바꾸지 않고 200을 반환한다.
- `false → true` 참여 시작과 재참여는 실제 변경 직전 `updated_at`에서 5초가 지나지 않았으면 `429 RATE_LIMITED`와 `Retry-After`를 반환한다.
- `true → false` 철회는 개인정보 보호를 위해 rate limit을 적용하지 않고 항상 즉시 처리한다.

## 8. 집계와 순위 규칙

한 query의 공통 CTE에서 활성 회원이면서 참여 중인 profile만 고른 뒤 활성 수결 정의와 award를 집계한다.

- `stampCount`: 활성 정의에 연결된 서로 다른 획득 수결 수
- `visitedRegionCount`: 활성 `REGION_VISIT` 획득 수결의 서로 다른 non-null `region_group` 수
- `completionRate`: `floor(stampCount * 100 / activeDefinitionCount)`, 정의가 없으면 0
- `lastAwardedAt`: 활성 수결의 `max(awarded_at)`, 정렬에만 사용하고 응답하지 않음

모든 행에 고유한 ordinal rank를 부여한다.

1. `stampCount DESC`
2. `visitedRegionCount DESC`
3. `lastAwardedAt ASC NULLS LAST`
4. `ranking_public_id ASC`

`row_number()`를 전체 참여자에 먼저 적용한 뒤 `limit`을 적용한다. 개인 순위와 공개 순위는 같은 query 구조와 정렬식을 재사용하며, `participantCount`도 같은 필터 집합에서 계산한다. 내부 member ID는 정렬 기준이나 응답에 사용하지 않는다.

## 9. 동시성, 일관성과 실패 처리

- 참여 변경은 회원별 PostgreSQL transaction에서 `stamp-ranking|{memberId}`를 입력으로 한 transaction advisory lock을 먼저 얻어 직렬화한다. 설정 row가 아직 없는 최초 참여에도 같은 잠금 경계를 적용한다.
- `member_id` PK와 공개 값 unique constraint는 advisory lock 밖에서 발생할 수 있는 운영 실수에도 중복 상태가 저장되지 않도록 최종 방어선으로 둔다.
- 같은 요청의 동시 재전송은 한 번만 별명을 만들고 나머지는 확정된 profile을 반환한다.
- 철회 transaction은 공개 필드를 null로 만든 뒤 commit한다. 공개 조회가 cache되지 않으므로 이후 시작한 요청에서 즉시 제외된다.
- 체크인과 award는 기존 transaction에서 함께 commit되고 랭킹은 원장을 직접 읽으므로 projection 불일치가 없다.
- SQL은 parameterized query만 사용한다.

오류는 기존 `ApiErrorResponse`와 request ID를 사용한다.

| HTTP | code | 조건 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | body 형식, 알 수 없는 필드 또는 limit 범위 오류 |
| 401 | `AUTH_REQUIRED` | 개인 조회·변경에 유효한 회원 session이 없음 |
| 403 | `CSRF_INVALID` | 변경 요청의 CSRF cookie/header 불일치 |
| 429 | `RATE_LIMITED` | 참여 시작 또는 재참여 5초 제한 |
| 503 | `SERVICE_UNAVAILABLE` | DB 장애 또는 익명 값 생성 충돌 재시도 소진 |

## 10. 성능과 관측성

현재는 `stamp_awards(member_id, stamp_code)` unique index와 활성 참여 profile partial index를 이용한 실시간 집계를 우선한다. Testcontainers PostgreSQL에서 현실적인 fixture를 만들고 `EXPLAIN (FORMAT JSON)`으로 다음을 확인한다.

- participant/profile join이 전체 회원 table의 불필요한 scan을 만들지 않는다.
- participant별 award lookup이 기존 member-leading index를 사용할 수 있다.
- `limit=100`에서도 중복 행이나 N+1 query가 없다.

초기 목표는 로컬 integration fixture에서 단일 leaderboard query이며, production p99 목표 주장은 staging 측정 전에는 하지 않는다. metric에는 결과, latency, 반환 entry 수만 기록하고 member ID, nickname, public ID를 label로 넣지 않는다. 예외 log에도 nickname과 공개 ID를 남기지 않는다.

## 11. 테스트 전략

모든 production 동작은 실패 test를 먼저 확인한 뒤 최소 구현으로 통과시킨다.

### Domain unit test

- 기본 미참여와 명시적 참여 상태 전이
- 참여 시 익명 값 생성, 같은 상태 PUT의 멱등성, 철회 시 공개 값 삭제
- NFKC 정규화, 길이와 생성 문자 정책
- ordinal 정렬과 공개 ID 최종 tie-break
- 수결첩과 같은 완료율·권역 계산 규칙

### Web boundary test

- 공개 endpoint의 비로그인 성공, 기본 limit와 1·100 경계, 범위 밖 400
- 개인 endpoint 401, PUT의 CSRF 403, 참여 시작 제한 429
- 개인 및 공개 응답의 `no-store`
- 응답 JSON에 member/OAuth/위치/장소/방문 시각 관련 field가 없음
- 기존 수결 세 endpoint의 회귀 없음

### Persistence integration test

- 미동의, `DELETING`, 존재하지 않는 profile 제외
- 동시 참여 요청이 하나의 공개 profile로 수렴
- 공개 ID와 정규화 nickname unique constraint
- 철회 commit 직후 공개 목록 제외와 재동의 시 식별자 회전
- award 추가 후 공개·개인 집계 즉시 변경
- 순위 정렬, ordinal rank와 limit 적용 순서
- migration cascade, check constraint와 `EXPLAIN` 계획

### Contract test

- OpenAPI 1.3 schema, 요청·응답과 실제 controller JSON 일치
- `additionalProperties: false`, nullable field와 오류 code 검증
- 기존 체크인·수결첩 contract fixture 회귀

## 12. 문서와 FE 인계

같은 구현 PR에서 다음을 갱신한다.

- `docs/contracts/openapi/hanok-stamps.openapi.yaml`
- `docs/contracts/rest-api.md`
- `docs/database/schema.md`
- `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`
- `handoff.md`
- `CHANGELOG.md`

FE 인계 문서는 비로그인 공개 조회, 로그인 미참여·참여 화면, 참여·철회 요청, `nicknameType`, ordinal tie-break, `no-store`, 철회 반영 시점, 오류 code와 절대 노출되지 않는 개인정보 field를 예제와 함께 설명한다.

## 13. 후속 확장 조건

사용자 지정 nickname은 별도 Issue에서 다음을 함께 승인한 뒤 추가한다.

- 허용 Unicode 문자와 grapheme 길이
- 금칙어 사전, Unicode confusable 대응과 운영자 override
- nickname 변경 빈도, 신고, 차단 및 이력 보존
- 철회·탈퇴 시 사용자 입력 문자열 삭제 정책
- `GENERATED`에서 `CUSTOM`으로 전환하는 API와 migration

집계 query가 staging SLO를 넘거나 참여자 규모가 실측 임계치를 초과할 때만 projection 또는 cache를 도입한다. 이 경우 철회가 cache보다 우선하도록 동기 invalidation과 최대 공개 지연을 별도 ADR로 정한다.
