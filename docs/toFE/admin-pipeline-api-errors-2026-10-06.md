# 관리자 데이터 파이프라인 API·오류 계약

Issue #668 기준 FE 연동 문서다. 관리자 화면은 TourAPI 수집을 시작하지 않고, Backend의 3일 주기 scheduler가 저장한 실행 상태와 실패 기록만 조회한다.

## 공통 규칙

- Base path: `/api/v1`
- canonical dataset: `kto-korean-tour`
- `Authorization: Bearer <admin-access-token>` 필수
- 성공·오류 응답 모두 `Cache-Control: no-store`
- 시각은 UTC ISO-8601
- FE는 오류 `message`를 직접 노출하지 말고 `code`를 사용자 문구로 매핑한다.

```json
{
  "schemaVersion": "1.2",
  "code": "VALIDATION_ERROR",
  "message": "VALIDATION_ERROR",
  "requestId": "uuid",
  "details": {}
}
```

`requestId`는 Backend 로그와 장애 문의의 상관관계 ID다.

## 상태 조회

`GET /admin/pipelines/{dataset}/status`

```json
{
  "schemaVersion": "1.1",
  "dataset": "kto-korean-tour",
  "status": "SUCCEEDED",
  "lastSuccessAt": "2026-10-06T04:05:32Z",
  "failureCount": 3,
  "cumulativeFailureRunCount": 9,
  "lastRun": {
    "runId": "00000000-0000-0000-0000-000000000668",
    "dataset": "kto-korean-tour",
    "scope": "ALL",
    "status": "SUCCEEDED",
    "progress": null,
    "startedAt": "2026-10-06T04:00:00Z",
    "finishedAt": "2026-10-06T04:05:32Z",
    "durationSeconds": 332,
    "failureCount": 3
  },
  "contentStats": null,
  "apiUsage": null
}
```

- `failureCount`: 최근 `lastRun.runId`에 속한 실패 항목 수
- `cumulativeFailureRunCount`: 실패 실행 누계
- 실행 이력이 없으면 `lastRun`, `lastSuccessAt`은 null
- 신뢰 가능한 집계가 없는 `contentStats`, `apiUsage`는 null 또는 생략되며 0으로 해석하지 않는다.

| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 지원하지 않는 dataset |
| 401 | `AUTH_REQUIRED` | token 누락·만료·위조 |
| 503 | `SERVICE_UNAVAILABLE` | DB 조회 불가 |
| 500 | `INTERNAL_ERROR` | 분류되지 않은 서버 오류 |

## 실행 단건 조회

`GET /admin/pipelines/{dataset}/runs/{runId}`

```json
{
  "runId": "00000000-0000-0000-0000-000000000668",
  "dataset": "kto-korean-tour",
  "scope": "ALL",
  "status": "RUNNING",
  "progress": null,
  "startedAt": "2026-10-06T06:00:03Z",
  "finishedAt": null,
  "durationSeconds": null,
  "failureCount": 1
}
```

`progress`가 null이면 FE가 시간 기반 가상 진행률을 만들지 않는다.

| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 잘못된 dataset 또는 UUID가 아닌 runId |
| 401 | `AUTH_REQUIRED` | 인증 실패 |
| 404 | `NOT_FOUND` | run이 없거나 dataset과 불일치 |
| 503 | `SERVICE_UNAVAILABLE` | DB 조회 불가 |
| 500 | `INTERNAL_ERROR` | 분류되지 않은 서버 오류 |

## 실패 로그

`GET /admin/pipelines/{dataset}/runs/{runId}/failures?limit=20&cursor=...`

- `limit`: 기본 20, 허용 범위 1~100
- `cursor`: 이전 응답의 `nextCursor`. 서명된 불투명 값이므로 해석·수정하지 않는다.

```json
{
  "schemaVersion": "1.0",
  "items": [{
    "id": "00000000-0000-0000-0000-000000000669",
    "occurredAt": "2026-10-06T04:02:11Z",
    "endpoint": "detailCommon2",
    "contentId": "126513",
    "errorCode": "UPSTREAM_TIMEOUT",
    "message": "TourAPI request timed out",
    "retryable": true
  }],
  "totalCount": 3,
  "hasNext": false,
  "nextCursor": null
}
```

| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | limit 범위 위반, 손상·만료·조건 불일치 cursor, 잘못된 dataset/runId |
| 401 | `AUTH_REQUIRED` | 인증 실패 |
| 404 | `NOT_FOUND` | run이 없거나 dataset과 불일치 |
| 503 | `SERVICE_UNAVAILABLE` | DB 조회 불가 |
| 500 | `INTERNAL_ERROR` | 분류되지 않은 서버 오류 |

손상된 cursor는 제거하고 첫 페이지부터 다시 조회한다. 실패 메시지는 관리자 진단용으로 정제하며 API key, Authorization header, 개인정보, 전체 upstream 응답은 제공하지 않는다.

## 수동 실행 endpoint

`POST /admin/pipelines/{dataset}/runs`

이 경로는 호환성을 위해 남아 있지만 수집 adapter를 호출하지 않는다. FE는 갱신 버튼을 연결하지 않는다.

| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Idempotency-Key 누락·형식 오류 또는 `ALL` 이외 scope |
| 401 | `AUTH_REQUIRED` | 인증 실패 |
| 403 | `FORBIDDEN` | pipeline 관리 권한 없음 또는 CSRF 검증 실패 |
| 501 | `NOT_IMPLEMENTED` | 유효한 수동 실행 요청; 3일 주기 scheduler 사용 |
| 503 | `SERVICE_UNAVAILABLE` | 의존 서비스 장애 |
| 500 | `INTERNAL_ERROR` | 분류되지 않은 서버 오류 |

## FE 오류 처리 권장 순서

1. 401: 관리자 로그인 화면으로 이동한다.
2. 403: 권한 부족 또는 CSRF session 갱신 안내를 표시한다.
3. 400: 요청 값을 수정한다. cursor 오류면 첫 페이지부터 다시 조회한다.
4. 404: status를 다시 조회해 최신 runId로 갱신한다.
5. 501: 수동 갱신 UI를 숨기고 자동 수집 안내를 표시한다.
6. 503: 짧은 backoff 후 제한적으로 재시도한다.
7. 500: 자동 반복 요청을 중단하고 `requestId`를 기록한다.

## 사이드바 집계

- 현재 전체 미처리 신고 수는 `/admin/reports.totalCount`가 canonical source다.
- 기간 내 온기 수는 dashboard의 `REVIEWS_CREATED` 의미로 사용한다.
- “마지막 관리자 확인 이후 신규” read cursor는 이번 계약에 포함되지 않는다.
