# Gemini provider adapter 운영 계약

## 경계

`ai/src/onmaru_ai/providers/gemini`은 Gemini를 신뢰하지 않는 structured proposal source로 다룬다. 호출은 `generateContent` 한 번으로 제한하고 provider-selected tool을 전달하지 않는다. policy, versioned few-shot, untrusted data를 분리해 보내며 응답 JSON은 Issue #105의 [proposal validation 계약](proposal-validation.md)에 따라 candidate와 evidence allowlist를 다시 검증한다.

Google 공식 REST 계약에 따라 API key는 `x-goog-api-key` header로 보내고 `generationConfig.responseMimeType=application/json`과 `responseJsonSchema`를 사용한다.

- API reference: https://ai.google.dev/api/generate-content
- Structured output: https://ai.google.dev/gemini-api/docs/generate-content/structured-output
- API key policy: https://ai.google.dev/gemini-api/docs/generate-content/api-key

## Secret과 모델 설정

key는 기존 `SecretProvider`의 `gemini.api-key`에서 주입하며 repository, prompt, exception, telemetry에 기록하지 않는다. 2026년 9월부터 unrestricted standard key가 거부되는 공식 전환 정책에 맞춰 운영은 Generative Language API로 제한된 authorization key만 사용한다. 임시 개발 key와 운영 key는 서로 다른 project·secret으로 관리하며 운영 key를 로컬 smoke에 재사용하지 않는다.

model name, 내부 alias, output token 상한, input/output 단가는 composition에서 `GeminiConfig`로 주입한다. 가격은 모델별로 바뀔 수 있으므로 코드 기본값이 없다. usage의 prompt/output/thought/total token과 주입된 단가로 계산한 추정 비용만 기록한다.

## 오류와 취소

| Provider 결과 | Typed failure |
|---|---|
| HTTP 429 | `AI_QUOTA_EXCEEDED` |
| transport 또는 absolute timeout | `AI_TIMEOUT` 또는 `AI_SERVICE_UNAVAILABLE` |
| HTTP 5xx, 401, 403 | `AI_SERVICE_UNAVAILABLE` |
| non-200, 빈 candidate, invalid JSON | `AI_INVALID_RESPONSE` |
| cancellation event | `CANCELLED` |

timeout과 cancellation은 in-flight transport task를 취소한다. 상위 task의 `CancelledError`는 cleanup 뒤 그대로 전파하고, 명시적 cancellation event만 typed `CANCELLED`로 반환한다. adapter는 자동 retry나 두 번째 model call을 만들지 않는다. JSON 파싱에 실패해도 provider가 반환한 usage는 비용 계측에 보존한다.

## 검증

기본 test suite는 fake transport로 request shape, timeout, 429, 5xx, malformed output, cancellation과 telemetry redaction을 검증한다. 실제 호출은 명시적으로만 실행한다.

```bash
ONMARU_GEMINI_LIVE_SMOKE=1 \
ONMARU_GEMINI_MODEL='<approved-model>' \
ONMARU_SECRET_GEMINI_API_KEY_CURRENT='<authorization-key>' \
uv run pytest tests/providers/gemini/test_live_smoke.py -q
```

live smoke는 synthetic ID, output 64 tokens, 전체 5초로 제한한다.
