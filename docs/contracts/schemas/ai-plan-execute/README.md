# Spring plan/execute 계약 provenance

`SafeQueryRequest.schema.json`, `PlanProposal.schema.json`, `ApprovedQueryPlan.schema.json`은 AI Engine commit [`6db985459e8aecea7dc918c273998fc89202528c`](https://github.com/YRootLab/onmaru-ai-engine/tree/6db985459e8aecea7dc918c273998fc89202528c/src/onmaru_ai/contracts/schemas)의 원본 schema다. Java fixture는 같은 commit의 `tests/contracts/fixtures/accepted-safe-query.json`, `accepted-plan-sql_only.json`, `accepted-plan-hybrid.json`을 `apps/spring-api/src/test/resources/ai-plan-execute/`에 보관한다.

Schema는 shape를, `DefaultPlanAuthorityGate`는 run/version/hash/filter authority 불변식을 검증한다. `planHash`는 원본 `contracts/validation.py::compute_plan_hash`와 같은 알고리즘을 쓴다. `planHash`와 `semanticQuery`를 제외하고 `semanticQueryHash`를 추가한 camelCase JSON을 key 순서로 재귀 정렬한다. JSON은 공백 없이 UTF-8로 직렬화하며 한글을 Unicode escape로 바꾸지 않는다. 그 결과의 SHA-256을 `sha256:` prefix로 표현한다.

`null` filter는 해당 축 제한 없음이고 `[]`는 결과 없음이다. 이를 서로 변환하면 권한 확장이 발생할 수 있으므로 그대로 보존한다.

## Rollout

`ONMARU_AI_PLAN_EXECUTE_ENABLED` / `onmaru.ai.plan-execute-enabled` 기본값은 `false`다. OFF에서는 기존 `HttpAiProposalClient`를 유지한다. ON은 다음 production bean을 모두 요구하며 누락하면 startup이 실패한다.

- `SafeQueryFactory`: safe query와 서버 소유 confirmed context/authority bounds를 제공한다.
- `CurrentVersions`: corpus/config/dictionary/revocation의 현재 권위 값이다. 임의 상수를 주입하지 않는다.
- `ApprovedBindingStore`: callback 전에 승인 run/plan을 내구 저장한다 (#809).
- `QueryGrantIssuer`: SQL route용 opaque grant를 발급한다 (#810).
- `ProgressCapabilityIssuer`: run/plan-bound callback capability를 발급한다 (#809).
- `ExecutionResultMapper`: AI artifact를 공개 자격 재검증 후 authoritative Snapshot으로 변환한다 (#811).

#808은 위 port에 permissive production default를 제공하지 않는다. #809/#810/#811 완료 전에는 flag를 활성화하지 않는다. `PlanExecuteAiProposalClient`의 process-local run reservation은 재실행을 제한하는 보조 장치이며 durable exactly-once 보장이 아니다. 10,000개 reservation이 차면 fail-closed하며 durable lifecycle과 배포 coordination은 후속 작업에서 다룬다.

새 plan/execute 경로는 raw text delta를 브라우저에 발행하지 않는다. 결과 publication과 Snapshot 저장은 Spring mapper/worker가 소유한다. clarification plan은 현재 기본 탐색 fallback으로 종료하며 공개 clarification renderer 연결은 별도 후속 작업이다.
