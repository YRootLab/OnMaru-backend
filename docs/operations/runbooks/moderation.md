# VisitReview moderation queue 운영 runbook

이 runbook은 보호된 queue에서 VisitReview 신고를 확인하고, 기존 moderation command로 복원·숨김·삭제한 뒤 공개 노출과 감사 기록을 검증하는 절차다. 운영 중 DB를 직접 수정하지 않으며, drill에는 synthetic 데이터만 사용한다.

## 접근 전 확인

- Spring API가 `onmaru.secrets.source=environment`로 실행 중인지 확인한다.
- `ONMARU_SECRET_MODERATION_OPERATOR_TOKEN_CURRENT`가 배포 secret에 있어야 한다. Rotation overlap 중에만 `..._PREVIOUS`를 함께 둔다.
- bearer token은 password manager 또는 secret manager에서 shell 환경 변수로 주입하고 터미널 history, Issue, PR, 로그에 기록하지 않는다.
- `X-OnMaru-Operator`에는 감사 기록에서 식별 가능한 영문·숫자·`.`, `_`, `@`, `-` 조합의 actor ref를 넣는다.

두 헤더가 모두 필요하다.

```text
Authorization: Bearer <moderation.operator-token>
X-OnMaru-Operator: <actor-ref>
```

헤더나 token이 없으면 `401 AUTH_REQUIRED`, 형식 또는 값이 잘못되면 `403 OPERATOR_FORBIDDEN`이어야 한다. Current와 previous token은 rotation overlap 동안 모두 허용된다. 설정 누락은 startup 실패로 차단한다.

## Queue 조회와 우선순위

```bash
curl --fail-with-body \
  -H "Authorization: Bearer ${ONMARU_MODERATION_TOKEN}" \
  -H "X-OnMaru-Operator: ${ONMARU_OPERATOR_REF}" \
  "${ONMARU_API_BASE_URL}/api/v1/operations/moderation/queue?limit=100"
```

응답은 `Cache-Control: no-store`이며 `generatedAt`, `oldestOpenReportAgeSeconds`, `items`를 포함한다. 각 item의 `oldestOpenReportAt`, `ageSeconds`, `slaTargetAt`, `overdue`를 기준으로 처리한다.

| Priority | 조건 | 목표 시간 | 초기 조치 |
| --- | --- | --- | --- |
| `HIGH_RISK` | `PERSONAL_DATA` 신고 또는 `SYSTEM / PII_HIGH_RISK` hide | 24시간 | 공개 제외를 먼저 확인하고 즉시 내용 검토 |
| `STANDARD` | 그 외 신고 | 72시간 | 오래된 open report부터 검토 |

Reporter member ID는 queue와 public DTO에 존재하지 않아야 한다. Queue의 review text와 report detail은 내부 검토용이므로 응답을 ticket, chat, dashboard annotation, metric label에 복사하지 않는다.

## Alert 대응

`moderation-pii-critical` alert를 받으면 다음 순서로 처리한다.

1. Grafana의 environment, service, errorCode, 발생 시각만 확인한다.
2. 보호된 queue에서 `HIGH_RISK` item을 조회한다.
3. 대상 review가 public list와 장소별 public list에서 제외됐는지 확인한다.
4. 사람이 원문을 검토하고 false positive인지 위반인지 판정한다.
5. 판정 command를 실행하고 audit, report disposition, public 노출을 다시 검증한다.
6. 실제 개인정보나 review text 없이 action ID, deployment SHA, 처리 시각, 결과만 incident evidence에 남긴다.

일반 queue age 경고는 `oldestOpenReportAgeSeconds`와 item의 `overdue`를 기준으로 확인한다. Alert를 닫는 행위가 신고를 처리한 것으로 간주되지는 않는다.

## 판정 실행

Operations POST는 공통 CSRF 보호를 받는다. 운영 client가 받은 CSRF cookie와 같은 값을 `X-CSRF-TOKEN`에 보내야 한다.

False positive 복원:

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer ${ONMARU_MODERATION_TOKEN}" \
  -H "X-OnMaru-Operator: ${ONMARU_OPERATOR_REF}" \
  -H "X-CSRF-TOKEN: ${ONMARU_CSRF_TOKEN}" \
  -H "Content-Type: application/json" \
  -b "__Host-onmaru-csrf=${ONMARU_CSRF_TOKEN}" \
  -d '{"nextStatus":"PUBLISHED","reason":"FALSE_POSITIVE"}' \
  "${ONMARU_API_BASE_URL}/api/v1/operations/moderation/visit-reviews/${REVIEW_ID}"
```

확인된 위반 삭제는 `nextStatus=REMOVED`와 해당 reason을 사용한다. 허용 reason은 `SPAM_CONFIRMED`, `ABUSE_CONFIRMED`, `PII_HIGH_RISK`, `COPYRIGHT_CONFIRMED`, `OTHER_POLICY_VIOLATION`이다. 추가 검토가 필요한 경우에만 `HIDDEN`을 사용하며, 어떠한 경우에도 DB table을 직접 update하지 않는다.

## 판정 후 검증

1. 응답의 `actorType=OPERATOR`, `actorRef`, `previousStatus`, `nextStatus`, `reason`, `createdAt`을 확인한다.
2. 복원된 review의 open report가 `DISMISSED`, 숨김·삭제된 review의 open report가 `RESOLVED`인지 감사 조회 경계에서 확인한다.
3. `PUBLISHED` 복원은 public list에 다시 나타나고 `HIDDEN` 또는 `REMOVED`는 public 전체/장소별 list 어디에도 나타나지 않아야 한다.
4. Public 응답, HTTP telemetry, Grafana label/annotation에 reporter ID, report detail, review text, actor ref, bearer token이 없는지 확인한다.

## Synthetic drill

로컬 또는 격리된 CI 환경에서만 실행한다.

```bash
./gradlew :apps:spring-api:test --tests '*ModerationOperatorDrillTests' --no-daemon
```

성공 기준은 다섯 신고 reason, 같은 reporter/review 중복, system PII hide, 즉시 public 제외, previous token을 사용한 false-positive 복원, current token을 사용한 위반 삭제, audit 순서, report disposition, public/telemetry 비노출이 모두 통과하는 것이다. Fixture는 `testing/e2e/moderation/operator-drill.json`의 synthetic 값만 사용한다.

## Token rotation과 폐기

Rotation은 [Secret Loading, Rotation, And Redaction Runbook](secrets.md)을 따른다. 새 값을 current, 기존 값을 previous로 겹쳐 배포한 뒤 두 값의 인증을 확인하고 caller를 전환한다. 전환 완료 후 previous를 제거하고 재배포하여 이전 token이 `403`으로 거부되는지 확인한다.
