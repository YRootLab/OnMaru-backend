# K-Contents 조사 워커 내부 계약 v1

이 API는 로컬 워커가 서버에서 작업을 가져오는 pull 경계다. 워커는 운영 PostgreSQL에 직접 접속하지 않으며 서버는 로컬 PC에 callback하지 않는다. DB가 준비된 환경에서 `onmaru.kcontents.research.enabled=true`와 운영 secret `onmaru.kcontents.research.worker-token`을 함께 설정해야 API가 활성화된다. 기능 플래그가 꺼져 있으면 route 자체가 등록되지 않고, 토큰이 빈 값이면 worker 요청은 401이다. 워커 토큰은 `/admin/*` 권한을 갖지 않으며 관리자 JWT는 worker API 권한을 갖지 않는다.

| 요청 | 권한 | 의미 |
|---|---|---|
| `POST /api/v1/internal/kcontents/research/jobs/lease` | worker bearer + `X-Worker-Id` | 준비된 작업 하나를 15분 lease. 없으면 204. 응답에 job/place/reason/source fingerprint/input JSON/lease token/만료/시도 번호 포함. |
| `POST .../jobs/{jobId}/evidence` | worker bearer + worker ID + `X-Lease-Token` | URL·제목·짧은 발췌 최대 20건 제출. URL 정규화·중복 제거 후 **서버가 발급한 evidenceId** 목록 반환. |
| `POST .../jobs/{jobId}/submit` | 위 lease 헤더 + `Idempotency-Key` | `resultStatus`(`MATCH/NO_MATCH/UNCERTAIN`), schema/prompt/model 버전, 서버 evidence ID 목록, JSON 문자열 결과를 원자적으로 접수. JSON 내부 모든 `evidenceId`/`evidenceIds`는 최상위 목록과 정확히 일치해야 하고 해당 job 소속이어야 한다. |
| `POST .../jobs/{jobId}/fail` | 위 lease 헤더 + `Idempotency-Key` | `TIMEOUT`, `RATE_LIMITED`, `CLI_FAILURE`, `INVALID_JSON`, `OTHER` 코드 제출. 일시 장애는 제한된 지수 backoff, 잘못된 JSON은 격리, 최대 시도는 FAILED. |
| `POST .../admin/jobs`, `GET .../admin/jobs/{jobId}`, `POST .../admin/jobs/{jobId}/requeue` | 관리자 JWT | 중복 fingerprint enqueue, 상태 조회, 실패/격리 작업 수동 재큐잉. |

Lease token은 DB에 SHA-256 해시만 저장하며 만료·소유자 불일치 시 409다. 같은 `Idempotency-Key`는 동일 worker·lease token·payload에만 동일 응답을 재생한다. 하루 lease 예산은 `onmaru.kcontents.research.daily-lease-budget`(기본 1000)으로 제한한다. 운영 재큐잉은 새 epoch를 만들며 과거 시도·receipt·감사 기록을 보존한다.

`SUCCEEDED`는 **원시 조사 결과 접수 완료**를 뜻한다. 촬영 관계의 근거 검증과 공개는 W7 및 게시 quality gate가 수행한다. 기존 공개 API와 데이터는 이 경로가 변경하지 않는다.

검색 worker는 같은 lease에서 검색→서버 evidence ID 발급→로컬 추출→최종 제출을 수행한다. 최종 결과는 W7의 `kcontents-extraction-v1` schema, `kcontents-evidence-v1` prompt와 `{resultStatus,candidates,ambiguityReason?,nextSearchAt?}` JSON을 따른다. `nextSearchAt`은 `NO_MATCH`에만 쓰는 재조사 권장 시각이며 서버 자동 예약이 아니다. 현재 관리자 `requeue`는 `FAILED`/`QUARANTINED`만 허용해 `SUCCEEDED/NO_MATCH` 동일 job을 다시 열 수 없다. 새 source fingerprint는 새 job으로 등록할 수 있고, 동일 fingerprint TTL 만료 재조사는 별도 서버 계약이 필요하다.
