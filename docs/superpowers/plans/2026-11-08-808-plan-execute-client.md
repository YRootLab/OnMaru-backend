# Spring AI plan/execute client 구현 계획 (#808)

**목표:** 기본 OFF인 rollout flag 아래 typed plan/execute transport와 Spring plan authority를 추가한다. 기존 legacy proposal 경로는 유지한다.

## 계약

- AI 기준: `YRootLab/onmaru-ai-engine` commit `6db985459e8aecea7dc918c273998fc89202528c`.
- `/internal/v1/journey/plan`: `ai-safe-query.v1` → `ai-plan-proposal.v1`, scope `journey.plan:write`.
- `/internal/v1/journey/execute`: `ai-approved-plan.v1` → `ai-execute-result.v1`, scope `journey.execute:write`.
- 두 호출은 같은 nonzero trace ID와 서로 다른 nonzero span ID를 사용한다. `X-Deadline-At`과 JWT 만료는 상위 deadline을 넘지 않는다.
- query grant, progress capability, 최종 Snapshot mapper는 #810/#809/#811의 extension port로 남긴다. 필수 production dependency가 없으면 flag ON이 fail-closed한다.

## 작업 및 검증 순서

- [ ] 고정 AI fixture와 typed DTO round-trip/plan hash/authority 거부 테스트를 먼저 실패시킨다.
- [ ] 순수 Java contract/port와 Jackson strict wire codec, authority gate를 구현한다.
- [ ] HTTP fixture server로 path/scope/deadline/trace/response binding 테스트를 먼저 실패시킨다.
- [ ] bounded HTTP client, 무재시도, deadline-capped signer를 구현한다.
- [ ] orchestration 테스트로 plan→authority→capability/grant→execute→mapper 순서를 고정한다.
- [ ] feature flag 기본 OFF와 ON dependency 누락 fail-closed를 context test로 고정한다.
- [ ] OpenAPI/fixture provenance, 관련 unit/contract test, repository 검증을 수행하고 커밋한다.

DB migration은 이 작업에서 만들지 않는다. durable run binding/grant/ordinal은 #809/#810이 소유한다.
