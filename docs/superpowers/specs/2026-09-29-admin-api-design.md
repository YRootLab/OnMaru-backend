# Admin API 설계안

> 상태: 제안(proposed) · 관련 이슈: #375 · 작성일: 2026-09-29
>
> 이 문서는 Admin 화면을 실제 백엔드 API에 연결하기 위한 계약·권한·저장 경계를 정의한다. 승인 전까지는 구현 규칙이 아니라 설계 기준으로 취급한다.

## 1. 설계 결론

Admin API는 Spring Boot의 `/api/v1/admin/**` 아래에 둔다. Admin 화면이 사용하는 조회와 운영자 명령을 한 경계에서 제공하되, 도메인 소유권은 기존 모듈에 유지한다.

```text
Admin FE
  -> AdminController                     HTTP/OpenAPI/권한
  -> AdminQueryService / AdminCommandService  유스케이스 조합
  -> community moderation / catalog / operations / identity
  -> PostgreSQL
```

핵심 결정은 다음과 같다.

1. Admin 인증은 회원 OAuth 세션과 분리한다. 짧은 수명의 access JWT와 회전 가능한 refresh session을 사용한다.
2. `ADMIN`, `EDITOR`는 Admin actor 역할이고, 일반 사용자의 `USER`는 권한 역할이 아니다.
3. 후기 검수는 기존 `VisitReviewModerationService`를 재사용한다. Admin API가 DB를 직접 갱신하지 않는다.
4. 파이프라인 조회는 기존 `operations_sync_runs`, `operations_sync_checkpoints`, `operations_sync_quarantine`를 projection으로 읽는다. 실행은 기존 scheduler/use case에 위임한다.
5. 큐레이션은 TourAPI 원천 데이터를 수정하지 않고, 별도 override를 active catalog projection에 적용한다.
6. 모든 변경 명령은 append-only Admin audit를 남기고, 재시도 가능한 명령에는 `Idempotency-Key`를 요구한다.

## 2. 현재 FE 계약에서 확인된 범위

현재 FE Admin 화면은 다음 mock 모델과 동작을 요구한다.

| 화면 | 필요한 기능 | 권한 |
|---|---|---|
| `/admin` | 통계 카드, 최근 후기, 신고 대기, pipeline 요약 | ADMIN, EDITOR |
| `/admin/reviews` | 후기 검색/필터, 단건·일괄 상태 변경 | ADMIN, EDITOR |
| `/admin/reports` | 신고 목록, 숨김/삭제/반려, 사용자 정지 | ADMIN, EDITOR(정지는 ADMIN만) |
| `/admin/users` | 사용자 검색, 상세 활동, 정지/해제, 역할 변경 | 조회: ADMIN, EDITOR / 변경: ADMIN |
| `/admin/curation` | 마을·숙소·루트 목록, 포함 여부·badge 수정 | ADMIN, EDITOR |
| `/admin/data` | pipeline 상태·실패 로그, 동기화 실행/중지 | 조회: ADMIN, EDITOR / 실행·중지: ADMIN |

FE의 `ReportItem.reporter`는 개인정보 최소화 원칙과 충돌한다. 실제 API에서는 신고자 ID·이메일을 기본 응답에서 제외하고, 신고 상세 권한이 있는 ADMIN에게만 감사 목적의 제한된 식별자를 제공한다.

## 3. 인증과 권한

### 3.1 Admin 로그인

```http
POST /api/v1/auth/admin/login
Content-Type: application/json

{
  "email": "admin@onmaru.kr",
  "password": "..."
}
```

응답은 access token만 JSON으로 반환하고 refresh token은 `HttpOnly; Secure; SameSite=Strict` 쿠키로 설정한다.

```json
{
  "schemaVersion": "1.0",
  "accessToken": "eyJ...",
  "expiresIn": 900,
  "admin": {
    "id": "uuid",
    "email": "admin@onmaru.kr",
    "nickname": "온마루지기",
    "role": "ADMIN"
  }
}
```

추가 인증 API:

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/v1/auth/admin/refresh` | refresh cookie 회전, access token 재발급 |
| `POST` | `/api/v1/auth/admin/logout` | 현재 refresh session 폐기 |
| `GET` | `/api/v1/auth/admin/me` | 현재 Admin actor 조회 |

초기 단계에서 FE가 Bearer access token을 사용하므로 access token은 기존 `api/client`와 호환한다. `localStorage` 저장은 운영에서 사용하지 않고, FE는 메모리 보관을 우선한다. 개발 mock은 별도 profile에서만 허용한다.

### 3.2 역할

| 권한 | ADMIN | EDITOR |
|---|---:|---:|
| Dashboard/목록 조회 | O | O |
| 후기 검수·신고 처리 | O | O |
| 큐레이션 override 수정 | O | O |
| pipeline 상태 조회 | O | O |
| pipeline 실행·중지 | O | X |
| 일반 사용자 정지·해제 | O | X |
| Admin 역할 변경 | O | X |
| Admin 계정 생성/비활성화 | O | X |
| 감사 로그 조회 | O | 제한된 자기 작업만 |

`X-OnMaru-Operator`는 기존 moderation 호환 endpoint에서만 단기 유지한다. 새 Admin API는 JWT의 `sub`, `role`을 권한 판단과 audit actor로 사용한다. 헤더만으로 권한을 부여하지 않는다.

## 4. 공통 API 규칙

- 기본 prefix: `/api/v1/admin`
- 모든 응답: `Cache-Control: no-store`
- 목록: `items`, `nextCursor`, `hasNext` 구조. 기본 limit 20, 최대 100
- 정렬: 서버 허용 목록만 사용하며 기본은 `createdAt desc`
- 변경 명령: `Idempotency-Key` 필수, 24시간 보존
- 오류: 기존 `ApiErrorResponse` 사용
- 공통 오류 코드: `AUTH_REQUIRED`, `FORBIDDEN`, `VALIDATION_ERROR`, `NOT_FOUND`, `CONFLICT`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE`
- 모든 응답과 로그에는 `requestId`를 포함한다. 원문 후기·비밀번호·토큰·신고 상세는 로그에 남기지 않는다.

## 5. Endpoint 계약

### 5.1 Dashboard

```http
GET /api/v1/admin/dashboard/summary?from=2026-09-28&to=2026-09-29
```

```json
{
  "schemaVersion": "1.0",
  "range": { "from": "2026-09-28", "to": "2026-09-29" },
  "stats": [
    { "key": "today_reviews", "value": 24, "delta": 8, "deltaType": "increase" },
    { "key": "pending_reports", "value": 3, "delta": 0, "deltaType": "neutral" },
    { "key": "new_users", "value": 12, "delta": 5, "deltaType": "increase" },
    { "key": "total_users", "value": 428, "delta": 12, "deltaType": "increase" }
  ],
  "recentReviews": [],
  "pendingReports": [],
  "pipeline": {
    "lastBuildAt": "2026-09-29T04:00:00Z",
    "result": "SUCCESS",
    "failureCount": 0
  }
}
```

카드별 집계 기준일과 timezone은 `Asia/Seoul`로 고정한다. 값이 계산 불가능하면 0으로 합성하지 않고 `coverageStatus: "MISSING"`을 내려야 한다.

### 5.2 Reviews

```http
GET /api/v1/admin/reviews?status=PUBLISHED&query=북촌&reported=true&limit=20&cursor=...
GET /api/v1/admin/reviews/{reviewId}
POST /api/v1/admin/reviews/{reviewId}/moderation-actions
POST /api/v1/admin/reviews/bulk-moderation-actions
```

```json
{
  "nextStatus": "HIDDEN",
  "reason": "SPAM_CONFIRMED",
  "note": "광고성 반복 게시"
}
```

단건 응답은 `id`, 작성자 공개 식별자, 장소 snapshot, content, mood, tags, image metadata, status, reportCount, createdAt, updatedAt을 포함한다. `email`은 목록에 포함하지 않는다. 일괄 명령은 최대 50개로 제한하고 일부 실패 시 전체를 rollback한다.

기존 `/api/v1/operations/moderation/visit-reviews/{reviewId}`는 호환 경로로 유지하되, 내부적으로 같은 command service를 호출한다. 상태 전이는 `PUBLISHED -> HIDDEN/REMOVED`, `HIDDEN -> PUBLISHED/REMOVED` 등 도메인 허용 전이만 허용한다.

### 5.3 Reports

```http
GET /api/v1/admin/reports?status=PENDING&reason=SPAM&limit=20&cursor=...
GET /api/v1/admin/reports/{reportId}
POST /api/v1/admin/reports/{reportId}/resolve
POST /api/v1/admin/reports/{reportId}/reject
```

```json
{
  "action": "HIDE_REVIEW",
  "moderationReason": "POLICY_VIOLATION",
  "note": "운영 정책 2.1 위반"
}
```

신고 해결은 후기 moderation action과 하나의 트랜잭션으로 처리한다. 단순 `RESOLVED` 처리만 하고 후기 상태를 바꾸지 않는 경우에는 `action: DISMISS`를 사용한다. 신고자는 기본 목록에서 익명화하며, 신고 상세의 민감 필드는 ADMIN만 볼 수 있다.

### 5.4 Users

```http
GET /api/v1/admin/users?status=ACTIVE&query=한옥&limit=20&cursor=...
GET /api/v1/admin/users/{memberId}
POST /api/v1/admin/users/{memberId}/suspensions
DELETE /api/v1/admin/users/{memberId}/suspension
PATCH /api/v1/admin/users/{memberId}/role
```

정지 요청:

```json
{
  "duration": "DAYS_7",
  "reason": "반복적인 광고성 후기 게시"
}
```

역할 변경 요청:

```json
{ "role": "EDITOR" }
```

일반 사용자의 역할은 `USER`로 표시할 수 있지만 Admin actor role과 같은 enum으로 저장하지 않는다. 사용자 이메일은 ADMIN만 조회 가능하며, 목록에서는 nickname과 maskedEmail만 제공한다. 자기 자신 정지, 마지막 ADMIN 강등, 이미 정지된 계정의 중복 정지는 `409 CONFLICT`다.

### 5.5 Curation

```http
GET /api/v1/admin/curations?category=VILLAGE&included=ALL&query=북촌&limit=20&cursor=...
GET /api/v1/admin/curations/{curationId}
PATCH /api/v1/admin/curations/{curationId}
POST /api/v1/admin/curations/publish
```

```json
{
  "isIncluded": true,
  "badges": ["고택", "도심형 한옥"]
}
```

TourAPI 원천 행과 Admin override를 분리한다. `contentId`는 외부 키일 뿐 API primary key로 노출하지 않고 canonical place ID를 사용한다. `publish`는 변경된 override를 active projection에 원자적으로 반영하며, 실패하면 이전 active projection을 유지한다. badge는 최대 12개, 각 40 code points로 제한한다.

### 5.6 Pipeline/Data

```http
GET /api/v1/admin/pipelines/{dataset}/status
GET /api/v1/admin/pipelines/{dataset}/runs?limit=20&cursor=...
GET /api/v1/admin/pipelines/{dataset}/runs/{runId}/failures?limit=100
POST /api/v1/admin/pipelines/{dataset}/runs
POST /api/v1/admin/pipelines/{dataset}/runs/{runId}/cancel
```

실행 요청:

```json
{ "scope": "FULL", "reason": "운영자 수동 재동기화" }
```

`POST`는 동기 완료를 기다리지 않고 `202 Accepted`와 `runId`를 반환한다. 같은 dataset에 실행 중인 run이 있으면 기존 run을 반환하거나 `409 RUN_ALREADY_ACTIVE`를 반환한다. Admin 화면의 `used/limit`, 실패 endpoint, 마지막 성공 시각은 `operations_sync_runs.counts`와 checkpoint/quarantine projection으로 계산한다. 원문 payload는 API로 노출하지 않는다.

## 6. 저장 모델 제안

기존 identity/community/operations 테이블을 재사용하고, Admin 전용 상태만 추가한다.

### `identity_admin_accounts`

- `id uuid primary key`
- `email citext unique not null`
- `password_hash varchar not null` — Argon2id 권장
- `nickname varchar not null`
- `role enum('ADMIN','EDITOR') not null`
- `status enum('ACTIVE','SUSPENDED','DISABLED') not null`
- `last_login_at`, `created_at`, `updated_at`

### `identity_admin_sessions`

- `token_hash varchar primary key`
- `admin_id` FK
- `created_at`, `last_seen_at`, `expires_at`, `revoked_at`
- refresh token 원문은 저장하지 않는다.

### `identity_member_sanctions`

- `id`, `member_id` FK, `type`, `reason`, `starts_at`, `ends_at`, `created_by`, `revoked_at`
- `(member_id, active)` partial unique index로 활성 정지 하나만 허용

### `catalog_admin_curation_overrides`

- `id`, `canonical_place_id`, `category`, `included`, `badges jsonb`
- `source_revision_id`, `version`, `updated_by`, `created_at`, `updated_at`
- `(canonical_place_id, category, version)` unique

### `identity_admin_audit_log`

- `id`, `actor_admin_id`, `action`, `resource_type`, `resource_id`
- `reason`, `note`, `before_state jsonb`, `after_state jsonb`, `request_id`, `created_at`
- append-only. UPDATE/DELETE 권한을 애플리케이션 role에서 제거한다.

Admin 계정과 sanction/curation/audit migration은 기존 baseline 번호를 재사용하지 않고 새 migration으로 추가한다. `docs/database/schema.md`, DBML, migration registry를 같은 변경에서 갱신한다.

## 7. 보안·신뢰성 요구사항

- 로그인: 이메일 정규화, 5회 실패 후 exponential backoff, IP·계정 단위 rate limit
- 비밀번호: Argon2id, 평문/복호화 불가. 초기 계정은 일회성 bootstrap secret으로 생성 후 즉시 교체
- JWT: 15분 access, refresh rotation/reuse detection, `iss/aud/sub/role/jti/exp` 검증
- 권한: controller 진입과 service command 양쪽에서 검사해 내부 호출 우회 방지
- 감사: 모든 상태 변경에 actor, reason, before/after, requestId 기록
- 멱등성: 동일 key + 다른 payload는 `409 IDEMPOTENCY_CONFLICT`
- 개인정보: 목록 email 제거·masking, reporter 식별자 최소화, 로그 redaction
- 동시성: 후기/신고/큐레이션 변경은 optimistic version 또는 `SELECT ... FOR UPDATE`
- 외부 pipeline: timeout, bounded retry, lease, cancel은 기존 sync operation 규칙 재사용

## 8. 구현 순서

### Phase 0 — 계약 고정

1. `docs/contracts/openapi/admin.openapi.yaml` 작성
2. FE mock enum과 실제 도메인 enum 차이 확정
3. `ADMIN/EDITOR` 권한 matrix와 개인정보 노출 정책 승인
4. synthetic fixture와 MockMvc contract test 추가

### Phase 1 — 인증과 공통 경계

1. Admin account/session migration
2. password login, refresh, logout, me
3. JWT verifier와 role guard
4. audit/idempotency 공통 컴포넌트

### Phase 2 — 운영 핵심

1. reports/reviews read projection
2. 기존 moderation service 연결
3. dashboard summary
4. FE `/admin/reviews`, `/admin/reports` 연결

### Phase 3 — 계정과 큐레이션

1. users 조회·정지·해제·역할 변경
2. curation override와 publish
3. FE users/curation 연결

### Phase 4 — pipeline과 운영 검증

1. sync run/status/failure projection
2. manual run/cancel command
3. 운영자 synthetic drill 및 load/권한 테스트
4. 운영 배포 후 Admin login, moderation, curation, pipeline smoke

## 9. 완료 기준

- OpenAPI lint와 FE contract fixture 검증 통과
- ADMIN/EDITOR 각 endpoint의 401/403/200/400/409 경계 테스트 통과
- 후기 검수와 신고 해결이 기존 public query에서 즉시 비공개 처리됨
- 사용자 정지/해제가 public write와 로그인 정책에 반영됨
- 큐레이션 publish가 실패 시 이전 active revision을 보존함
- pipeline 수동 실행이 중복 실행을 막고 `runId`를 통해 상태 조회 가능함
- 모든 mutation에 audit row와 requestId가 남음
- 비밀번호·JWT·신고자 개인정보·후기 원문이 로그와 기본 목록 응답에 노출되지 않음
- 운영 smoke에서 mock token과 in-memory 데이터가 사용되지 않음

## 10. 의도적으로 제외하는 범위

- Admin UI 자체 구현
- 일반 회원 OAuth를 Admin 로그인으로 재사용
- TourAPI 원천 데이터 직접 수정
- pipeline 실패 행의 원문 payload 편집
- 다중 조직/tenant 권한 모델
- 사용자 이의제기·appeal workflow

이 범위는 먼저 현재 Admin 화면을 실제 데이터에 연결하는 MVP이며, 조직·세분화된 정책·appeal은 별도 이슈로 분리한다.
