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
| `moderation.operator-token` | `ONMARU_SECRET_MODERATION_OPERATOR_TOKEN_CURRENT` | `ONMARU_SECRET_MODERATION_OPERATOR_TOKEN_PREVIOUS` |
| `admin.cursor-signing-key` | `ONMARU_SECRET_ADMIN_CURSOR_SIGNING_KEY_CURRENT` | `ONMARU_SECRET_ADMIN_CURSOR_SIGNING_KEY_PREVIOUS` |

`*_CURRENT`는 environment provider를 사용하는 런타임에서 필수다. `*_PREVIOUS`는 rotation overlap window 동안만 둔다.
`admin.cursor-signing-key`는 최소 32바이트의 임의 값이어야 한다. 이전 키는 최대 커서 수명(15분) 이상 유지한 뒤 제거한다.

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

## CI 관측 전용 Grafana Cloud credential (#555)

선택적 `CI Observability` workflow의 `ci-observability` GitHub environment에만 `OTLP_ENDPOINT`와 `OTLP_HEADERS` secret을 둔다. 이것은 위 Spring/FastAPI runtime `ONMARU_SECRET_OTLP_EXPORTER_TOKEN_CURRENT/PREVIOUS`와 다른 자격 증명이다. required `CI / verify`, pull request head, 원본 artifact 수집 job에는 Cloud secret을 주지 않는다. GitHub job은 source artifact 읽기용 `contents: read`, `actions: read` 권한만 사용하고, Cloud 전송은 environment가 붙은 export job에서만 수행한다.

1. Cloud 운영자는 해당 tenant·region의 OTLP ingest endpoint를 확인하고 metrics/traces write 범위의 최소 권한 access policy credential을 별도로 만든다. Grafana 관리·query·billing 권한을 전송 token에 넣지 않는다. endpoint와 인증 header 형식을 staging에서 검증한 뒤 GitHub environment secret으로 등록한다. Issue/PR/문서/로그에는 값 대신 version ID, 등록 시각, 관리 담당자만 기록한다.
2. Environment 보호 규칙과 workflow 접근 범위를 검토한다. fork PR 또는 임의 PR head가 secret을 읽지 못하고 trusted default-branch post-run adapter만 export하는지 확인한다. 유효한 `OTLP_HEADERS` JSON에도 실제 header 값은 증적으로 남기지 않는다.
3. 교체 시 새 version을 발급해 environment secret을 갱신하고 완료된 검증용 source run의 제한된 replay에서 metric/trace 저장과 diagnostic artifact를 확인한다. 옛 version을 폐기한 뒤 다시 검증하고 두 version ID, 시각, 결과만 기록한다. 인증 실패는 `export-result.json`과 `diagnostics.md`에서 확인하며 CI 원래 결론을 바꾸지 않는다.
4. 침해 시 기존 version을 즉시 revoke하고 새 version을 발급한다. 영향을 받은 run/attempt와 누락된 telemetry를 기록해 원본 manifest와 replay checkpoint가 있는 경우에만 같은 digest로 복구한다. Secret 값이나 서명된 artifact URL을 복구 기록에 넣지 않는다.

Cloud 전송·권한·보존·비용의 실제 수용 결과는 [CI 관측 왕복 증적](../release-evidence/ci-observability.md)에 기록한다. tenant 검증이 아직 수행되지 않은 항목은 `pending`으로 유지한다.
