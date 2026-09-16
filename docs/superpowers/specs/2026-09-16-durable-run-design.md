# Durable run 상태 머신 설계

## 결정

- `modules/journey/run`이 run 상태, stage 순서, terminal 규칙과 persistence port를 소유한다.
- `adapters/persistence-jdbc`가 PostgreSQL의 `discovery_runs`와 새 `discovery_run_commands`를 한 transaction에서 갱신한다.
- command별 transaction-scoped advisory lock으로 같은 actor/operation/key를 직렬화한다. 같은 request hash는 저장된 receipt를 replay하고 다른 hash는 conflict다.
- run claim은 `QUEUED + generation`, stage는 `RUNNING + generation + expected stage`, terminal은 active status와 generation을 predicate로 사용하는 compare-and-set이다. terminal race의 후발 FINISH command는 이미 확정된 terminal snapshot을 receipt로 저장한다.
- `V006`의 exploration별 active run partial unique index가 동시 run 생성을 최종 차단한다. 취소와 완료 race는 같은 active row CAS를 사용해 하나만 terminal이 된다.
- AI 호출과 SSE는 transaction 밖의 후속 Issue 범위다. 조회는 언제나 DB snapshot을 다시 읽는다.

## 저장 계약

`discovery_run_commands`는 actor, operation, command key, request hash와 immutable result receipt를 저장한다. receipt는 run ID, status, stage, outcome, generation을 담아 retry 응답을 안정적으로 재현한다. business mutation과 receipt insert는 같은 transaction에서 commit된다.

## 오류 경계

- 동일 key와 다른 hash: `RunCommandConflictException`이며 HTTP 연결 시 `IDEMPOTENCY_CONFLICT`로 변환한다.
- stale generation/stage: `RunTransitionConflictException`이며 HTTP 연결 시 `VERSION_CONFLICT`로 변환한다. 이미 terminal인 run에 도착한 FINISH command는 현재 terminal receipt를 반환한다.
- exploration active run 중복: `ActiveRunConflictException`이며 HTTP 연결 시 `ACTIVE_RUN`으로 변환한다.
- actor/run 불일치: `RunNotFoundException`으로 자원 존재를 숨긴다.

## 검증

실제 PostgreSQL Testcontainers에서 create/claim/stage/terminal, 동일 command concurrent replay, payload mismatch, exploration active run uniqueness, cancel/complete race와 새 adapter instance의 snapshot 복구를 검증한다.
