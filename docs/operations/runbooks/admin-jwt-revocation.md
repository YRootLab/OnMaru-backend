# 관리자 JWT 폐기 운영 Runbook

## 정상 동작

관리자 logout은 현재 Bearer access token의 JTI를 SHA-256 hash로 변환해 PostgreSQL에 `exp`까지 저장한 뒤 refresh session을 폐기한다. 동일 access token의 후속 요청은 즉시 `401 AUTH_REQUIRED`가 된다. 관리자 계정이 `SUSPENDED` 또는 `DISABLED`로 전환되면 `tokens_valid_after`가 전진하며 이전 `iat`의 모든 token이 거부된다.

production은 메모리 fallback을 사용하지 않는다. PostgreSQL 조회·기록 장애는 인증 우회가 아니라 `503 AUTH_UNAVAILABLE`로 처리한다.

## 관측 신호

- `onmaru.admin.jwt.revocation.lookup` timer: `result=clear|revoked|error`
- `onmaru.admin.jwt.revocation.write` timer: `result=success|error`
- `onmaru.admin.jwt.revocation.cleanup` counter: `result=success|error`
- `onmaru.admin.jwt.revocation.cleanup.deleted` counter
- 로그: `admin.jwt.revocation.cleanup.completed`, `admin.jwt.revocation.cleanup.failed`

token, JTI, JTI hash, signing secret, password, email은 로그나 metric tag로 남기지 않는다.

## 안전한 상태 확인

아래 쿼리는 값 자체를 노출하지 않고 건수만 확인한다.

```sql
SELECT count(*) AS unexpired_revocations
FROM onmaru.identity_admin_access_token_revocations
WHERE expires_at > CURRENT_TIMESTAMP;

SELECT count(*) AS expired_revocations
FROM onmaru.identity_admin_access_token_revocations
WHERE expires_at <= CURRENT_TIMESTAMP;
```

계정 단위 경계 확인은 관리자 ID를 이미 알고 있는 사고 대응 상황에서만 수행한다.

```sql
SELECT status, tokens_valid_after
FROM onmaru.identity_admin_accounts
WHERE id = :admin_id;
```

## 장애 대응

1. `AUTH_UNAVAILABLE` 증가와 PostgreSQL health, connection pool 고갈, migration V044 적용 여부를 확인한다.
2. 인증 우회를 위해 memory profile이나 fallback bean을 활성화하지 않는다.
3. PostgreSQL 복구 후 로그인으로 새 token을 발급하고 `/api/v1/auth/admin/me`가 200인지 확인한다.
4. logout 후 같은 access token으로 `/api/v1/auth/admin/me`가 401인지 확인한다.
5. cleanup error가 있었다면 만료 row 건수를 확인하고 다음 정기 실행 결과를 확인한다.

## cleanup

기본 cron은 매시간 10분이며 batch size 500, 최대 10 batch로 제한된다.

- `onmaru.admin.jwt-revocation.cleanup-cron`
- `onmaru.admin.jwt-revocation.cleanup-batch-size`
- `onmaru.admin.jwt-revocation.cleanup-max-batches`

긴 transaction을 피하기 위해 운영 중 무제한 `DELETE`를 실행하지 않는다. 긴급 수동 정리가 필요하면 먼저 backup 상태와 대상 건수를 확인한 뒤 `expires_at <= CURRENT_TIMESTAMP` 조건과 제한된 batch를 사용한다.
