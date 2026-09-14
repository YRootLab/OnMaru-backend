# Secret Loading, Rotation, And Redaction Runbook

이 runbook은 Issue #72의 server-only secret loading, rotation overlap, log redaction 운영 절차를 고정한다. 실제 secret 값은 문서, 이슈, PR, 로그, fixture, 스크린샷에 기록하지 않는다.

## Scope

Spring API와 FastAPI AI service는 같은 naming 규칙을 사용한다.

| Logical name | Current environment variable | Previous environment variable |
| --- | --- | --- |
| `tourapi.service-key` | `ONMARU_SECRET_TOURAPI_SERVICE_KEY_CURRENT` | `ONMARU_SECRET_TOURAPI_SERVICE_KEY_PREVIOUS` |
| `odii.service-key` | `ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT` | `ONMARU_SECRET_ODII_SERVICE_KEY_PREVIOUS` |
| `gemini.api-key` | `ONMARU_SECRET_GEMINI_API_KEY_CURRENT` | `ONMARU_SECRET_GEMINI_API_KEY_PREVIOUS` |
| `oauth.client-secret` | `ONMARU_SECRET_OAUTH_CLIENT_SECRET_CURRENT` | `ONMARU_SECRET_OAUTH_CLIENT_SECRET_PREVIOUS` |
| `otlp.exporter-token` | `ONMARU_SECRET_OTLP_EXPORTER_TOKEN_CURRENT` | `ONMARU_SECRET_OTLP_EXPORTER_TOKEN_PREVIOUS` |

`*_CURRENT`는 environment provider를 사용하는 런타임에서 필수다. `*_PREVIOUS`는 rotation overlap window 동안만 둔다.

## Runtime Modes

Local development와 test는 fake provider를 사용한다. Fake 값은 deterministic이지만 staging 또는 production traffic에서 허용하면 안 된다.

Production-like 환경은 environment provider를 사용한다.

- Spring: `onmaru.secrets.source=environment`
- FastAPI: `ONMARU_SECRETS_SOURCE=environment`

필수 `*_CURRENT` 값이 없거나 blank이면 요청을 받기 전에 startup이 실패해야 한다.

## Rotation Drill

1. 외부 secret manager에 새 secret version을 만든다.
2. 기존 값을 `*_PREVIOUS`, 새 값을 `*_CURRENT`로 배포 설정에 반영한다.
3. Spring API와 FastAPI AI service를 함께 배포한다.
4. startup과 readiness가 성공하는지 확인한다.
5. 이전 값을 사용하는 호출이 계획된 overlap window 안에서만 허용되는지 확인한다.
6. 모든 caller를 새 값으로 전환한다.
7. `*_PREVIOUS`를 제거한다.
8. 재배포 후 readiness가 계속 healthy인지 확인한다.
9. work log에는 secret manager version ID, deployment SHA, timestamp, 검증 결과만 기록한다.

## Emergency Revocation

1. 침해된 값을 `*_CURRENT`와 `*_PREVIOUS`에서 제거한다.
2. 대체 값을 `*_CURRENT`로 설치한다.
3. 즉시 재배포한다.
4. runtime startup, readiness, revoked value 거부를 확인한다.
5. provider 콘솔에서 침해된 key disable/delete를 완료한다.
6. 실제 secret 값 없이 version ID와 검증 결과만 기록한다.

## Redaction Verification

두 런타임은 current와 previous 값을 application log text가 외부로 나가기 전에 redaction해야 한다. 검증 명령:

```bash
./gradlew :apps:spring-api:test --tests '*Secret*' --no-daemon
cd ai && uv run pytest tests/test_secrets.py
```

Expected result: Spring secret tests와 FastAPI secret tests가 통과하고 current 및 previous value redaction이 확인된다.
