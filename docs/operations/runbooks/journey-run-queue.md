# 여정 run 큐와 워커 드레인 운영 런북

> 대상: 여정 탐색 실행(`discovery_runs`)을 워커가 처리하는 경로. 관련 이슈: #811, #109, #114.

## 구조 요약

- 탐색을 만들면 `discovery_runs`에 `QUEUED` 행과 질의 턴(`discovery_turns`)이 함께 저장됩니다. **이 행이 곧 작업 큐**입니다. 별도 큐나 Redis는 없습니다.
- 워커 드레인(`JourneyWorkerDrainScheduler`)이 주기적으로 `QUEUED` 행을 폴링하고, CAS claim으로 한 번에 한 인스턴스만 처리합니다.
- 탐색 상태와 결과, 실행 상태는 모두 Postgres에 있습니다. 앱 프로세스 메모리에는 SSE 이벤트 버퍼만 있습니다.

## 설정 (운영 compose)

| 변수 | 운영 값 | 의미 |
| --- | --- | --- |
| `ONMARU_JOURNEY_EXPLORATION_STORE` | `jdbc` | 탐색 저장소를 Postgres로. 그 외 값이면 메모리(개발용). |
| `ONMARU_JOURNEY_WORKER_DRAIN_ENABLED` | `true` | 드레인 스케줄러 활성화. `false`면 `QUEUED`가 쌓이기만 합니다. |
| `ONMARU_JOURNEY_WORKER_DRAIN_INTERVAL_MS` | `1000` | 드레인 주기(밀리초). |

## 재시작 동작

- **앱 재시작·재배포**: `QUEUED` 행은 DB에 그대로 남습니다. 기동 후 드레인이 다시 집어 갑니다. 메모리에 있던 SSE 연결과 버퍼는 사라지므로 클라이언트는 재연결하고 스냅샷을 다시 읽어야 합니다.
- **처리 도중 재시작**: `RUNNING` 행은 lease가 만료되면 sweeper가 취소합니다. 결과 저장은 deadline 안에서만 반영됩니다.
- **deadline 경과**: `QUEUED`인데 deadline이 지난 run은 드레인이 집어 가지 않고, sweeper가 취소합니다.

## 로그 확인

- 처리가 일어나면 `journey worker drained processed=N outcomes={...}` 로그가 남습니다. run id나 질의는 로그에 없습니다.
- 드레인 중 예외는 `journey worker drain failed` 경고로 남고, 스케줄은 계속 돕니다.

## 상태 확인 SQL (읽기 전용)

```sql
-- 대기 중인 run 수와 가장 오래된 대기 시간
SELECT count(*) AS queued, min(created_at) AS oldest
FROM onmaru.discovery_runs WHERE status = 'QUEUED';

-- 처리 중인 run (lease 만료 여부 확인)
SELECT id, status, generation, lease_expires_at, deadline_at
FROM onmaru.discovery_runs WHERE status = 'RUNNING';
```

## 롤백

1. 드레인을 끈다: `ONMARU_JOURNEY_WORKER_DRAIN_ENABLED=false` 로 바꾸고 재기동. `QUEUED` 행은 보존됩니다.
2. 탐색 저장소를 메모리로 되돌려야 할 때만: `ONMARU_JOURNEY_EXPLORATION_STORE` 를 `jdbc`가 아닌 값으로 바꿉니다. **주의**: 이 경우 DB 탐색 상태는 새 요청에 보이지 않습니다. 이미 만든 run은 DB에 남아 있으니, 롤백 전에 `QUEUED`와 `RUNNING` 건수를 확인하세요.
3. 마이그레이션(V055~V057)은 되돌리지 않습니다. 이전 버전 앱은 새 단계 값(`SELECTING_EVIDENCE`, `GENERATING`)을 모를 수 있으니, 롤백 후 해당 run은 수동으로 정리합니다.

## 알려진 제약

- SSE 이벤트는 프로세스 메모리 버퍼입니다. 인스턴스가 여러 대가 되면 DB sequence 재생 방식으로 바꿔야 합니다.
- 결과 저장과 워커는 같은 앱 시계를 씁니다. 서버 시계 동기화(NTP)가 깨지면 deadline 판단이 틀어질 수 있습니다.
- 드레인은 단일 스레드입니다. 적체가 길어지면 주기나 처리량을 늘리기 전에 AI 지연부터 확인하세요.
