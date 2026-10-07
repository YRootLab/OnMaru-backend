# `/admin/data` Backend 구현 완료 명세

Issue #668 및 Backend PR #670 기준이다. FE는 fixture 없이 Backend scheduler의 실제 실행 이력을 조회할 수 있다.

## 구현 완료 범위

| 기능 | API | 상태 |
|---|---|---|
| 최근 pipeline 상태 | `GET /api/v1/admin/pipelines/kto-korean-tour/status` | 구현 완료 |
| 실행 단건 조회 | `GET /api/v1/admin/pipelines/kto-korean-tour/runs/{runId}` | 구현 완료 |
| 실행별 실패 로그 | `GET /api/v1/admin/pipelines/kto-korean-tour/runs/{runId}/failures` | 구현 완료 |
| 관리자 수동 갱신 | `POST /api/v1/admin/pipelines/kto-korean-tour/runs` | 의도적으로 미지원, 501 |
| 수집량·이미지율 | status의 `contentStats` | 신뢰 가능한 집계 전까지 null/생략 |
| API quota | status의 `apiUsage` | 신뢰 가능한 계측 전까지 null/생략 |

TourAPI 수집 주기와 실행 소유권은 기존 Backend scheduler에 있다. FE 버튼이나 관리자 POST가 수집을 시작하지 않는다.

## 화면 진입 시 호출 순서

1. `/status`를 조회한다.
2. `lastRun`이 있으면 상태, 시작·종료 시각, 소요 시간과 실패 건수를 표시한다.
3. 실행 상세 화면이 필요할 때 `lastRun.runId`로 `/runs/{runId}`를 조회한다.
4. `failureCount > 0`이면 `/failures?limit=20`을 조회한다.
5. `hasNext=true`이면 응답의 `nextCursor`를 그대로 다음 요청에 전달한다.

권장 refresh 방식은 화면 focus 또는 사용자의 명시적인 “상태 새로고침”이다. 이 새로고침은 GET 재조회만 의미하며 데이터 재수집이 아니다.

## `/status` 필드 매핑

| 응답 필드 | FE 표시 | 주의사항 |
|---|---|---|
| `status` | pipeline 요약 상태 badge | `MISSING`, `IDLE`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 처리 |
| `lastSuccessAt` | 마지막 성공 시각 | null이면 성공 이력 없음 |
| `failureCount` | 최근 실행 실패 항목 수 | 누적 실패 실행 수가 아님 |
| `cumulativeFailureRunCount` | 선택적 운영 누계 | 최근 실행 실패 항목과 혼합 금지 |
| `lastRun.runId` | 상세·실패 로그 조회 key | UUID opaque 값 |
| `lastRun.durationSeconds` | 실행 소요 시간 | 실행 중이거나 시각 미확정이면 null |
| `contentStats` | 수집량 카드 | null/필드 없음이면 “집계 준비 중” |
| `apiUsage` | quota 카드 | null/필드 없음이면 “계측 준비 중” |

## 상태 표시

- `/status.status`의 `IDLE`: 최근 실행이 대기 중
- `/status.status`의 `CANCELLED`: 취소됨
- `/runs/{runId}.status`의 `QUEUED`: 대기
- `RUNNING`: 실행 중
- `SUCCEEDED`: 성공
- `FAILED`: 실패
- `/runs/{runId}.status`의 `ABANDONED`: 중단·건너뜀
- `/status.status`의 `MISSING`: 실행 이력 없음

`progress`가 null이면 임의의 percentage나 자동 증가 progress bar를 표시하지 않는다. `total=0`인 경우에도 `percent`는 null이다.

## 실패 로그 표시

- `message`는 관리자 진단용 sanitized 문구다.
- `endpoint`, `contentId`는 저장 근거가 없으면 null일 수 있다.
- 기존 scheduler의 run-level `errorCode`도 안전한 단일 실패 항목으로 조회된다.
- API key, Authorization header, 개인정보, 전체 upstream body는 응답에 포함되지 않는다.
- cursor 오류 400이 발생하면 저장한 cursor를 버리고 첫 페이지부터 다시 조회한다.

## 수동 갱신 UI

수동 TourAPI 갱신 버튼은 연결하지 않는다. 기존 POST endpoint는 호환성을 위해 존재하지만 정상적인 요청도 아래 응답을 반환한다.

```json
{
  "schemaVersion": "1.2",
  "code": "NOT_IMPLEMENTED",
  "message": "NOT_IMPLEMENTED",
  "requestId": "uuid",
  "details": {}
}
```

상태를 다시 보고 싶은 경우 버튼 문구는 “데이터 갱신”이 아니라 “상태 새로고침”으로 제공하고 `/status`만 다시 호출한다.

## 인증과 오류 처리

- 모든 GET 요청에 관리자 Bearer token을 보낸다.
- 401이면 관리자 재로그인을 수행한다.
- 404이면 오래된 runId일 수 있으므로 `/status`를 다시 조회한다.
- 503이면 짧은 backoff 후 제한적으로 재시도한다.
- 500이면 반복 요청을 중단하고 `requestId`를 기록한다.
- 상세 HTTP status와 오류 code 표는 `admin-pipeline-api-errors-2026-10-06.md`를 따른다.

## 기존 관리자 화면과의 관계

- 현재 전체 OPEN 신고 수는 `GET /api/v1/admin/reports`의 `totalCount`를 사용한다.
- dashboard 기간 집계의 `REPORTS_PENDING`과 전체 미처리 신고 수를 혼합하지 않는다.
- 기간 내 신규 온기는 `REVIEWS_CREATED`로 해석한다.
- 마지막 관리자 확인 이후 신규 기능은 이번 구현 범위가 아니다.

## FE 완료 체크리스트

- [ ] pipeline fixture와 hardcoding 제거
- [ ] `/status`의 기존 필드와 확장 필드를 함께 파싱
- [ ] `lastRun: null` empty state 처리
- [ ] nullable `progress`, `durationSeconds`, `contentStats`, `apiUsage` 처리
- [ ] 최근 실행 `failureCount`와 `cumulativeFailureRunCount` 구분
- [ ] 실패 로그 cursor를 수정하지 않고 재사용
- [ ] 수동 갱신 POST 및 TourAPI 재수집 버튼 제거
- [ ] “상태 새로고침”은 GET 재조회로만 구현
- [ ] 오류 code 기반 사용자 문구와 `requestId` 기록
- [ ] 응답을 브라우저 영구 cache에 저장하지 않음
