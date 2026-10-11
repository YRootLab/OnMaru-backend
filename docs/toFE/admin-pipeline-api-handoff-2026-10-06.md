# 관리자 데이터 파이프라인 API FE handoff

Issue #668 기준 계약이다. 모든 응답은 `Cache-Control: no-store`이며 관리자 Bearer 인증을 사용한다.

## 상태 조회

`GET /api/v1/admin/pipelines/kto-korean-tour/status`

- 기존 `dataset`, `status`, `lastSuccessAt`, `failureCount`는 유지한다.
- `failureCount`의 의미는 최근 `lastRun.runId`에 속한 실패 항목 수다.
- 과거 실패 실행 누계가 필요하면 `cumulativeFailureRunCount`를 사용한다.
- 실행 이력이 없으면 `lastRun: null`이다.
- `contentStats`, `apiUsage`는 신뢰 가능한 저장 집계가 없으므로 현재 null 또는 생략될 수 있다. 0으로 해석하지 않는다.

## 실행 이력 조회

TourAPI 수집은 Backend의 3일 주기 scheduler가 소유한다. 관리자 화면에서 갱신을 시작하지 않는다.

기존 `POST /api/v1/admin/pipelines/kto-korean-tour/runs` 경로는 호환성을 위해 남지만 수집을 실행하지 않고 501 `NOT_IMPLEMENTED`를 반환한다. FE에서는 갱신 버튼을 연결하지 않는다.

`GET /api/v1/admin/pipelines/kto-korean-tour/runs/{runId}`는 scheduler가 만든 실행 이력을 조회한다. `progress`가 null이면 임의 progress bar를 만들지 말고 status와 시각만 표시한다.

## 실패 로그

`GET /api/v1/admin/pipelines/kto-korean-tour/runs/{runId}/failures?limit=20&cursor=...`

- `nextCursor`는 그대로 다음 요청에 전달하며 직접 해석하거나 수정하지 않는다.
- `totalCount`는 해당 run의 전체 실패 항목 수다.
- `endpoint`, `contentId`는 원천에서 안전하게 식별할 수 있을 때만 존재한다.
- `message`는 sanitized 진단 메시지이며 전체 upstream response가 아니다.

사이드바의 현재 전체 미처리 신고 수는 계속 `/api/v1/admin/reports.totalCount`를 canonical source로 사용한다. 대시보드 기간 통계와 혼합하지 않는다.
