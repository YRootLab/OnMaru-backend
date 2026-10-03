# Journey 통합 계약 출시 증거

## 게이트 범위

Journey 출시 게이트는 `testing/e2e/journey/contract-gate.json`을 기준으로 Journey 탐색, AI 생성·수정, 실시간 SSE, 취소, Quota·장애 격리, 저장 및 재개의 전 과정 계약을 하나의 Spring runtime 흐름에서 검증한다. 계약 문서와 fixture 자체의 검증은 `scripts/verify-contracts`가 먼저 수행하고, 같은 명령이 scenario manifest 누락도 차단한다.

| Scenario | Runtime 증거 | 실패 시 차단하는 drift |
| --- | --- | --- |
| `clarification-and-board` | 게스트/회원 탐색 생성, 지역 결측 clarification, 답변 intake 후 AI run 완료, 초기 board snapshot 생성 | snapshot serializer shape/type drift, clarification 필드 누락, 임의 필드 누출, no-store 누락 |
| `proposal-actions-and-versioning` | AI proposal 기반 PIN·EXCLUDE 액션 수행, stateVersion 증가, stale version 충돌 방어, 멱등성 replay/conflict 처리 | stateVersion 불일치, 동시 수정 충돌 무시, Idempotency-Key 재사용 conflict 누락, 타 actor 리소스 접근 허용 |
| `sse-lifecycle-and-reconnect` | SSE 실시간 스트림 연결, run.stage 순차 전달, run.terminal 전달, Last-Event-ID 기반 replay, buffer miss 시 reset 발행, 인증 실패 시 auth_closed 및 세션 분리 | SSE frame schema drift, replay sequence 역전, stale 연결 무한 대기, auth close 시 private data 유출 |
| `run-cancellation-and-race` | 실행 중 run 취소, terminal 상태 전이 및 CAS race 방어, active admission slot 즉시 반환, sweeper deadline | 취소 후 run 계속 실행, 취소-완료 race 시 복수 terminal 상태 생성, active slot 누수 |
| `ai-outage-fallback-and-quota` | AI worker/FastAPI 장애 및 timeout 시 BASELINE 엔진 fallback 및 degradedReason 기록, 일일 quota 초과 시 429 RATE_LIMITED 및 Retry-After 제공 | AI 장애 시 서버 500 전파, 비결정적 board 생성, quota 초과 무시, Retry-After header 누락 |
| `saved-journey-and-resume` | 완성된 exploration 기반 saved journey 생성, 커서 기반 페이징 목록, active run 저장 차단, 상세 조회, 카탈로그 상태 기반 unavailable ref 분리 재개, 삭제 멱등성 | source exploration 만료 시 상세 실패, unavailable place 누락, 타 actor saved journey 노출/조작 |

## 재현 명령

```bash
python3 scripts/test/validate-journey-e2e-gate.py
bash scripts/verify-contracts
./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.journey.JourneyContractE2ETests --no-daemon --max-workers=1
./gradlew test --no-daemon --max-workers=1
```

PostgreSQL migration 계약과 도메인 불변식은 전체 Gradle 검증의 `apps:spring-api` Testcontainers 및 단위 suite가 함께 실행한다. 고정 fixture는 외부 AI 호출 없이 동일 입력을 재현하며, runtime suite는 응답을 Journey fixture와 재귀적으로 key/type 비교해 serializer drift를 검출한다.

## 판정 기준

- manifest, OpenAPI, SSE schema, fixture, runtime suite 중 하나라도 없거나 scenario coverage가 빠지면 실패한다.
- 공개 응답에서 내부 DB row ID, provider raw identifier, 민감 인증 정보가 노출되면 실패한다.
- active run이 있는 상태에서 저장 시도 시 `409 ACTIVE_RUN`으로 차단되지 않으면 실패한다.
- AI 다운 또는 timeout 시 `500 INTERNAL_SERVER_ERROR`가 발생하거나 fallback board 생성이 실패하면 실패한다.
- SSE 연결 끊김/재연결 시 reset 이벤트 또는 replay가 계약대로 동작하지 않으면 실패한다.

## 2026년 10월 AI streaming 테스트 gate (#518)

로컬 회귀 통과만으로 #518을 완료 처리하지 않는다. `staging` 환경에서 다음 값을 secret 원문 없이 배포 SHA와 함께 기록한다.

- 적용 Gemini model과 tier, streaming 호출 허용 여부 및 실제 quota 응답
- `ONMARU_JOURNEY_TEST_QUOTA_START/END/LIMIT`와 exempt 계정 설정 여부. 계정 ID 자체는 증거에 남기지 않는다.
- 일반 회원 2회 허용·3회차 provider 호출 전 429, exempt 회원의 총량 우회와 active 1회 제한, guest AI 요청의 zero-dispatch 401
- 첫 `run.text.delta` latency, delta가 terminal보다 먼저 도착하는지, Nginx/Vercel proxy buffering이 없는지
- 연결 종료·취소·timeout·provider 429/5xx에서 late delta가 없고 BASELINE 또는 기존 terminal 계약으로 수렴하는지
- browser event에는 narration만 있고 provider raw JSON, candidate refs, 좌표, prompt, key와 내부 오류 원문이 없는지
- 최종 proposal이 Spring allowlist·검증·snapshot 저장을 통과한 뒤 GET snapshot으로만 지도에 반영되는지
- streaming endpoint 미지원/비활성 시 unary fallback, 둘 다 실패할 때 deterministic BASELINE이 동작하는지

실패한 항목은 배포를 차단하고 workflow run URL, deployment SHA, image digest, sanitized timing/count만 남긴다. 테스트 기간 종료 후에는 설정을 운영 정책으로 되돌리거나 새 정책을 승인하고, exempt 목록 제거 여부를 재확인한다.
