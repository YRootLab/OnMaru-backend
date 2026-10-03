# AI 여정지도 스트리밍·테스트 사용량 정책 설계

## 목적과 범위

Issue #518의 2026년 10월 테스트를 위해 회원 계정 단위 월간 사용량 제한과 Gemini 텍스트 스트리밍을 추가한다. 기존 REST command → SSE notification → PostgreSQL snapshot 복구 구조, Spring의 최종 제안 검증 책임, provider 장애 시 baseline fallback은 유지한다.

이번 범위는 결제 quota, guest AI 체험, 지도 후보의 검증 전 공개, WebSocket, durable token event log를 포함하지 않는다.

## 사용량 정책

- AI run을 만드는 `POST /api/v1/explorations`와 `POST /api/v1/explorations/{explorationId}/turns`는 회원 세션을 요구한다. guest credential만 있으면 provider 호출과 worker dispatch 전에 `401 AUTH_REQUIRED`를 반환한다.
- 테스트 기간은 기본값 `[2026-10-01T00:00:00+09:00, 2026-11-01T00:00:00+09:00)`이며 환경 설정으로 시작·종료·일반 한도·예외 회원 ID를 변경할 수 있다.
- 기간 안의 일반 회원 한도는 계정당 2회다. PostgreSQL admission counter의 row lock을 재사용해 동시 요청에서도 세 번째 run이 접수되지 않게 한다.
- 예외 회원은 월간 총량만 우회한다. 계정당 active run 1회 제한은 동일하게 적용한다. 예외 목록은 서버 설정에만 두고 응답·로그·metric label에 회원 ID 목록을 노출하지 않는다.
- 사용량은 새 AI run을 성공적으로 접수할 때 차감한다. 같은 `Idempotency-Key` 재전송은 기존 idempotency 저장 결과를 재사용해 중복 차감하지 않는다. 취소, provider 오류, baseline fallback에는 차감분을 환불하지 않는다.
- 테스트 기간 밖에서는 기존 운영 정책으로 자동 복귀한다. 고정 기간 window는 KST 시작 시각을 anchor로 사용하며 Unix epoch 31일 구간으로 근사하지 않는다.

## 스트리밍 경계

FastAPI는 Gemini `streamGenerateContent?alt=sse`를 사용해 `GenerateContentResponse` 청크를 읽는다. provider 요청 body와 구조화 응답 schema는 unary 호출과 동일하게 유지한다.

구조화 응답에는 사용자 표시 전용 `narration` 문자열을 추가한다. provider의 원시 JSON 조각, `orderedRefs`, 좌표, 내부 prompt, provider 오류 원문은 브라우저로 보내지 않는다. FastAPI는 누적 JSON에서 `narration` 문자열만 증분 해석해 내부 `text.delta` event로 전달하고, 전체 응답을 조립·검증한 뒤 마지막 `proposal` event로 완성된 구조화 제안을 Spring에 전달한다.

Spring은 내부 stream의 `text.delta`를 기존 run event buffer에 `run.text.delta`로 추가한다.

```json
{
  "schemaVersion": "1.2",
  "runId": "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e6f",
  "sequence": 4,
  "text": "전주의 한옥과 골목을 잇는 여정을 구성하고 있어요."
}
```

각 delta는 최대 512자, 한 run의 공개 narration은 최대 4,000자로 제한한다. text 본문은 로그·metric attribute에 기록하지 않는다. `run.text.delta`는 replay buffer의 일반 sequence를 사용하지만 상태의 정답은 아니며, `reset`, 재시작, 버퍼 공백 뒤에는 GET snapshot으로 복구한다.

최종 `proposal`은 기존 Spring `AiProposalClient` 검증과 `JourneyWorkerService`의 candidate ref 검증·late result CAS를 통과해야만 저장된다. `run.terminal`에는 결과 본문을 넣지 않고 FE는 terminal 후 GET snapshot으로 지도에 반영한다.

## 실패·취소·fallback

- provider timeout, 429, malformed stream, 내부 stream 단절은 기존 `AiProposalException` 분류로 변환하고 baseline planner를 사용한다.
- 일부 delta 뒤 provider가 실패할 수 있다. 이때 이미 보낸 narration은 임시 UX로만 취급하며 `run.terminal`과 GET snapshot이 최종 상태를 결정한다.
- 브라우저 SSE 연결 종료는 run 취소가 아니다. 명시 cancel만 run 상태를 바꾸며 worker는 cancel/late-result 검사를 계속 수행한다.
- 내부 stream endpoint가 비활성화되거나 호환되지 않으면 기존 unary endpoint를 사용하는 설정 가능한 fallback을 유지한다.
- API key, 내부 auth token, prompt 원문, 사용자 비밀정보, provider error body를 SSE와 로그에 노출하지 않는다.

## 검증

- fake clock으로 KST 시작 직전·시작·종료 직전·종료, 일반 회원 2회/3회, 예외 회원, guest 거절을 검증한다.
- JDBC 동시 요청으로 동일 회원의 세 번째 요청이 원자적으로 거절되고 active slot이 하나만 열리는지 검증한다.
- Python fake transport가 여러 Gemini SSE 청크를 보낼 때 narration delta가 최종 proposal보다 먼저 오며 JSON escape와 청크 경계를 안전하게 처리하는지 검증한다.
- Spring fake internal stream으로 delta → terminal 순서, replay/reset, cancel, provider 오류 fallback, text/secret 비로그를 검증한다.
- staging에서는 실제 Gemini 선택 모델, 첫 delta 시간, proxy buffering, 실패 fallback, 최종 snapshot을 확인한다. 실제 provider·배포 검증 전에는 Issue #518을 완료로 처리하지 않는다.

## 외부 근거

- Gemini REST `models.streamGenerateContent`는 기존 `GenerateContentRequest`를 받아 SSE로 `GenerateContentResponse` 청크를 반환한다: <https://ai.google.dev/api/generate-content>
- Gemini API reference는 streaming content generation을 SSE 기반 incremental 응답으로 정의한다: <https://ai.google.dev/api>
