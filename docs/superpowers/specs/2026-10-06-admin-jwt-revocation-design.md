# 관리자 JWT JTI PostgreSQL 폐기 설계

## 목적과 범위

Issue #492는 관리자 access token의 폐기 상태를 PostgreSQL에 영속화해 로그아웃, 애플리케이션 재시작, 다중 인스턴스, 계정 비활성화 상황에서 동일한 인증 결과를 보장한다.

이 변경은 관리자 JWT access token에만 적용한다. 일반 사용자 세션 저장 방식과 신규 공유 단기 상태 인프라는 변경하지 않는다.

## 확인된 현재 상태

- 관리자 access token은 15분 수명의 JWT이며 `sub`, `iat`, `exp`, `jti`를 포함한다.
- `AdminJwtTokenCodec`은 `AdminJtiRevocationStore`를 조회하지만 production도 메모리 구현을 사용한다.
- 관리자 logout은 Bearer token을 검증한 뒤 refresh session만 폐기한다.
- 관리자 계정은 PostgreSQL의 `identity_admin_accounts`에 저장되며 `ACTIVE`, `SUSPENDED`, `DISABLED` 상태를 가진다.
- production의 관리자 계정과 refresh session은 이미 PostgreSQL 저장소를 사용한다.

## 선택한 설계

개별 token 폐기와 계정 단위 전체 폐기를 서로 다른 경계로 처리한다.

1. 일반 logout은 검증된 현재 access token의 JTI hash와 만료 시각을 저장한다.
2. 계정 비활성화는 `identity_admin_accounts.tokens_valid_after`를 전진시켜 그 이전에 발급된 모든 access token을 거부한다.
3. 관리자 인증은 JWT 자체 검증, 계정 상태·발급 경계 검증, JTI 폐기 검증을 모두 통과해야 한다.
4. production에서 PostgreSQL 조회 또는 기록이 실패하면 인증과 폐기는 실패한다. 메모리 fallback은 사용하지 않는다.

이 방식은 모든 발급 JTI를 추적하는 것보다 DB 쓰기와 보관량이 작고, 개별 logout과 계정 전체 차단을 모두 지원한다. JWT `token_version` 방식보다 기존 claim 계약 변경이 적고 운영자가 시간 경계를 직접 해석할 수 있다.

## 데이터 모델

다음 forward migration을 추가한다.

### `identity_admin_accounts.tokens_valid_after`

- 형식: `timestamptz NOT NULL DEFAULT '-infinity'`
- 의미: JWT `iat`가 이 시각보다 이르면 거부한다.
- 계정이 `SUSPENDED` 또는 `DISABLED`로 전환될 때 현재 epoch second의 다음 초로 전진시킨다.
- JWT `iat`가 초 단위이므로 경계도 초 단위로 정규화한다. 비활성화와 재활성화가 같은 초 안에 일어나면 새 token 발급이 최대 1초 지연될 수 있으며, 과거 token을 허용하는 것보다 안전한 방향을 택한다.
- 상태가 `ACTIVE`가 아니면 경계와 관계없이 인증을 거부한다.

### `identity_admin_access_token_revocations`

| column | type | rule |
|---|---|---|
| `jti_hash` | `varchar(64)` | SHA-256 lowercase hex, primary key |
| `admin_id` | `uuid` | `identity_admin_accounts(id)` 참조 |
| `expires_at` | `timestamptz` | JWT의 원래 `exp` |
| `revoked_at` | `timestamptz` | 폐기 기록 시각 |

- token 원문, signing secret, JTI 원문은 저장하지 않는다.
- 같은 JTI가 반복 저장되면 `expires_at`을 더 늦은 값으로 유지하는 idempotent upsert를 사용한다.
- `expires_at` 인덱스로 cleanup 범위를 제한한다.
- `expires_at <= now`인 행은 조회 시 폐기되지 않은 것으로 취급하고 cleanup 대상이 된다.

## 컴포넌트 경계

### access token 검증 결과

`AdminAuthenticator`가 principal만 반환하면 logout에서 JTI와 `exp`를 다시 얻을 수 없다. 인증 결과를 `AdminAccessToken`으로 반환하는 경계를 추가하고, principal만 필요한 호출자는 그 안의 principal을 사용한다.

### 폐기 저장소

`AdminJtiRevocationStore`는 다음 책임을 갖는다.

- `revoke(adminId, jti, expiresAt)`: JTI를 내부에서 SHA-256 hash한 뒤 저장한다.
- `isRevoked(jti, now)`: 동일하게 hash한 값으로 만료 전 row 존재 여부를 조회한다.
- `deleteExpired(now, limit)`: 제한된 batch로 만료 row를 삭제하고 건수를 반환한다.

hash 계산은 저장소 구현의 공통 decorator 또는 별도 hasher에 둬 JDBC SQL과 로그가 JTI 원문을 받지 않게 한다. production은 `JdbcAdminJtiRevocationStore`, non-production은 `InMemoryAdminJtiRevocationStore`를 사용한다.

### 계정 인증 경계

별도의 `AdminTokenValidityStore`가 `adminId`로 다음 값을 읽는다.

- 계정 존재 여부
- 현재 계정 상태
- `tokens_valid_after`

JWT 서명과 표준 claim을 검증한 다음 이 저장소를 조회한다. 계정이 없거나 `ACTIVE`가 아니거나 `iat < tokens_valid_after`이면 `AdminAuthenticationException`으로 거부한다. 조회 예외도 같은 외부 응답으로 변환하되 내부 관측에는 `storage_error` 사유를 남긴다.

기존 `AdminAccountStore`의 login·refresh 책임과 request-time token 검증 책임은 분리한다. request-time 조회에는 password hash가 필요하지 않기 때문이다.

### logout 흐름

1. Authorization Bearer token을 검증해 `AdminAccessToken`을 얻는다.
2. JTI 폐기 row를 기록한다.
3. refresh session을 폐기한다.
4. 두 폐기가 성공한 경우에만 `204`와 refresh cookie 삭제를 반환한다.

JTI 기록에 실패하면 `401`로 축소하지 않고 인증 인프라 장애를 나타내는 `503 AUTH_UNAVAILABLE`을 반환한다. token 또는 JTI는 응답과 로그에 포함하지 않는다. 이미 JTI가 기록됐지만 refresh session 폐기가 실패한 재시도는 idempotent JTI upsert와 refresh revoke로 안전하게 처리한다.

## 계정 비활성화

계정 상태 변경 저장 경계는 다음 불변식을 보장한다.

- `ACTIVE`에서 `SUSPENDED` 또는 `DISABLED`로 전환하는 SQL은 상태와 `tokens_valid_after`를 한 transaction에서 변경한다.
- 경계는 뒤로 이동하지 않는다.
- refresh 경로도 계정이 `ACTIVE`인지 확인하므로 비활성 계정은 새 access token을 발급받지 못한다.
- 기존 access token은 매 요청의 상태·경계 조회에서 거부된다.

현재 관리자 계정 상태 변경 API가 없더라도 저장소 계약과 통합 테스트로 이 불변식을 제공한다. 향후 상태 변경 API는 이 저장소 경계를 우회할 수 없다.

## cleanup과 관측

- 기본 cleanup 주기: 매시간 10분, configurable cron.
- 한 실행에서 제한된 batch를 반복 삭제하되 최대 batch 횟수를 설정해 장시간 transaction을 피한다.
- 여러 인스턴스가 동시에 cleanup해도 `expires_at <= now` 조건과 제한 삭제가 안전해야 한다.
- 기록할 metric:
  - `onmaru.admin.jwt.revocation.lookup` timer (`result=clear|revoked|error`)
  - `onmaru.admin.jwt.revocation.write` timer (`result=success|error`)
  - `onmaru.admin.jwt.revocation.cleanup` counter (`result=success|error`)
  - `onmaru.admin.jwt.revocation.cleanup.deleted` counter
- 구조화 로그에는 operation, result, duration, 삭제 건수만 기록한다. token, JTI, JTI hash, signing secret, email은 기록하지 않는다.

## production 안전장치

- `production` profile에서만 JDBC 폐기 저장소와 JDBC token validity 저장소를 생성한다.
- 메모리 구현은 `!production` profile에만 둔다.
- startup 검증은 production에서 실제 bean class가 JDBC 구현인지 확인하고 아니면 시작을 실패시킨다.
- DataSource가 없거나 migration이 적용되지 않아 첫 조회가 실패하면 인증은 fail-closed 된다.

## 오류 정책

| 상황 | 외부 결과 | 내부 관측 |
|---|---|---|
| 잘못되거나 만료된 JWT | `401 AUTH_REQUIRED` | 검증 실패 사유만 기록 |
| 폐기된 JTI | `401 AUTH_REQUIRED` | `result=revoked` |
| 비활성 계정 또는 과거 `iat` | `401 AUTH_REQUIRED` | 계정 경계 거부 사유 |
| PostgreSQL 인증 조회 장애 | `503 AUTH_UNAVAILABLE` | `result=error`, 예외 stack |
| logout 폐기 기록 장애 | `503 AUTH_UNAVAILABLE` | write error |
| cleanup 장애 | 사용자 요청 영향 없음 | cleanup error, 다음 주기 재시도 |

인증 실패와 저장소 장애를 내부 타입으로 구분해 controller가 401과 503을 정확히 선택하도록 한다.

## 테스트 전략

### 단위 테스트

- JTI는 원문이 아니라 안정적인 SHA-256 hash로 변환된다.
- 만료 전 폐기 JTI는 거부되고 만료 시각 이후에는 허용된다.
- inactive 계정과 `iat < tokens_valid_after` token은 거부된다.
- 저장소 예외는 인증 인프라 오류로 보존된다.
- logout은 검증된 현재 token의 JTI와 `exp`를 폐기 서비스에 전달한다.

### PostgreSQL 통합 테스트

- revoke 후 다른 `JdbcAdminJtiRevocationStore` 인스턴스에서도 조회된다.
- 동일 JTI 동시 upsert가 중복 row 없이 성공한다.
- store 객체를 재생성해도 만료 전 폐기 상태가 유지된다.
- DB 연결 장애에서 조회와 기록이 fail-closed 된다.
- cleanup이 만료 row만 삭제하고 아직 유효한 폐기 row는 보존한다.
- 상태 변경과 `tokens_valid_after` 전진이 원자적으로 적용된다.

### Web·startup 테스트

- logout 직후 같은 access token으로 `/api/v1/auth/admin/me` 호출 시 `401`이다.
- logout 저장 장애는 `503`이며 refresh cookie를 성공 응답처럼 제거하지 않는다.
- production context에는 JDBC 구현만 존재한다.
- non-production context에는 메모리 구현이 허용된다.

## 문서와 운영 변경

- `docs/database/schema.md`와 Azimutt PostgreSQL schema에 두 데이터 모델 변경을 반영한다.
- 관리자 인증 운영 runbook에 DB 장애 증상, metric·로그 확인, cleanup 확인 SQL, 복구 후 검증 절차를 기록한다.
- OpenAPI에는 logout의 `503 AUTH_UNAVAILABLE` 응답을 반영한다.

## 제외 범위

- Redis 등 신규 공유 상태 인프라 도입
- 일반 사용자 session 변경
- access token 발급 JTI 전체 기록
- 특정 관리자의 모든 token을 사용자가 직접 폐기하는 신규 HTTP API
- 관리자 계정 관리 UI

