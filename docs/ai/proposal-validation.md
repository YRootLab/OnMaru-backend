# AI proposal validation 계약

## 책임 경계

`ai/src/onmaru_ai/proposal`은 Gemini를 포함한 provider의 JSON을 신뢰하지 않고 두 단계로 검증한다.

1. Pydantic discriminated schema가 `PROPOSE_BOARD`, `ASK_CLARIFICATION`, `NO_RESULTS`별 필수·금지 field와 길이·개수 상한을 검사한다.
2. `ProposalValidator`가 Spring이 전달한 `ProposalScope`와 비교해 candidate ref, pin, exclude, evidence ownership과 revision을 검사한다.

provider schema는 `$ref`, `$defs`, discriminator를 제거한 closed JSON Schema로 생성한다. 모든 object는 `additionalProperties: false`이며 board와 reason은 각각 최대 3개다. wire field는 `orderedRefs`, `evidenceIds`, `allowFreeText` 같은 camelCase alias만 허용하고 내부 Python field name 우회 입력을 받지 않는다. provider output에 tool call, URL, HTML, Markdown, control character, 임의 user-facing error field를 허용하지 않는다.

## Scope와 불변식

- candidate는 1..12개이며 ref는 고유해야 한다.
- candidate별 evidence는 1..3개이고 전체 scope에서 ID가 고유해야 한다.
- 모든 evidence의 revision은 `candidateRevision`과 같아야 한다.
- `PROPOSE_BOARD`는 1..3개의 고유 ref를 반환하고 모든 pin을 유지해야 한다.
- ordered ref마다 같은 순서의 reason이 정확히 하나 있어야 한다.
- reason은 해당 candidate가 소유한 evidence ID를 1..3개 참조해야 한다.
- `ASK_CLARIFICATION`은 `UNSUPPORTED_CONDITION`만 허용하며 canonical region ref를 새로 만들 수 없다.
- `NO_RESULTS`와 clarification 결과에는 board ref와 reason이 없어야 한다.

검증 실패는 provider payload를 exception message에 포함하지 않는 `ProposalValidationError`다. `code`는 duplicate, unknown, missing, over-limit, unsafe text 같은 내부 진단용 typed rejection이고, orchestration에 노출하는 안정된 값은 `degradedReason=AI_INVALID_RESPONSE`다. caller는 자동 repair나 두 번째 model call 없이 이미 계산한 deterministic baseline으로 전환한다.

## 보장하지 않는 것

evidence ID 소유권 검사는 모델이 허용되지 않은 문서를 인용하는 일을 막지만, 한국어 summary의 모든 주장에 의미적으로 근거가 있는지 증명하지는 않는다. claim support와 faithfulness는 Issue #111의 frozen evaluation에서 측정하며, Spring은 최종 canonical publication·pin·exclude·deadline을 다시 검증한다.

## 검증

```bash
cd ai
uv run pytest tests/proposal -q
uv run ruff check src/onmaru_ai/proposal tests/proposal
uv run mypy src/onmaru_ai/proposal tests/proposal
```

adversarial suite는 unknown ref 100개, duplicate/missing pin·reason, cross-candidate evidence, 빈 evidence, 초과 길이, extra field와 snake_case alias 우회, URL/HTML/Markdown/control character, outcome별 shape 혼합을 포함한다.
